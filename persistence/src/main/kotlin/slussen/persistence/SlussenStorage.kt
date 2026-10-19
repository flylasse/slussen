package slussen.persistence

import android.content.Context
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import slussen.core.port.BackupSizeMonitor
import slussen.core.port.BankAccountType
import slussen.core.port.CoordinatorMode
import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.JournalEntry
import slussen.core.port.ProviderRequestKind
import slussen.core.port.RecoveryCause
import slussen.core.port.StartKind
import slussen.core.port.StartState
import slussen.core.port.UnreadablePart
import slussen.persistence.credential.KeystoreCredentialVault
import slussen.persistence.device.DeviceDatabase
import slussen.persistence.journal.DecisionJournalDao
import slussen.persistence.journal.DecisionJournalEntryEntity
import slussen.persistence.journal.JournalDatabase
import slussen.persistence.journal.JournalEventType
import slussen.persistence.main.CoordinatorStateEntity
import slussen.persistence.main.RecoveryCheckEntity
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.start.SqliteDatabaseCheck
import slussen.persistence.start.SqliteDatabaseCheck.ValueColumn
import slussen.persistence.start.StartFiles
import slussen.persistence.start.StartOutcome
import slussen.persistence.start.StartSequence
import java.io.File
import java.time.Clock
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** The result of the start sequence, reported to the core (ADR-001 §3, ADR-002 §6). */
class SequenceStartState(outcome: StartOutcome) : StartState {
    override val startKind: StartKind = outcome.kind
    override val unreadableParts: List<UnreadablePart> = outcome.unreadableParts
}

/** The size of `slussen.db`, the only file that is backed up (ADR-002 §5). */
class FileBackupSizeMonitor(private val main: File) : BackupSizeMonitor {
    override fun backedUpBytes(): Long = if (main.exists()) main.length() else 0

    /**
     * Android backs up at most 25 MB of an app's data and makes no backup at all above it: a limit Android sets
     * (Android developer documentation, "Back up user data with Auto Backup"; C-015), not a Slussen choice.
     */
    override val limitBytes: Long = ANDROID_BACKUP_LIMIT_BYTES

    companion object {
        const val ANDROID_BACKUP_LIMIT_BYTES: Long = 25L * 1024 * 1024
    }
}

/**
 * Writes Decision Journal entries on its own single-threaded dispatcher, never inside a transaction of `slussen.db`;
 * any failure is caught, counted and dropped and never reaches the caller (ADR-002 §9, NFR-006).
 */
class RoomDecisionJournalRecorder(private val dao: DecisionJournalDao) : DecisionJournalRecorder {
    private val dropped = AtomicLong()
    private val scope = CoroutineScope(
        SupervisorJob() + Executors.newSingleThreadExecutor().asCoroutineDispatcher() +
            CoroutineExceptionHandler { _, _ -> dropped.incrementAndGet() },
    )

    /** How many entries could not be written. */
    val droppedEntries: Long get() = dropped.get()

    override fun record(entry: JournalEntry) {
        scope.launch {
            dao.insert(
                DecisionJournalEntryEntity(
                    recordedAt = entry.recordedAt,
                    eventType = JournalEventType.entries.single { it.text == entry.eventKind.text },
                    importIdentifier = entry.importIdentifier,
                    bankAccountName = entry.bankAccountName,
                    details = entry.details,
                ),
            )
        }
    }
}

/**
 * The storage of one app process (ADR-002): the start sequence, the three databases and the implementations of the
 * core's ports. `slussen.app.di` opens it once, before any operation is admitted.
 */
class SlussenStorage private constructor(
    val main: SlussenDatabase,
    val device: DeviceDatabase,
    val journal: JournalDatabase,
    val startState: StartState,
    val backupSize: BackupSizeMonitor,
) {
    val settings = RoomOperatorSettingsStore(main.settings())
    val registry = RoomPluginRegistryStore(main.registry())
    val connections = RoomConnectionStore(main.connections())
    val offered = RoomOfferedBankAccountStore(device.offeredBankAccounts())
    val providerRequests = RoomProviderRequestStore(main.providerRequests())
    val recovery = RoomRecoveryStore(main.recovery())
    val coordinator = RoomCoordinatorStore(main.coordinator())
    val credentialVault = KeystoreCredentialVault(device.serviceCredentials())
    val journalRecorder = RoomDecisionJournalRecorder(journal.entries())
    val importStatus = RoomImportStatusStore()

    companion object {
        /** The `Values:` columns of `slussen.db` in this schema, for the logical check of ADR-002 §8 step 1. */
        val MAIN_VALUES: List<ValueColumn> = listOf(
            ValueColumn("bank_account", "bank_account_type", BankAccountType.entries.map { it.text }),
            ValueColumn("recovery_check", "cause", RecoveryCause.entries.map { it.text }),
            ValueColumn("provider_request", "request_kind", ProviderRequestKind.entries.map { it.text }),
            ValueColumn("coordinator_state", "mode", CoordinatorMode.entries.map(RoomCoordinatorStore::textOf)),
        )

        val JOURNAL_VALUES: List<ValueColumn> = listOf(
            ValueColumn("decision_journal_entry", "event_type", JournalEventType.entries.map { it.text }),
        )

        /** Runs the start sequence (ADR-002 §6, §8), opens the databases and opens a Recovery Check when one is due. */
        suspend fun open(context: Context, clock: Clock): SlussenStorage {
            val mainFile = SlussenDatabases.mainFile(context)
            mainFile.parentFile?.mkdirs()
            val files = StartFiles(mainFile.parentFile!!, context.noBackupFilesDir)
            val sequence = StartSequence(
                files,
                SqliteDatabaseCheck(MAIN_VALUES),
                SqliteDatabaseCheck(emptyList()),
                SqliteDatabaseCheck(JOURNAL_VALUES),
                clock,
            )
            val outcome = sequence.run()
            val storage = SlussenStorage(
                SlussenDatabases.openMain(context),
                SlussenDatabases.openDevice(context),
                SlussenDatabases.openJournal(context),
                SequenceStartState(outcome),
                FileBackupSizeMonitor(mainFile),
            )
            storage.start(outcome, clock)
            if (outcome.recoveryCause != null) sequence.finish()
            return storage
        }
    }

    /** The default content (ADR-002 §8 step 3) and step 5 of the start sequence: O-12 opens the Recovery Check. */
    internal suspend fun start(outcome: StartOutcome, clock: Clock) {
        main.withTransaction {
            settings.ensureDefault()
            val coordinatorDao = main.coordinator()
            if (coordinatorDao.state() == null) {
                coordinatorDao.insert(
                    CoordinatorStateEntity(
                        mode = RoomCoordinatorStore.textOf(CoordinatorMode.Normal),
                        uploadPlanId = null,
                        stateVersion = 0,
                        scheduledFetchDeferred = false,
                        comparisonPending = false,
                    ),
                )
            }
            val cause = outcome.recoveryCause ?: return@withTransaction
            // UC-006 BR-017: After a new installation, a restored backup or a last good copy, Uploads wait for the Recovery Check.
            main.recovery().insert(RecoveryCheckEntity(cause = cause.text, openedAt = clock.instant(), comparedAt = null, confirmedAt = null))
            coordinatorDao.resetMode(RoomCoordinatorStore.textOf(CoordinatorMode.Normal))
        }
    }
}

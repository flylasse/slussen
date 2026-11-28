package slussen.persistence

import android.content.Context
import androidx.room.withTransaction
import slussen.core.connection.BanksOfDeviceData
import slussen.core.consent.LeftBehindBanks
import slussen.core.port.BackOffCause
import slussen.core.port.BackupSizeMonitor
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.EnteredBy
import slussen.core.port.CoordinatorMode
import slussen.core.port.DecisionJournalControl
import slussen.core.port.DecisionJournalReader
import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.ProviderRequestKind
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.ReviewChoice
import slussen.core.port.SentRecordOutcome
import slussen.core.port.StableReferenceKind
import slussen.core.port.StartKind
import slussen.core.port.TransactionFileOutcome
import slussen.core.port.TransactionStatus
import slussen.core.port.TransferCounterpartState
import slussen.core.port.TransferDecision
import slussen.core.port.TransferDescriptionKind
import slussen.core.port.TransferType
import slussen.core.port.UnreadableTransactionStatus
import slussen.core.port.UploadKind
import slussen.core.port.UploadPlanState
import slussen.core.port.StartedBy
import slussen.core.port.StartState
import slussen.core.port.UnreadablePart
import slussen.persistence.credential.KeystoreCredentialVault
import slussen.persistence.device.DeviceDatabase
import slussen.persistence.journal.JournalDatabase
import slussen.persistence.journal.RoomJournalFile
import slussen.persistence.journal.JournalEventType
import slussen.persistence.main.CoordinatorStateEntity
import slussen.persistence.main.FirstUploadMatchingEntity
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.UploadPlanItemKind
import slussen.persistence.start.SqliteDatabaseCheck
import slussen.persistence.start.SqliteDatabaseCheck.ValueColumn
import slussen.persistence.start.StartFiles
import slussen.persistence.start.StartOutcome
import slussen.persistence.start.StartSequence
import java.io.File
import java.time.Clock

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
    val mappings = RoomMappingStore(main.connections())
    val offered = RoomOfferedBankAccountStore(device.offeredBankAccounts())
    val providerRequests = RoomProviderRequestStore(main.providerRequests())
    val firstUploadMatching = RoomFirstUploadMatchingStore(main.firstUploadMatching())
    val coordinator = RoomCoordinatorStore(main.coordinator())
    val credentialVault = KeystoreCredentialVault(device.serviceCredentials())
    /** `journal.db`: the recorder and the Decision Journal's own control, on one thread of their own (ADR-002 §9). */
    val journalDatabase = RoomDecisionJournal(RoomJournalFile(journal))
    val journalRecorder: DecisionJournalRecorder = journalDatabase
    val journalControl: DecisionJournalControl = journalDatabase

    /** The Decision Journal read for the Journal Export only (UC-011, Konsist K-07), on the same thread. */
    val journalReader: DecisionJournalReader = journalDatabase
    val fetches = RoomFetchStore(main.fetches())
    /** O-10 of Grant Bank Consent (UC-007), `device.db` version 2. */
    val consents = RoomConsentStore(device.consents())
    val importStatus = RoomImportStatusStore(main.fetches(), connections, main.transactionFiles(), consents)

    /** O-3 of Import a Transaction File (UC-002). */
    val fileImports = RoomFileImportStore(main.transactionFiles())
    val comparisons = RoomComparisonStore(main, connections)
    val reviews = RoomReviewStore(main, comparisons)
    val transfers = RoomTransferStore(main, comparisons)

    companion object {
        /** The `Values:` columns of `slussen.db` in this schema, for the logical check of ADR-002 §8 step 1. */
        val MAIN_VALUES: List<ValueColumn> = listOf(
            ValueColumn("bank_account", "bank_account_type", BankAccountType.entries.map { it.text }),
            ValueColumn("recovery_check", "cause", FirstUploadMatchingCause.entries.map { it.text }),
            ValueColumn("provider_request", "request_kind", ProviderRequestKind.entries.map { it.text }),
            ValueColumn("fetch", "started_by", StartedBy.entries.map { it.text }),
            ValueColumn("fetch", "outcome", FetchOutcome.entries.map { it.text }),
            ValueColumn("fetch_result", "outcome", FetchResultOutcome.entries.map { it.text }),
            ValueColumn("back_off", "cause", BackOffCause.entries.map { it.text }),
            ValueColumn("coordinator_state", "mode", CoordinatorMode.entries.map(RoomCoordinatorStore::textOf)),
            // Transactions plan Phase 6a: the value columns O-1 writes (ADR-002 §8 step 1); an empty status passes the check.
            ValueColumn("bank_transaction", "reference_kind", StableReferenceKind.entries.map { it.text }),
            ValueColumn("bank_transaction", "status", TransactionStatus.entries.map { it.text }),
            ValueColumn("unreadable_transaction", "status", UnreadableTransactionStatus.entries.map { it.text }),
            // Transactions plan Phase 7a: the value columns O-4 writes (ADR-002 §8 step 1).
            ValueColumn("budget_app_transaction", "entered_by", EnteredBy.entries.map { it.text }),
            ValueColumn("budget_app_transaction", "state", BudgetAppTransactionState.entries.map { it.text }),
            // Transactions plan Phase 7b: the value columns O-5 writes for a Transfer and reads for the description lists.
            ValueColumn("transfer", "transfer_type", TransferType.entries.map { it.text }),
            ValueColumn("transfer", "decision", TransferDecision.entries.map { it.text }),
            ValueColumn("transfer_description", "kind", TransferDescriptionKind.entries.map { it.text }),
            // Schema version 6 (build-out plan §3): the value columns of the tables it adds; an empty one passes the check.
            ValueColumn("review_decision", "choice", ReviewChoice.entries.map { it.text }),
            ValueColumn("upload_plan", "state", UploadPlanState.entries.map { it.text }),
            ValueColumn("eligible_transaction", "upload_kind", UploadKind.entries.map { it.text }),
            ValueColumn("sent_record", "outcome", SentRecordOutcome.entries.map { it.text }),
            ValueColumn("transfer_counterpart", "state", TransferCounterpartState.entries.map { it.text }),
            ValueColumn("upload_plan_item", "write_kind", UploadPlanItemKind.entries.map { it.text }),
            ValueColumn("upload_plan_item", "reused_outcome", SentRecordOutcome.entries.map { it.text }),
            ValueColumn("transaction_file", "outcome", TransactionFileOutcome.entries.map { it.text }),
        )

        /**
         * The `Values:` columns of `device.db` in this schema, for the logical check of ADR-002 §8 step 1: none. Version 2
         * adds `bank_consent`, `bank_session`, `consent_request` and `consent_request_correlation`, none of which has a
         * `Values:` attribute in the entity model (`answered` is a Boolean); `EntityModelSchemaTest` checks this list
         * against the entity model and the technical table of ADR-002 §2.
         */
        val DEVICE_VALUES: List<ValueColumn> = emptyList()

        val JOURNAL_VALUES: List<ValueColumn> = listOf(
            ValueColumn("decision_journal_entry", "event_type", JournalEventType.entries.map { it.text }),
        )

        /** Runs the start sequence (ADR-002 §6, §8), opens the databases and opens a First Upload Matching when one is due. */
        suspend fun open(context: Context, clock: Clock): SlussenStorage {
            val mainFile = SlussenDatabases.mainFile(context)
            mainFile.parentFile?.mkdirs()
            val files = StartFiles(mainFile.parentFile!!, context.noBackupFilesDir)
            val sequence = StartSequence(
                files,
                SqliteDatabaseCheck(MAIN_VALUES),
                SqliteDatabaseCheck(DEVICE_VALUES),
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
            if (outcome.firstUploadMatchingCause != null) sequence.finish()
            return storage
        }
    }

    /** The default content (ADR-002 §8 step 3) and step 5 of the start sequence: O-12 opens First Upload Matching. */
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
            val cause = outcome.firstUploadMatchingCause ?: return@withTransaction
            // UC-006 BR-017: After a new installation, a restored backup or a last good copy, Uploads wait for First Upload Matching.
            main.firstUploadMatching().insert(FirstUploadMatchingEntity(cause = cause.text, openedAt = clock.instant(), comparedAt = null, confirmedAt = null))
            coordinatorDao.resetMode(RoomCoordinatorStore.textOf(CoordinatorMode.Normal))
        }
        // ADR-002 §2: once slussen.db is open by whichever path of the start sequence, and before anything can create a
        // Bank, device.db keeps nothing of a Bank slussen.db does not hold (after a last good copy, a restored backup or
        // an empty slussen.db), so that no Bank created later under the same key inherits it.
        BanksOfDeviceData(connections, RoomDeviceBankReferences(device.banks())).run()
        // ADR-002 §3 O-10: a Bank an attempt to grant a Bank Consent created and then could not store a Bank Consent
        // for, because the process ended in between, is removed when nothing refers to it.
        LeftBehindBanks(connections, consents, offered).run()
    }
}

package slussen.core.journal

import kotlinx.coroutines.CancellationException
import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.StatusChange
import java.time.Clock
import java.util.concurrent.atomic.AtomicLong

/**
 * Records Decision Journal entries through the write-only recorder (ADR-001 §3). No entry is recorded while the
 * Decision Journal is switched off (`OPERATOR_SETTINGS.journal_on`), and recording never affects the caller (NFR-006).
 */
class DecisionJournal(
    private val recorder: DecisionJournalRecorder,
    private val settings: OperatorSettingsStore,
    private val clock: Clock,
) {
    private val droppedEntries = AtomicLong()

    /**
     * ADR-002 §9: the entries dropped here, in memory since this process started, because what they need could not be
     * read; the recorder counts its own drops apart.
     */
    val dropped: Long get() = droppedEntries.get()

    /**
     * Records one entry; an entry about a Bank Transaction names its Bank Account and its Import Identifier, both or
     * neither (UC-011 BR-001, `DECISION_JOURNAL_ENTRY` constraints).
     */
    suspend fun record(kind: JournalEventKind, details: String, importIdentifier: String? = null, bankAccountName: String? = null) {
        require((importIdentifier == null) == (bankAccountName == null)) { "An Import Identifier is unique only within its Bank Account." }
        // ADR-002 §9: any failure, also of reading whether the Decision Journal is on, is dropped and never reaches the caller.
        try {
            if (!settings.current().journalOn) return
            recorder.record(
                JournalEntry(
                    recordedAt = clock.instant(),
                    eventKind = kind,
                    details = details,
                    importIdentifier = importIdentifier,
                    bankAccountName = bankAccountName,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            droppedEntries.incrementAndGet()
        }
    }

    // UC-011 BR-001: Every change of the Transaction Status Slussen gave a Bank Transaction is recorded, naming its Bank Account and its Import Identifier.
    /**
     * One entry per changed Transaction Status of [changes] (UC-003 step 6), in Slussen's own words, never a payee or
     * memo; [bankAccountNames] names the Bank Account of each by its id. Like every recording it never affects the caller:
     * a change whose Bank Account is not named is dropped and counted (ADR-002 §9).
     */
    suspend fun recordStatusChanges(changes: List<StatusChange>, bankAccountNames: Map<Long, String>) {
        changes.forEach { change ->
            val bankAccountName = bankAccountNames[change.bankAccountId]
            if (bankAccountName == null) {
                droppedEntries.incrementAndGet()
                return@forEach
            }
            val from = change.from?.text ?: "not compared yet"
            record(JournalEventKind.StatusChanged, "Transaction Status changed from $from to ${change.to.text}", change.importIdentifier, bankAccountName)
        }
    }
}

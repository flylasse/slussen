package slussen.core.journal

import slussen.core.port.DecisionJournalRecorder
import slussen.core.port.JournalEntry
import slussen.core.port.JournalEventKind
import slussen.core.port.OperatorSettingsStore
import java.time.Clock

/**
 * Records Decision Journal entries through the write-only recorder (ADR-001 §3). No entry is recorded while the
 * Decision Journal is switched off (`OPERATOR_SETTINGS.journal_on`), and recording never affects the caller (NFR-006).
 */
class DecisionJournal(
    private val recorder: DecisionJournalRecorder,
    private val settings: OperatorSettingsStore,
    private val clock: Clock,
) {
    suspend fun record(kind: JournalEventKind, details: String) {
        if (!settings.current().journalOn) return
        recorder.record(JournalEntry(recordedAt = clock.instant(), eventKind = kind, details = details))
    }
}

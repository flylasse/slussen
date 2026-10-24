package slussen.core.firstupload

import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.FirstUploadMatchingStore
import java.time.Instant

/**
 * How First Upload Matching is shown (UC-008 BR-009). It holds Uploads back and is not a failure, so it is always
 * information, never an error.
 */
enum class FirstUploadMatchingTone {
    /** Plain information: only First Upload Matchings of a new installation are open. */
    Information,

    /** A caution: one of a restore from a device backup or of a start from the last good copy is open. */
    Caution,
}

/**
 * UC-008 A8, BR-009: Uploads wait for First Upload Matching. [causes] holds the cause of every open First Upload
 * Matching, each once, in the order they opened; each has its own reason line. [openedAt] is when the latest one opened.
 * [canStart] says whether the action "Start First Upload Matching" can run Compare With the Budget App (A9 step 1),
 * which no slice builds yet (O-P2-09).
 */
data class FirstUploadMatchingNotice(
    val causes: List<FirstUploadMatchingCause>,
    val tone: FirstUploadMatchingTone,
    val openedAt: Instant,
    val canStart: Boolean,
)

/** The outcome of the action "Start First Upload Matching" (UC-008 A9). */
sealed interface FirstUploadMatchingStart {
    /** No First Upload Matching is open. */
    data object NoneOpen : FirstUploadMatchingStart

    /**
     * A9 step 1: Compare With the Budget App (UC-003) must run first, and no slice has built it yet (O-P2-09); First
     * Upload Matching stays open.
     */
    data object ComparisonNotAvailable : FirstUploadMatchingStart
}

/**
 * First Upload Matching (UC-008 A8, A9, BR-009), `:core` `firstupload` (ADR-001 §7): what the open ones show, from
 * their `cause` alone (ADR-002 §6), and the action that starts it. The start sequence of `:persistence` opens them
 * (ADR-002 §6, O-12).
 */
class FirstUploadMatching(private val store: FirstUploadMatchingStore) {
    /** A8: the notice of every open First Upload Matching, or null while none is open. */
    suspend fun notice(): FirstUploadMatchingNotice? {
        val open = store.openChecks()
        if (open.isEmpty()) return null
        return FirstUploadMatchingNotice(
            // UC-008 BR-009: While more than one First Upload Matching is open, the reason line of each cause is shown once.
            causes = open.map { it.cause }.distinct(),
            tone = toneOf(open.map { it.cause }),
            openedAt = open.maxOf { it.openedAt },
            canStart = COMPARISON_BUILT,
        )
    }

    // UC-008 BR-009: Plain information after a new installation; a caution after a restore or a start from the last good copy, also beside a new installation; never an error.
    private fun toneOf(causes: List<FirstUploadMatchingCause>): FirstUploadMatchingTone =
        if (causes.all { it == FirstUploadMatchingCause.NewInstallation }) FirstUploadMatchingTone.Information else FirstUploadMatchingTone.Caution

    /** A9: the Operator chooses "Start First Upload Matching". */
    suspend fun start(): FirstUploadMatchingStart {
        val open = store.openChecks()
        if (open.isEmpty()) return FirstUploadMatchingStart.NoneOpen
        // UC-008 BR-009: First Upload Matching can be confirmed only after a Comparison that read every Budget has run since it opened.
        if (open.any { it.comparedAt == null }) return FirstUploadMatchingStart.ComparisonNotAvailable
        // A9 steps 2-4 come with the slice that builds Compare With the Budget App (UC-003), which fills compared_at (O-P2-09).
        return FirstUploadMatchingStart.ComparisonNotAvailable
    }

    private companion object {
        /** A9 step 1: Compare With the Budget App (UC-003) is not built in this build (O-P2-09); the slice that builds it changes this. */
        const val COMPARISON_BUILT = false
    }
}

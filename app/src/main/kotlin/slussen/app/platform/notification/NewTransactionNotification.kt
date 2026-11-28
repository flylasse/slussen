package slussen.app.platform.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow

/**
 * The device's notifications as the New Transaction Notification uses them (UC-001 BR-014), behind an interface so that
 * a JVM test replaces it: whether Slussen may show one, the number of the one shown, and showing, replacing or removing it.
 */
interface NotificationDevice {
    /** Whether the device allows Slussen's notifications now. */
    fun allowed(): Boolean

    /** The number of the New Transaction Notification shown now, or null when none is shown. */
    fun shownNumber(): Int?

    /** Shows the one New Transaction Notification with [number], replacing the one shown. */
    fun show(number: Int)

    /** Removes the New Transaction Notification. */
    fun remove()
}

/** What the New Transaction Notification does when the number is worked out. */
sealed interface NotificationDecision {
    data object Unchanged : NotificationDecision

    data class Show(val number: Int) : NotificationDecision

    data object Remove : NotificationDecision
}

/** The rules of the New Transaction Notification (UC-001 BR-014). Pure. */
object NotificationRules {
    // UC-001 BR-014: Slussen shows, replaces or removes the notification when the number differs from the number shown; it disappears when the number is 0, and when the device does not allow notifications the number is not shown and nothing else changes.
    /** What to do with the number [number] worked out, while the device [allowed] notifications and shows [shown]. */
    fun decide(number: Int, allowed: Boolean, shown: Int?): NotificationDecision = when {
        !allowed -> NotificationDecision.Unchanged
        number <= 0 -> if (shown != null) NotificationDecision.Remove else NotificationDecision.Unchanged
        shown == number -> NotificationDecision.Unchanged
        else -> NotificationDecision.Show(number)
    }
}

/** Slussen's own words in the New Transaction Notification: the number, nothing of a Bank Transaction, Bank Account or Budget (NFR-012). */
object NotificationTexts {
    /** The name of the notification's channel, as the device's settings for Slussen show it. */
    const val CHANNEL_NAME: String = "New transactions"

    /** The text of the notification with [number] (`overview.html#notification`). */
    fun text(number: Int): String = "Waiting for an Upload Plan or your Review Decision: $number"
}

/**
 * The New Transaction Notification (UC-001 BR-014, S-B-39) on [device], with the number [number] worked out from stored
 * data in `:core`. It is worked out at a start and after every operation of the coordinator ([follow]), in whichever
 * process of the app runs it, the process of a Scheduled Fetch too. What the storage or the device throws leaves the
 * notification as it is, never a crash.
 */
class NewTransactionNotification(private val device: NotificationDevice, private val number: suspend () -> Int) {
    /** Works out the number and shows, replaces or removes the notification when it differs from the number shown. */
    suspend fun update() {
        try {
            val now = number()
            when (val decision = NotificationRules.decide(now, device.allowed(), device.shownNumber())) {
                NotificationDecision.Unchanged -> Unit
                is NotificationDecision.Show -> device.show(decision.number)
                NotificationDecision.Remove -> device.remove()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Unit
        }
    }

    // UC-001 BR-014: Slussen works the number out from stored data at every start and after every operation of the coordinator.
    /** Updates now, the start, and again whenever [ended], the coordinator's count of ended operations, moves on. */
    suspend fun follow(ended: StateFlow<Long>) {
        ended.collect { update() }
    }
}

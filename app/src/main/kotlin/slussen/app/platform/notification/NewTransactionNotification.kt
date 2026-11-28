package slussen.app.platform.notification

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The device's notifications as the New Transaction Notification uses them (UC-001 BR-014), behind an interface so that
 * a JVM test replaces it: whether Slussen may show one, the number of the one shown, and showing, replacing or removing it.
 */
interface NotificationDevice {
    /** Whether the device allows Slussen's notifications now. */
    fun allowed(): Boolean

    /** The number of the New Transaction Notification shown now, or null when none is shown. */
    fun shownNumber(): Int?

    /** Shows the one New Transaction Notification as [content] says, field for field, replacing the one shown. */
    fun show(content: NotificationContent)

    /** Removes the New Transaction Notification. */
    fun remove()
}

/** The importance of the notification's channel: low, below every notification that makes a sound (UC-001 BR-014). */
enum class NotificationImportance { Low }

/** What a tap on the notification opens. */
enum class NotificationTap {
    /** Slussen started afresh on the Overview, its first screen (UC-001 BR-014). */
    Overview,
}

/**
 * Everything the New Transaction Notification holds (UC-001 BR-014, NFR-012), as a plain value that the device only
 * renders: its channel with its name, low importance, no sound or vibration and the badge on the app icon; one text, with
 * the number in Slussen's own words; the number again for the badge; what a tap opens. Nothing else is shown.
 */
data class NotificationContent(
    val channelId: String,
    val channelName: String,
    val importance: NotificationImportance,
    val silent: Boolean,
    val vibrates: Boolean,
    val badge: Boolean,
    val text: String,
    val number: Int,
    val opens: NotificationTap,
) {
    companion object {
        // UC-001 BR-014: One silent, low-priority New Transaction Notification, also shown as a badge on the app icon, with the number of unprocessed Bank Transactions; tapping it opens the Overview.
        fun of(number: Int): NotificationContent = NotificationContent(
            channelId = "new-transactions",
            channelName = NotificationTexts.CHANNEL_NAME,
            importance = NotificationImportance.Low,
            silent = true,
            vibrates = false,
            badge = true,
            text = NotificationTexts.text(number),
            number = number,
            opens = NotificationTap.Overview,
        )
    }
}

/** What the New Transaction Notification does when the number is worked out. */
sealed interface NotificationDecision {
    data object Unchanged : NotificationDecision

    data class Show(val number: Int) : NotificationDecision

    data object Remove : NotificationDecision
}

/** The rules of the New Transaction Notification (UC-001 BR-014). Pure. */
object NotificationRules {
    // UC-001 BR-014: When the device does not allow notifications, the number is not shown; Settings says so while notifications are not allowed.
    /**
     * Whether Slussen's notifications are allowed: the device allows notifications for Slussen ([appEnabled]), the
     * permission to show them is held ([permissionHeld], asked on Android 13 and later), and Slussen's one channel is not
     * switched off ([channelSwitchedOff]).
     */
    fun allowed(appEnabled: Boolean, permissionHeld: Boolean, channelSwitchedOff: Boolean): Boolean =
        appEnabled && permissionHeld && !channelSwitchedOff

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
    /** One update at a time in this process, the worker's and the follower's, so that an older number never replaces a newer one. */
    private val one = Mutex()

    /** Works out the number and shows, replaces or removes the notification when it differs from the number shown. */
    suspend fun update() = one.withLock {
        try {
            val now = number()
            when (val decision = NotificationRules.decide(now, device.allowed(), device.shownNumber())) {
                NotificationDecision.Unchanged -> Unit
                is NotificationDecision.Show -> device.show(NotificationContent.of(decision.number))
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

package slussen.app.platform.notification

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.NumberSetting
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.TestSlice

/**
 * The New Transaction Notification (UC-001 BR-014, S-B-39) on the JVM, behind [NotificationDevice], which a test replaces:
 * shown, replaced or removed when the number worked out from stored data differs from the number shown, at a start and
 * after every operation of the coordinator, in whichever process; nothing but the number and Slussen's own words.
 */
@DisplayName("UC-001: Fetch Transactions, the New Transaction Notification")
class UC001NewTransactionNotificationTest {
    /** The device's notifications as a JVM test sees them: one notification at most, with its number. */
    private class FakeDevice(var allowed: Boolean = true) : NotificationDevice {
        var shown: Int? = null
        val calls = mutableListOf<String>()

        override fun allowed(): Boolean = allowed

        override fun shownNumber(): Int? = shown

        var content: NotificationContent? = null

        override fun show(content: NotificationContent) {
            calls += "show ${content.number}"
            shown = content.number
            this.content = content
        }

        override fun remove() {
            calls += "remove"
            shown = null
        }
    }

    private val device = FakeDevice()
    private var number = 0
    private val notification = NewTransactionNotification(device) { number }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: a number above 0 is shown; a changed number replaces it instead of adding another; an equal one changes nothing")
    fun shownAndReplaced() = runTest {
        number = 23
        notification.update()
        notification.update()
        number = 5
        notification.update()

        assertEquals(listOf("show 23", "show 5"), device.calls)
        assertEquals(5, device.shown)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: the notification disappears when the number is 0; with none shown nothing is done")
    fun removedAtZero() = runTest {
        notification.update()
        assertTrue(device.calls.isEmpty())

        number = 3
        notification.update()
        number = 0
        notification.update()

        assertEquals(listOf("show 3", "remove"), device.calls)
        assertNull(device.shown)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: when the device does not allow notifications the number is not shown and nothing else changes")
    fun notAllowedShowsNothing() = runTest {
        device.allowed = false
        number = 9

        notification.update()

        assertTrue(device.calls.isEmpty())
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: a number that cannot be worked out from storage leaves the notification as it is, never a crash")
    fun unreadableNumberChangesNothing() = runTest {
        number = 4
        notification.update()

        NewTransactionNotification(device) { error("The invented storage cannot be read.") }.update()

        assertEquals(listOf("show 4"), device.calls)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014, NFR-012: the notification says the number in Slussen's own words, and nothing of a Bank Transaction, Bank Account or Budget")
    fun textHoldsOnlyTheNumber() {
        assertEquals(
            NotificationContent(
                channelId = "new-transactions",
                channelName = "New transactions",
                importance = NotificationImportance.Low,
                silent = true,
                vibrates = false,
                badge = true,
                text = "Waiting for an Upload Plan or your Review Decision: 23",
                number = 23,
                opens = NotificationTap.Overview,
            ),
            NotificationContent.of(23),
        )
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: the device is given exactly that content, the one text and the number for the badge")
    fun deviceIsGivenTheContent() = runTest {
        number = 7
        notification.update()

        assertEquals(NotificationContent.of(7), device.content)
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014 (settings.html#notifications-off): notifications are allowed only when the device allows them for Slussen, the permission is held and Slussen's channel is not switched off")
    fun allowedOnlyWhenAllThreeHold() {
        assertTrue(NotificationRules.allowed(appEnabled = true, permissionHeld = true, channelSwitchedOff = false))
        assertFalse(NotificationRules.allowed(appEnabled = false, permissionHeld = true, channelSwitchedOff = false))
        assertFalse(NotificationRules.allowed(appEnabled = true, permissionHeld = false, channelSwitchedOff = false))
        assertFalse(NotificationRules.allowed(appEnabled = true, permissionHeld = true, channelSwitchedOff = true))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: two updates at once, the worker's and the follower's, show one notification and never an older number over a newer one")
    fun neverAStaleNumberOverANewerOne() = runTest(UnconfinedTestDispatcher()) {
        val holdFirstRead = CompletableDeferred<Unit>()
        var reads = 0
        var stored = 3
        val shared = NewTransactionNotification(device) {
            val read = stored
            if (reads++ == 0) holdFirstRead.await()
            read
        }
        val first = async { shared.update() }
        stored = 8
        val second = async { shared.update() }
        holdFirstRead.complete(Unit)
        first.await()
        second.await()

        assertEquals(8, device.shown)
        assertEquals("show 8", device.calls.last())
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    @UseCase(id = "UC-001", scenario = "A12: Scheduled Fetch", businessRules = ["BR-014"])
    @DisplayName("BR-014: the number is worked out at a start and after every operation of the coordinator, in the process that ran it")
    fun followsEveryOperation() = runTest(UnconfinedTestDispatcher()) {
        val slice = TestSlice()
        var workedOut = 0
        val following = NewTransactionNotification(device) { workedOut++; slice.status.newTransactionNumber() + workedOut }
        val job = launch { following.follow(slice.coordinator.ended) }
        assertEquals(listOf("show 1"), device.calls, "worked out at the start")

        slice.generalSettings.change(NumberSetting.FetchSchedule, 1)

        assertEquals(listOf("show 1", "show 2"), device.calls, "worked out again after the operation ended")
        job.cancel()
    }
}

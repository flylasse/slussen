package slussen.app.ui.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.core.port.NumberSetting
import slussen.pluginapi.testfixtures.UseCase
import java.time.Duration

/**
 * The Fetch Schedule on Settings (UC-010 A6, `settings.html#fetch-schedule`, `#notifications-off`): a saved value sets
 * the Scheduled Fetches again from the time of the change (UC-001 BR-013), the line shows the approximate next time
 * from what was scheduled and that the device may shift it, and, while notifications are not allowed, that the number
 * of waiting transactions is not shown (UC-001 BR-014).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-010: Adjust Settings, the Fetch Schedule and its next Scheduled Fetch")
class UC010FetchScheduleViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice

    private fun settings() = SettingsViewModel(fixture.services).also { it.load() }

    private fun SettingsViewModel.fetchSchedule() = state.value!!.line(NumberSetting.FetchSchedule)!!

    private val notificationsOff =
        "Notifications are not allowed for Slussen: the number of waiting transactions is not shown. The device's settings for " +
            "Slussen allow them; nothing else is needed."

    private fun nextAbout(hoursFromNow: Long): String =
        "Next Scheduled Fetch about ${fixture.services.formats.dateTime(slice.clock.instant().plus(Duration.ofHours(hoursFromNow)))}, " +
            "as the device allows; the device can start it later."

    @Test
    @UseCase(id = "UC-010", scenario = "A6: Change the Fetch Schedule", businessRules = ["BR-002"])
    @DisplayName("A6: the line shows the approximate time of the next Scheduled Fetch from what is scheduled, and that the device may start it later")
    fun lineShowsTheNextTime() {
        fixture.scheduleWork.alreadyScheduled(Duration.ofHours(12), slice.clock.instant().plus(Duration.ofHours(7)))

        val line = settings().fetchSchedule()

        assertEquals(listOf(nextAbout(7)), line.notes)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A6: Change the Fetch Schedule", businessRules = ["BR-002"])
    @DisplayName("A6, UC-001 BR-013: saving a new Fetch Schedule sets the Scheduled Fetches again from the time of the change, and the line shows the new next time")
    fun savingSetsTheScheduleAgain() {
        fixture.scheduleWork.alreadyScheduled(Duration.ofHours(12), slice.clock.instant().plus(Duration.ofHours(2)))
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.FetchSchedule)

        viewModel.save("1")

        assertEquals(listOf("schedule every PT24H first after PT24H"), fixture.scheduleWork.calls)
        assertEquals(listOf(nextAbout(24)), viewModel.fetchSchedule().notes)
        assertEquals(1, slice.settings.settings.scheduledFetchesPerDay)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A6: Change the Fetch Schedule", businessRules = ["BR-002"])
    @DisplayName("A6, UC-001 BR-013: saving 0 removes the scheduled work, and no next time is shown")
    fun savingZeroCancels() {
        fixture.scheduleWork.alreadyScheduled(Duration.ofHours(12), slice.clock.instant().plus(Duration.ofHours(2)))
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.FetchSchedule)

        viewModel.save("0")

        assertEquals(listOf("cancel"), fixture.scheduleWork.calls)
        assertTrue(viewModel.fetchSchedule().notes.isEmpty())
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A6: Change the Fetch Schedule", businessRules = ["BR-002"])
    @DisplayName("A6, NFR-023: a value stored while the device's work cannot be set is said at the line, with when it is set")
    fun scheduleNotSetIsSaid() {
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.FetchSchedule)
        fixture.scheduleWork.fails = true

        viewModel.save("1")

        assertEquals(1, slice.settings.settings.scheduledFetchesPerDay)
        assertEquals(
            listOf("The device did not take the new schedule now; Slussen sets it at its next start. Nothing else is needed."),
            viewModel.fetchSchedule().notes,
        )
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A6: Change the Fetch Schedule", businessRules = ["BR-002"])
    @DisplayName("UC-001 BR-014 (#notifications-off): while notifications are not allowed, the Fetch Schedule says that the number is not shown and where it is allowed")
    fun notificationsOffIsSaid() {
        fixture.notificationsAllowed = false
        fixture.scheduleWork.alreadyScheduled(Duration.ofHours(12), slice.clock.instant().plus(Duration.ofHours(7)))

        assertEquals(listOf(nextAbout(7), notificationsOff), settings().fetchSchedule().notes)

        fixture.notificationsAllowed = true
        assertEquals(listOf(nextAbout(7)), settings().fetchSchedule().notes)
    }

    private val notSet = "The device did not take the new schedule now; Slussen sets it at its next start. Nothing else is needed."

    @Test
    @UseCase(id = "UC-010", scenario = "A6: Change the Fetch Schedule", businessRules = ["BR-002"])
    @DisplayName("A6, UC-001 BR-013: after a change the device did not take, the note stays when an operation ends and when Settings is opened again, and the old work's time is not shown")
    fun failedChangeStaysOverReloads() {
        fixture.scheduleWork.alreadyScheduled(Duration.ofHours(12), slice.clock.instant().plus(Duration.ofHours(2)))
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.FetchSchedule)
        fixture.scheduleWork.fails = true
        viewModel.save("1")
        fixture.scheduleWork.fails = false

        kotlinx.coroutines.runBlocking { slice.generalSettings.change(NumberSetting.DefaultWait, 12) }
        assertEquals(listOf(notSet), viewModel.fetchSchedule().notes)
        viewModel.load()
        assertEquals(listOf(notSet), viewModel.fetchSchedule().notes)
        assertEquals(listOf(notSet), settings().fetchSchedule().notes)
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A6: Change the Fetch Schedule", businessRules = ["BR-002"])
    @DisplayName("A6, UC-001 BR-013: after a cancel the device did not take, no next time is shown beside 0; a later change that sets the work removes the note")
    fun failedCancelShowsNoTimeAtZero() {
        fixture.scheduleWork.alreadyScheduled(Duration.ofHours(12), slice.clock.instant().plus(Duration.ofHours(2)))
        val viewModel = settings()
        viewModel.startEditing(NumberSetting.FetchSchedule)
        fixture.scheduleWork.fails = true
        viewModel.save("0")
        fixture.scheduleWork.fails = false

        viewModel.load()
        assertEquals(listOf(notSet), viewModel.fetchSchedule().notes)

        viewModel.startEditing(NumberSetting.FetchSchedule)
        viewModel.save("2")
        assertEquals(listOf(nextAbout(12)), viewModel.fetchSchedule().notes)
    }
}

package slussen.app.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext
import slussen.core.port.StartKind
import slussen.core.port.UnreadablePart
import slussen.testing.TestSlice
import java.time.ZoneId

/**
 * The slice on the fakes of `:testing`, as the screens see it, in an invented device time zone, with an invented version
 * of the app (ADR-005 §4).
 */
class UiFixture(
    startKind: StartKind = StartKind.Normal,
    unreadableParts: List<UnreadablePart> = emptyList(),
    appVersion: AppVersion = AppVersion.of(packageName = "slussen.app", versionName = "9.8.7"),
    /** The Import Identifiers the slice computes; a test passes a test-only digest to plant a collision (ADR-008 §5). */
    identify: (slussen.core.identity.ImportIdentifierInput) -> String = slussen.core.identity.ImportIdentifierProtocol::importIdentifier,
) {
    val slice = TestSlice(startKind, unreadableParts, identify)
    val zone: ZoneId = ZoneId.of("Europe/Stockholm")

    /** The device's periodic work of the Fetch Schedule, replaced for the JVM (UC-001 BR-013). */
    val scheduleWork = slussen.app.schedule.FakeFetchScheduleWork(slice.clock)

    /** Whether the device allows Slussen's notifications (UC-001 BR-014); a test switches it. */
    var notificationsAllowed: Boolean = true

    val services = UiServices(
        credentials = slice.credentials,
        settingsOverview = slice.settingsOverview,
        connect = slice.connect,
        providerSettings = slice.providerSettings,
        generalSettings = slice.generalSettings,
        descriptionLists = slice.descriptionLists,
        journalControl = slice.journalControl,
        journalExport = slice.journalExport,
        mappings = slice.mappings,
        status = slice.status,
        bankAccountTransactions = slice.bankAccountTransactions,
        transfers = slice.budgetTransfers,
        fetch = slice.fetch,
        fileImport = slice.fileImport,
        review = slice.review,
        transferDecisions = slice.transferDecisions,
        comparison = slice.comparison,
        formats = Formats(zone),
        clock = slice.clock,
        appVersion = appVersion,
        running = slice.coordinator.running,
        ended = slice.coordinator.ended,
        budgetAppName = slice.plugins.budgetApp.profile.name,
        fetchSchedule = slussen.app.schedule.FetchSchedule(scheduleWork) { slice.settings.current().scheduledFetchesPerDay },
        notificationsAllowed = { notificationsAllowed },
    )
}

/** Runs every coroutine of `viewModelScope` at once on the test thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) = Dispatchers.setMain(UnconfinedTestDispatcher())

    override fun afterEach(context: ExtensionContext) = Dispatchers.resetMain()
}

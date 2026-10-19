package slussen.app.ui

import slussen.testing.TestSlice
import java.time.ZoneId

/**
 * The slice on the fakes of `:testing` for the screen tests on a device or emulator, with invented data only, and an
 * invented version of the app unless a test gives the installed one.
 */
class ScreenFixture(appVersion: AppVersion = AppVersion.of(packageName = "slussen.app", versionName = "9.8.7")) {
    val slice = TestSlice()
    val services = UiServices(
        credentials = slice.credentials,
        connections = slice.connections,
        connect = slice.connect,
        providerSettings = slice.providerSettings,
        mappings = slice.mappings,
        status = slice.status,
        fetch = slice.fetch,
        formats = Formats(ZoneId.of("Europe/Stockholm")),
        clock = slice.clock,
        appVersion = appVersion,
    )
}

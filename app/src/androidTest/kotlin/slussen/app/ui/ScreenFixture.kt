package slussen.app.ui

import slussen.testing.TestSlice
import java.time.ZoneId

/** The slice on the fakes of `:testing` for the screen tests on a device or emulator, with invented data only. */
class ScreenFixture {
    val slice = TestSlice()
    val services = UiServices(
        credentials = slice.credentials,
        connections = slice.connections,
        connect = slice.connect,
        providerSettings = slice.providerSettings,
        mappings = slice.mappings,
        status = slice.status,
        formats = Formats(ZoneId.of("Europe/Stockholm")),
        clock = slice.clock,
    )
}

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

/** The slice on the fakes of `:testing`, as the screens see it, in an invented device time zone. */
class UiFixture(startKind: StartKind = StartKind.Normal, unreadableParts: List<UnreadablePart> = emptyList()) {
    val slice = TestSlice(startKind, unreadableParts)
    val zone: ZoneId = ZoneId.of("Europe/Stockholm")
    val services = UiServices(
        credentials = slice.credentials,
        connections = slice.connections,
        connect = slice.connect,
        status = slice.status,
        formats = Formats(zone),
        clock = slice.clock,
    )
}

/** Runs every coroutine of `viewModelScope` at once on the test thread. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherExtension : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext) = Dispatchers.setMain(UnconfinedTestDispatcher())

    override fun afterEach(context: ExtensionContext) = Dispatchers.resetMain()
}

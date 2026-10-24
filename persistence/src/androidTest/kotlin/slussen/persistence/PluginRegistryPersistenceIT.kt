package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.DataProviderRegistration
import slussen.core.port.NewProviderRequest
import slussen.core.port.ProviderRequestKind
import slussen.persistence.main.SlussenDatabase
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.testfixtures.UseCase
import java.time.Duration
import java.time.Instant

/** O-11 (ADR-002 §2) and the Request Limit count of the request gate (ADR-001 §4.0) on the platform's SQLite. */
@DisplayName("Plug-in registration and Provider Requests, on the device")
class PluginRegistryPersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private lateinit var main: SlussenDatabase

    @BeforeEach
    fun open() {
        DeviceFiles.wipe()
        main = SlussenDatabases.openMain(DeviceFiles.context)
    }

    @AfterEach
    fun close() = main.close()

    private fun published(requests: Int) = RequestLimitDeclaration.Published(requests, "Kestrel Feeds developer guide, an invented source")

    @Test
    @UseCase(id = "UC-012", businessRules = ["BR-011"])
    @DisplayName("O-11: a published Request Limit always comes from the part; the Operator's Fetch Cooldown is kept at the next start")
    fun registrationKeepsTheOperatorsFetchCooldown() = runBlocking {
        val registry = RoomPluginRegistryStore(main.registry())
        val declared = DataProviderRegistration("Kestrel Feeds", "example-feeds", true, false, 60, published(20))
        val id = registry.reconcile(listOf(declared), emptyList()).dataProviders.single().id
        main.openHelper.writableDatabase.execSQL("UPDATE data_provider SET fetch_cooldown_minutes = 15 WHERE id = $id")

        val again = registry.reconcile(listOf(declared.copy(requestLimit = published(25), defaultFetchCooldownMinutes = 90)), emptyList())

        assertEquals(15, again.dataProviders.single().fetchCooldownMinutes)
        assertEquals(25, again.dataProviders.single().requestLimit)
        assertTrue(again.dataProviders.single().requestLimitPublished)
    }

    @Test
    @UseCase(id = "UC-001", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Request Limit the Data Provider does not publish starts at the default, keeps the Operator's value within the range at the next start, and a published one is not changed by the Operator's setting")
    fun notPublishedRequestLimitIsAnOperatorSetting() = runBlocking {
        val registry = RoomPluginRegistryStore(main.registry())
        val openBanking = DataProviderRegistration(
            "Aurora Open Banking", "example-open-banking", true, true, 360, RequestLimitDeclaration.NotPublished(50, 10..500),
        )
        val feeds = DataProviderRegistration("Kestrel Feeds", "example-feeds", true, false, 60, published(20))
        val first = registry.reconcile(listOf(feeds, openBanking), emptyList()).dataProviders.associateBy { it.sourceKey }
        assertEquals(50, first.getValue("example-open-banking").requestLimit)
        assertFalse(first.getValue("example-open-banking").requestLimitPublished)

        registry.storeRequestLimit(first.getValue("example-open-banking").id, 80)
        registry.storeRequestLimit(first.getValue("example-feeds").id, 300)
        val again = registry.reconcile(listOf(feeds, openBanking), emptyList()).dataProviders.associateBy { it.sourceKey }
        val narrower = registry.reconcile(
            listOf(openBanking.copy(requestLimit = RequestLimitDeclaration.NotPublished(40, 20..60))),
            emptyList(),
        ).dataProviders.single()

        assertEquals(80, again.getValue("example-open-banking").requestLimit)
        assertEquals(20, again.getValue("example-feeds").requestLimit)
        assertEquals(40, narrower.requestLimit)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 3: a Data Provider's request is counted durably; at the Request Limit in the preceding 24 hours none is stored")
    fun providerRequestsAreCountedWithinTheLimit() = runBlocking {
        val registry = RoomPluginRegistryStore(main.registry())
        val id = registry.reconcile(listOf(DataProviderRegistration("Kestrel Feeds", "example-feeds", true, false, 60, published(2))), emptyList())
            .dataProviders.single().id
        val requests = RoomProviderRequestStore(main.providerRequests())
        fun request(at: Instant) = NewProviderRequest(id, null, ProviderRequestKind.Other, false, at)
        val window = now.minus(Duration.ofHours(24))

        assertTrue(requests.admitWithinLimit(request(now.minus(Duration.ofHours(25))), window.minus(Duration.ofHours(1)), 2))
        assertTrue(requests.admitWithinLimit(request(now.minus(Duration.ofHours(1))), window, 2))
        assertTrue(requests.admitWithinLimit(request(now), window, 2))
        assertFalse(requests.admitWithinLimit(request(now), window, 2))
        assertEquals(2, requests.sentSince(window).size)
    }
}

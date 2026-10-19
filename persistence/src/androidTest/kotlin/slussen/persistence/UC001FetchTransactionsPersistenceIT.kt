package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.BackOffCause
import slussen.core.port.DataProviderRegistration
import slussen.core.port.FetchOutcome
import slussen.core.port.NewBackOff
import slussen.core.port.NewFetch
import slussen.core.port.StartedBy
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * The Fetches and Back-offs of Fetch Transactions (UC-001) in `slussen.db` on a device or emulator: what the import
 * status reads back, the housekeeping of the Fetch history, and that both survive a restart (ADR-002 §1, §3).
 */
@DisplayName("UC-001: Fetch Transactions, on the device")
class UC001FetchTransactionsPersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private suspend fun registeredFeeds(storage: SlussenStorage): Long {
        val profile = ExampleData.feedsProfile
        val rows = storage.registry.reconcile(
            listOf(
                DataProviderRegistration(
                    profile.name, profile.sourceKey, profile.givesStableReference, profile.needsBankConsent,
                    profile.defaultFetchCooldownMinutes, profile.requestLimit,
                ),
            ),
            emptyList(),
        )
        return rows.dataProviders.single().id
    }

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 11: a recorded Fetch is read back with its time, who started it and its outcome, most recent first, also after a restart")
    fun recordedFetchesSurviveARestart() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val feeds = registeredFeeds(storage)
        storage.fetches.recordFetch(NewFetch(feeds, now, StartedBy.Operator, FetchOutcome.Fetched), keep = 60)
        storage.fetches.recordFetch(NewFetch(feeds, now.plusSeconds(600), StartedBy.Operator, FetchOutcome.ServedFromFetchCooldown), keep = 60)
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)

        assertEquals(
            listOf(FetchOutcome.ServedFromFetchCooldown, FetchOutcome.Fetched),
            restarted.fetches.fetchesOf(feeds).map { it.outcome },
        )
        assertEquals(listOf(now.plusSeconds(600), now), restarted.importStatus.recentFetches().map { it.startedAt })
        assertEquals(StartedBy.Operator, restarted.importStatus.recentFetches().first().startedBy)
        restarted.close()
    }

    @Test
    @UseCase(id = "UC-001", scenario = "Main Success Scenario")
    @DisplayName("Step 11: only the most recent Fetches are kept, as many as the Operator Setting says, in the same transaction")
    fun onlyTheMostRecentFetchesAreKept() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val feeds = registeredFeeds(storage)

        repeat(4) { storage.fetches.recordFetch(NewFetch(feeds, now.plusSeconds(it * 60L), StartedBy.Operator, FetchOutcome.Fetched), keep = 2) }

        assertEquals(listOf(now.plusSeconds(180), now.plusSeconds(120)), storage.fetches.fetchesOf(feeds).map { it.startedAt })
        storage.close()
    }

    @Test
    @UseCase(id = "UC-001", scenario = "A5: Data Provider Asks Slussen to Wait", businessRules = ["BR-004"])
    @DisplayName("A5, BR-004: a Back-off is kept with its cause and end and survives a restart, so a restart does not end it")
    fun backOffSurvivesARestart() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val feeds = registeredFeeds(storage)
        val endsAt = now.plus(Duration.ofHours(8))
        storage.fetches.startBackOff(NewBackOff(feeds, null, BackOffCause.RequestToWait, now, endsAt))
        storage.close()

        val restarted = SlussenStorage.open(DeviceFiles.context, clock)

        val backOff = restarted.fetches.backOffsOf(feeds).single()
        assertEquals(BackOffCause.RequestToWait, backOff.cause)
        assertEquals(endsAt, backOff.endsAt)
        assertEquals(null, backOff.bankId)
        assertEquals(listOf(backOff), restarted.importStatus.backOffs())
        restarted.close()
    }
}

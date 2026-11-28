package slussen.app.ui.fetches

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.budget.RemedyAction
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredFetch
import slussen.core.port.StoredFetchResult
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.EveryStatusScenario
import java.time.Duration
import java.time.Instant

/**
 * The recent Fetches on the screen (UC-008 A5, BR-008; UC-001 step 11, A1-A3, A19, A20; `docs/mockups/fetch-history.html`):
 * what the screen decides to show for each state of the list and of an opened Fetch, from the read model of `:core`,
 * whose rules `UC008RecentFetchesTest` tests. Every name is invented (C-005); the clock reads 2026-10-15 12:00 UTC,
 * 14:00 in the invented device time zone.
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-008: Check Import Status, the recent Fetches on the screen")
class UC008RecentFetchesViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val scenario = EveryStatusScenario(slice)
    private val now: Instant get() = slice.clock.instant()
    private var nextFetchId = 700L

    private fun ago(minutes: Long): Instant = now.minus(Duration.ofMinutes(minutes))

    private fun accounts() = runBlocking {
        scenario.serve()
        scenario.addBankAccount("Kestrel Checking", "Checking")
        scenario.addBankAccount("Kestrel Reserve", "Reserve Savings", slussen.core.port.BankAccountType.Savings)
        scenario.addBankAccount("Pinecrest Rewards Card", "Rewards Card", slussen.core.port.BankAccountType.CreditCard)
    }

    private fun fetch(at: Instant, outcome: FetchOutcome, vararg results: Pair<String, FetchResultOutcome>, startedBy: StartedBy = StartedBy.Operator): Long {
        val id = nextFetchId++
        slice.importStatusStore.fetches += StoredFetch(
            id, slice.feedsId, at, startedBy, outcome, results.map { (name, result) -> StoredFetchResult(scenario.bankAccount(name), result, 2) },
        )
        return id
    }

    private fun screen() = RecentFetchesViewModel(fixture.services).also { it.load() }

    private fun RecentFetchesViewModel.ui(): RecentFetchesUi = state.value.ui!!

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5, BR-008 (fetch-history.html#list): each Fetch shows its local time, Source, who started it and its outcome by its specified name; a Fetch with Bank Accounts adds how many were fetched and which were not, a Fetch without adds nothing")
    fun listLines() {
        accounts()
        fetch(ago(300), FetchOutcome.Fetched, "Kestrel Checking" to FetchResultOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.Fetched, startedBy = StartedBy.Schedule)
        fetch(ago(60), FetchOutcome.Failed)
        fetch(
            ago(30), FetchOutcome.Fetched,
            "Kestrel Checking" to FetchResultOutcome.Fetched,
            "Kestrel Reserve" to FetchResultOutcome.NotDelivered,
            "Pinecrest Rewards Card" to FetchResultOutcome.SignInNeeded,
        )

        val ui = screen().ui()

        assertEquals(FetchesTexts.LIST_NOTE, ui.note)
        assertNull(ui.empty)
        assertEquals(listOf("2026-10-15 13:30 · Kestrel Feeds · started by you", "2026-10-15 13:00 · Kestrel Feeds · started by you", "2026-10-15 09:00 · Kestrel Feeds · Fetch Schedule"), ui.lines.map { it.title })
        val newest = ui.lines[0]
        assertEquals("Fetched", newest.outcome)
        assertEquals(
            "Bank Accounts fetched: 1 of 3 · Kestrel Reserve: not delivered · Pinecrest Rewards Card: the Bank's sign-in needs you",
            newest.summary,
        )
        assertTrue(newest.opens)
        val failed = ui.lines[1]
        assertEquals("Failed", failed.outcome)
        assertNull(failed.summary)
        assertFalse(failed.opens)
        val old = ui.lines[2]
        assertEquals("Bank Accounts fetched: 2 of 2", old.summary)
        assertEquals("Nothing needed now", old.need)
        assertFalse(old.asksNow)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A19, A20, step 11 (fetch-history.html#list): the latest Fetch says what is still needed now with the action for each Bank Account; the Fetch before it, which the latest one answered, is history and says so")
    fun stillNeededNowAndHistory() {
        accounts()
        fetch(ago(120), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.NotDelivered)
        fetch(ago(30), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.Fetched, "Pinecrest Rewards Card" to FetchResultOutcome.SignInNeeded)

        val lines = screen().ui().lines

        assertEquals(
            "Still needed now: Pinecrest Rewards Card, the Bank's sign-in at Kestrel Feeds needs you: sign in to the Bank again at Kestrel Feeds, then fetch again",
            lines[0].need,
        )
        assertTrue(lines[0].asksNow)
        assertEquals("Nothing needed now", lines[1].need)
        assertFalse(lines[1].asksNow)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A19, A20 (fetch-history.html#fetch-detail): an opened Fetch shows the outcome of each Bank Account at the time, and under each whether something is still needed, with the Blocked Reason's advice and the action that leads to its remedy on the Data Provider's page")
    fun openedFetchWithWhatIsStillNeeded() {
        accounts()
        val id = fetch(
            ago(30), FetchOutcome.Fetched,
            "Kestrel Checking" to FetchResultOutcome.Fetched,
            "Kestrel Reserve" to FetchResultOutcome.NotDelivered,
            "Pinecrest Rewards Card" to FetchResultOutcome.SignInNeeded,
        )
        val screen = screen()

        screen.open(id)
        val detail = screen.ui().detail!!

        assertEquals("Fetch of 2026-10-15 13:30", detail.title)
        assertEquals("Kestrel Feeds · started by you · Fetched · Bank Accounts fetched: 1 of 3", detail.subtitle)
        val checking = detail.bankAccounts[0]
        assertEquals(listOf("Kestrel Checking", "Fetched", "Nothing needed now"), listOf(checking.name, checking.result, checking.note))
        assertNull(checking.asks)
        val reserve = detail.bankAccounts[1]
        assertEquals("Not Delivered", reserve.result)
        assertNull(reserve.note)
        assertEquals("Still needed: Kestrel Reserve, not delivered by Kestrel Feeds (13:30): fetch again", reserve.asks)
        assertEquals(
            "Kestrel Feeds answered without this Bank Account; its Bank Transactions and Account Balance are kept unchanged. " +
                "Check at Kestrel Feeds that Kestrel Reserve is still connected there, then fetch again.",
            reserve.advice,
        )
        assertEquals(RemedyAction("Fetch again", available = true, service = ServiceKey.dataProvider(slice.feedsId), note = null), reserve.action)
        val card = detail.bankAccounts[2]
        assertEquals("Sign-In Needed", card.result)
        assertEquals(
            "Still needed: Pinecrest Rewards Card, the Bank's sign-in at Kestrel Feeds needs you: sign in to the Bank again at Kestrel Feeds, then fetch again",
            card.asks,
        )
        assertEquals(RemedyAction("Fetch again", available = true, service = ServiceKey.dataProvider(slice.feedsId), note = null), card.action)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A2 (fetch-history.html#fetch-detail-cooldown): a Fetch served from the Fetch Cooldown says which Bank Account it read and, for each other one, which earlier Fetch it came from; it asks nothing itself")
    fun openedServedFetch() {
        accounts()
        fetch(ago(40), FetchOutcome.Fetched, "Kestrel Checking" to FetchResultOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.Fetched)
        val served = fetch(ago(15), FetchOutcome.ServedFromFetchCooldown, "Pinecrest Rewards Card" to FetchResultOutcome.Fetched)
        val screen = screen()

        val line = screen.ui().lines.first()
        assertEquals("Served From Fetch Cooldown", line.outcome)
        assertEquals("Read: Pinecrest Rewards Card · served from earlier Fetches: 2", line.summary)
        assertEquals("Nothing needed now", line.need)

        screen.open(served)
        val accounts = screen.ui().detail!!.bankAccounts

        assertEquals(listOf("Pinecrest Rewards Card", "Kestrel Checking", "Kestrel Reserve"), accounts.map { it.name })
        assertEquals(listOf("Fetched", "Served", "Served"), accounts.map { it.result })
        assertEquals("Nothing needed now", accounts[0].note)
        assertEquals("From the Fetch of 13:20", accounts[1].note)
        assertEquals("From the Fetch of 13:20", accounts[2].note)
        assertTrue(accounts.all { it.asks == null && it.action == null })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A1, A3, A6, A7 (fetch-history.html#list): a Fetch that was held back by a wait, the Request Limit, a failure or refused credentials says what is current or past and whether it asks something")
    fun fetchesWithoutBankAccounts() {
        accounts()
        val waitEnds = ago(60).plus(Duration.ofHours(8))
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, slussen.core.port.BackOffCause.RequestToWait, ago(120), ago(90))
        fetch(ago(100), FetchOutcome.WaitingForBackOff)
        fetch(ago(80), FetchOutcome.RequestLimitReached)
        fetch(ago(70), FetchOutcome.Failed)
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, slussen.core.port.BackOffCause.RequestToWait, ago(60), waitEnds)
        fetch(ago(50), FetchOutcome.WaitingForBackOff)

        val lines = screen().ui().lines

        assertEquals("Waiting for Kestrel Feeds until 21:00 · nothing needed now", lines[0].need)
        assertEquals("History: the Fetch of 13:10 came after it and ended Waiting For Back-off · nothing needed now", lines[1].need)
        assertEquals("History: the Request Limit of Kestrel Feeds is not reached now · nothing needed now", lines[2].need)
        assertEquals("History: the wait ended 12:30 · nothing needed now", lines[3].need)
        assertTrue(lines.none { it.opens || it.summary != null })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A6, A7: the latest Fetch of a Data Provider that failed, or that its credentials were refused for, asks for the action that removes it")
    fun latestFailedAndRefusedFetchesAsk() {
        accounts()
        // The credentials were accepted at 12:00; a refusal only asks for new ones when it is later than that.
        slice.clock.advance(Duration.ofHours(1))
        fetch(ago(30), FetchOutcome.Failed)

        val failed = screen().ui().lines.single()

        assertEquals("Still needed now: Kestrel Feeds, Fetch failed (14:30): fetch again", failed.need)
        assertTrue(failed.asksNow)

        fetch(ago(10), FetchOutcome.CredentialsRefused)
        val lines = screen().ui().lines

        assertEquals("Still needed now: Kestrel Feeds, credentials refused: enter the credentials again", lines[0].need)
        assertEquals("History: the Fetch of 14:50 came after it and ended Credentials Refused · nothing needed now", lines[1].need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A6 (review 2a): the latest Fetch that failed as a whole, whose credentials were refused, or that held a Bank Account back without the Request Limit holding it now, has the action that leads to its remedy at its own line; a wait or an older Fetch has none")
    fun wholeFetchRemedyIsAControlAtItsLine() {
        accounts()
        slice.clock.advance(Duration.ofHours(1))
        val feeds = ServiceKey.dataProvider(slice.feedsId)
        fetch(ago(30), FetchOutcome.Failed)

        val failed = screen().ui().lines.single()

        assertFalse(failed.opens)
        assertEquals(listOf(RemedyAction("Fetch again", available = true, service = feeds, note = null)), failed.actions)

        fetch(ago(10), FetchOutcome.CredentialsRefused)
        val lines = screen().ui().lines

        assertEquals(listOf(RemedyAction("Enter credentials", available = true, service = feeds, note = null)), lines[0].actions)
        assertEquals(emptyList<RemedyAction>(), lines[1].actions)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A2 (review 1b): a Fetch within the Fetch Cooldown that the Request Limit stopped says which Bank Account it did not read; with the limit not reached now it asks for a Fetch with the action, with the limit reached it only waits")
    fun heldBackByTheRequestLimit() {
        accounts()
        fetch(ago(40), FetchOutcome.Fetched, "Kestrel Checking" to FetchResultOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.Fetched)
        slice.connectionStore.bankAccountList.replaceAll { if (it.name == "Pinecrest Rewards Card") it.copy(identifiedAt = ago(20)) else it }
        val id = fetch(ago(15), FetchOutcome.ServedFromFetchCooldown)

        val line = screen().ui().lines.first()

        assertEquals("No Bank Account read · served from earlier Fetches: 2 · not read, held back by the Request Limit: Pinecrest Rewards Card", line.summary)
        assertEquals("Still needed now: Pinecrest Rewards Card not read, held back by the Request Limit; fetch again", line.need)
        assertEquals(listOf(RemedyAction("Fetch again", available = true, service = ServiceKey.dataProvider(slice.feedsId), note = null)), line.actions)
        val screen = screen()
        screen.open(id)
        val held = screen.ui().detail!!.bankAccounts.single { it.name == "Pinecrest Rewards Card" }
        assertEquals("Not read", held.result)
        assertEquals("Held back by the Request Limit when this Fetch ran; the limit is not reached now", held.note)
        assertEquals(RemedyAction("Fetch again", available = true, service = ServiceKey.dataProvider(slice.feedsId), note = null), held.action)

        slice.providerRequests.requests.clear()
        repeat(20) { index ->
            slice.providerRequests.requests += slussen.core.port.StoredProviderRequest(slice.feedsId, null, slussen.core.port.ProviderRequestKind.Other, false, ago(120L - index))
        }
        val waiting = screen().ui().lines.first()

        assertEquals("Pinecrest Rewards Card: not read, held back by the Request Limit until 10-16 12:00 · nothing needed from you now", waiting.need)
        assertTrue(waiting.actions.isEmpty())
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5 (fetch-history.html#empty): without a Fetch the screen says why the list is empty, with the number of Fetches kept, and leads to the Fetch of every Data Provider on the Overview")
    fun noFetchYet() {
        accounts()

        val ui = screen().ui()

        assertTrue(ui.lines.isEmpty())
        assertEquals(
            FetchesEmptyUi(
                "No Fetches yet",
                "No Fetch of a Data Provider is recorded on this device. Each Fetch is listed here, the most recent ones as the number of recent Fetches kept, " +
                    "an Operator Setting (60 now), allows.",
                "Fetch all Data Providers on the Overview",
            ),
            ui.empty,
        )
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5: an opened Fetch closes with Close or system back, back answers whether it closed one, and opening the screen again shows the list, never the Fetch left open")
    fun openCloseAndBack() {
        accounts()
        val id = fetch(ago(30), FetchOutcome.Fetched, "Kestrel Checking" to FetchResultOutcome.Fetched)
        val screen = screen()

        assertFalse(screen.back())
        screen.open(id)
        assertEquals("Fetch of 2026-10-15 13:30", screen.ui().detail!!.title)
        assertTrue(screen.back())
        assertNull(screen.ui().detail)
        screen.open(id)
        screen.close()
        assertNull(screen.ui().detail)
        screen.open(id)
        screen.load()
        assertNull(screen.ui().detail)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"], requirements = ["NFR-023"])
    @DisplayName("NFR-023: when an operation started elsewhere ends, the open list shows what it recorded and no longer calls a need that it answered still needed; an opened Fetch that is not kept any more gives way to the list with a line that says so")
    fun followsOperationsAndTheKeptNumber() {
        runBlocking { scenario.compareEveryStatus() }
        val id = fetch(ago(30), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.NotDelivered)
        val screen = screen()
        assertEquals(1, screen.ui().lines.size)
        assertTrue(screen.ui().lines.single().asksNow)
        screen.open(id)

        fetch(ago(5), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.Fetched)
        runBlocking { slice.comparison.readAgain() }

        assertEquals(2, screen.ui().lines.size)
        assertFalse(screen.ui().lines.any { it.asksNow })
        assertEquals("Read since by the Fetch of 14:05 · nothing needed now", screen.ui().detail!!.bankAccounts.single().note)

        slice.settings.settings = slice.settings.settings.copy(fetchHistorySize = 1)
        runBlocking { slice.comparison.readAgain() }

        assertNull(screen.ui().detail)
        assertEquals(FetchesTexts.NO_LONGER_KEPT, screen.ui().notListed)
        assertEquals(1, screen.ui().lines.size)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"], requirements = ["NFR-023"])
    @DisplayName("NFR-023: stored Fetches that cannot be read are said on the screen, which keeps what it showed, and show again once they can be read; never a crash")
    fun neverCrashes() {
        accounts()
        fetch(ago(30), FetchOutcome.Failed)
        val screen = screen()
        assertEquals(1, screen.ui().lines.size)

        slice.importStatusStore.failsToRead = true
        screen.load()

        assertEquals(FetchesTexts.NOT_READ, screen.state.value.failure)
        assertEquals(1, screen.ui().lines.size)
        val unread = screen()
        assertNull(unread.state.value.ui)
        assertEquals(FetchesTexts.NOT_READ, unread.state.value.failure)

        slice.importStatusStore.failsToRead = false
        unread.load()

        assertNull(unread.state.value.failure)
        assertEquals(1, unread.ui().lines.size)
    }
}

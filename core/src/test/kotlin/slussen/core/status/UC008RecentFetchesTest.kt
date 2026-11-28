package slussen.core.status

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountType
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.ProviderRequestKind
import slussen.core.port.StoredProviderRequest
import slussen.core.port.StartedBy
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredFetch
import slussen.core.port.StoredFetchResult
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.EveryStatusScenario
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant

/**
 * What each recent Fetch says for each Bank Account and whether it still asks something of the Operator now (UC-008 A5,
 * BR-008, UC-001 step 11, A19, A20; `docs/mockups/fetch-history.html`), derived from stored data only, and the same
 * answer the Blocked Reasons of the Budget give. Every name is invented (C-005).
 */
@DisplayName("UC-008: Check Import Status, the recent Fetches")
class UC008RecentFetchesTest {
    private val slice = TestSlice()
    private val scenario = EveryStatusScenario(slice)
    private val now: Instant get() = slice.clock.instant()
    private var nextFetchId = 100L

    private fun ago(minutes: Long): Instant = now.minus(Duration.ofMinutes(minutes))

    /** Household served, with Kestrel Checking, Kestrel Reserve and Pinecrest Rewards Card fetched through Kestrel Feeds. */
    private suspend fun accounts() {
        scenario.serve()
        scenario.addBankAccount("Kestrel Checking", "Checking")
        scenario.addBankAccount("Kestrel Reserve", "Reserve Savings", BankAccountType.Savings)
        scenario.addBankAccount("Pinecrest Rewards Card", "Rewards Card", BankAccountType.CreditCard)
    }

    private fun id(name: String) = scenario.bankAccount(name)

    private fun fetch(
        at: Instant,
        outcome: FetchOutcome,
        vararg results: Pair<String, FetchResultOutcome>,
        providerId: Long = slice.feedsId,
        startedBy: StartedBy = StartedBy.Operator,
    ): Long {
        val fetchId = nextFetchId++
        slice.importStatusStore.fetches += StoredFetch(
            fetchId, providerId, at, startedBy, outcome, results.map { (name, result) -> StoredFetchResult(id(name), result, transactionCount = 2) },
        )
        return fetchId
    }

    private suspend fun recent(fetchId: Long): RecentFetch = slice.status.recentFetches().fetches.single { it.fetchId == fetchId }

    private fun RecentFetch.line(name: String): FetchBankAccount = bankAccounts.single { it.name == name }

    private suspend fun blockedOf(name: String): List<Blocked> =
        slice.status.workspace(slice.connectionStore.budgets().single { it.name == "Household" }.id).bankAccounts.single { it.name == name }.blocked

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5, BR-008: the most recent Fetches come newest first with Source, time, who started them and outcome, as many as the Operator Setting keeps, and the number kept is given")
    fun newestFirstAsManyAsKept() = runTest {
        accounts()
        repeat(12) { index ->
            fetch(
                ago(index.toLong()),
                if (index == 0) FetchOutcome.Failed else FetchOutcome.Fetched,
                providerId = if (index % 2 == 0) slice.feedsId else slice.openBankingId,
                startedBy = if (index == 1) StartedBy.Schedule else StartedBy.Operator,
            )
        }
        slice.settings.settings = slice.settings.settings.copy(fetchHistorySize = 10)

        val history = slice.status.recentFetches()

        assertEquals(10, history.kept)
        assertEquals(10, history.fetches.size)
        assertEquals(history.fetches.sortedByDescending { it.startedAt }, history.fetches)
        val newest = history.fetches[0]
        assertEquals(listOf("Kestrel Feeds", now, StartedBy.Operator, FetchOutcome.Failed), listOf(newest.sourceName, newest.startedAt, newest.startedBy, newest.outcome))
        val second = history.fetches[1]
        assertEquals(listOf("Aurora Open Banking", StartedBy.Schedule, FetchOutcome.Fetched), listOf(second.sourceName, second.startedBy, second.outcome))
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5 (fetch-history.html#empty): without a Fetch the list is empty and says how many Fetches are kept, for the text that says why it is empty")
    fun noFetchYet() = runTest {
        accounts()

        val history = slice.status.recentFetches()

        assertEquals(emptyList<RecentFetch>(), history.fetches)
        assertEquals(60, history.kept)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A19, A20, step 11 (fetch-history.html#fetch-detail): the latest Fetch gives each Bank Account its own outcome, and what it still asks is exactly the Blocked Reason the Budget shows for that Bank Account, with its remedy")
    fun latestFetchStillAsksWhatTheBudgetShows() = runTest {
        accounts()
        val fetchId = fetch(
            ago(30), FetchOutcome.Fetched,
            "Kestrel Checking" to FetchResultOutcome.Fetched,
            "Kestrel Reserve" to FetchResultOutcome.NotDelivered,
            "Pinecrest Rewards Card" to FetchResultOutcome.SignInNeeded,
        )

        val entry = recent(fetchId)

        assertEquals(FetchedWhat.Recorded(FetchResultOutcome.Fetched), entry.line("Kestrel Checking").what)
        assertEquals(AccountNeed.Delivered, entry.line("Kestrel Checking").need)
        val reserve = blockedOf("Kestrel Reserve").single()
        val card = blockedOf("Pinecrest Rewards Card").single()
        assertEquals(BlockedReason.NotDelivered, reserve.reason)
        assertEquals(AccountNeed.Still(reserve), entry.line("Kestrel Reserve").need)
        assertEquals(BlockedReason.SignInNeeded, card.reason)
        assertEquals(AccountNeed.Still(card), entry.line("Pinecrest Rewards Card").need)
        assertEquals(FetchNeed.Needed(listOf(reserve, card)), entry.need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5 (fetch-history.html#list): a Fetch that delivered every Bank Account asks nothing")
    fun everyBankAccountDelivered() = runTest {
        accounts()
        val fetchId = fetch(
            ago(10), FetchOutcome.Fetched,
            "Kestrel Checking" to FetchResultOutcome.Fetched,
            "Kestrel Reserve" to FetchResultOutcome.Fetched,
        )

        val entry = recent(fetchId)

        assertEquals(FetchNeed.NotNeeded(null), entry.need)
        assertEquals(listOf(AccountNeed.Delivered, AccountNeed.Delivered), entry.bankAccounts.map { it.need })
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5 (fetch-history.html#list): once a later Fetch read a Bank Account that an earlier one did not deliver, the earlier Fetch is history for it and says which Fetch read it; a later Fetch that failed it again carries the need, and the earlier one points to it")
    fun laterFetchesTakeOverTheNeed() = runTest {
        accounts()
        val first = fetch(ago(120), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.NotDelivered, "Pinecrest Rewards Card" to FetchResultOutcome.Failed)
        fetch(ago(60), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.Fetched, "Pinecrest Rewards Card" to FetchResultOutcome.SignInNeeded)

        val entry = recent(first)

        assertEquals(AccountNeed.ReadSince(ago(60)), entry.line("Kestrel Reserve").need)
        assertEquals(AccountNeed.LaterFetch(ago(60), FetchResultOutcome.SignInNeeded), entry.line("Pinecrest Rewards Card").need)
        assertEquals(FetchNeed.NotNeeded(null), entry.need)
        val latest = slice.status.recentFetches().fetches.first()
        assertEquals(FetchNeed.Needed(listOf(blockedOf("Pinecrest Rewards Card").single())), latest.need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5: a Fetch of another Data Provider changes nothing for a Bank Account of this one")
    fun anotherDataProvidersFetchIsNotLater() = runTest {
        accounts()
        val first = fetch(ago(120), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.NotDelivered)
        fetch(ago(30), FetchOutcome.Fetched, providerId = slice.openBankingId)

        val entry = recent(first)

        assertEquals(AccountNeed.Still(blockedOf("Kestrel Reserve").single()), entry.line("Kestrel Reserve").need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A2, step 11 (fetch-history.html#fetch-detail-cooldown): a Fetch served from the Fetch Cooldown names the Bank Account it read and, for each other Bank Account the Fetch selects, the earlier Fetch that served it; it asks nothing itself")
    fun servedFromTheFetchCooldown() = runTest {
        accounts()
        val earlier = fetch(
            ago(40), FetchOutcome.Fetched,
            "Kestrel Checking" to FetchResultOutcome.Fetched,
            "Kestrel Reserve" to FetchResultOutcome.Fetched,
        )
        val servedId = fetch(ago(15), FetchOutcome.ServedFromFetchCooldown, "Pinecrest Rewards Card" to FetchResultOutcome.Fetched)

        val served = recent(servedId)

        assertEquals(FetchedWhat.Recorded(FetchResultOutcome.Fetched), served.line("Pinecrest Rewards Card").what)
        assertEquals(FetchedWhat.Served(EarlierFetch(ago(40), FetchResultOutcome.Fetched)), served.line("Kestrel Checking").what)
        assertEquals(FetchedWhat.Served(EarlierFetch(ago(40), FetchResultOutcome.Fetched)), served.line("Kestrel Reserve").what)
        assertEquals(AccountNeed.SeeEarlier, served.line("Kestrel Reserve").need)
        assertEquals(FetchNeed.NotNeeded(null), served.need)
        assertEquals(AccountNeed.Delivered, recent(earlier).line("Kestrel Reserve").need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A2, step 3 (review 1a): a Fetch served from the Fetch Cooldown lists exactly the Bank Accounts a Fetch selects: not a Retired Account, not one without an Account Mapping or without a reference at the Data Provider, and one whose balance alone comes from the Data Provider")
    fun servedListsOnlyWhatAFetchSelects() = runTest {
        accounts()
        val template = slice.connectionStore.bankAccountList.first()
        slice.connectionStore.bankAccountList += template.copy(
            id = 9100, name = "Harbor Health Savings", dataProviderId = null, fileFormatId = slice.plugins.fileFormats.single().id,
            balanceDataProviderId = slice.feedsId, providerReference = null, balanceProviderReference = "bal-9100",
            generatedIdentifier = "00000000-0000-4000-8000-000000009100", mapping = template.mapping?.copy(id = 9101, bankAccountId = 9100),
        )
        slice.connectionStore.bankAccountList += template.copy(
            id = 9200, name = "Kestrel Unreferenced", providerReference = null,
            generatedIdentifier = "00000000-0000-4000-8000-000000009200", mapping = template.mapping?.copy(id = 9201, bankAccountId = 9200),
        )
        fetch(
            ago(40), FetchOutcome.Fetched,
            "Kestrel Checking" to FetchResultOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.Fetched,
            "Pinecrest Rewards Card" to FetchResultOutcome.Fetched,
        )
        slice.importStatusStore.fetches.replaceAll { fetch ->
            fetch.copy(results = fetch.results + StoredFetchResult(9100, FetchResultOutcome.Fetched, 0))
        }
        val servedId = fetch(ago(15), FetchOutcome.ServedFromFetchCooldown)
        slice.connectionStore.bankAccountList.replaceAll {
            when (it.name) {
                "Kestrel Reserve" -> it.copy(retired = true)
                "Pinecrest Rewards Card" -> it.copy(mapping = null)
                else -> it
            }
        }

        val served = recent(servedId)

        assertEquals(setOf("Kestrel Checking", "Harbor Health Savings"), served.bankAccounts.map { it.name }.toSet())
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A2 (review 1b): a Bank Account not read since it was added was not read by a Fetch within the Fetch Cooldown that the Request Limit stopped; it says so, as not read, held back by the Request Limit, and never as served or as nothing needed")
    fun heldBackByTheRequestLimit() = runTest {
        accounts()
        fetch(ago(40), FetchOutcome.Fetched, "Kestrel Checking" to FetchResultOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.Fetched)
        slice.connectionStore.bankAccountList.replaceAll { if (it.name == "Pinecrest Rewards Card") it.copy(identifiedAt = ago(20)) else it }
        val servedId = fetch(ago(15), FetchOutcome.ServedFromFetchCooldown)

        val served = recent(servedId)

        assertEquals(FetchedWhat.Served(EarlierFetch(ago(40), FetchResultOutcome.Fetched)), served.line("Kestrel Checking").what)
        assertEquals(FetchedWhat.HeldByRequestLimit, served.line("Pinecrest Rewards Card").what)
        assertEquals(AccountNeed.NotReadYet(null), served.line("Pinecrest Rewards Card").need)
        assertEquals(FetchNeed.NotReadYet(listOf("Pinecrest Rewards Card"), null), served.need)

        slice.providerRequests.requests.clear()
        repeat(20) { index ->
            slice.providerRequests.requests += slussen.core.port.StoredProviderRequest(slice.feedsId, null, slussen.core.port.ProviderRequestKind.Other, false, ago(120L - index))
        }
        val until = ago(120).plus(Duration.ofHours(24))
        assertEquals(AccountNeed.NotReadYet(until), recent(servedId).line("Pinecrest Rewards Card").need)
        assertEquals(FetchNeed.NotReadYet(listOf("Pinecrest Rewards Card"), until), recent(servedId).need)

        fetch(ago(5), FetchOutcome.Fetched, "Pinecrest Rewards Card" to FetchResultOutcome.Fetched)

        assertEquals(AccountNeed.ReadSince(ago(5)), recent(servedId).line("Pinecrest Rewards Card").need)
        assertEquals(FetchNeed.NotNeeded(null), recent(servedId).need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A5 (review 4a, 5): a stored Fetch of a Data Provider this build no longer has does not make the list unreadable; and a Bank Account not recognised that is still mapped asks what the Budget shows for it")
    fun unknownDataProviderAndNotRecognised() = runTest {
        accounts()
        slice.importStatusStore.fetches += StoredFetch(50, 9999, ago(500), StartedBy.Operator, FetchOutcome.Failed, emptyList())
        slice.importStatusStore.fetches += StoredFetch(51, 9999, ago(400), StartedBy.Operator, FetchOutcome.CredentialsRefused, emptyList())
        val fetchId = fetch(ago(30), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.NotRecognised)

        val history = slice.status.recentFetches()

        assertEquals(3, history.fetches.size)
        assertEquals(FetchNeed.NotNeeded(null), history.fetches.single { it.fetchId == 51L }.need)
        val blocked = blockedOf("Kestrel Reserve").single()
        assertEquals(BlockedReason.UnrecognisedBankAccount, blocked.reason)
        assertEquals(AccountNeed.Still(blocked), recent(fetchId).line("Kestrel Reserve").need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A5: a Bank Account that is a Retired Account now, or has no Account Mapping, is no longer fetched and nothing is asked for it")
    fun noLongerFetched() = runTest {
        accounts()
        val fetchId = fetch(ago(30), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.Failed, "Pinecrest Rewards Card" to FetchResultOutcome.NotRecognised)
        slice.connectionStore.bankAccountList.replaceAll { if (it.name == "Kestrel Reserve") it.copy(retired = true) else if (it.name == "Pinecrest Rewards Card") it.copy(mapping = null) else it }

        val entry = recent(fetchId)

        assertEquals(AccountNeed.NoLongerFetched, entry.line("Kestrel Reserve").need)
        assertEquals(AccountNeed.NoLongerFetched, entry.line("Pinecrest Rewards Card").need)
        assertEquals(FetchNeed.NotNeeded(null), entry.need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A9: a Bank Account the Bank refused its consent for asks for it while no valid Bank Consent exists, and nothing once the Data Provider needs none")
    fun notConsentedEndsWithTheReason() = runTest {
        accounts()
        val fetchId = fetch(ago(30), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.NotConsented)

        val entry = recent(fetchId)

        // Kestrel Feeds needs no Bank Consent, so nothing is missing now: the reason ended.
        assertEquals(AccountNeed.Ended, entry.line("Kestrel Reserve").need)
        assertEquals(FetchNeed.NotNeeded(null), entry.need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A1 (fetch-history.html#list): a Fetch that met a Back-off asks nothing; while the Back-off holds it says when it ends, afterwards when it ended")
    fun waitingForBackOff() = runTest {
        accounts()
        val fetchId = fetch(ago(30), FetchOutcome.WaitingForBackOff)
        val waitEnds = ago(30).plus(Duration.ofHours(8))
        slice.importStatusStore.backOffList += StoredBackOff(slice.feedsId, null, BackOffCause.RequestToWait, ago(60), waitEnds)

        assertEquals(FetchNeed.NotNeeded(FetchNote.WaitEnds(waitEnds, over = false)), recent(fetchId).need)

        slice.clock.advance(Duration.ofHours(9))

        assertEquals(FetchNeed.NotNeeded(FetchNote.WaitEnds(waitEnds, over = true)), recent(fetchId).need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-008"])
    @DisplayName("A1: when no Back-off that covers the Fetch is stored, it still asks nothing and names no time")
    fun waitingWithoutAStoredBackOff() = runTest {
        accounts()
        val fetchId = fetch(ago(30), FetchOutcome.WaitingForBackOff)

        assertEquals(FetchNeed.NotNeeded(null), recent(fetchId).need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A3: a Fetch the Request Limit stopped asks nothing; it says until when the limit holds while it does, and that it is not reached when it is not")
    fun requestLimitReached() = runTest {
        accounts()
        val fetchId = fetch(ago(60), FetchOutcome.RequestLimitReached)

        assertEquals(FetchNeed.NotNeeded(FetchNote.RequestLimitUntil(null)), recent(fetchId).need)

        val limit = slice.plugins.dataProviders.single { it.stored.id == slice.feedsId }.stored.requestLimit
        slice.providerRequests.requests.clear()
        repeat(limit) { index ->
            slice.providerRequests.requests += slussen.core.port.StoredProviderRequest(
                slice.feedsId, null, slussen.core.port.ProviderRequestKind.Other, false, ago(120L - index),
            )
        }
        val held = recent(fetchId).need as FetchNeed.NotNeeded
        val until = (held.note as FetchNote.RequestLimitUntil).at
        assertEquals(ago(120).plus(Duration.ofHours(24)), until)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A6 (fetch-history.html#list): the latest Fetch of a Data Provider that failed asks for another Fetch; once a later Fetch exists the failed one is history and names it")
    fun failedFetch() = runTest {
        accounts()
        val failed = fetch(ago(90), FetchOutcome.Failed)

        val asking = recent(failed).need as FetchNeed.Needed
        assertEquals(listOf(BlockedReason.FailedFetch), asking.blocked.map { it.reason })
        assertEquals(Remedy.FetchAgain, asking.blocked.single().remedy)
        assertEquals(ago(90), asking.blocked.single().at)

        fetch(ago(30), FetchOutcome.Fetched, "Kestrel Checking" to FetchResultOutcome.Fetched)

        assertEquals(FetchNeed.NotNeeded(FetchNote.LaterFetch(ago(30), FetchOutcome.Fetched)), recent(failed).need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A7 (fetch-history.html#list): a Fetch the credentials were refused for asks for them again; once new credentials were accepted it is history and says when they were accepted")
    fun credentialsRefused() = runTest {
        accounts()
        val acceptedAt = now
        slice.clock.advance(Duration.ofHours(2))
        val refused = fetch(ago(30), FetchOutcome.CredentialsRefused)

        val asking = recent(refused).need as FetchNeed.Needed
        assertEquals(listOf(BlockedReason.CredentialsRefused), asking.blocked.map { it.reason })
        assertEquals(Remedy.EnterCredentials, asking.blocked.single().remedy)

        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to slussen.testing.ExampleData.SETUP_KEY))
        val newAcceptedAt = now
        slice.clock.advance(Duration.ofHours(1))

        assertTrue(newAcceptedAt.isAfter(acceptedAt))
        assertEquals(FetchNeed.NotNeeded(FetchNote.CredentialsAcceptedSince(newAcceptedAt)), recent(refused).need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A4, A9: a Fetch that recorded Not Consented, as the latest of a Data Provider that needs Bank Consents, asks for the Bank Consent while one is missing, and says that none is missing once it is granted")
    fun notConsentedFetch() = runTest {
        accounts()
        val bankId = slice.connectionStore.bankNamed("Aurora Bank").id
        val kestrel = slice.connectionStore.bankAccountList.first()
        slice.connectionStore.bankAccountList += kestrel.copy(
            id = 9001, name = "Aurora Current", bankId = bankId, providerBankId = bankId, dataProviderId = slice.openBankingId, providerReference = "aurora-1",
            generatedIdentifier = "00000000-0000-4000-8000-000000009001", mapping = kestrel.mapping?.copy(id = 9002, bankAccountId = 9001),
        )
        val fetchId = fetch(ago(30), FetchOutcome.NotConsented, providerId = slice.openBankingId)

        val asking = recent(fetchId).need as FetchNeed.Needed
        assertEquals(listOf(BlockedReason.MissingBankConsent), asking.blocked.map { it.reason })
        assertEquals(Remedy.GrantBankConsent, asking.blocked.single().remedy)

        slice.importStatusStore.consents += slussen.core.port.StoredBankConsent(7, bankId, slice.openBankingId, ago(60), now.plus(Duration.ofDays(30)))

        assertEquals(FetchNeed.NotNeeded(FetchNote.NoConsentMissingNow), recent(fetchId).need)
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A5: Recent Fetch Outcomes", businessRules = ["BR-006"])
    @DisplayName("A9, UC-008 BR-006: a Bank Account that reached its access limit, a wait that has not ended, asks nothing of the Operator, and the Fetch lists the wait among what is current")
    fun accessLimitIsAWaitNotAnAsk() = runTest {
        accounts()
        // E6: Access Limit Reached is recorded only through a Data Provider that needs a Bank Consent, and its Blocked Reason holds while the stored Provider Requests reach the limit (UC-001 BR-012).
        val reserve = slice.connectionStore.bankAccountList.indexOfFirst { it.name == "Kestrel Reserve" }
        slice.connectionStore.bankAccountList[reserve] = slice.connectionStore.bankAccountList[reserve].copy(dataProviderId = slice.openBankingId)
        repeat(4) { slice.providerRequests.requests += StoredProviderRequest(slice.openBankingId, id("Kestrel Reserve"), ProviderRequestKind.Transactions, true, ago(60L + it)) }
        val fetchId = fetch(ago(20), FetchOutcome.Fetched, "Kestrel Reserve" to FetchResultOutcome.AccessLimitReached, providerId = slice.openBankingId, startedBy = StartedBy.Schedule)

        val entry = recent(fetchId)
        val current = blockedOf("Kestrel Reserve").single { it.reason == BlockedReason.AccessLimitReached }

        assertEquals(Remedy.WaitUntilItEnds, current.remedy)
        assertEquals(AccountNeed.Still(current), entry.line("Kestrel Reserve").need)
        assertEquals(FetchNeed.NotNeeded(FetchNote.WaitingOn(listOf(current))), entry.need)
    }
}

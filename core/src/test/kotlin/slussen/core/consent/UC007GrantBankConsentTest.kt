package slussen.core.consent

import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.identity.BankAccountFingerprint
import slussen.core.identity.ConsentStateValue
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationRefusal
import slussen.core.operation.RunningOperation
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountType
import slussen.core.port.CoordinatorMode
import slussen.core.port.NewLeftOutBankAccount
import slussen.core.port.ProviderRequestKind
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredProviderRequest
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankChoice
import slussen.pluginapi.provider.BankList
import slussen.pluginapi.provider.ConsentAnswer
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentFailureKind
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.RevokeResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.value.BankAccountNumber
import slussen.pluginapi.value.Secret
import slussen.testing.ExampleData
import slussen.testing.ScriptedBankConsent
import slussen.testing.TestSlice
import java.time.Duration
import java.time.Instant

/**
 * UC-007 Grant Bank Consent in the core (`slussen.core.consent`), on the fakes of `:testing`: the Data Provider of the
 * slice that needs a Bank Consent ([TestSlice.openBanking]) with its scripted Bank Consent operations. Every value is
 * invented; the planted ones are easy to search for.
 */
@DisplayName("UC-007: Grant Bank Consent")
class UC007GrantBankConsentTest {
    private val slice = TestSlice()
    private val consent = slice.bankConsent
    private val consents = slice.bankConsents
    private val store = slice.consentStore
    private val now: Instant get() = slice.clock.instant()
    private val key get() = slice.openBankingKey
    private val dataProviderId get() = slice.openBankingId

    private val bank = "Aurora Sparbank (FI)"
    private val expiry: Instant = Instant.parse("2027-04-13T12:00:00Z")
    private val consentAddress = "https://aurora.example/sca?ticket=PLANTED-CONSENT-ADDRESS-3c1d"
    private val handle = "PLANTED-HANDLE-61f0"
    private val code = "PLANTED-CODE-9e2a"
    private val sessionText = "PLANTED-SESSION-REFERENCE-77aa"

    @BeforeEach
    fun credentialsAccepted() = runTest {
        slice.credentials.enter(key, slice.input("setupKey" to ExampleData.SETUP_KEY))
        consent.startAnswer = { ConsentStart.Started(consentAddress, Secret(handle)) }
        consent.completeAnswer = { _, _ -> completed() }
    }

    private fun delivered(reference: String, name: String, number: String? = null, bankReference: String? = bank) = DeliveredBankAccount(
        providerReference = reference,
        bankReference = bankReference,
        name = name,
        kind = "current",
        currency = ExampleData.EUR,
        number = number?.let(::BankAccountNumber),
        result = BankAccountResult.NotRead(NotReadCause.NotSelected),
    )

    private val household = delivered("lasting-household", "Household Current", "FI21 1234 5600 0007 85")
    private val savings = delivered("lasting-savings", "Rainy Day Savings", "FI14 9876 5400 0002 31")

    private fun completed(
        accounts: List<DeliveredBankAccount> = listOf(household, savings),
        expiresAt: Instant = expiry,
        session: String = sessionText,
    ) = ConsentCompletion.Completed(expiresAt, accounts, ConsentSession(session))

    private fun fingerprintOf(number: String) = BankAccountFingerprint.of(ExampleData.openBankingProfile.sourceKey, bank, BankAccountNumber(number))

    /** An added Bank Account of the slice's Data Provider, as UC-013 would have stored it. */
    private fun added(id: Long, reference: String, providerBankId: Long?, number: String? = null, name: String = "Added $id") =
        StoredBankAccount(
            id = id,
            generatedIdentifier = "00000000-0000-4000-8000-0000000000${id.toString().padStart(2, '0')}",
            bankId = providerBankId ?: 1,
            providerBankId = providerBankId,
            dataProviderId = dataProviderId,
            fileFormatId = null,
            balanceDataProviderId = null,
            name = name,
            currency = ExampleData.EUR,
            type = BankAccountType.Checking,
            startDate = null,
            providerReference = reference,
            bankAccountFingerprint = number?.let(::fingerprintOf),
            balanceProviderReference = null,
            identifiedAt = Instant.parse("2026-09-01T08:00:00Z"),
            retired = false,
            fundedByBudgetAccountId = null,
            transferPartnerBudgetAccountId = null,
            mapping = null,
        ).also { slice.connectionStore.bankAccountList += it }

    private suspend fun started(reference: String = bank): StartOutcome.Started =
        consents.start(key, reference, browserAvailable = true) as StartOutcome.Started

    private fun lastState(): String = consent.stateTexts().last()

    private fun answerTo(state: String, extra: String = "code=$code") =
        ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "$extra&state=$state")

    private suspend fun granted(reference: String = bank): CompletionOutcome.Granted {
        started(reference)
        return consents.complete(answerTo(lastState())) as CompletionOutcome.Granted
    }

    private suspend fun bankNames() = slice.connectionStore.banks().map { it.name }

    // ---- Main Success Scenario ---------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-004", "BR-009"])
    @DisplayName("Steps 2-10: a granted Bank Consent is stored with the expiry the Data Provider reported and its Bank Session, its Bank is created then, and the Bank Accounts it covers are its Offered Bank Accounts, to map")
    fun grantStoresTheBankConsentAndItsBankAccounts() = runTest {
        val start = started()
        assertEquals(bank, start.bankReference)
        assertEquals(consentAddress, start.consentAddress.address)
        assertEquals(ExampleData.CONSENT_RETURN_ADDRESS, start.returnAddress)
        assertEquals(now.plus(Duration.ofMinutes(15)), start.expiresAt)
        assertEquals(emptyList<String>(), bankNames(), "no Bank exists before the Bank Consent is granted (W6-0 item 8)")

        val outcome = consents.complete(answerTo(lastState())) as CompletionOutcome.Granted

        val stored = store.bankConsents().single()
        val theBank = slice.connectionStore.banks().single()
        assertEquals(bank, theBank.name)
        assertEquals(theBank.id, stored.bankId)
        assertEquals(dataProviderId, stored.dataProviderId)
        assertEquals(expiry, stored.expiresAt, "BR-004: the expiry the Data Provider reported, no length of Slussen's own")
        assertEquals(expiry, outcome.expiresAt)
        assertEquals(EarlierBankConsent.None, outcome.earlier)
        assertEquals(stored.id, store.bankSessions().single().bankConsentId)
        assertEquals(1, consent.handlesCompleted.size, "the handle stored with the Consent Request is handed back")
        // BR-001: one Strong Customer Authentication grants the Bank Consent for every Bank Account of the Bank.
        val offered = slice.offeredStore.offered()
        assertEquals(setOf("lasting-household", "lasting-savings"), offered.map { it.providerReference }.toSet())
        assertTrue(offered.all { it.bankId == theBank.id && it.dataProviderId == dataProviderId })
        assertEquals(listOf("0785", "0231"), offered.map { it.lastFourDigits })
        assertEquals(Coverage(2, emptyList(), emptyList(), emptyList(), toMap = 2, leftOut = 0, noLongerDelivered = emptyList()), outcome.coverage)
        assertTrue(store.consentRequests().single().answered)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-006"])
    @DisplayName("Step 2: the state is made by the core from the random source, 256 bits written as base64url without padding (ADR-008 §7), and only its SHA-256 digest is stored with the Consent Request")
    fun theStateIsTheCoresAndOnlyItsDigestIsStored() = runTest {
        started()
        val state = lastState()
        // ADR-008 §7: 256 bits from the random source, base64url without padding: 43 characters of that alphabet.
        assertTrue(Regex("[A-Za-z0-9_-]{43}").matches(state), state)
        assertEquals(32, java.util.Base64.getUrlDecoder().decode(state).size)
        // Another Consent Request gets another state.
        started("Vesta Bank (SE)")
        assertFalse(lastState() == state)
        val digest = ConsentStateValue.digestOf(Secret(state))
        assertEquals(64, digest.length)
        // The digest finds the request; the state itself does not.
        assertEquals(slussen.core.port.AnswerRejection.NoSuchRequest, (store.consumeAnswer(state, now) as slussen.core.port.ConsumedAnswer.Rejected).reason)
        assertTrue(store.consumeAnswer(digest, now) is slussen.core.port.ConsumedAnswer.Accepted)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: the Banks of one country are asked of the Data Provider as one request of kind Other through the gate, nothing is stored, and a search narrows them on the device")
    fun banksOfOneCountry() = runTest {
        consent.banksAnswer = { BankList.Listed(listOf(BankChoice(bank, "Aurora Sparbank"), BankChoice("Borealis Bank (FI)", "Borealis Bank"))) }
        val requestsBefore = slice.providerRequests.requests.size

        val listed = consents.banks(key, "FI") as BanksOutcome.Listed

        assertEquals(listOf("FI"), consent.countries)
        assertEquals(listOf(bank, "Borealis Bank (FI)"), listed.banks.map { it.bankReference })
        assertEquals(listOf("Borealis Bank"), listed.matching("bore").map { it.name })
        assertEquals(listed.banks, listed.matching(""))
        assertEquals(listOf(RequestKind.Other), consent.sent.map { it.kind })
        val counted = slice.providerRequests.requests.drop(requestsBefore)
        assertEquals(listOf(StoredProviderRequest(dataProviderId, null, ProviderRequestKind.Other, false, now)), counted, "counted as Other, with the Operator present")
        assertEquals(emptyList<String>(), bankNames())
        assertEquals(emptyList<Any>(), store.consentRequests())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010: a country that is no ISO 3166 two-letter code sends nothing; a Bank whose reference Slussen cannot store is not offered, and two Banks of one name in two countries are two Banks")
    fun banksSlussenCannotStoreAreNotOffered() = runTest {
        assertEquals(BanksOutcome.CountryNotValid, consents.banks(key, "Finland"))
        assertEquals(emptyList<Any>(), consent.asked)

        consent.banksAnswer = {
            BankList.Listed(listOf(BankChoice("Nordic Bank (FI)", "Nordic Bank"), BankChoice("Nordic Bank (SE)", "Nordic Bank"), BankChoice("X".repeat(101), "Too Long"), BankChoice(" ", "Blank")))
        }
        val listed = consents.banks(key, "FI") as BanksOutcome.Listed
        assertEquals(listOf("Nordic Bank (FI)", "Nordic Bank (SE)"), listed.banks.map { it.bankReference })
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("BR-011: the return address sent is the one registered with the Data Provider's credentials, else the application's own; the page shows the exact address")
    fun theReturnAddress() = runTest {
        assertEquals(ExampleData.CONSENT_RETURN_ADDRESS, consents.page(key).returnAddress)
        assertTrue(consents.page(key).ownAddress)
        assertEquals(ExampleData.CONSENT_RETURN_ADDRESS, started().returnAddress)
        assertEquals(ExampleData.CONSENT_RETURN_ADDRESS, consent.starts.last().returnAddress)

        consent.registered = { "https://household.example/back" }
        assertEquals("https://household.example/back", consents.page(key).returnAddress)
        assertFalse(consents.page(key).ownAddress)
        assertEquals("https://household.example/back", started().returnAddress)
        assertEquals("https://household.example/back", consent.starts.last().returnAddress)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("BR-011: an address the Operator pastes is checked exactly as a returned one, and the address Slussen was sent at the Data Provider's page completes it")
    fun aPastedAddressIsCheckedAsAReturnedOne() = runTest {
        consent.registered = { "https://household.example/back" }
        started()
        val pasted = ConsentAnswer(Secret("https://household.example/back?state=${lastState()}&code=$code"))
        assertTrue(consents.complete(pasted) is CompletionOutcome.Granted)
        assertTrue(consents.complete(pasted) == CompletionOutcome.NotAccepted(AnswerNotAccepted.AlreadyAnswered))
    }

    // ---- BR-002: one outcome per answer -------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("BR-002: a replayed answer is rejected, nothing more is stored, and a second Consent Request can be started")
    fun replayedAnswer() = runTest {
        started()
        val answer = answerTo(lastState())
        assertTrue(consents.complete(answer) is CompletionOutcome.Granted)
        val consentsAfter = store.bankConsents()

        assertEquals(CompletionOutcome.NotAccepted(AnswerNotAccepted.AlreadyAnswered), consents.complete(answer))
        assertEquals(consentsAfter, store.bankConsents())
        assertEquals(1, consent.handlesCompleted.size, "the Data Provider is not asked again")
        assertTrue(consents.start(key, bank, browserAvailable = true) is StartOutcome.Started)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("BR-002: an answer to a Consent Request that a newer one replaced is rejected, and the newer one can still be answered")
    fun answerToAReplacedRequest() = runTest {
        started()
        val older = answerTo(lastState())
        started()
        val newer = answerTo(lastState())

        assertEquals(CompletionOutcome.NotAccepted(AnswerNotAccepted.NoSuchRequest), consents.complete(older), "starting a new one deletes the earlier one of the Bank (ADR-002 §10)")
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<String>(), bankNames())
        assertTrue(consents.complete(newer) is CompletionOutcome.Granted)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("BR-002: an answer at the end of the Consent Request lifetime is accepted; one after it is rejected and nothing is stored; the lifetime is the Operator Setting")
    fun lateAnswer() = runTest {
        started()
        val onTime = answerTo(lastState())
        slice.clock.advance(Duration.ofMinutes(15))
        assertTrue(consents.complete(onTime) is CompletionOutcome.Granted)

        slice.settings.settings = slice.settings.settings.copy(consentRequestMinutes = 5)
        started()
        val late = answerTo(lastState())
        slice.clock.advance(Duration.ofMinutes(5).plusSeconds(1))
        val before = store.bankConsents()
        assertEquals(CompletionOutcome.NotAccepted(AnswerNotAccepted.LifetimeEnded), consents.complete(late))
        assertEquals(before, store.bankConsents())
        assertTrue(consents.start(key, bank, browserAvailable = true) is StartOutcome.Started)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("A2: an answer whose state matches no Consent Request of this installation is rejected, the Data Provider is not asked, and nothing is stored")
    fun foreignAnswer() = runTest {
        started()
        val foreign = answerTo("f".repeat(64))
        assertEquals(CompletionOutcome.NotAccepted(AnswerNotAccepted.NoSuchRequest), consents.complete(foreign))
        assertEquals(emptyList<Any?>(), consent.handlesCompleted)
        assertFalse(store.consentRequests().single().answered, "the Consent Request of this installation stays answerable")
        assertTrue(consents.complete(answerTo(lastState())) is CompletionOutcome.Granted)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("A2: an answer that cannot be read, without a state, or one the Data Provider does not understand, is rejected and nothing is stored")
    fun malformedAnswer() = runTest {
        started()
        assertEquals(CompletionOutcome.NotAccepted(AnswerNotAccepted.Unreadable), consents.complete(ConsentAnswer(Secret("not an address at all"))))
        assertEquals(CompletionOutcome.NotAccepted(AnswerNotAccepted.Unreadable), consents.complete(answerTo("", extra = "code=$code")))
        assertFalse(store.consentRequests().single().answered)

        consent.completeAnswer = { _, _ -> ConsentCompletion.NotConfirmed(ConsentFailureKind.AnswerNotUnderstood) }
        assertEquals(CompletionOutcome.NotAccepted(AnswerNotAccepted.Unreadable), consents.complete(answerTo(lastState())))
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<String>(), bankNames())
        assertTrue(consents.start(key, bank, browserAvailable = true) is StartOutcome.Started)
    }

    // ---- A1, A3, A4, A8, A9: nothing stored, the earlier Bank Consent kept --------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "A1: Authentication Not Completed")
    @DisplayName("A1: a Strong Customer Authentication the Operator cancelled or the Bank refused stores no Bank Consent, keeps the earlier one, leaves no Bank, and a new Consent Request can be started")
    fun authenticationNotCompleted() = runTest {
        consent.completeAnswer = { _, _ -> ConsentCompletion.NotCompleted }
        started()
        assertEquals(CompletionOutcome.NotGranted(bank), consents.complete(answerTo(lastState())))
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<String>(), bankNames())
        assertTrue(store.consentRequests().single().answered)

        consent.completeAnswer = { _, _ -> completed() }
        granted()
        val earlier = store.bankConsents()
        consent.completeAnswer = { _, _ -> ConsentCompletion.NotCompleted }
        started()
        assertEquals(CompletionOutcome.NotGranted(bank), consents.complete(answerTo(lastState())))
        assertEquals(earlier, store.bankConsents())
        assertTrue(consents.start(key, bank, browserAvailable = true) is StartOutcome.Started)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A3: Data Provider Refuses", businessRules = ["BR-011"])
    @DisplayName("A3: a refusal to start stores nothing and keeps the earlier Consent Request; a refused return address names the address Slussen sent")
    fun dataProviderRefuses() = runTest {
        started()
        val earlier = store.consentRequests()
        consent.startAnswer = { ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider) }
        assertEquals(StartOutcome.Refused(bank, ConsentFailureKind.RefusedByDataProvider, returnAddress = null), consents.start(key, bank, true))
        assertEquals(earlier, store.consentRequests())

        consent.startAnswer = { ConsentStart.Refused(ConsentFailureKind.ReturnAddressRefused) }
        assertEquals(StartOutcome.Refused(bank, ConsentFailureKind.ReturnAddressRefused, ExampleData.CONSENT_RETURN_ADDRESS), consents.start(key, bank, true))
        assertEquals(earlier, store.consentRequests())
        assertEquals(emptyList<String>(), bankNames())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A4: Data Provider Does Not Confirm the Consent")
    @DisplayName("A4: the Data Provider refusing the completion after the answer was taken stores nothing, leaves no Bank, and the Operator starts again")
    fun dataProviderDoesNotConfirm() = runTest {
        consent.completeAnswer = { _, _ -> ConsentCompletion.NotConfirmed(ConsentFailureKind.RefusedByDataProvider) }
        started()
        val answer = answerTo(lastState())
        assertEquals(CompletionOutcome.NotConfirmed(bank, ConsentFailureKind.RefusedByDataProvider), consents.complete(answer))
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<String>(), bankNames())
        assertEquals(CompletionOutcome.NotAccepted(AnswerNotAccepted.AlreadyAnswered), consents.complete(answer), "it cannot be answered again")
        assertTrue(consents.start(key, bank, true) is StartOutcome.Started)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("A8: listing or starting that fails stores nothing and keeps the earlier Consent Request; completing that fails stores nothing and says the Bank may hold a consent")
    fun dataProviderCannotBeReached() = runTest {
        consent.banksAnswer = { BankList.TransientFailure(FailureKind.Timeout) }
        assertEquals(BanksOutcome.NotReached(FailureKind.Timeout, now), consents.banks(key, "FI"))

        started()
        val earlier = store.consentRequests()
        consent.startAnswer = { ConsentStart.TransientFailure(FailureKind.ConnectionLost) }
        assertEquals(StartOutcome.NotReached(bank, FailureKind.ConnectionLost, now), consents.start(key, bank, true))
        assertEquals(earlier, store.consentRequests())

        consent.completeAnswer = { _, _ -> ConsentCompletion.TransientFailure(FailureKind.Timeout) }
        assertEquals(CompletionOutcome.MayHaveBeenGranted(bank, FailureKind.Timeout, now), consents.complete(answerTo(slice.bankConsent.stateTexts().first())))
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<String>(), bankNames())
        assertTrue(store.consentRequests().single().answered)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A9: Held Back by the Request Gate")
    @DisplayName("A9: a reached Request Limit holds back listing, starting and completing, with the time it frees up; nothing is sent or stored")
    fun requestLimitReached() = runTest {
        started()
        val state = lastState()
        val limit = slice.plugins.dataProvider(key).stored.requestLimit
        val firstSent = now
        repeat(limit - slice.providerRequests.requests.size) {
            slice.providerRequests.requests += StoredProviderRequest(dataProviderId, null, ProviderRequestKind.Other, false, firstSent)
        }
        val freesUpAt = slice.providerRequests.requests.minOf { it.sentAt }.plus(Duration.ofHours(24))
        val sentBefore = consent.sent.size

        assertEquals(BanksOutcome.HeldBackByGate(HeldBack(HeldBackBy.RequestLimitReached, freesUpAt)), consents.banks(key, "FI"))
        assertEquals(StartOutcome.HeldBackByGate(bank, HeldBack(HeldBackBy.RequestLimitReached, freesUpAt)), consents.start(key, bank, true))
        assertEquals(CompletionOutcome.HeldBackByGate(bank, HeldBack(HeldBackBy.RequestLimitReached, freesUpAt)), consents.complete(answerTo(state)))
        assertEquals(sentBefore, consent.sent.size)
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<String>(), bankNames())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A9: Held Back by the Request Gate")
    @DisplayName("A9: a Back-off of the Bank holds back every request at that Bank with the time it ends, and not those of another Bank")
    fun backOffOfTheBank() = runTest {
        granted()
        val bankId = slice.connectionStore.banks().single().id
        val endsAt = now.plus(Duration.ofHours(6))
        slice.importStatusStore.backOffList += StoredBackOff(dataProviderId, bankId, BackOffCause.RequestToWait, now, endsAt)
        val sentBefore = consent.sent.size

        assertEquals(StartOutcome.HeldBackByGate(bank, HeldBack(HeldBackBy.WaitingForProvider, endsAt)), consents.renew(key, bankId, true))
        assertEquals(sentBefore, consent.sent.size)
        assertTrue(consents.start(key, "Vesta Bank (SE)", true) is StartOutcome.Started)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A11: No Browser on the Device")
    @DisplayName("A11: without a browser on the device no Consent Request is started and nothing is sent")
    fun noBrowser() = runTest {
        assertEquals(StartOutcome.NoBrowser, consents.start(key, bank, browserAvailable = false))
        assertEquals(emptyList<Any>(), consent.asked)
        assertEquals(emptyList<Any>(), store.consentRequests())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("Precondition: without accepted credentials the Data Provider is not contacted and nothing is stored (UC-014 BR-005)")
    fun withoutCredentialsNothingIsSent() = runTest {
        slice.credentials.remove(key)
        assertEquals(StartOutcome.NotAvailable(ConsentUnavailable.CredentialsMissing), consents.start(key, bank, true))
        assertEquals(BanksOutcome.NotAvailable(ConsentUnavailable.CredentialsMissing), consents.banks(key, "FI"))
        assertEquals(emptyList<Any>(), consent.asked)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("Entity model: a Bank reference longer than BANK.name holds is refused before anything is sent")
    fun bankReferenceTooLong() = runTest {
        assertEquals(StartOutcome.BankReferenceNotUsable, consents.start(key, "B".repeat(101), true))
        assertEquals(StartOutcome.BankReferenceNotUsable, consents.start(key, "  ", true))
        assertEquals(emptyList<Any>(), consent.asked)
        assertTrue(consents.start(key, "B".repeat(100), true) is StartOutcome.Started)
    }

    // ---- A10 and ADR-007 §2, §3 ---------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "A10: Consent Request Abandoned", businessRules = ["BR-002"])
    @DisplayName("A10: an unanswered Consent Request within its lifetime holds back an Upload Plan; Cancel deletes it, an answer to it is then rejected, and nothing else changes")
    fun abandoned() = runTest {
        granted()
        val earlier = store.bankConsents()
        assertFalse(consents.holdsBackUploadPlan())
        val waiting = started()
        assertTrue(consents.holdsBackUploadPlan())
        assertEquals(listOf(WaitingRequest(waiting.requestId, bank, waiting.expiresAt)), consents.page(key).waiting)
        val answer = answerTo(lastState())

        assertEquals(AbandonOutcome.Abandoned, consents.abandon(waiting.requestId))
        assertFalse(consents.holdsBackUploadPlan())
        assertEquals(AbandonOutcome.NothingToAbandon, consents.abandon(waiting.requestId))
        assertEquals(CompletionOutcome.NotAccepted(AnswerNotAccepted.NoSuchRequest), consents.complete(answer))
        assertEquals(earlier, store.bankConsents())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A10: Consent Request Abandoned", businessRules = ["BR-002"])
    @DisplayName("A10: without Cancel, a Consent Request holds back an Upload Plan only until its lifetime ends")
    fun notAnsweredInTime() = runTest {
        started()
        slice.clock.advance(Duration.ofMinutes(15))
        assertTrue(consents.holdsBackUploadPlan())
        slice.clock.advance(Duration.ofSeconds(1))
        assertFalse(consents.holdsBackUploadPlan())
        assertEquals(emptyList<WaitingRequest>(), consents.page(key).waiting)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("ADR-007 §2: listing, starting, answering, abandoning and revoking are admitted in Normal and refused while an Upload Plan awaits confirmation or is written, with nothing sent")
    fun admittedOnlyInNormal() = runTest {
        val waiting = started()
        val answer = answerTo(lastState())
        val sent = consent.sent.size
        listOf(CoordinatorMode.PlanOpen to OperationRefusal.UploadPlanAwaitingConfirmation, CoordinatorMode.Uploading to OperationRefusal.UploadBeingWritten).forEach { (mode, refusal) ->
            slice.coordinatorStore.mode = mode
            val paused = ConsentUnavailable.Paused(refusal)
            assertEquals(BanksOutcome.NotAvailable(paused), consents.banks(key, "FI"))
            assertEquals(StartOutcome.NotAvailable(paused), consents.start(key, bank, true))
            assertEquals(CompletionOutcome.NotAvailable(paused), consents.complete(answer))
            assertEquals(AbandonOutcome.NotAvailable(paused), consents.abandon(waiting.requestId))
            assertEquals(RevokeOutcome.NotAvailable(paused), consents.revoke(key, 1))
        }
        assertEquals(sent, consent.sent.size)
        assertFalse(store.consentRequests().single().answered)
        slice.coordinatorStore.mode = CoordinatorMode.Normal
        assertTrue(consents.complete(answer) is CompletionOutcome.Granted)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("ADR-007 §1: while a Bank Consent operation runs, the lane shows it with the Data Provider it waits for, and it is free again while the Operator is at the Bank")
    fun theLaneSaysWhatItWaitsFor() = runTest {
        val seen = mutableListOf<RunningOperation?>()
        consent.startAnswer = {
            seen += slice.coordinator.running.value
            ConsentStart.Started(consentAddress, null)
        }
        started()
        assertEquals(listOf(RunningOperation(OperationKind.BankConsent, ExampleData.openBankingProfile.name)), seen)
        assertNull(slice.coordinator.running.value, "nothing holds the lane while the answer is awaited")
    }

    // ---- Two databases: no Bank left behind --------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "A4: Data Provider Does Not Confirm the Consent")
    @DisplayName("O-10: when the completion is refused, no Bank is created")
    fun noBankWhenTheCompletionIsRefused() = runTest {
        consent.completeAnswer = { _, _ -> ConsentCompletion.NotConfirmed(ConsentFailureKind.RefusedByBank) }
        started()
        consents.complete(answerTo(lastState()))
        assertEquals(emptyList<String>(), bankNames())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("O-10: when storing the Bank Consent throws after the Bank was created, the Bank is removed and nothing of the consent is stored")
    fun noBankWhenStoringThrows() = runTest {
        started()
        store.failsToReplace = true
        assertEquals(CompletionOutcome.NotStored(bank), consents.complete(answerTo(lastState())))
        assertEquals(emptyList<String>(), bankNames())
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<Any>(), slice.offeredStore.offered())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("O-10: a Bank that existed before the attempt is never removed by its failure")
    fun anEarlierBankStays() = runTest {
        val known = slice.connectionStore.bankNamed(bank)
        started()
        store.failsToReplace = true
        consents.complete(answerTo(lastState()))
        assertEquals(listOf(known), slice.connectionStore.banks())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("O-10: at a start after a process that ended between the Bank's creation and the Bank Consent, the Bank nothing refers to is removed; a Bank with a Bank Consent, a Bank Account or a Back-off stays")
    fun noBankAfterTheProcessEnded() = runTest {
        // The process ended after the answer was taken and the Bank created, before the Bank Consent was stored.
        started()
        val digest = ConsentStateValue.digestOf(Secret(lastState()))
        store.consumeAnswer(digest, now)
        val left = slice.connectionStore.bankNamed(bank)
        // Three Banks named by answered Consent Requests that other things refer to.
        val withConsent = granted("Vesta Bank (SE)").let { slice.connectionStore.banks().single { it.name == "Vesta Bank (SE)" } }
        started("Borealis Bank (FI)")
        store.consumeAnswer(ConsentStateValue.digestOf(Secret(lastState())), now)
        val withBankAccount = slice.connectionStore.bankNamed("Borealis Bank (FI)").also { added(41, "lasting-borealis", it.id) }
        started("Kestrel Bank (NO)")
        store.consumeAnswer(ConsentStateValue.digestOf(Secret(lastState())), now)
        val withBackOff = slice.connectionStore.bankNamed("Kestrel Bank (NO)")
        slice.importStatusStore.backOffList += StoredBackOff(dataProviderId, withBackOff.id, BackOffCause.RequestToWait, now, now.plusSeconds(60))

        assertEquals(1, slice.leftBehindBanks.run())

        assertFalse(left.id in slice.connectionStore.banks().map { it.id })
        assertEquals(setOf(withConsent.id, withBankAccount.id, withBackOff.id), slice.connectionStore.banks().map { it.id }.toSet())
        assertEquals(0, slice.leftBehindBanks.run())
    }

    // ---- BR-003, BR-007, BR-008, BR-009 ------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-004"])
    @DisplayName("BR-003: a renewal replaces the Bank Consent and its Bank Session, then asks the Data Provider to end the earlier session; when that fails the renewal stands and says the earlier consent may stay")
    fun renewal() = runTest {
        granted()
        val bankId = slice.connectionStore.banks().single().id
        val later = expiry.plus(Duration.ofDays(90))
        consent.completeAnswer = { _, _ -> completed(expiresAt = later, session = "PLANTED-SESSION-REFERENCE-88bb") }
        consents.renew(key, bankId, true)
        val renewed = consents.complete(answerTo(lastState())) as CompletionOutcome.Granted
        assertEquals(EarlierBankConsent.Ended, renewed.earlier)
        assertEquals(1, consent.revoked.size)
        assertEquals(later, store.bankConsents().single().expiresAt)
        assertEquals(bankId, store.bankConsents().single().bankId, "the renewal names the existing Bank")
        assertEquals(1, store.bankSessions().size)
        assertEquals(listOf(bank), bankNames())

        consent.revokeAnswer = { RevokeResult.NotConfirmed }
        consents.renew(key, bankId, true)
        assertEquals(EarlierBankConsent.MayStayAtBank, (consents.complete(answerTo(lastState())) as CompletionOutcome.Granted).earlier)
        consent.revokeAnswer = { RevokeResult.EndedAtDataProvider }
        consents.renew(key, bankId, true)
        assertEquals(EarlierBankConsent.MayStayAtBank, (consents.complete(answerTo(lastState())) as CompletionOutcome.Granted).earlier)
        assertEquals(1, store.bankConsents().size)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("BR-004: a completion whose reported expiry is not after now stores no Bank Consent and leaves no Bank")
    fun expiryNotInTheFuture() = runTest {
        consent.completeAnswer = { _, _ -> completed(expiresAt = now) }
        started()
        assertEquals(CompletionOutcome.MayHaveBeenGranted(bank, FailureKind.UnreadableAnswer, now), consents.complete(answerTo(lastState())))
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<String>(), bankNames())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A7: Revoke a Bank Consent", businessRules = ["BR-007"])
    @DisplayName("A7, BR-007: revoking deletes the Bank Consent, its Bank Session and every Consent Request of the Bank, also when the Data Provider does not confirm; the Bank Accounts are kept")
    fun revoke() = runTest {
        granted()
        val bankId = slice.connectionStore.banks().single().id
        added(51, "lasting-household", bankId)
        consents.renew(key, bankId, true)
        val accounts = slice.connectionStore.bankAccounts()

        assertEquals(RevokeOutcome.Revoked(bank, endedAtBank = true, dataProviderContacted = true, heldBack = null), consents.revoke(key, bankId))
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<Any>(), store.bankSessions())
        assertEquals(emptyList<Any>(), store.consentRequests())
        assertEquals(accounts, slice.connectionStore.bankAccounts())
        assertEquals(1, consent.revoked.size, "the Data Provider is asked to end the Bank Session")
        assertEquals(RevokeOutcome.NothingToRevoke, consents.revoke(key, bankId))

        granted()
        consent.revokeAnswer = { RevokeResult.NotConfirmed }
        assertEquals(RevokeOutcome.Revoked(bank, endedAtBank = false, dataProviderContacted = true, heldBack = null), consents.revoke(key, bankId))
        assertEquals(emptyList<Any>(), store.bankConsents())

        granted()
        consent.revokeAnswer = { RevokeResult.EndedAtDataProvider }
        assertEquals(RevokeOutcome.Revoked(bank, endedAtBank = false, dataProviderContacted = true, heldBack = null), consents.revoke(key, bankId))
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A9: Held Back by the Request Gate", businessRules = ["BR-007"])
    @DisplayName("A9 step 3, BR-007: a revocation the gate holds back sends nothing, still deletes everything, and says the Data Provider was not contacted")
    fun revokeHeldBack() = runTest {
        granted()
        val bankId = slice.connectionStore.banks().single().id
        val endsAt = now.plus(Duration.ofHours(2))
        slice.importStatusStore.backOffList += StoredBackOff(dataProviderId, null, BackOffCause.RequestToWait, now, endsAt)
        val sent = consent.sent.size

        assertEquals(RevokeOutcome.Revoked(bank, false, dataProviderContacted = false, heldBack = HeldBack(HeldBackBy.WaitingForProvider, endsAt)), consents.revoke(key, bankId))
        assertEquals(sent, consent.sent.size)
        assertEquals(emptyList<Any>(), store.bankConsents())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A7: Revoke a Bank Consent", businessRules = ["BR-007"])
    @DisplayName("BR-007: without credentials the Data Provider is not contacted, and the Bank Consent is still deleted")
    fun revokeWithoutCredentials() = runTest {
        granted()
        val bankId = slice.connectionStore.banks().single().id
        slice.credentials.remove(key)
        assertEquals(RevokeOutcome.Revoked(bank, false, dataProviderContacted = false, heldBack = null), consents.revoke(key, bankId))
        assertEquals(emptyList<Any>(), consent.revoked)
        assertEquals(emptyList<Any>(), store.bankConsents())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-009"])
    @DisplayName("BR-008, BR-009: the Data Provider's page shows each Bank Consent with its Bank, status, expiry and the Bank Accounts the Data Provider names this Bank for, whatever Bank the Operator gave them")
    fun thePage() = runTest {
        granted()
        val bankId = slice.connectionStore.banks().single().id
        val other = slice.connectionStore.bankNamed("Operator's Own Name").id
        added(61, "lasting-household", bankId, name = "Household")
        added(62, "lasting-savings", bankId, name = "Savings").let { a ->
            slice.connectionStore.bankAccountList.replaceAll { if (it.id == a.id) it.copy(bankId = other) else it }
        }
        added(63, "lasting-elsewhere", other, name = "Elsewhere")

        val line = consents.page(key).consents.single()
        assertEquals(BankConsentLine(bankId, bank, BankConsentState.Valid, expiry, expiry, false, listOf("Household", "Savings")), line)

        slice.clock.set(expiry.minus(Duration.ofDays(7)))
        assertEquals(BankConsentState.Expiring, consents.page(key).consents.single().state)
        slice.clock.set(expiry)
        assertEquals(BankConsentState.Expired, consents.page(key).consents.single().state)
    }

    // ---- Step 9, A5, A6: recognising Bank Accounts again -------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-009"])
    @DisplayName("Step 9: a renewal whose Bank Accounts come back with the same lasting references changes nothing for them; a new reference is offered to map; a known one no longer delivered is said")
    fun recognisedAgainByTheLastingReference() = runTest {
        granted()
        val bankId = slice.connectionStore.banks().single().id
        added(71, "lasting-household", bankId, name = "Household")
        added(72, "lasting-savings", bankId, name = "Savings")
        val accounts = slice.connectionStore.bankAccounts()
        val version = slice.stateVersion.value

        consents.renew(key, bankId, true)
        val same = consents.complete(answerTo(lastState())) as CompletionOutcome.Granted
        assertEquals(listOf("Household", "Savings"), same.coverage!!.identified)
        assertEquals(0, same.coverage!!.toMap)
        assertEquals(accounts, slice.connectionStore.bankAccounts())
        assertEquals(version, slice.stateVersion.value)

        consent.completeAnswer = { _, _ -> completed(listOf(household, delivered("lasting-card", "Travel Card"))) }
        consents.renew(key, bankId, true)
        val changed = (consents.complete(answerTo(lastState())) as CompletionOutcome.Granted).coverage!!
        assertEquals(listOf("Household"), changed.identified)
        assertEquals(1, changed.toMap)
        assertEquals(listOf("Savings"), changed.noLongerDelivered)
        assertEquals(accounts, slice.connectionStore.bankAccounts(), "a Bank Account no longer delivered keeps its stored data")
        assertEquals(setOf("lasting-household", "lasting-card"), slice.offeredStore.offered().map { it.providerReference }.toSet())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A5: Bank Account Only Probably Recognised")
    @DisplayName("A5: a covered Bank Account that only its number makes likely is proposed for the Operator to confirm, never identified by a guess")
    fun probablyRecognised() = runTest {
        val theBank = slice.connectionStore.bankNamed(bank)
        added(81, "session-scoped-old", theBank.id, number = "FI21 1234 5600 0007 85", name = "Household")
        val accounts = slice.connectionStore.bankAccounts()

        val coverage = granted().coverage!!

        assertEquals(listOf(ProposedMatch("Household Current", "0785", "Household")), coverage.toConfirm)
        assertEquals(emptyList<String>(), coverage.identified)
        assertEquals(1, coverage.toMap, "only the savings Bank Account is to map")
        assertEquals(81L, slice.offeredStore.offered().single { it.providerReference == "lasting-household" }.proposedBankAccountId)
        assertEquals(accounts, slice.connectionStore.bankAccounts(), "nothing is identified until the Operator confirms")
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A6: Bank Account Not Identified")
    @DisplayName("A6: when two added Bank Accounts are possible for one covered Bank Account, or two delivered share a reference, Slussen stops for them and proposes nothing")
    fun notIdentified() = runTest {
        val theBank = slice.connectionStore.bankNamed(bank)
        added(91, "old-a", theBank.id, number = "FI21 1234 5600 0007 85", name = "First")
        added(92, "old-b", theBank.id, number = "FI21 1234 5600 0007 85", name = "Second")
        added(93, "lasting-twice", theBank.id, name = "Shared")
        consent.completeAnswer = { _, _ ->
            completed(listOf(household, delivered("lasting-twice", "One"), delivered("lasting-twice", "Two")))
        }

        val coverage = granted().coverage!!

        assertEquals(listOf("First", "Second", "Shared"), coverage.notIdentified.sorted())
        assertEquals(emptyList<ProposedMatch>(), coverage.toConfirm)
        assertEquals(0, coverage.toMap)
        assertEquals(listOf("lasting-household"), slice.offeredStore.offered().map { it.providerReference }, "UC-009 BR-004: a shared reference is not kept")
        assertTrue(slice.offeredStore.offered().all { it.proposedBankAccountId == null })
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("Step 9, UC-013 BR-011: a Left-Out Bank Account stays not imported: kept as offered without balance, never to map or proposed")
    fun leftOutStaysLeftOut() = runTest {
        slice.connectionStore.leaveOut(listOf(NewLeftOutBankAccount(dataProviderId, "lasting-savings", "Rainy Day Savings", now)))
        val coverage = granted().coverage!!
        assertEquals(1, coverage.leftOut)
        assertEquals(1, coverage.toMap)
        assertNull(slice.offeredStore.offered().single { it.providerReference == "lasting-savings" }.proposedBankAccountId)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: a delivered Bank Account the Data Provider names another Bank for is not kept with this Bank Consent")
    fun anotherBanksBankAccountIsNotKept() = runTest {
        consent.completeAnswer = { _, _ -> completed(listOf(household, delivered("lasting-foreign", "Elsewhere", bankReference = "Vesta Bank (SE)"))) }
        val coverage = granted().coverage!!
        assertEquals(listOf("lasting-household"), slice.offeredStore.offered().map { it.providerReference })
        assertEquals(1, coverage.delivered)
    }

    // ---- BR-006: nothing sensitive leaves ----------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-006"], requirements = ["NFR-008"])
    @DisplayName("BR-006: the state, the consent address, the answer and the Bank Session reach no log line, no result's text, no Decision Journal entry and no stored value other than the Bank Session's own")
    fun nothingSensitiveIsLoggedOrStored() = runTest {
        consent.banksAnswer = { BankList.Listed(listOf(BankChoice(bank, "Aurora Sparbank"))) }
        val shown = mutableListOf<String>()
        shown += consents.banks(key, "FI").toString()
        val start = started()
        shown += start.toString()
        shown += start.consentAddress.toString()
        val state = lastState()
        val answer = answerTo(state)
        shown += answer.toString()
        shown += consents.complete(answer).toString()
        shown += consents.complete(answer).toString()
        // A failure on the way: its exception text is not shown either.
        store.failsToStart = true
        shown += consents.start(key, bank, true).toString()
        store.failsToStart = false
        shown += consents.page(key).toString()
        shown += consents.revoke(key, slice.connectionStore.banks().single().id).toString()

        shown += slice.consentLog.records.map { it.toString() }
        shown += slice.journalRecorder.entries.map { it.toString() }
        shown += store.bankConsents().toString() + store.consentRequests().toString() + store.bankSessions().toString()
        shown += slice.offeredStore.offered().toString()
        shown += slice.connectionStore.banks().toString() + slice.connectionStore.bankAccounts().toString()
        shown += slice.providerRequests.requests.toString()

        assertTrue(slice.consentLog.records.isNotEmpty(), "the operations do write their events")
        listOf(state, consentAddress, code, sessionText, handle, ConsentStateValue.digestOf(Secret(state))).forEach { planted ->
            shown.forEach { text -> assertFalse(planted in text, "a planted value is shown: $text") }
        }
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("BR-006: the Consent Request in progress holds the digest of the state and the handle, never the state")
    fun theStoredCorrelationHoldsOnlyTheDigest() = runTest {
        started()
        val state = lastState()
        assertTrue(store.consumeAnswer(ConsentStateValue.digestOf(Secret(state)), now) is slussen.core.port.ConsumedAnswer.Accepted)
        val accepted = store.consentRequests().single()
        assertFalse(state in accepted.toString())
        assertNotNull(accepted)
    }

    // ---- The review of phase E2 ---------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "A6: Bank Account Not Identified", businessRules = ["BR-009"])
    @DisplayName("A6, BR-009: a lasting reference already offered under another Bank of the Data Provider is not kept for this Bank and is reported as not identified; the rest of the Bank's Bank Accounts are kept")
    fun aReferenceOfferedUnderAnotherBank() = runTest {
        consent.completeAnswer = { _, _ ->
            completed(listOf(household.copy(bankReference = "Vesta Bank (SE)"), savings.copy(bankReference = "Vesta Bank (SE)")))
        }
        granted("Vesta Bank (SE)")
        val vesta = slice.connectionStore.banks().single().id
        // Vesta's consent kept lasting-household and lasting-savings; Aurora's consent delivers lasting-savings again.
        consent.completeAnswer = { _, _ -> completed(listOf(delivered("lasting-savings", "Rainy Day Savings"), delivered("lasting-aurora", "Aurora Only"))) }
        val coverage = granted().coverage!!

        val aurora = slice.connectionStore.banks().single { it.name == bank }.id
        assertEquals(listOf("lasting-aurora"), slice.offeredStore.offered().filter { it.bankId == aurora }.map { it.providerReference })
        assertEquals(setOf("lasting-household", "lasting-savings"), slice.offeredStore.offered().filter { it.bankId == vesta }.map { it.providerReference }.toSet())
        assertEquals(listOf("Rainy Day Savings"), coverage.notIdentified)
        assertEquals(1, coverage.toMap)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("BR-009: a delivered Bank Account without a Bank's reference is kept with the Bank of the Bank Consent; one whose reference is longer than the entity model holds is not kept")
    fun withoutABanksReferenceAndTooLong() = runTest {
        consent.completeAnswer = { _, _ -> completed(listOf(delivered("lasting-plain", "Plain", bankReference = null), delivered("R".repeat(201), "Too Long"))) }
        val coverage = granted().coverage!!
        assertEquals(listOf("lasting-plain"), slice.offeredStore.offered().map { it.providerReference })
        assertEquals(1, coverage.delivered)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("NFR-023: when the Offered Bank Accounts cannot be kept, the Bank Consent stands and its coverage is unknown")
    fun offeredNotKept() = runTest {
        slice.offeredStore.failsToReplace = true
        val outcome = granted()
        assertNull(outcome.coverage)
        assertEquals(1, store.bankConsents().size)
        assertEquals(listOf(bank), bankNames())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("BR-002: the lifetime is counted to the moment the answer arrived, also when it waited for the lane")
    fun theArrivalIsTakenBeforeTheLane() = runTest {
        started()
        val answer = answerTo(lastState())
        slice.clock.advance(Duration.ofMinutes(14))
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val busy = launch { slice.coordinator.run(OperationKind.Comparison, slussen.core.port.StartedBy.Operator) { release.await() } }
        testScheduler.runCurrent()
        val completing = async { consents.complete(answer) }
        testScheduler.runCurrent()
        slice.clock.advance(Duration.ofMinutes(5))
        release.complete(Unit)
        busy.join()
        assertTrue(completing.await() is CompletionOutcome.Granted)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("BR-002: the time the screen took when the answer reached Slussen decides the lifetime, not the later time it is handed in; a time after now counts as now")
    fun theArrivalTheScreenTook() = runTest {
        started()
        val answer = answerTo(lastState())
        slice.clock.advance(Duration.ofMinutes(14))
        val arrived = now
        slice.clock.advance(Duration.ofMinutes(5))
        assertTrue(consents.complete(answer, arrivedAt = arrived) is CompletionOutcome.Granted)

        started()
        val late = answerTo(lastState())
        slice.clock.advance(Duration.ofMinutes(16))
        assertEquals(
            CompletionOutcome.NotAccepted(AnswerNotAccepted.LifetimeEnded),
            consents.complete(late, arrivedAt = now.plus(Duration.ofDays(1))),
        )
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-008", "BR-010"])
    @DisplayName("BR-003, BR-010: the page names each Bank the Data Provider names for its Bank Accounts that has no Bank Consent, with those Bank Accounts, and it is granted again without a choice")
    fun banksWithoutABankConsent() = runTest {
        granted()
        val bankId = slice.connectionStore.banks().single().id
        added(81, "lasting-household", bankId, name = "Household")
        assertEquals(emptyList<BankWithoutConsent>(), consents.page(key).withoutConsent)

        consents.revoke(key, bankId)

        assertEquals(listOf(BankWithoutConsent(bankId, bank, listOf("Household"))), consents.page(key).withoutConsent)
        assertTrue(consents.renew(key, bankId, browserAvailable = true) is StartOutcome.Started)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-002"])
    @DisplayName("S-B-52: an answer that arrives while the Data Provider's credentials are missing is left unconsumed and can be answered once they are accepted")
    fun anAnswerWithoutCredentialsIsLeftUnconsumed() = runTest {
        started()
        val answer = answerTo(lastState())
        slice.credentials.remove(key)
        assertEquals(CompletionOutcome.NotAvailable(ConsentUnavailable.CredentialsMissing), consents.complete(answer))
        assertFalse(store.consentRequests().single().answered)
        assertEquals(emptyList<Any?>(), consent.handlesCompleted)
        slice.credentials.enter(key, slice.input("setupKey" to ExampleData.SETUP_KEY))
        assertTrue(consents.complete(answer) is CompletionOutcome.Granted)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("O-10: a completion cancelled after the Bank was created removes the Bank and stores no Bank Consent")
    fun cancelledAfterTheBankWasCreated() = runTest {
        started()
        val answer = answerTo(lastState())
        store.beforeReplace = { kotlinx.coroutines.awaitCancellation() }
        val completing = launch { consents.complete(answer) }
        testScheduler.runCurrent()
        assertEquals(listOf(bank), bankNames())
        completing.cancelAndJoin()
        assertEquals(emptyList<String>(), bankNames())
        assertEquals(emptyList<Any>(), store.bankConsents())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-010"])
    @DisplayName("BR-003, BR-010: renewing is refused for a Bank that has no Bank Consent of this Data Provider and is not the Bank it names for one of its Bank Accounts; nothing is sent")
    fun renewOnlyAKnownBankOfTheDataProvider() = runTest {
        val elsewhere = slice.connectionStore.bankNamed("Kestrel Bank")
        assertEquals(StartOutcome.NotABankOfTheDataProvider, consents.renew(key, elsewhere.id, true))
        assertEquals(emptyList<Any>(), consent.asked)
        val named = slice.connectionStore.bankNamed("Borealis Bank (FI)")
        added(21, "lasting-borealis", named.id)
        assertTrue(consents.renew(key, named.id, true) is StartOutcome.Started, "the Bank it names for one of its Bank Accounts (UC-001 A4)")
        granted()
        val aurora = slice.connectionStore.banks().single { it.name == bank }.id
        assertTrue(consents.renew(key, aurora, true) is StartOutcome.Started)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("A8 step 2, ADR-001 §4.0: a plug-in that throws in the completion after the answer was taken is answered as a failure in transit: nothing stored, no Bank left, start again")
    fun aCompletionThatThrows() = runTest {
        consent.completeAnswer = { _, _ -> throw IllegalStateException("invented failure of a plug-in") }
        started()
        assertEquals(CompletionOutcome.MayHaveBeenGranted(bank, FailureKind.ConnectionLost, now), consents.complete(answerTo(lastState())))
        assertEquals(emptyList<Any>(), store.bankConsents())
        assertEquals(emptyList<String>(), bankNames())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached", businessRules = ["BR-007"])
    @DisplayName("A8 step 3, ADR-001 §4.0: a plug-in that throws in the revocation is answered as an end the Data Provider did not confirm, never as an exception")
    fun aRevocationThatThrows() = runTest {
        granted()
        val bankId = slice.connectionStore.banks().single().id
        consent.revokeAnswer = { throw IllegalStateException("invented failure of a plug-in") }
        assertEquals(RevokeOutcome.Revoked(bank, endedAtBank = false, dataProviderContacted = true, heldBack = null), consents.revoke(key, bankId))
        assertEquals(emptyList<Any>(), store.bankConsents())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("O-10: a failure while removing the Banks left behind at a start is caught, logs nothing of its content, and the start goes on")
    fun aFailingRemovalAtAStart() = runTest {
        started()
        store.consumeAnswer(ConsentStateValue.digestOf(Secret(lastState())), now)
        slice.connectionStore.bankNamed(bank)
        slice.connectionStore.failsToStore = true
        assertEquals(0, slice.leftBehindBanks.runAtStart())
        slice.connectionStore.failsToStore = false
        assertEquals(1, slice.leftBehindBanks.runAtStart())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario")
    @DisplayName("The Consent Request found by the digest names its Data Provider: that one's credentials are checked and its Bank Consent operations complete it")
    fun theRequestsOwnDataProviderCompletes() = runTest {
        val two = TwoDataProviders()
        two.credentials.enter(two.secondKey, CredentialInput(mapOf("setupKey" to Secret(ExampleData.SETUP_KEY))))
        two.second.startAnswer = { ConsentStart.Started(consentAddress, Secret(handle)) }
        two.second.completeAnswer = { _, _ -> completed() }
        two.first.completeAnswer = { _, _ -> error("the first Data Provider is never asked") }
        assertTrue(two.bankConsents.start(two.secondKey, bank, true) is StartOutcome.Started)
        val answer = ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "code=$code&state=${two.second.stateTexts().last()}")

        assertTrue(two.bankConsents.complete(answer) is CompletionOutcome.Granted)
        assertEquals(1, two.second.handlesCompleted.size)
        assertEquals(emptyList<Any?>(), two.first.handlesCompleted)
    }
}

/**
 * Two Data Providers that need a Bank Consent, wired as the composition root wires them, on fresh fakes: the slice of
 * `:testing` has only one, and adding one there would change what every other test sees.
 */
private class TwoDataProviders {
    val clock = slussen.testing.MutableClock()
    val first = ScriptedBankConsent()
    val second = ScriptedBankConsent()
    private val firstProvider = slussen.testing.FakeDataProvider(ExampleData.openBankingProfile, bankConsent = first)
    private val secondProvider = slussen.testing.FakeDataProvider(
        ExampleData.openBankingProfile.copy(name = "Borealis Open Banking", sourceKey = "example-open-banking-two"),
        bankConsent = second,
    )
    private val settings = slussen.testing.InMemoryOperatorSettingsStore()
    private val providerRequests = slussen.testing.InMemoryProviderRequestStore()
    private val connections = slussen.testing.InMemoryConnectionStore()
    private val importStatus = slussen.testing.InMemoryImportStatusStore(connections = connections)
    private val consents = slussen.testing.InMemoryConsentStore(importStatus.consents)
    private val coordinator = slussen.core.operation.OperationCoordinator(slussen.testing.InMemoryCoordinatorStore())
    val plugins = kotlinx.coroutines.runBlocking {
        slussen.core.plugin.PluginRegistration(slussen.testing.InMemoryPluginRegistryStore())
            .register(slussen.testing.FakeBudgetApp(), listOf(firstProvider, secondProvider), emptyList())
    }
    val credentials = slussen.core.credential.ServiceCredentials(
        plugins, slussen.testing.InMemoryCredentialVault(), coordinator,
        slussen.core.journal.DecisionJournal(slussen.testing.InMemoryDecisionJournal(), settings, clock),
        settings, providerRequests, importStatus, slussen.core.credential.CredentialRefusals(), clock,
    )
    val bankConsents = BankConsents(
        plugins, credentials, consents, connections, slussen.testing.InMemoryOfferedBankAccountStore(), importStatus, providerRequests,
        coordinator, settings, clock, java.util.Random(7).let { seeded -> slussen.core.port.RandomSource { seeded.nextBytes(it) } },
        ExampleData.CONSENT_RETURN_ADDRESS, slussen.testing.RecordingSlussenLog(),
    )
    val secondKey = plugins.dataProviders.single { it.plugin === secondProvider }.key
}

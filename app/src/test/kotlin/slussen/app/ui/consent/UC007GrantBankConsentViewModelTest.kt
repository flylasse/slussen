package slussen.app.ui.consent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.UiServices
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.overview.OverviewViewModel
import slussen.app.ui.service.ServicePageViewModel
import slussen.core.port.BackOffCause
import slussen.core.port.BankAccountType
import slussen.core.port.ProviderRequestKind
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBankConsent
import slussen.core.port.StoredProviderRequest
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankChoice
import slussen.pluginapi.provider.BankList
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentFailureKind
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.provider.RevokeResult
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import java.time.Duration
import java.time.Instant

/**
 * UC-007 Grant Bank Consent, the Bank Consents part of a Data Provider's page (`docs/mockups/consent.html`, every state)
 * on the fakes of `:testing`: what the screen decides and that each outcome reaches the Operator at its control in the
 * mockup's words. The rules themselves are tested in the core (`UC007GrantBankConsentTest`).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-007: Grant Bank Consent, the screens")
class UC007GrantBankConsentViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val formats = fixture.services.formats
    private val key get() = slice.openBankingKey
    private val provider = "Aurora Open Banking"
    private val vesta = "Vesta Sparbank (SE)"
    private val expiry: Instant = Instant.parse("2027-04-12T10:00:00Z")
    private val services = MutableStateFlow<UiServices?>(fixture.services)
    private val answers = ConsentAnswers(services, CoroutineScope(Dispatchers.Main), slice.clock)
    private var browser = true

    private fun page(country: String = "SE") =
        BankConsentsViewModel(fixture.services, key, answers, deviceCountry = country, browserAvailable = { browser }).also { it.load() }

    private fun BankConsentsViewModel.ui() = state.value!!

    private fun bankIdOf(name: String) = runBlocking { slice.connectionStore.banks().single { it.name == name }.id }

    private fun requests() = runBlocking { slice.consentStore.consentRequests() }

    private fun storedConsents() = runBlocking { slice.consentStore.bankConsents() }

    private fun accepted() = runBlocking {
        slice.credentials.enter(key, slice.input("setupKey" to ExampleData.SETUP_KEY))
        slice.bankConsent.banksAnswer = { country ->
            when (country) {
                "SE" -> BankList.Listed(listOf(BankChoice("Vesta Sparbank (SE)", "Vesta Sparbank (SE)"), BankChoice("Vestra Bank (SE)", "Vestra Bank (SE)"), BankChoice("Norrby Bank (SE)", "Norrby Bank (SE)")))
                else -> BankList.Listed(listOf(BankChoice("Aurora Bank ($country)", "Aurora Bank ($country)")))
            }
        }
        slice.bankConsent.startAnswer = { ConsentStart.Started("https://vesta.example/sca?ticket=PLANTED-TICKET-1", null) }
        slice.bankConsent.completeAnswer = { _, _ -> ConsentCompletion.Completed(expiry, listOf(card), ConsentSession("PLANTED-SESSION-2")) }
    }

    private val card = DeliveredBankAccount(
        providerReference = "lasting-card", bankReference = vesta, name = "Vesta Card", kind = "card", currency = ExampleData.EUR,
        number = null, result = BankAccountResult.NotRead(NotReadCause.NotSelected),
    )

    /** Steps 1-4 on the page: choose Vesta Sparbank (SE), open the Bank's page in the browser; returns the state Slussen sent. */
    private fun BankConsentsViewModel.startedAtVesta(): String {
        openChooser()
        chooseBank(ui().chooser!!.banks.first { it.name == vesta })
        openMyBank()
        assertNotNull(toOpen.value)
        opened(true)
        return slice.bankConsent.stateTexts().last()
    }

    private fun answerTo(state: String) = "${ExampleData.CONSENT_RETURN_ADDRESS}?code=PLANTED-CODE-3&state=$state"

    private fun BankConsentsViewModel.granted() {
        val state = startedAtVesta()
        answers.returned(answerTo(state))
        load()
    }

    private fun added(id: Long, name: String, providerBankId: Long, reference: String) = StoredBankAccount(
        id = id, generatedIdentifier = "00000000-0000-4000-8000-0000000000$id", bankId = providerBankId, providerBankId = providerBankId,
        dataProviderId = slice.openBankingId, fileFormatId = null, balanceDataProviderId = null, name = name, currency = ExampleData.EUR,
        type = BankAccountType.CreditCard, startDate = null, providerReference = reference, bankAccountFingerprint = null,
        balanceProviderReference = null, identifiedAt = Instant.parse("2026-09-01T08:00:00Z"), retired = false,
        fundedByBudgetAccountId = null, transferPartnerBudgetAccountId = null, mapping = null,
    ).also { slice.connectionStore.bankAccountList += it }

    // ---- #none-yet, #list, #expired ---------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-011"])
    @DisplayName("BR-008, BR-011 (#none-yet): no Bank Consent yet; the exact return address to register, with Copy, and Grant Bank Consent")
    fun noBankConsentYet() {
        accepted()
        val ui = page().ui()

        assertEquals("Bank Consents · 0", ui.heading)
        assertEquals("No Bank Consent yet.", ui.emptyText)
        assertEquals("Return address to register", ui.returnAddressTitle)
        assertEquals(ExampleData.CONSENT_RETURN_ADDRESS, ui.returnAddress)
        assertEquals("Grant Bank Consent", ui.grantAction)
        assertTrue(ui.lines.isEmpty())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-008", "BR-009"])
    @DisplayName("BR-008 (#list, #expired): each Bank Consent with its Bank and country, status, expiry and the Bank Accounts it covers, with Renew and Revoke; an expired one says so and that its Bank Accounts are not fetched")
    fun theBankConsents() {
        accepted()
        val page = page()
        page.granted()
        val bankId = bankIdOf(vesta)
        added(41, "Vesta Card", bankId, "lasting-card")

        page.load()
        val line = page.ui().lines.single()
        assertEquals("Bank Consents · 1", page.ui().heading)
        assertEquals("Grant Bank Consent for another Bank", page.ui().grantAction)
        assertEquals(vesta, line.bankName)
        assertEquals("Valid until 2027-04-12", line.status)
        assertEquals("Covers Vesta Card", line.detail)
        assertEquals("Renew", line.renewAction)
        assertTrue(line.canRevoke)

        slice.clock.set(expiry.minus(Duration.ofDays(3)))
        page.load()
        assertEquals("Expires 2027-04-12", page.ui().lines.single().status)

        slice.clock.set(expiry.plus(Duration.ofDays(1)))
        page.load()
        assertEquals("Bank Consent expired 2027-04-12", page.ui().lines.single().status)
        assertEquals("Vesta Card not fetched", page.ui().lines.single().detail)
        assertEquals("Renew Bank Consent", page.ui().lines.single().renewAction)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-010"])
    @DisplayName("BR-003 (E2 review): renewing is offered only for a Bank that has a Bank Consent or whose Bank Accounts miss one; such a Bank opens the sheet with no choice")
    fun renewOnlyWhereItApplies() {
        accepted()
        val known = runBlocking { slice.connectionStore.bankNamed("Aurora Bank (FI)") }
        val other = runBlocking { slice.connectionStore.bankNamed("Unrelated Bank (FI)") }
        added(42, "Aurora Current", known.id, "lasting-current")

        val page = page()
        val line = page.ui().lines.single()
        assertEquals("Aurora Bank (FI)", line.bankName)
        assertEquals("No Bank Consent", line.status)
        assertEquals("Aurora Current not fetched", line.detail)
        assertEquals("Grant Bank Consent", line.renewAction)
        assertFalse(line.canRevoke)

        page.renew(other.id)
        assertNull(page.ui().sheet)
        page.renew(known.id)
        assertEquals("Grant Bank Consent · Aurora Bank (FI)", page.ui().sheet!!.title)
    }

    // ---- #choose-bank -----------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-010"])
    @DisplayName("BR-010 (#choose-bank): the country first, starting at the device's; the list from the Data Provider, each Bank named with its country; the search narrows it on the device; another country asks again")
    fun choosingTheBank() {
        accepted()
        val page = page(country = "SE")

        page.openChooser()
        val chooser = page.ui().chooser!!
        assertEquals("Grant Bank Consent · choose the Bank", chooser.title)
        assertEquals("Banks $provider reaches", chooser.subtitle)
        assertEquals("SE · Sweden", chooser.country)
        assertEquals(listOf("Vesta Sparbank (SE)", "Vestra Bank (SE)", "Norrby Bank (SE)"), chooser.banks.map { it.name })
        assertEquals("Each Bank is named with its country.", chooser.note)

        val sent = slice.bankConsent.sent.size
        page.search("ves")
        assertEquals(listOf("Vesta Sparbank (SE)", "Vestra Bank (SE)"), page.ui().chooser!!.banks.map { it.name })
        assertEquals(sent, slice.bankConsent.sent.size)

        page.chooseCountry("FI")
        assertEquals("FI · Finland", page.ui().chooser!!.country)
        assertEquals(listOf("SE", "FI"), slice.bankConsent.countries)
        assertEquals(listOf("Aurora Bank (FI)"), page.ui().chooser!!.banks.map { it.name })

        page.cancelChooser()
        assertNull(page.ui().chooser)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached", businessRules = ["BR-010"])
    @DisplayName("A8 step 1 (#failed): a list that could not be read is said at the chooser with its time and Try again; a device without a country asks for one first")
    fun theListNotRead() {
        accepted()
        slice.bankConsent.banksAnswer = { BankList.TransientFailure(FailureKind.Timeout) }
        val page = page(country = "")

        page.openChooser()
        assertEquals("Choose the country first", page.ui().chooser!!.country)
        assertTrue(slice.bankConsent.countries.isEmpty())

        page.chooseCountry("SE")
        val answer = page.ui().chooser!!.answer!!
        assertEquals("$provider did not answer · ${formats.time(slice.clock.instant())}", answer.title)
        assertEquals(listOf("Nothing stored. Try again."), answer.lines)
        assertEquals(listOf(ResultAction.TryAgain), answer.actions)
    }

    // ---- #grant, #waiting, #granted ---------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Steps 1-4 (#grant, #waiting): the sheet with its steps; Open my Bank opens the Bank's page and Slussen waits until the lifetime ends, with the field to paste the address and Cancel")
    fun grantingAndWaiting() {
        accepted()
        val page = page()
        page.openChooser()
        page.chooseBank(page.ui().chooser!!.banks.first { it.name == vesta })

        val sheet = page.ui().sheet!!
        assertEquals("Grant Bank Consent · $vesta", sheet.title)
        assertEquals("$provider · one sign-in for every Bank Account at the Bank", sheet.subtitle)
        assertEquals(
            listOf("1 Open the Strong Customer Authentication of $vesta", "2 Complete it with your Bank, for example with BankID", "3 Return to Slussen with the result"),
            sheet.steps,
        )
        assertEquals("Open my Bank", sheet.openAction)
        assertNull(page.ui().chooser)

        page.openMyBank()
        assertEquals("https://vesta.example/sca?ticket=PLANTED-TICKET-1", page.toOpen.value!!.address.address)
        page.opened(true)
        assertNull(page.toOpen.value)
        assertNull(page.ui().sheet)

        val waiting = page.ui().waiting.single()
        val until = formats.time(slice.clock.instant().plus(Duration.ofMinutes(15)))
        assertEquals("Grant Bank Consent · $vesta", waiting.title)
        assertEquals("Waiting for the result · until $until", waiting.until)
        assertEquals("Not back by itself? Paste the address your browser shows after the sign-in.", waiting.pasteNote)
        assertEquals("${ExampleData.CONSENT_RETURN_ADDRESS}?…", waiting.pastePlaceholder)
        assertFalse(page.ui().toString().contains("PLANTED"))
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-009"])
    @DisplayName("Steps 8-10 (#granted, #granted-first-run): granted, with the expiry the Bank reported and the Bank Accounts it covers; Done ends the answer")
    fun granted() {
        accepted()
        val page = page()
        page.granted()

        val result = page.ui().result!!
        assertEquals("✓ Bank Consent granted · $vesta", result.title)
        assertEquals(
            listOf("Valid until 2027-04-12, the expiry date the Bank reported.", "Bank Accounts offered 1 · none added yet: import and map them on this page"),
            result.lines,
        )
        assertEquals(AnswerKind.Done, result.kind)
        assertEquals(listOf(ResultAction.Done, ResultAction.AnotherBank), result.actions)
        assertTrue(page.ui().waiting.isEmpty())

        page.done()
        assertNull(page.ui().result)
        assertNull(answers.state.value.result)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003 (E2 review): a renewal whose earlier consent could not be ended says that it may stay at the Bank; Bank Accounts that could not be kept are said as they are")
    fun earlierConsentAndCoverageAsTheyAre() {
        accepted()
        val page = page()
        page.granted()
        page.done()
        val bankId = bankIdOf(vesta)
        added(43, "Vesta Card", bankId, "lasting-card")
        page.load()
        slice.bankConsent.revokeAnswer = { RevokeResult.NotConfirmed }

        page.renew(bankId)
        assertEquals("Renew Bank Consent · $vesta", page.ui().sheet!!.title)
        page.openMyBank()
        page.opened(true)
        answers.returned(answerTo(slice.bankConsent.stateTexts().last()))

        val lines = page.ui().result!!.lines
        assertEquals("Covers Vesta Card · identified", lines[1])
        assertEquals("The earlier consent may stay at $vesta until it expires; you can end it at your Bank.", lines.last())
    }

    // ---- A1, A2, A4, A8, A9, A10, A11 ------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "A1: Authentication Not Completed")
    @DisplayName("A1 (#not-completed): the Strong Customer Authentication was cancelled or refused; the earlier Bank Consent is unchanged; Try again")
    fun notCompleted() {
        accepted()
        slice.bankConsent.completeAnswer = { _, _ -> ConsentCompletion.NotCompleted }
        val page = page()
        page.granted()

        val result = page.ui().result!!
        assertEquals("Bank Consent not granted · $vesta", result.title)
        assertEquals(listOf("The Strong Customer Authentication was cancelled or refused.", "Any earlier Bank Consent of $vesta is unchanged."), result.lines)
        assertEquals(listOf(ResultAction.TryAgain), result.actions)

        page.act(result, ResultAction.TryAgain)
        assertEquals("Grant Bank Consent · $vesta", page.ui().sheet!!.title)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-011"])
    @DisplayName("A2 (#not-accepted): a pasted address Slussen cannot read is not accepted; nothing stored; Start again; the field is cleared after use")
    fun notAccepted() {
        accepted()
        val page = page()
        page.startedAtVesta()

        page.paste("${ExampleData.CONSENT_RETURN_ADDRESS}?code=no-state-here")
        assertTrue(page.ui().waiting.single().pasted.text.isNotEmpty())
        page.usePasted()

        assertEquals("", page.ui().waiting.single().pasted.text)
        val result = page.ui().result!!
        assertEquals("Result not accepted", result.title)
        assertEquals(listOf(vesta, "Unreadable · nothing stored"), result.lines)
        assertEquals(listOf(ResultAction.StartAgain), result.actions)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A3: Data Provider Refuses", businessRules = ["BR-011"])
    @DisplayName("A3 (#provider-refused, #address-refused): a refusal at Open my Bank, nothing stored; a refused return address names the address sent, with Copy and Try again")
    fun providerRefuses() {
        accepted()
        slice.bankConsent.startAnswer = { ConsentStart.Refused(ConsentFailureKind.RefusedByDataProvider) }
        val page = page()
        page.openChooser()
        page.chooseBank(page.ui().chooser!!.banks.first { it.name == vesta })
        page.openMyBank()

        val refused = page.ui().sheet!!.answer!!
        assertEquals("$vesta · $provider refused", refused.title)
        assertEquals(listOf("$provider refused · nothing stored · try again later"), refused.lines)
        assertNull(page.toOpen.value)

        slice.bankConsent.startAnswer = { ConsentStart.Refused(ConsentFailureKind.ReturnAddressRefused) }
        page.openMyBank()
        val address = page.ui().sheet!!.answer!!
        assertEquals("$vesta · Return address not accepted", address.title)
        assertEquals(
            listOf("Register ${ExampleData.CONSENT_RETURN_ADDRESS} as a return address of your application at $provider, then try again · nothing stored"),
            address.lines,
        )
        assertEquals(listOf(ResultAction.CopyAddress, ResultAction.TryAgain), address.actions)
        assertEquals(ExampleData.CONSENT_RETURN_ADDRESS, address.address)
        assertTrue(requests().isEmpty())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A4: Data Provider Does Not Confirm the Consent")
    @DisplayName("A4: the Data Provider does not confirm after the authentication; the Consent Request was answered, so start again")
    fun notConfirmed() {
        accepted()
        slice.bankConsent.completeAnswer = { _, _ -> ConsentCompletion.NotConfirmed(ConsentFailureKind.RefusedByDataProvider) }
        val page = page()
        page.granted()

        val result = page.ui().result!!
        assertEquals("$vesta · $provider refused", result.title)
        assertEquals(listOf("$provider did not confirm the Bank Consent · nothing stored · the Consent Request was answered, so start again"), result.lines)
        assertEquals(listOf(ResultAction.StartAgain), result.actions)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A8: Data Provider Cannot Be Reached")
    @DisplayName("A8 (#failed): not reached when completing: the Bank may hold a consent that never reached Slussen; when starting, only the failure with its time")
    fun cannotBeReached() {
        accepted()
        slice.bankConsent.completeAnswer = { _, _ -> ConsentCompletion.TransientFailure(FailureKind.Timeout) }
        val page = page()
        page.granted()

        val result = page.ui().result!!
        assertEquals("Bank Consent not granted · $vesta", result.title)
        assertEquals(
            listOf(
                "$provider did not answer · ${formats.time(slice.clock.instant())}",
                "Nothing stored; any earlier Bank Consent of $vesta is unchanged. Your Bank may have granted a consent that did not reach Slussen: it is never used and ends at its expiry, or end it at your Bank.",
            ),
            result.lines,
        )
        page.done()

        slice.bankConsent.startAnswer = { ConsentStart.TransientFailure(FailureKind.ConnectionLost) }
        page.openChooser()
        page.chooseBank(page.ui().chooser!!.banks.first { it.name == vesta })
        page.openMyBank()
        val start = page.ui().sheet!!.answer!!
        assertEquals("Bank Consent not started · $vesta", start.title)
        assertEquals(listOf("$provider did not answer · ${formats.time(slice.clock.instant())}", "Nothing stored; any earlier Bank Consent of $vesta is unchanged. Try again."), start.lines)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A9: Held Back by the Request Gate")
    @DisplayName("A9 (#held-back): the Request Limit or a Back-off holds the start back, with the time it ends; nothing sent and nothing stored")
    fun heldBack() {
        accepted()
        val page = page()
        page.openChooser()
        page.chooseBank(page.ui().chooser!!.banks.first { it.name == vesta })
        val limit = slice.plugins.dataProvider(key).stored.requestLimit
        val at = slice.clock.instant()
        repeat(limit - slice.providerRequests.requests.size) {
            slice.providerRequests.requests += StoredProviderRequest(slice.openBankingId, null, ProviderRequestKind.Other, false, at)
        }

        page.openMyBank()

        val held = page.ui().sheet!!.answer!!
        assertEquals("Bank Consent not started · $vesta", held.title)
        assertEquals(listOf("Request Limit reached until ${formats.time(at.plus(Duration.ofHours(24)))}", "Nothing sent and nothing stored. Try again once it ends."), held.lines)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A9: Held Back by the Request Gate")
    @DisplayName("A9: a Back-off of the Bank holds a renewal back with the time it ends")
    fun heldBackByABackOff() {
        accepted()
        val page = page()
        page.granted()
        page.done()
        val bankId = bankIdOf(vesta)
        val endsAt = slice.clock.instant().plus(Duration.ofHours(6))
        slice.importStatusStore.backOffList += StoredBackOff(slice.openBankingId, bankId, BackOffCause.RequestToWait, slice.clock.instant(), endsAt)

        page.renew(bankId)
        page.openMyBank()

        assertEquals("Waiting for the provider until ${formats.time(endsAt)}", page.ui().sheet!!.answer!!.lines.first())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A10: Consent Request Abandoned", businessRules = ["BR-002"])
    @DisplayName("A10 (#abandoned): Cancel while waiting deletes the Consent Request; not granted, the earlier Bank Consent unchanged; Start again")
    fun abandoned() {
        accepted()
        val page = page()
        val state = page.startedAtVesta()

        page.cancelWaiting(page.ui().waiting.single().requestId)

        val result = page.ui().result!!
        assertEquals("Bank Consent not granted · $vesta", result.title)
        assertEquals(listOf("The Consent Request was cancelled, or no result arrived in time.", "Any earlier Bank Consent of $vesta is unchanged."), result.lines)
        assertEquals(listOf(ResultAction.StartAgain), result.actions)
        assertTrue(page.ui().waiting.isEmpty())

        answers.returned(answerTo(state))
        assertTrue(storedConsents().isEmpty())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A11: No Browser on the Device")
    @DisplayName("A11 (#no-browser): without a browser nothing is started or sent; a browser that does not open is the same, and its Consent Request is deleted")
    fun noBrowser() {
        accepted()
        browser = false
        val page = page()
        page.openChooser()
        page.chooseBank(page.ui().chooser!!.banks.first { it.name == vesta })

        page.openMyBank()

        val answer = page.ui().sheet!!.answer!!
        assertEquals("Bank Consent not started · $vesta", answer.title)
        assertEquals(listOf("A browser is needed to open your Bank's sign-in.", "Install or enable a browser on this phone, then try again. Nothing was sent."), answer.lines)
        assertTrue(slice.bankConsent.starts.isEmpty())

        browser = true
        page.openMyBank()
        page.opened(false)
        val withdrawn = page.ui().sheet!!.answer!!
        assertEquals("Bank Consent not opened · $vesta", withdrawn.title)
        assertEquals(
            listOf(
                "The Consent Request was started, but no app on this phone could show your Bank's sign-in, so it was withdrawn.",
                "Install or enable a browser on this phone, then try again.",
            ),
            withdrawn.lines,
        )
        assertTrue(requests().isEmpty())
        assertTrue(page.ui().waiting.isEmpty())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A11: No Browser on the Device")
    @DisplayName("A11 (E4 review item 4): a Consent Request that could not be shown and could not be withdrawn stays shown as waiting, with its paste field and Cancel")
    fun notShownAndNotWithdrawn() {
        accepted()
        val page = page()
        page.openChooser()
        page.chooseBank(page.ui().chooser!!.banks.first { it.name == vesta })
        page.openMyBank()
        slice.consentStore.failsToAbandon = true

        page.opened(false)

        assertNull(page.ui().sheet)
        val waiting = page.ui().waiting.single()
        assertEquals(
            "Your Bank's sign-in could not be shown, and the Consent Request could not be withdrawn: paste the address your browser shows, or Cancel.",
            waiting.note,
        )
        assertEquals(1, requests().size)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A2: Result Not Accepted", businessRules = ["BR-011"])
    @DisplayName("S-B-52 (E4 review item 6): an answer that arrives without accepted credentials is lost with Slussen's own address: start again; pasting is offered only for a registered address of another kind")
    fun resultNotHandedIn() {
        accepted()
        val page = page()
        val state = page.startedAtVesta()
        runBlocking { slice.credentials.remove(key) }

        answers.returned(answerTo(state))

        val lost = page.ui().result!!
        assertEquals("Result not handed in", lost.title)
        assertEquals(
            listOf(
                "Enter the credentials of $provider first · its Bank Consents are granted and renewed once they are accepted",
                "The answer is lost: start again once they are accepted.",
            ),
            lost.lines,
        )
        assertEquals(listOf(ResultAction.StartAgain), lost.actions)

        page.done()
        slice.bankConsent.registered = { "https://household.example/return" }
        page.load()
        answers.returned(answerTo(state))
        assertEquals(
            "Or, once they are accepted, paste the address your browser shows on https://household.example/return again, within the Consent Request lifetime.",
            page.ui().result!!.lines.last(),
        )
    }

    @Test
    @UseCase(id = "UC-007", businessRules = ["BR-002"])
    @DisplayName("Step 2: a second start for the same Bank while one waits says that it makes the waiting one unanswerable, and replaces it")
    fun aSecondStartForTheSameBank() {
        accepted()
        val page = page()
        page.startedAtVesta()
        val first = page.ui().waiting.single().requestId

        page.openChooser()
        page.chooseBank(page.ui().chooser!!.banks.first { it.name == vesta })
        assertEquals("Starting again makes the Consent Request that waits now unanswerable.", page.ui().sheet!!.note)
        page.openMyBank()
        page.opened(true)

        assertTrue(page.ui().waiting.single().requestId != first)
    }

    // ---- A7: revoke -------------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "A7: Revoke a Bank Consent", businessRules = ["BR-007"])
    @DisplayName("A7 (#revoke, #revoked): confirmation first; revoked also when the Data Provider does not confirm, saying to end it at the Bank too, with Grant Bank Consent")
    fun revoke() {
        accepted()
        val page = page()
        page.granted()
        page.done()
        val bankId = bankIdOf(vesta)
        added(44, "Vesta Card", bankId, "lasting-card")
        page.load()
        slice.bankConsent.revokeAnswer = { RevokeResult.NotConfirmed }

        page.requestRevoke(bankId)
        val question = page.ui().revokeQuestion!!
        assertEquals("Revoke the Bank Consent of $vesta?", question.title)
        assertEquals("Vesta Card will not be fetched until you grant a new Bank Consent.", question.text)
        assertEquals("Your Bank Accounts, Account Mappings, Bank Transactions and Sent Record are kept.", question.kept)
        page.cancelRevoke()
        assertNull(page.ui().revokeQuestion)
        assertEquals(1, storedConsents().size)

        page.requestRevoke(bankId)
        page.confirmRevoke()

        val line = page.ui().lines.single()
        assertEquals("Revoked", line.status)
        assertEquals(listOf("Deleted · its Bank Accounts are not fetched", "Not confirmed by $provider · end it at $vesta too"), line.answer!!.lines)
        assertEquals("Grant Bank Consent", line.renewAction)
        assertTrue(storedConsents().isEmpty())
    }

    @Test
    @UseCase(id = "UC-007", scenario = "A7: Revoke a Bank Consent", businessRules = ["BR-007"])
    @DisplayName("A7 step 5: an end the Data Provider confirmed at the Bank says nothing more")
    fun revokeConfirmed() {
        accepted()
        val page = page()
        page.granted()
        page.done()
        val bankId = bankIdOf(vesta)

        page.requestRevoke(bankId)
        page.confirmRevoke()

        assertEquals(listOf("Deleted · its Bank Accounts are not fetched"), page.ui().lines.single().answer!!.lines)
    }

    // ---- Preconditions ----------------------------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("Preconditions (`connections.html#enable-banking-removed`): without credentials nothing is offered that would contact the Data Provider; the Bank Consents stay listed")
    fun credentialsMissing() {
        runBlocking {
            slice.importStatusStore.consents += StoredBankConsent(1, slice.connectionStore.bankNamed(vesta).id, slice.openBankingId, slice.clock.instant(), expiry)
        }
        val ui = page().ui()

        assertNull(ui.grantAction)
        assertEquals("Enter the credentials of $provider first · its Bank Consents are granted and renewed once they are accepted", ui.unavailable)
        assertEquals(vesta, ui.lines.single().bankName)
        assertNull(ui.lines.single().renewAction)
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("BR-008: a Data Provider that needs no Bank Consent has no Bank Consents on its page, and reading it contacts nothing")
    fun noBankConsentsForADataProviderThatNeedsNone() {
        val page = BankConsentsViewModel(fixture.services, slice.feedsKey, answers, "SE", { true }).also { it.load() }

        assertNull(page.state.value)
        assertEquals(0, slice.feeds.requestsSent)
    }

    // ---- State that outlives its screen -----------------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("Step 6 (D4 lessons): an answer that comes back while the page is not shown is said on whichever screen he is, until he sees it on the page and leaves it, or dismisses it")
    fun theResultOutlivesItsScreen() {
        accepted()
        val page = page()
        val state = page.startedAtVesta()
        page.left()

        val waitingBanner = ConsentBannerTexts.of(answers.state.value, fixture.services, current = null)!!
        assertEquals("Waiting for the result of $vesta · until ${formats.time(slice.clock.instant().plus(Duration.ofMinutes(15)))}", waitingBanner.title)
        assertEquals(key, waitingBanner.service)

        answers.returned(answerTo(state))
        val banner = ConsentBannerTexts.of(answers.state.value, fixture.services, current = null)!!
        assertEquals("✓ Bank Consent granted · $vesta", banner.title)
        assertNull(ConsentBannerTexts.of(answers.state.value, fixture.services, current = key))

        page.load()
        assertEquals("✓ Bank Consent granted · $vesta", page.ui().result!!.title)
        page.left()
        assertNull(answers.state.value.result)
        assertNull(ConsentBannerTexts.of(answers.state.value, fixture.services, current = null))
    }

    // ---- UC-008 step 2, the Overview's renew action; the lines of a later version gone -------------------------------

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("BR-003 (UC-008 BR-002): the Overview's expiring Bank Consent has its live Renew action for its Data Provider and Bank, and no line says that Bank Consents come later")
    fun theOverviewRenewsAnExpiringBankConsent() {
        runBlocking {
            slice.enterCredentials()
            val bankId = slice.connectionStore.bankNamed(vesta).id
            slice.importStatusStore.consents += StoredBankConsent(1, bankId, slice.openBankingId, slice.clock.instant(), slice.clock.instant().plus(Duration.ofDays(3)))
            added(45, "Vesta Card", bankId, "lasting-card")
        }
        val overview = OverviewViewModel(fixture.services).also { it.load() }.state.value!!

        val expiring = overview.expiringConsents.single()
        assertEquals("Bank Consent of $vesta through $provider expires ${formats.date(slice.clock.instant().plus(Duration.ofDays(3)))}", expiring.text)
        assertEquals("Renew", expiring.action)
        assertEquals(key, expiring.service)
        assertEquals(bankIdOf(vesta), expiring.bankId)
        assertFalse(overview.toString().contains("not in this build"))
    }

    @Test
    @UseCase(id = "UC-007", scenario = "Main Success Scenario", businessRules = ["BR-008"])
    @DisplayName("Phase E5: the page of a Data Provider that needs a Bank Consent offers its Fetch, with no text that its Bank Accounts are not read, and offers Bank Consents")
    fun theFetchSaysWhatThisBuildDoes() {
        accepted()
        val page = ServicePageViewModel(fixture.services, key).also { it.load() }

        assertNull(page.state.value!!.fetchUnavailable)
        assertTrue(page.state.value!!.canFetch)
        assertFalse(page.state.value!!.offered!!.emptyText!!.contains("not in this build"))
    }
}

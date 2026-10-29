package slussen.app.ui.service

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
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import kotlinx.coroutines.CompletableDeferred
import slussen.core.port.FetchOutcome
import slussen.core.port.StartedBy
import slussen.core.port.StoredFetch
import slussen.app.ui.settings.EntryPage
import slussen.app.ui.settings.SettingsViewModel
import slussen.core.port.CoordinatorMode
import slussen.core.port.ServiceKey
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData

@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-014: Manage Service Credentials, the screens")
class UC014ManageServiceCredentialsViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice

    private fun page(service: ServiceKey) =
        ServicePageViewModel(fixture.services, service).also { it.load() }

    private fun ServicePageViewModel.ui() = state.value!!

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 1: Settings lists the Budget App and each Data Provider, also those not set up, each with one status line and opening its page; there is no separate Set up action")
    fun settingsListsEveryServiceWithItsStatus() = runBlocking {
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))

        val ui = SettingsViewModel(fixture.services).also { it.load() }.state.value!!

        val services = ui.entries.filter { it.page is EntryPage.Service }
        assertEquals(listOf(ExampleData.BUDGET_APP_NAME, "Kestrel Feeds", "Aurora Open Banking", "Wren Feeds"), services.map { it.name })
        assertEquals(listOf("Budget App", "Data Provider", "Data Provider · needs Bank Consent", "Data Provider"), services.map { it.kind })
        assertEquals("Not set up · next: enter the ${ExampleData.BUDGET_APP_NAME} credentials", services[0].status)
        assertEquals("Nothing offered yet", services[1].status)
        assertEquals("Not set up", services[2].status)
        assertEquals(EntryPage.Service(slice.feedsKey), services[1].page)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-007"])
    @DisplayName("BR-007: a service's page opens also while it is not set up, saying so, with the action that enters its credentials")
    fun pageOfAServiceNotSetUp() {
        val page = page(slice.wrenKey)

        assertEquals("Wren Feeds", page.ui().name)
        assertEquals("Data Provider · not set up", page.ui().kind)
        assertEquals("Not set up", page.ui().status)
        assertNull(page.ui().blocked)
        assertFalse(page.ui().canRemove)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 2: the form shows the fields the service's part declares, with what each is and where to get it")
    fun formShowsTheDeclaredFields() {
        val page = page(slice.feedsKey)

        page.startEntering()

        assertTrue(page.ui().entering)
        assertEquals(listOf(FieldUi("setupKey", FieldKind.Secret, "Setup key, from the example provider's website")), page.ui().fields)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-003"])
    @DisplayName("Steps 3-6: accepted credentials are shown as accepted on the service's page")
    fun acceptedCredentialsAreShownAccepted() {
        val page = page(ServiceKey.budgetApp)
        page.startEntering()

        page.save(mapOf("token" to ExampleData.BUDGET_APP_TOKEN))

        val ui = page.ui()
        assertFalse(ui.entering)
        assertEquals("Accepted 2026-10-15", ui.credentials)
        // NFR-023: the answer is at the credentials, where the check started.
        assertEquals(AnswerUi("Accepted by ${ExampleData.BUDGET_APP_NAME}", AnswerKind.Done), ui.credentialAnswer)
        assertEquals("Replace", ui.enterAction)
        assertEquals("Connected · 0 Budgets in use", ui.status)
        assertNull(ui.blocked)
        assertFalse(ExampleData.BUDGET_APP_TOKEN in ui.toString())
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A1: Credentials Refused")
    @DisplayName("A1: a refusal is shown, nothing is stored and the form stays open to enter them again")
    fun refusalIsShown() {
        slice.budgetApp.credentials.answer = CredentialCheck.Refused
        val page = page(ServiceKey.budgetApp)
        page.startEntering()

        page.save(mapOf("token" to "PLANTED-REFUSED-TOKEN"))

        assertEquals(
            AnswerUi(
                "Refused by ${ExampleData.BUDGET_APP_NAME} 14:00 · nothing stored",
                AnswerKind.Failed,
                "Check what you entered, or get new credentials from ${ExampleData.BUDGET_APP_NAME}, and try again.",
            ),
            page.ui().credentialAnswer,
        )
        assertEquals("Try again", page.ui().saveAction)
        assertNull(page.ui().earlierCredentialsNote)
        assertTrue(page.ui().entering)
        assertEquals("Not entered", page.ui().credentials)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A2: Service Cannot Be Reached")
    @DisplayName("A2: a service that cannot be reached is shown as such, nothing is stored and the use case ends")
    fun unreachableServiceIsShown() {
        slice.feeds.credentials.answer = CredentialCheck.TransientFailure(FailureKind.Timeout)
        val page = page(slice.feedsKey)
        page.startEntering()

        page.save(mapOf("setupKey" to ExampleData.SETUP_KEY))

        assertEquals(
            AnswerUi("Kestrel Feeds could not be reached: no answer within the request timeout, an Operator Setting · nothing stored", AnswerKind.Failed),
            page.ui().credentialAnswer,
        )
        assertFalse(page.ui().entering)
        assertEquals("Not entered", page.ui().credentials)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A2: Service Cannot Be Reached", requirements = ["NFR-004"])
    @DisplayName("A2: a service the device cannot reach, a lost connection, is shown as not reached, nothing is stored and the use case ends")
    fun serviceTheDeviceCannotReachIsShown() {
        slice.feeds.credentials.answer = CredentialCheck.TransientFailure(FailureKind.ConnectionLost)
        val page = page(slice.feedsKey)
        page.startEntering()

        page.save(mapOf("setupKey" to ExampleData.SETUP_KEY))

        assertEquals(AnswerUi("Kestrel Feeds could not be reached: the connection was lost · nothing stored", AnswerKind.Failed), page.ui().credentialAnswer)
        assertFalse(page.ui().entering)
        assertEquals("Not entered", page.ui().credentials)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A3: Remove Credentials", businessRules = ["BR-005", "BR-007"])
    @DisplayName("A3: removing asks first, stating the service is not contacted until new credentials are entered; confirming shows credentials missing for a Data Provider that still has a Bank Account")
    fun removalAsksFirstAndShowsCredentialsMissing() = runBlocking {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as slussen.core.connection.BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))
        val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-4410", "CHK", "checking", ExampleData.USD, "4410", null, bank, slice.clock.instant())),
        )
        val checking = slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == "Checking" }.id
        slice.connect.add(
            slice.offeredStore.offered().single().id, checking, "Kestrel Checking", slussen.core.port.BankAccountType.Checking,
            slussen.core.port.BankChoice.Known(bank), null,
        )
        val page = page(slice.feedsKey)

        page.requestRemoval()
        assertEquals(
            "Remove Kestrel Feeds credentials? Kestrel Feeds is not contacted until you enter new ones.",
            page.ui().removalQuestion,
        )
        page.cancelRemoval()
        assertNull(page.ui().removalQuestion)
        assertTrue(slice.vault.read(slice.feedsKey) != null)

        page.requestRemoval()
        page.confirmRemoval()

        assertNull(page.ui().removalQuestion)
        assertEquals("Credentials missing", page.ui().status)
        assertEquals("Blocked: credentials missing · Enter credentials", page.ui().blocked)
        assertFalse(page.ui().canRemove)
        assertEquals(AnswerUi("Removed · recorded in the Decision Journal", AnswerKind.Done), page.ui().credentialAnswer)
        assertEquals("Enter credentials", page.ui().enterAction)
        assertNull(slice.vault.read(slice.feedsKey))
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A4: Operator Cancels")
    @DisplayName("A4: cancelling closes the form and keeps the earlier credentials")
    fun cancellingKeepsTheEarlierCredentials() = runBlocking {
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))
        val page = page(ServiceKey.budgetApp)
        page.startEntering()

        page.cancelEntering()

        assertFalse(page.ui().entering)
        assertEquals("Accepted 2026-10-15", page.ui().credentials)
        assertEquals(1, slice.budgetApp.credentials.requestsSent)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A5: Held Back by the Request Gate", businessRules = ["BR-002"])
    @DisplayName("A5: at the Request Limit the check is not sent; the page shows the Blocked Reason with the time it frees up, nothing stored, and the form closes")
    fun requestLimitReachedIsShown() {
        runBlocking { repeat(20) { slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) } }
        val page = page(slice.feedsKey)
        page.startEntering()

        page.save(mapOf("setupKey" to "PLANTED-NEW-SETUP-KEY"))

        assertEquals(
            AnswerUi(
                "Blocked: Request Limit reached · not checked · frees up from 2026-10-16 14:00 · nothing stored, the earlier credentials stay unchanged",
                AnswerKind.Unchanged,
            ),
            page.ui().credentialAnswer,
        )
        assertFalse(page.ui().entering)
        assertEquals("Accepted 2026-10-15", page.ui().credentials)
        assertEquals(20, slice.feeds.credentials.requestsSent)
    }

    @Test
    @UseCase(id = "UC-014")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation, saving shows why it is unavailable")
    fun savingIsUnavailableWhileAPlanAwaitsConfirmation() {
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen
        val page = page(ServiceKey.budgetApp)
        page.startEntering()

        page.save(mapOf("token" to ExampleData.BUDGET_APP_TOKEN))

        assertEquals(
            AnswerUi("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", AnswerKind.Unchanged),
            page.ui().credentialAnswer,
        )
        assertEquals(0, slice.budgetApp.credentials.requestsSent)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Data Provider's page, also not set up, shows its Fetch Cooldown with the declared default, its Request Limit and its Bank Accounts")
    fun dataProviderPageShowsItsDeclarations() {
        val ui = page(slice.feedsKey).ui()

        assertEquals("Kestrel Feeds", ui.name)
        assertEquals("Data Provider · not set up", ui.kind)
        assertEquals("60 min · default 60, declared by its part · 0 to 1440 · 0 is off", ui.fetchCooldown)
        assertEquals(
            "20 per 24 hours · the Data Provider's own limit, published by Kestrel Feeds · source: Kestrel Feeds developer guide, an invented source · not a setting",
            ui.requestLimit,
        )
        assertFalse(ui.requestLimitEditable)
        assertNull(ui.blocked)
        assertTrue(ui.bankAccounts.isEmpty())
        assertFalse(ui.uploadAsClearedShown)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-007"])
    @DisplayName("BR-007: the Budget App's page shows the Budgets in use and Upload as Cleared, which it supports")
    fun budgetAppPageShowsBudgetsInUse() = runBlocking {
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as slussen.core.connection.BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household", "budget-summer"))

        val ui = page(ServiceKey.budgetApp).ui()

        assertEquals("Budget App", ui.kind)
        assertEquals(listOf("Household · USD", "Summer House · EUR"), ui.budgets!!.inUse)
        assertTrue(ui.uploadAsClearedShown)
        assertNull(ui.fetchCooldown)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A5: Held Back by the Request Gate", businessRules = ["BR-002"])
    @DisplayName("A5: during a Back-off of the whole Data Provider the page shows waiting for the provider with the time it ends, nothing stored, and the form closes")
    fun backOffIsShown() {
        val endsAt = slice.clock.instant().plus(java.time.Duration.ofHours(3))
        slice.importStatusStore.backOffList += slussen.core.port.StoredBackOff(
            slice.feedsId, null, slussen.core.port.BackOffCause.RequestToWait, slice.clock.instant(), endsAt,
        )
        val page = page(slice.feedsKey)
        page.startEntering()

        page.save(mapOf("setupKey" to "PLANTED-NEW-SETUP-KEY"))

        assertEquals(
            AnswerUi(
                "Blocked: waiting for the provider · not checked · until 2026-10-15 17:00 · nothing stored, the earlier credentials stay unchanged",
                AnswerKind.Unchanged,
            ),
            page.ui().credentialAnswer,
        )
        assertFalse(page.ui().entering)
        assertEquals("Not entered", page.ui().credentials)
        assertEquals(0, slice.feeds.credentials.requestsSent)
    }

    // ---- Answers in place (NFR-023, UX-D4: UX-21) -------------------------------------------------------------------

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Steps 3-4 (connections.html#simplefin-checking): Save shows at once that the service is being asked, before any answer, and Cancel waits; it ends as accepted")
    fun checkShowsCheckingAtSave() {
        val hold = CompletableDeferred<Unit>()
        slice.feeds.credentials.beforeAnswer = { hold.await() }
        val page = page(slice.feedsKey)
        page.startEntering()

        page.save(mapOf("setupKey" to ExampleData.SETUP_KEY))
        assertTrue(page.ui().checking)
        assertTrue(page.ui().entering)
        assertNull(page.ui().credentialAnswer)
        page.cancelEntering()
        assertTrue(page.ui().entering)
        hold.complete(Unit)

        assertFalse(page.ui().checking)
        assertFalse(page.ui().entering)
        assertEquals(AnswerUi("Accepted by Kestrel Feeds", AnswerKind.Done), page.ui().credentialAnswer)
        assertEquals("Accepted 2026-10-15", page.ui().credentials)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A1: Credentials Refused", businessRules = ["BR-005"])
    @DisplayName("A1, BR-005 (connections.html#simplefin-check-refused, #simplefin-replace): replacing credentials says that the earlier ones stay; refused, the answer is at the form, nothing is stored and the earlier credentials stay accepted")
    fun refusedReplacementKeepsTheEarlierCredentials() {
        runBlocking { slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }
        slice.feeds.credentials.answer = CredentialCheck.Refused
        val page = page(slice.feedsKey)
        assertEquals("Replace", page.ui().enterAction)

        page.startEntering()
        assertEquals("The earlier credentials stay until the new ones are accepted.", page.ui().earlierCredentialsNote)
        page.save(mapOf("setupKey" to "PLANTED-REFUSED-SETUP-KEY"))

        assertEquals(
            AnswerUi(
                "Refused by Kestrel Feeds 14:00 · nothing stored",
                AnswerKind.Failed,
                "Check what you entered, or get new credentials from Kestrel Feeds, and try again.",
            ),
            page.ui().credentialAnswer,
        )
        assertTrue(page.ui().entering)
        assertEquals("Try again", page.ui().saveAction)
        assertEquals("The earlier credentials stay until the new ones are accepted.", page.ui().earlierCredentialsNote)
        assertEquals("Accepted 2026-10-15", page.ui().credentials)
        assertEquals(ExampleData.EXCHANGED_ACCESS_CREDENTIAL, runBlocking { slice.credentials.stored(slice.feedsKey) }?.let { reveal(it) })
    }

    @OptIn(slussen.pluginapi.value.SensitiveApi::class)
    private fun reveal(secret: slussen.pluginapi.value.Secret): String = secret.reveal()

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-005"])
    @DisplayName("BR-005 (UX-21, connections.html#simplefin-refused): the page of a Data Provider that refused the credentials at a Fetch keeps the refusal in view instead of saying Accepted, with the Fetch's time, and its first action enters new credentials; stored data stays")
    fun refusedPageLeadsToTheEntryAction() {
        runBlocking { slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }
        slice.clock.advance(java.time.Duration.ofMinutes(5))
        slice.importStatusStore.fetches += StoredFetch(
            1, slice.feedsId, slice.clock.instant(), StartedBy.Operator, FetchOutcome.CredentialsRefused, emptyList(),
        )

        val ui = page(slice.feedsKey).ui()

        assertEquals("Credentials refused", ui.status)
        assertEquals("Refused at the Fetch of 2026-10-15 14:05 · accepted 2026-10-15", ui.credentials)
        assertEquals("Blocked: credentials refused · Enter credentials", ui.blocked)
        assertEquals(
            "Kestrel Feeds refused Slussen's credentials at the Fetch of 2026-10-15 14:05. Enter new credentials; your Bank " +
                "Accounts and their stored data stay.",
            ui.blockedDetail,
        )
        assertEquals("Enter new credentials", ui.enterAction)
    }
}

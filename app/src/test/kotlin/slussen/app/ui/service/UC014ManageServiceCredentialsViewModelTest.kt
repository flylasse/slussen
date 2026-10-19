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
import slussen.app.ui.connections.ConnectionsViewModel
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

    private fun page(service: ServiceKey, fromSetup: Boolean = false) =
        ServicePageViewModel(fixture.services, service, fromSetup).also { it.load() }

    private fun ServicePageViewModel.ui() = state.value!!

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 1: Connections shows each service in use with one status line and the Data Providers not set up")
    fun connectionsShowsServicesInUseAndNotSetUp() = runBlocking {
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        val connections = ConnectionsViewModel(fixture.services).also { it.load() }

        val ui = connections.state.value!!

        assertEquals(listOf(ExampleData.BUDGET_APP_NAME, "Kestrel Feeds"), ui.inUse.map { it.name })
        assertEquals(listOf("Budget App", "Data Provider"), ui.inUse.map { it.kind })
        assertEquals("Credentials missing", ui.inUse[0].status)
        assertEquals("Connected · no Fetch yet", ui.inUse[1].status)
        assertEquals(listOf("Aurora Open Banking", "Wren Feeds"), ui.notSetUp.map { it.name })
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
        assertEquals("Accepted by ${ExampleData.BUDGET_APP_NAME}", ui.message)
        assertEquals("Connected · 0 Budgets in use", ui.status)
        assertNull(ui.blocked)
        assertFalse(ui.returnToSetup)
        assertFalse(ExampleData.BUDGET_APP_TOKEN in ui.toString())
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario")
    @DisplayName("Step 6: when Connect Budgets and Bank Accounts started it, accepted credentials return there")
    fun acceptedCredentialsReturnToSetup() {
        val page = page(ServiceKey.budgetApp, fromSetup = true)
        page.startEntering()

        page.save(mapOf("token" to ExampleData.BUDGET_APP_TOKEN))

        assertTrue(page.ui().returnToSetup)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A1: Credentials Refused")
    @DisplayName("A1: a refusal is shown, nothing is stored and the form stays open to enter them again")
    fun refusalIsShown() {
        slice.budgetApp.credentials.answer = CredentialCheck.Refused
        val page = page(ServiceKey.budgetApp)
        page.startEntering()

        page.save(mapOf("token" to "PLANTED-REFUSED-TOKEN"))

        assertEquals("Refused by ${ExampleData.BUDGET_APP_NAME} · nothing stored", page.ui().message)
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
            "Kestrel Feeds could not be reached: no answer within the request timeout, an Operator Setting · nothing stored",
            page.ui().message,
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

        assertEquals("Kestrel Feeds could not be reached: the connection was lost · nothing stored", page.ui().message)
        assertFalse(page.ui().entering)
        assertEquals("Not entered", page.ui().credentials)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A3: Remove Credentials", businessRules = ["BR-005"])
    @DisplayName("A3: removing asks first, stating the service is not contacted until new credentials are entered; confirming shows credentials missing")
    fun removalAsksFirstAndShowsCredentialsMissing() = runBlocking {
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
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
            "Blocked: Request Limit reached · not checked · frees up from 2026-10-16 14:00 · nothing stored, the earlier credentials stay unchanged",
            page.ui().message,
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

        assertEquals("Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first.", page.ui().message)
        assertEquals(0, slice.budgetApp.credentials.requestsSent)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Data Provider's page shows its Fetch Cooldown with the declared default, its Request Limit and its Bank Accounts")
    fun dataProviderPageShowsItsDeclarations() {
        val ui = page(slice.feedsKey).ui()

        assertEquals("Kestrel Feeds", ui.name)
        assertEquals("Data Provider", ui.kind)
        assertEquals("60 min · default 60, declared by its part · 0 to 1440 · 0 is off", ui.fetchCooldown)
        assertEquals(
            "20 per 24 hours · the Data Provider's own limit, published by Kestrel Feeds · source: Kestrel Feeds developer guide, an invented source · not a setting",
            ui.requestLimit,
        )
        assertFalse(ui.requestLimitEditable)
        assertEquals("Blocked: credentials missing · Enter credentials", ui.blocked)
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
        assertEquals(listOf("Household · USD", "Summer House · EUR"), ui.budgetsInUse)
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
            "Blocked: waiting for the provider · not checked · until 2026-10-15 17:00 · nothing stored, the earlier credentials stay unchanged",
            page.ui().message,
        )
        assertFalse(page.ui().entering)
        assertEquals("Not entered", page.ui().credentials)
        assertEquals(0, slice.feeds.credentials.requestsSent)
    }
}

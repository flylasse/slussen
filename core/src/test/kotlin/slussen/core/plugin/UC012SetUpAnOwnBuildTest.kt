package slussen.core.plugin

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.testing.TestSlice
import slussen.core.credential.CredentialStatus
import slussen.core.port.ServiceKey
import slussen.core.settings.ConnectionsOverview
import slussen.core.settings.RequestLimitShown
import slussen.core.settings.ServiceStatus
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.ExampleData
import slussen.testing.FakeDataProvider
import slussen.testing.InMemoryPluginRegistryStore

/**
 * The parts of Set Up an Own Build (UC-012) that the core realizes: every Source is a part that declares itself, and a new
 * installation takes its Data Providers' numbers from their parts and everything else from the Operator in the app.
 * The build rules of UC-012 (signing values, no credential in a built app) are proven by the TestKit tests of
 * `build-logic/` (ADR-003 §6).
 */
@DisplayName("UC-012: Set Up an Own Build")
class UC012SetUpAnOwnBuildTest {
    @Test
    @UseCase(id = "UC-012", scenario = "A2: Data Provider Missing", businessRules = ["BR-005", "BR-006"], requirements = ["C-010"])
    @DisplayName("A2: a Data Provider added as its own part is registered from its declarations and gets its own page in Connections")
    fun addedDataProviderGetsItsOwnPage() = runTest {
        val slice = TestSlice()
        val added = FakeDataProvider(
            ExampleData.wrenProfile.copy(
                name = "Osprey Feeds",
                sourceKey = "example-osprey",
                requestLimit = RequestLimitDeclaration.NotPublished(defaultRequests = 7, range = 5..40),
            ),
        )
        val plugins = PluginRegistration(slice.registry)
            .register(slice.budgetApp, listOf(slice.feeds, slice.openBanking, slice.wren, added), emptyList())
        val registered = plugins.dataProviders.single { it.plugin === added }
        val credentials = slussen.core.credential.ServiceCredentials(
            plugins, slice.vault, slice.coordinator, slice.journal, slice.settings, slice.providerRequests,
            slussen.core.credential.CredentialRefusals(), slice.clock,
        )
        val connections = ConnectionsOverview(
            plugins, credentials, slice.connectionStore, slice.importStatusStore, slice.settings, slice.clock,
        )

        val page = connections.servicePage(registered.key)

        assertEquals("Osprey Feeds", page.name)
        assertEquals(RequestLimitShown.NotPublished(requests = 7, defaultRequests = 7, range = 5..40), page.requestLimit)
        assertEquals(ExampleData.wrenProfile.defaultFetchCooldownMinutes, page.defaultFetchCooldownMinutes)
        assertEquals(added.credentials.form.fields, page.credentialFields)
        assertEquals(ServiceStatus.CredentialsMissing, page.status)
        assertTrue(registered.stored.givesStableReference == ExampleData.wrenProfile.givesStableReference)
        assertTrue(connections.connections().notSetUp.any { it.name == "Osprey Feeds" })
    }

    @Test
    @UseCase(id = "UC-012", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("BR-011: each Data Provider's published Request Limit and default Fetch Cooldown come from its part; the Operator's Fetch Cooldown is kept at the next start")
    fun declaredNumbersComeFromThePart() = runTest {
        val registry = InMemoryPluginRegistryStore()
        val feeds = FakeDataProvider(ExampleData.feedsProfile)
        val budgetApp = TestSlice().budgetApp
        val first = PluginRegistration(registry).register(budgetApp, listOf(feeds), emptyList())
        val id = first.dataProviders.single().stored.id
        registry.dataProviders.replaceAll { if (it.id == id) it.copy(fetchCooldownMinutes = 15) else it }
        val newerPart = FakeDataProvider(
            ExampleData.feedsProfile.copy(
                requestLimit = RequestLimitDeclaration.Published(25, ExampleData.FEEDS_LIMIT_SOURCE),
                defaultFetchCooldownMinutes = 90,
            ),
        )

        val next = PluginRegistration(registry).register(budgetApp, listOf(newerPart), emptyList())

        val stored = next.dataProviders.single().stored
        assertEquals(id, stored.id)
        assertEquals(25, stored.requestLimit)
        assertTrue(stored.requestLimitPublished)
        assertEquals(15, stored.fetchCooldownMinutes)
        assertEquals(20, first.dataProviders.single().stored.requestLimit)
    }

    @Test
    @UseCase(id = "UC-012", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-012"], requirements = ["NFR-021"])
    @DisplayName("Step 6: a started app holds no credential and lets the Operator enter each service's credentials and connect Budgets and Bank Accounts in the app")
    fun aNewInstallationIsSetUpInTheApp() = runTest {
        val slice = TestSlice()

        slice.plugins.serviceKeys.forEach { assertEquals(CredentialStatus.Missing, slice.credentials.status(it)) }
        assertTrue(slice.connectionStore.budgets().isEmpty())
        assertTrue(slice.connectionStore.bankAccounts().isEmpty())
        assertTrue(slice.status.open().noBankAccountAdded)
        slice.enterCredentials()
        assertTrue(slice.credentials.status(ServiceKey.budgetApp) is CredentialStatus.Accepted)
    }

    @Test
    @UseCase(id = "UC-012", scenario = "A3: Bank Export Format Missing", businessRules = ["BR-005"])
    @DisplayName("A3: a File Pre-processor added as its own part is registered as a File Format a Bank Account can be added with")
    fun addedFilePreprocessorIsAFileFormat() = runTest {
        val slice = TestSlice()

        assertEquals(listOf("Harbor Health statement"), slice.plugins.fileFormats.map { it.name })
        assertEquals("example-format", slice.plugins.fileFormats.single().sourceKey)
    }

    @Test
    @UseCase(id = "UC-012", scenario = "A4: Another Budget App", businessRules = ["BR-006"])
    @DisplayName("A4: the Budget App is one part; its name and credentials reach the screens only through its declarations")
    fun budgetAppIsOnePart() = runTest {
        val slice = TestSlice()

        val page = slice.connections.servicePage(ServiceKey.budgetApp)

        assertEquals(ExampleData.BUDGET_APP_NAME, page.name)
        assertEquals(slice.budgetApp.credentials.form.fields, page.credentialFields)
        assertTrue(page.supportsClearedMarker)
    }

    @Test
    @UseCase(id = "UC-012", businessRules = ["BR-004"], requirements = ["C-004"])
    @DisplayName("BR-004: every service credential entered in the app can be replaced and removed there, so exposed ones are rotated")
    fun exposedCredentialsAreRotatedInTheApp() = runTest {
        val slice = TestSlice()
        slice.enterCredentials()
        slice.feeds.credentials.answer = slussen.pluginapi.credential.CredentialCheck.Accepted(slussen.pluginapi.value.Secret("PLANTED-ROTATED"))

        val replaced = slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to "PLANTED-NEW-SETUP-KEY"))
        val removed = slice.credentials.remove(ServiceKey.budgetApp)

        assertTrue(replaced is slussen.core.credential.CredentialOutcome.Accepted)
        assertEquals(slussen.core.credential.RemovalOutcome.Removed, removed)
        assertEquals(CredentialStatus.Missing, slice.credentials.status(ServiceKey.budgetApp))
    }

    @Test
    @UseCase(id = "UC-012", businessRules = ["BR-007"])
    @DisplayName("BR-007: taking over from another import path, the Start Date of a Bank Account is set in the app when it is added")
    fun startDateIsSetInTheApp() = runTest {
        val slice = TestSlice()
        slice.enterCredentials()
        val read = slice.connect.readBudgets() as slussen.core.connection.BudgetsRead.Listed
        slice.connect.chooseBudgets(read.budgets, setOf("budget-household"))
        val bank = slice.connectionStore.bankNamed("Kestrel Bank").id
        slice.offeredStore.replaceOffered(
            slice.feedsId,
            null,
            listOf(ExampleData.offered("acct-1", "CHK", "checking", ExampleData.USD, "4410", null, bank, slice.clock.instant())),
        )
        val offered = slice.offeredStore.offered().single().id
        val budgetAccount = slice.connectionStore.budgets().single().budgetAccounts.first().id
        val proposal = (slice.connect.propose(offered, budgetAccount) as slussen.core.connection.PairOutcome.Proposed).proposal

        slice.connect.add(
            offered, budgetAccount, "Kestrel Checking", proposal.proposedType,
            slussen.core.port.BankChoice.Known(bank), java.time.LocalDate.parse("2026-09-02"),
        )

        assertEquals(java.time.LocalDate.parse("2026-09-02"), slice.connectionStore.bankAccounts().single().startDate)
    }
}

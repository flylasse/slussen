package slussen.core.credential

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.testing.TestSlice
import slussen.core.operation.OperationRefusal
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.CoordinatorMode
import slussen.core.port.FetchOutcome
import slussen.core.port.JournalEventKind
import slussen.core.port.NewBankAccount
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.core.port.StoredFetch
import slussen.core.settings.RequestLimitShown
import slussen.core.settings.ServiceStatus
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import slussen.testing.ExampleData
import java.time.Duration

@DisplayName("UC-014: Manage Service Credentials")
class UC014ManageServiceCredentialsTest {
    private val slice = TestSlice()

    private suspend fun storedSecret(service: ServiceKey): String? =
        slice.vault.read(service)?.credential?.let { reveal(it) }

    @OptIn(SensitiveApi::class)
    private fun reveal(secret: Secret): String = secret.reveal()

    private suspend fun addBankAccountOf(dataProviderId: Long) {
        val budget = slice.connectionStore.budgets().first()
        slice.connectionStore.addBankAccount(
            NewBankAccount(
                generatedIdentifier = "00000000-0000-4000-8000-000000000001",
                bank = BankChoice.Named("Kestrel Bank"),
                providerBankId = null,
                dataProviderId = dataProviderId,
                fileFormatId = null,
                balanceDataProviderId = null,
                balanceProviderReference = null,
                name = "Kestrel Checking",
                currency = budget.currency,
                type = BankAccountType.Checking,
                providerReference = "acct-1",
                bankAccountFingerprint = null,
                identifiedAt = slice.clock.instant(),
                startDate = null,
                budgetAccountId = budget.budgetAccounts.first().id,
            ),
        )
    }

    private suspend fun serveHousehold() {
        val read = ExampleData.budgetsRead().first()
        slice.connectionStore.serveBudgets(
            listOf(
                slussen.core.port.BudgetToServe(
                    read.reference,
                    read.name,
                    read.currency,
                    read.budgetAccounts.map { slussen.core.port.BudgetAccountToStore(it.reference, it.name, it.balance) },
                ),
            ),
            setOf(read.reference),
            slice.clock.instant(),
        )
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-007"])
    @DisplayName("Step 1: Connections lists the Budget App and each Data Provider in use with its status, and the Data Providers not set up")
    fun connectionsListsServicesInUseAndNotSetUp() = runTest {
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        serveHousehold()
        addBankAccountOf(slice.openBankingId)

        val list = slice.connections.connections()

        assertEquals(
            listOf(ExampleData.BUDGET_APP_NAME, "Kestrel Feeds", "Aurora Open Banking"),
            list.inUse.map { it.name },
        )
        assertEquals(ServiceStatus.CredentialsMissing, list.inUse[0].status)
        assertInstanceOf(ServiceStatus.Connected::class.java, list.inUse[1].status)
        assertEquals(ServiceStatus.CredentialsMissing, list.inUse[2].status)
        assertEquals(listOf("Wren Feeds"), list.notSetUp.map { it.name })
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 2: the form is the one the service's part declares, each field with what it is and where to get it")
    fun formIsTheOneThePartDeclares() {
        assertEquals(slice.budgetApp.credentials.form, slice.credentials.form(ServiceKey.budgetApp))
        val fields = slice.credentials.form(slice.feedsKey).fields
        assertEquals(listOf("setupKey"), fields.map { it.key })
        assertEquals(FieldKind.Secret, fields.single().kind)
        assertTrue(fields.single().description.isNotBlank())
    }

    @Test
    @UseCase(
        id = "UC-014",
        scenario = "Main Success Scenario",
        businessRules = ["BR-002", "BR-003"],
        requirements = ["NFR-012", "NFR-021"],
    )
    @DisplayName("Steps 3-6: the service checks the credentials; what it accepted is stored, journaled without any credential and shown as accepted")
    fun acceptedCredentialsAreStoredJournaledAndShown() = runTest {
        val outcome = slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))

        assertEquals(1, slice.feeds.credentials.requestsSent)
        assertEquals(CredentialOutcome.Accepted(slice.clock.instant()), outcome)
        // Step 4: the credential the part returned is kept; the setup key it was exchanged for is not.
        assertEquals(ExampleData.EXCHANGED_ACCESS_CREDENTIAL, storedSecret(slice.feedsKey))
        val entry = slice.journalRecorder.entries.single()
        assertEquals(JournalEventKind.ConfigurationChange, entry.eventKind)
        assertTrue("Kestrel Feeds" in entry.details)
        assertFalse(ExampleData.SETUP_KEY in entry.details)
        assertFalse(ExampleData.EXCHANGED_ACCESS_CREDENTIAL in entry.details)
        assertEquals(CredentialStatus.Accepted(slice.clock.instant()), slice.credentials.status(slice.feedsKey))
        assertInstanceOf(ServiceStatus.Connected::class.java, slice.connections.servicePage(slice.feedsKey).status)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-003", "BR-006"])
    @DisplayName("Step 4: accepted credentials replace the earlier ones")
    fun acceptedCredentialsReplaceTheEarlierOnes() = runTest {
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to "PLANTED-OLD-TOKEN"))
        slice.budgetApp.credentials.answer = CredentialCheck.Accepted(Secret("PLANTED-NEW-TOKEN"))
        slice.clock.advance(Duration.ofMinutes(5))

        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to "PLANTED-NEW-TOKEN"))

        assertEquals("PLANTED-NEW-TOKEN", storedSecret(ServiceKey.budgetApp))
        assertEquals(CredentialStatus.Accepted(slice.clock.instant()), slice.credentials.status(ServiceKey.budgetApp))
    }

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("Step 3: the check goes through the request gate with the request timeout; a Data Provider's check counts as a Provider Request")
    fun checkGoesThroughTheGateWithTheRequestTimeout() = runTest {
        slice.settings.settings = slice.settings.settings.copy(requestTimeoutSeconds = 45)

        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))

        val context = slice.feeds.credentials.contexts.single()
        assertEquals(Duration.ofSeconds(45), context.timeout.duration)
        assertNull(context.credential)
        val counted = slice.providerRequests.requests.single()
        assertEquals(slice.feedsId, counted.dataProviderId)
        assertEquals(slussen.core.port.ProviderRequestKind.Other, counted.kind)
        assertFalse(counted.unattended)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A5: Request Limit Reached", businessRules = ["BR-002"])
    @DisplayName("A5: at the Data Provider's Request Limit the check is not sent, nothing is stored, the earlier credentials stay, and the time it frees up is shown")
    fun checkAtTheRequestLimitIsNotSent() = runTest {
        val limit = 20
        val first = slice.clock.instant()
        repeat(limit) {
            slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
            slice.clock.advance(Duration.ofMinutes(10))
        }
        val acceptedAt = (slice.credentials.status(slice.feedsKey) as CredentialStatus.Accepted).acceptedAt
        val journalBefore = slice.journalRecorder.entries.size
        slice.feeds.credentials.answer = CredentialCheck.Accepted(Secret("PLANTED-NEVER-STORED"))

        val outcome = slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to "PLANTED-NEW-SETUP-KEY"))

        assertEquals(CredentialOutcome.RequestLimitReached(first.plus(Duration.ofHours(24))), outcome)
        assertEquals(limit, slice.feeds.credentials.requestsSent)
        assertEquals(limit, slice.providerRequests.requests.size)
        assertEquals(ExampleData.EXCHANGED_ACCESS_CREDENTIAL, storedSecret(slice.feedsKey))
        assertEquals(CredentialStatus.Accepted(acceptedAt), slice.credentials.status(slice.feedsKey))
        assertEquals(journalBefore, slice.journalRecorder.entries.size)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A5: Request Limit Reached", businessRules = ["BR-002"])
    @DisplayName("A5: without earlier credentials nothing is stored; once the oldest counted Provider Request has left the 24-hour window, the check is sent again")
    fun checkIsSentAgainOnceTheLimitFreesUp() = runTest {
        val first = slice.clock.instant()
        repeat(20) { slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }
        slice.credentials.remove(slice.feedsKey)

        val held = slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        assertEquals(CredentialOutcome.RequestLimitReached(first.plus(Duration.ofHours(24))), held)
        assertNull(slice.vault.read(slice.feedsKey))

        slice.clock.set(first.plus(Duration.ofHours(24)).plusSeconds(1))
        val again = slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))

        assertInstanceOf(CredentialOutcome.Accepted::class.java, again)
        assertEquals(21, slice.feeds.credentials.requestsSent)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A5: Request Limit Reached", businessRules = ["BR-002"])
    @DisplayName("A5: the Request Limit the Operator set for a Data Provider that publishes none holds for its credential check")
    fun operatorsRequestLimitHoldsForTheCheck() = runTest {
        slice.providerSettings.changeRequestLimit(slice.openBankingKey, 10)
        repeat(10) { slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY)) }

        val outcome = slice.credentials.enter(slice.openBankingKey, slice.input("setupKey" to ExampleData.SETUP_KEY))

        assertInstanceOf(CredentialOutcome.RequestLimitReached::class.java, outcome)
        assertEquals(10, slice.openBanking.credentials.requestsSent)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A1: Credentials Refused")
    @DisplayName("A1: refused credentials store nothing and keep the earlier ones unchanged")
    fun refusedCredentialsStoreNothing() = runTest {
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))
        val acceptedAt = slice.clock.instant()
        slice.budgetApp.credentials.answer = CredentialCheck.Refused
        slice.clock.advance(Duration.ofMinutes(1))

        val outcome = slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to "PLANTED-REFUSED-TOKEN"))

        assertEquals(CredentialOutcome.Refused, outcome)
        assertEquals(ExampleData.BUDGET_APP_TOKEN, storedSecret(ServiceKey.budgetApp))
        assertEquals(CredentialStatus.Accepted(acceptedAt), slice.credentials.status(ServiceKey.budgetApp))
        assertEquals(1, slice.journalRecorder.entries.size)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A2: Service Cannot Be Reached")
    @DisplayName("A2: a service that does not answer or is unavailable stores nothing and keeps the earlier credentials")
    fun unreachableServiceStoresNothing() = runTest {
        slice.feeds.credentials.answer = CredentialCheck.TransientFailure(FailureKind.Timeout)

        val outcome = slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))

        assertEquals(CredentialOutcome.NotReached(FailureKind.Timeout), outcome)
        assertNull(slice.vault.read(slice.feedsKey))
        assertEquals(CredentialStatus.Missing, slice.credentials.status(slice.feedsKey))
        assertTrue(slice.journalRecorder.entries.isEmpty())
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A3: Remove Credentials", businessRules = ["BR-005", "BR-007"])
    @DisplayName("A3: removed credentials are deleted and recorded; a Data Provider with a Bank Account stays in use as credentials missing")
    fun removedCredentialsLeaveTheDataProviderInUseAsCredentialsMissing() = runTest {
        serveHousehold()
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        addBankAccountOf(slice.feedsId)

        val outcome = slice.credentials.remove(slice.feedsKey)

        assertEquals(RemovalOutcome.Removed, outcome)
        assertNull(slice.vault.read(slice.feedsKey))
        val line = slice.connections.connections().inUse.single { it.service == slice.feedsKey }
        assertEquals(ServiceStatus.CredentialsMissing, line.status)
        val page = slice.connections.servicePage(slice.feedsKey)
        assertEquals(CredentialStatus.Missing, page.credentialStatus)
        assertEquals(listOf("Kestrel Checking"), page.bankAccounts.map { it.name })
        val removal = slice.journalRecorder.entries.last()
        assertEquals(JournalEventKind.ConfigurationChange, removal.eventKind)
        assertTrue("removed" in removal.details)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A3: Remove Credentials", businessRules = ["BR-007"])
    @DisplayName("A3: a Data Provider without credentials and without a Bank Account is listed as not set up again")
    fun removedDataProviderWithoutBankAccountIsNotSetUp() = runTest {
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))

        slice.credentials.remove(slice.feedsKey)

        val list = slice.connections.connections()
        assertTrue(list.inUse.none { it.service == slice.feedsKey })
        assertTrue(list.notSetUp.any { it.service == slice.feedsKey })
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A4: Operator Cancels")
    @DisplayName("A4: showing the form and entering nothing sends nothing and keeps the earlier credentials")
    fun cancellingKeepsTheEarlierCredentials() = runTest {
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))

        slice.credentials.form(ServiceKey.budgetApp)

        assertEquals(1, slice.budgetApp.credentials.requestsSent)
        assertEquals(ExampleData.BUDGET_APP_TOKEN, storedSecret(ServiceKey.budgetApp))
    }

    @Test
    @UseCase(id = "UC-014")
    @DisplayName("Precondition: while an Upload Plan awaits confirmation or an Upload is written, credentials cannot be entered or removed")
    fun credentialChangesWaitForTheUpload() = runTest {
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))
        slice.coordinatorStore.mode = CoordinatorMode.PlanOpen

        val entered = slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        val removed = slice.credentials.remove(ServiceKey.budgetApp)
        slice.coordinatorStore.mode = CoordinatorMode.Uploading
        val enteredWhileUploading = slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))

        assertEquals(CredentialOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), entered)
        assertEquals(RemovalOutcome.Unavailable(OperationRefusal.UploadPlanAwaitingConfirmation), removed)
        assertEquals(CredentialOutcome.Unavailable(OperationRefusal.UploadBeingWritten), enteredWhileUploading)
        assertEquals(0, slice.feeds.credentials.requestsSent)
        assertNull(slice.vault.read(slice.feedsKey))
        assertEquals(ExampleData.BUDGET_APP_TOKEN, storedSecret(ServiceKey.budgetApp))
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-001"])
    @DisplayName("BR-001: a new installation holds no credential of any service; each is entered in the app")
    fun noCredentialComesFromTheBuild() = runTest {
        slice.plugins.serviceKeys.forEach { assertEquals(CredentialStatus.Missing, slice.credentials.status(it)) }
        assertEquals(ServiceStatus.CredentialsMissing, slice.connections.connections().inUse.single().status)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-003"], requirements = ["NFR-008", "NFR-012", "NFR-021"])
    @DisplayName("BR-003: no planted credential appears in a result, the Decision Journal or the text of what was entered")
    fun plantedCredentialsAppearNowhere() = runTest {
        val input = slice.input("token" to ExampleData.BUDGET_APP_TOKEN)
        val outcome = slice.credentials.enter(ServiceKey.budgetApp, input)
        slice.credentials.remove(ServiceKey.budgetApp)

        val texts = listOf(input.toString(), outcome.toString(), slice.vault.toString()) +
            slice.journalRecorder.entries.map { it.toString() }
        texts.forEach { assertFalse(ExampleData.BUDGET_APP_TOKEN in it, it) }
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-004"], requirements = ["NFR-009"])
    @DisplayName("BR-004: credentials that are not there after a reinstall, or cannot be decrypted, are missing and must be entered again")
    fun lostCredentialsAreMissing() = runTest {
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))

        slice.vault.remove(ServiceKey.budgetApp)

        assertEquals(CredentialStatus.Missing, slice.credentials.status(ServiceKey.budgetApp))
        assertEquals(ServiceStatus.CredentialsMissing, slice.connections.servicePage(ServiceKey.budgetApp).status)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-005"])
    @DisplayName("BR-005: credentials the Budget App refused show credentials refused and the Budget App is not contacted until new ones are accepted")
    fun refusedStoredCredentialsStopContact() = runTest {
        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))
        slice.budgetApp.reader.readBudgetsAnswer = ReadResult.CredentialsRefused
        slice.connect.readBudgets()
        val readsAfterRefusal = slice.budgetApp.reader.budgetReads

        slice.connect.readBudgets()

        assertEquals(CredentialStatus.Refused, slice.credentials.status(ServiceKey.budgetApp))
        assertEquals(ServiceStatus.CredentialsRefused, slice.connections.servicePage(ServiceKey.budgetApp).status)
        assertEquals(readsAfterRefusal, slice.budgetApp.reader.budgetReads)

        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))
        assertInstanceOf(CredentialStatus.Accepted::class.java, slice.credentials.status(ServiceKey.budgetApp))
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-005", "BR-007"])
    @DisplayName("BR-005: a Data Provider whose last Fetch was refused for its credentials shows credentials refused until new ones are accepted")
    fun dataProviderRefusedInItsLastFetchShowsCredentialsRefused() = runTest {
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        slice.clock.advance(Duration.ofHours(1))
        slice.importStatusStore.fetches += StoredFetch(
            1, slice.feedsId, slice.clock.instant(), StartedBy.Operator, FetchOutcome.CredentialsRefused, emptyList(),
        )

        assertEquals(ServiceStatus.CredentialsRefused, slice.connections.servicePage(slice.feedsKey).status)

        slice.clock.advance(Duration.ofMinutes(1))
        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to ExampleData.SETUP_KEY))
        assertInstanceOf(ServiceStatus.Connected::class.java, slice.connections.servicePage(slice.feedsKey).status)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-006"], requirements = ["C-004"])
    @DisplayName("BR-006: every entered credential can be replaced and removed in the app, so exposed ones can be rotated")
    fun exposedCredentialsCanBeRotated() = runTest {
        slice.enterCredentials()
        slice.feeds.credentials.answer = CredentialCheck.Accepted(Secret("PLANTED-ROTATED-CREDENTIAL"))

        slice.credentials.enter(slice.feedsKey, slice.input("setupKey" to "PLANTED-NEW-SETUP-KEY"))
        slice.credentials.remove(ServiceKey.budgetApp)

        assertEquals("PLANTED-ROTATED-CREDENTIAL", storedSecret(slice.feedsKey))
        assertNull(slice.vault.read(ServiceKey.budgetApp))
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-007"])
    @DisplayName("BR-007: the Budget App is always listed, with credentials missing until they are accepted, then connected with its Budgets in use")
    fun budgetAppIsAlwaysListed() = runTest {
        assertEquals(ServiceStatus.CredentialsMissing, slice.connections.connections().inUse.first().status)

        slice.credentials.enter(ServiceKey.budgetApp, slice.input("token" to ExampleData.BUDGET_APP_TOKEN))
        serveHousehold()

        val line = slice.connections.connections().inUse.first()
        assertTrue(line.isBudgetApp)
        assertEquals(ServiceStatus.Connected(lastFetchAt = null, budgetsInUse = 1), line.status)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-007"])
    @DisplayName("BR-007: a Data Provider's page shows its Bank Accounts, its Fetch Cooldown with the declared default and its Request Limit, also while credentials are missing")
    fun dataProviderPageComesFromItsDeclarations() = runTest {
        val page = slice.connections.servicePage(slice.feedsKey)

        assertEquals("Kestrel Feeds", page.name)
        assertEquals(ServiceStatus.CredentialsMissing, page.status)
        assertEquals(ExampleData.feedsProfile.defaultFetchCooldownMinutes, page.fetchCooldownMinutes)
        assertEquals(ExampleData.feedsProfile.defaultFetchCooldownMinutes, page.defaultFetchCooldownMinutes)
        assertEquals(RequestLimitShown.Published(20, ExampleData.FEEDS_LIMIT_SOURCE), page.requestLimit)
        assertFalse(page.needsBankConsent)
        assertTrue(slice.connections.servicePage(slice.openBankingKey).needsBankConsent)
        assertEquals(
            RequestLimitShown.NotPublished(requests = 50, defaultRequests = 50, range = 10..500),
            slice.connections.servicePage(slice.openBankingKey).requestLimit,
        )
        assertEquals(slice.feeds.credentials.form.fields, page.credentialFields)
    }
}

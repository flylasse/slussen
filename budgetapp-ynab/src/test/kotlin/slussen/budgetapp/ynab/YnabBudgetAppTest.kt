package slussen.budgetapp.ynab

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.budgetapp.BudgetAccountRead
import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.budgetapp.BudgetRead
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.diagnostic.LogEvent
import slussen.pluginapi.diagnostic.LogFact
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.testfixtures.BudgetAppContractTest
import slussen.pluginapi.testfixtures.CredentialScenario
import slussen.pluginapi.testfixtures.ReadScenario
import slussen.pluginapi.testfixtures.TestRequestContext
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.Secret
import slussen.pluginapi.value.SensitiveApi
import java.util.concurrent.TimeUnit

/** The YNAB Budget App against the contract tests of ADR-001 §4.4, with invented recorded answers. */
class YnabBudgetAppTest : BudgetAppContractTest() {
    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    @BeforeEach
    fun startServer() {
        server = MockWebServer().apply { start() }
    }

    @AfterEach
    fun stopServer() {
        server.shutdown()
    }

    private fun app(): YnabBudgetApp = YnabBudgetApp(SilentLog, server.url("/v1/"), http)

    private fun tokenInput() = CredentialInput(mapOf(YnabCredentials.TOKEN to Secret(PLANTED_TOKEN)))

    private fun answering(response: MockResponse): CredentialScenario {
        server.enqueue(response)
        return CredentialScenario(app().credentials, tokenInput())
    }

    override fun budgetApp(log: SlussenLog): BudgetApp = YnabBudgetApp(log)

    override fun credentialsAccepted() = answering(MockResponse().setBody(BUDGETS_ANSWER))

    override fun credentialsRefused() = answering(MockResponse().setResponseCode(401).setBody(UNAUTHORIZED))

    override fun credentialServiceUnavailable() = answering(MockResponse().setResponseCode(503))

    override fun credentialServiceNeverAnswering() =
        answering(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    override fun requestsReceived(): Int = server.requestCount

    override fun runningCalls(): Int = http.dispatcher.runningCallsCount()

    override fun plantedValues(): List<String> = listOf(PLANTED_TOKEN)

    private fun reading(response: MockResponse): ReadScenario {
        server.enqueue(response)
        return ReadScenario(app().reader, Secret(PLANTED_TOKEN))
    }

    override fun budgetsAnswered() = reading(MockResponse().setBody(BUDGETS_ANSWER))

    override fun expectedBudgets(): List<BudgetRead> = listOf(
        BudgetRead(
            reference = "budget-0001",
            name = "Example Household",
            currency = CurrencyCode("USD"),
            budgetAccounts = listOf(
                BudgetAccountRead("account-0001", "Example Checking", Amount(4140050)),
                BudgetAccountRead("account-0002", "Example Card", Amount(-1268400)),
                BudgetAccountRead("account-0004", "Example Closed Savings", Amount(0)),
            ),
        ),
        BudgetRead("budget-0002", "Example Summer House", CurrencyCode("EUR"), emptyList()),
    )

    override fun budgetsCredentialRefused() = reading(MockResponse().setResponseCode(401).setBody(UNAUTHORIZED))

    override fun budgetsUnavailable() = reading(MockResponse().setResponseCode(503))

    override fun budgetsNeverAnswering() = reading(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"], requirements = ["C-008"])
    @DisplayName("BR-002: YNAB asks for a personal access token and checks it by listing the Budgets")
    @OptIn(SensitiveApi::class)
    fun tokenIsCheckedByListingTheBudgets() {
        val credentials = app().credentials
        assertEquals(listOf(YnabCredentials.TOKEN), credentials.form.fields.map { it.key })
        assertEquals(FieldKind.Secret, credentials.form.fields.single().kind)
        server.enqueue(MockResponse().setBody(BUDGETS_ANSWER))
        val result = runBlocking { credentials.check(tokenInput(), TestRequestContext()) }
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/v1/budgets", request.requestUrl!!.encodedPath)
        assertEquals("Bearer $PLANTED_TOKEN", request.getHeader("Authorization"))
        assertEquals(PLANTED_TOKEN, (result as CredentialCheck.Accepted).credential.reveal())
    }

    @Test
    @DisplayName("ADR-001 §4.2.3: Budgets are read with their Budget Accounts and the stored token")
    fun budgetsAreReadWithAccountsAndTheStoredToken() {
        server.enqueue(MockResponse().setBody(BUDGETS_ANSWER))
        runBlocking { app().reader.readBudgets(TestRequestContext(credential = Secret(PLANTED_TOKEN))) }
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("true", request.requestUrl!!.queryParameter("include_accounts"))
        assertEquals("Bearer $PLANTED_TOKEN", request.getHeader("Authorization"))
    }

    @Test
    @DisplayName("C-009: the profile declares YNAB's numbers and capabilities")
    fun profileDeclaresTheNumbersOfC009() {
        val profile = YnabBudgetApp(SilentLog).profile
        assertEquals(36, profile.importIdentifierMaxLength)
        assertEquals(200, profile.payeeMaxLength)
        assertEquals(500, profile.memoMaxLength)
        assertEquals(10, profile.matchingDateWindowDays)
        assertEquals(true, profile.refusesReusedImportIdentifier)
        assertEquals(true, profile.supportsClearedMarker)
    }

    private object SilentLog : SlussenLog {
        override fun event(event: LogEvent, vararg facts: LogFact) {}

        override fun detail(event: LogEvent, message: () -> String) {}
    }

    private companion object {
        const val PLANTED_TOKEN = "invented-ynab-token-Q7ZK3"
        const val UNAUTHORIZED = """{"error":{"id":"401","name":"unauthorized","detail":"invented"}}"""
        const val BUDGETS_ANSWER = """
            {"data":{"budgets":[
              {"id":"budget-0001","name":"Example Household","currency_format":{"iso_code":"USD"},
               "accounts":[
                 {"id":"account-0001","name":"Example Checking","balance":4140050,"closed":false,"deleted":false},
                 {"id":"account-0002","name":"Example Card","balance":-1268400,"closed":false,"deleted":false},
                 {"id":"account-0003","name":"Example Deleted","balance":0,"closed":true,"deleted":true},
                 {"id":"account-0004","name":"Example Closed Savings","balance":0,"closed":true,"deleted":false}
               ]},
              {"id":"budget-0002","name":"Example Summer House","currency_format":{"iso_code":"EUR"},"accounts":[]}
            ]}}
        """
    }
}

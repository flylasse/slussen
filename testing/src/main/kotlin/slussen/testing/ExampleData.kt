package slussen.testing

import slussen.core.port.NewOfferedBankAccount
import slussen.pluginapi.budgetapp.BudgetAccountRead
import slussen.pluginapi.budgetapp.BudgetRead
import slussen.pluginapi.provider.DataProviderProfile
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant

/**
 * Invented example data (C-005): every name, number, amount and credential here is made up and easy to search for, so
 * that a test can show that a planted value never reaches a log, the Decision Journal or a screen.
 */
object ExampleData {
    val USD = CurrencyCode("USD")
    val EUR = CurrencyCode("EUR")

    const val BUDGET_APP_NAME = "Example Budget App"
    const val BUDGET_APP_TOKEN = "PLANTED-BUDGET-APP-TOKEN-7f3a9c"
    const val SETUP_KEY = "PLANTED-SETUP-KEY-51d0e2"
    const val EXCHANGED_ACCESS_CREDENTIAL = "PLANTED-ACCESS-CREDENTIAL-88b4f1"

    /** Where the invented Data Provider Kestrel Feeds publishes its Request Limit. */
    const val FEEDS_LIMIT_SOURCE = "Kestrel Feeds developer guide, an invented source"

    /** A Data Provider that gives Stable References, publishes its Request Limit and needs no Bank Consent. */
    val feedsProfile = DataProviderProfile(
        name = "Kestrel Feeds",
        sourceKey = "example-feeds",
        givesStableReference = true,
        needsBankConsent = false,
        defaultFetchCooldownMinutes = 60,
        requestLimit = RequestLimitDeclaration.Published(20, FEEDS_LIMIT_SOURCE),
    )

    /** A Data Provider that needs a Bank Consent for each Bank and publishes no Request Limit. */
    val openBankingProfile = DataProviderProfile(
        name = "Aurora Open Banking",
        sourceKey = "example-open-banking",
        givesStableReference = true,
        needsBankConsent = true,
        defaultFetchCooldownMinutes = 360,
        requestLimit = RequestLimitDeclaration.NotPublished(defaultRequests = 50, range = 10..500),
    )

    /** A third Data Provider of the build that this household does not use (UC-012 A2). */
    val wrenProfile = DataProviderProfile(
        name = "Wren Feeds",
        sourceKey = "example-wren",
        givesStableReference = false,
        needsBankConsent = false,
        defaultFetchCooldownMinutes = 30,
        requestLimit = RequestLimitDeclaration.Published(10, "Wren Feeds developer notes, an invented source"),
    )

    fun amount(units: Long, thousandths: Long = 0): Amount =
        Amount(units * 1000 + if (units < 0) -thousandths else thousandths)

    /** Three Budgets as the Budget App lists them: Household (USD), Summer House (EUR), Care Fund (USD). */
    fun budgetsRead(): List<BudgetRead> = listOf(
        BudgetRead(
            reference = "budget-household",
            name = "Household",
            currency = USD,
            budgetAccounts = listOf(
                BudgetAccountRead("ba-checking", "Checking", amount(4140, 50)),
                BudgetAccountRead("ba-rewards", "Rewards Card", amount(-1268, 400)),
                BudgetAccountRead("ba-reserve", "Reserve Savings", amount(12000)),
            ),
        ),
        BudgetRead(
            reference = "budget-summer",
            name = "Summer House",
            currency = EUR,
            budgetAccounts = listOf(
                BudgetAccountRead("ba-current", "Current", amount(3402, 300)),
                BudgetAccountRead("ba-savings", "Savings", amount(8000)),
                BudgetAccountRead("ba-card", "Card", amount(-200)),
            ),
        ),
        BudgetRead(
            reference = "budget-care",
            name = "Care Fund",
            currency = USD,
            budgetAccounts = listOf(BudgetAccountRead("ba-health", "Health Savings", amount(6324, 200))),
        ),
    )

    /** An Offered Bank Account as a Fetch (UC-001) would have stored it. */
    fun offered(
        providerReference: String,
        name: String?,
        kind: String?,
        currency: CurrencyCode?,
        lastFour: String?,
        balance: Amount?,
        bankId: Long?,
        offeredAt: Instant,
        proposedBankAccountId: Long? = null,
        fingerprint: String? = "fp-$providerReference",
    ): NewOfferedBankAccount = NewOfferedBankAccount(
        bankId = bankId,
        providerReference = providerReference,
        bankAccountFingerprint = fingerprint,
        name = name,
        bankAccountKind = kind,
        currency = currency,
        balance = balance,
        lastFourDigits = lastFour,
        proposedBankAccountId = proposedBankAccountId,
        offeredAt = offeredAt,
    )
}

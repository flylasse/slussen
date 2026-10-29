package slussen.app.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.components.FirstUploadMatchingUi
import slussen.core.port.ServiceKey
import slussen.core.status.BalanceComparison
import slussen.core.status.Blocked
import slussen.core.status.NotYetUploaded
import slussen.core.status.Remedy
import java.time.Instant

/**
 * The action that removes a Blocked Reason (UC-008 A6). Only [available] actions start their use case; an action whose
 * use case no slice has built yet is shown with [note] instead.
 */
data class RemedyAction(val title: String, val available: Boolean, val service: ServiceKey?, val note: String?)

/** One Blocked Reason with its action, and the advice it carries (UC-001 A19, A20). */
data class BlockedLine(val text: String, val action: RemedyAction?, val advice: String? = null)

/** One Source of the Budget (UC-008 steps 5-6, BR-003). */
data class SourceCard(
    val name: String,
    val kind: String,
    val health: String,
    val freshness: String,
    /** Null for a Source of the balance only, which brings no Bank Transaction to upload. */
    val counts: String?,
    val blocked: List<BlockedLine>,
)

/** One Bank Account of the Budget (UC-008 steps 5-8, A2-A4). */
data class BankAccountCard(
    val name: String,
    val type: String,
    val health: String,
    val freshness: String,
    val counts: String,
    val balance: String,
    val blocked: List<BlockedLine>,
    /** UC-001 A21: the balance its last Fetch reported in another currency, or null. */
    val balanceNotice: String? = null,
)

/** One Budget's workspace (UC-008 steps 4-8, BR-007). */
data class BudgetUi(
    val title: String,
    val subtitle: String,
    val counts: String,
    val sources: List<SourceCard>,
    val bankAccounts: List<BankAccountCard>,
    /** P9-15: what this build does not show yet. */
    val note: String? = null,
    /** UC-008 A8, BR-006, BR-009 (`budget.html#recovery-blocked`): Uploads wait for First Upload Matching; null while none is open. */
    val firstUploadMatching: FirstUploadMatchingUi? = null,
)

/** A Budget tab (UC-008 steps 4-8), from stored data only. */
class BudgetViewModel(private val services: UiServices, private val budgetId: Long) : ViewModel() {
    private val mutableState = MutableStateFlow<BudgetUi?>(null)
    val state: StateFlow<BudgetUi?> = mutableState

    fun load() {
        viewModelScope.launch {
            val formats = services.formats
            val workspace = services.status.workspace(budgetId)
            val serviceByName = services.settingsOverview.entries().entries
                .mapNotNull { entry -> entry.service?.let { entry.name to it } }.toMap()
            fun lines(blocked: List<Blocked>, dataProvider: ServiceKey?) =
                blocked.map { BlockedLine(Texts.blocked(it, formats), action(it, serviceByName[it.subject] ?: dataProvider), Texts.advice(it)) }
            mutableState.value = BudgetUi(
                title = workspace.summary.name,
                subtitle = "Budget · ${workspace.summary.currency.code} · from stored data",
                counts = counts(workspace.summary.notYetUploaded),
                sources = workspace.sources.map { source ->
                    SourceCard(
                        name = source.name,
                        kind = when {
                            source.isFileFormat -> "Transaction Files"
                            source.balanceOnly -> "Data Provider · Account Balance only"
                            else -> "Data Provider"
                        },
                        health = Texts.health(source.health, formats),
                        freshness = freshness(source.freshness, formats),
                        // UC-001 BR-011: a Source of the balance only brings no Bank Transaction to upload.
                        counts = if (source.balanceOnly) null else "Not yet uploaded: " + counts(source.notYetUploaded),
                        blocked = lines(source.blocked, dataProvider = null),
                    )
                },
                bankAccounts = workspace.bankAccounts.map { account ->
                    BankAccountCard(
                        name = account.name,
                        type = formats.type(account.type),
                        health = Texts.health(account.health, formats),
                        freshness = freshness(account.freshness, formats),
                        counts = counts(account.notYetUploaded),
                        balance = balance(account.balance, formats),
                        blocked = lines(account.blocked, account.dataProviderId?.let(ServiceKey::dataProvider)),
                        balanceNotice = account.balanceInOtherCurrency?.let { Texts.balanceInOtherCurrencyNotice(it, formats) },
                    )
                },
                // P9-15: while the Comparison (UC-003) is not built, the tab says why the counts stay at 0.
                note = Texts.TRANSACTIONS_NOT_IN_THIS_BUILD,
                // UC-008 BR-009: The Budget shows that its Uploads wait for First Upload Matching, as information, never as an error.
                firstUploadMatching = workspace.firstUploadMatching?.let { Texts.firstUploadMatching(it, workspace.budgetAppName) },
            )
        }
    }

    private fun counts(counts: NotYetUploaded) = "Eligible ${counts.eligible} · waiting for a decision ${counts.waitingForDecision}"

    private fun freshness(at: Instant?, formats: Formats) =
        at?.let { "Freshness: data as of ${formats.dateTime(it)}" } ?: "Freshness: no data yet"

    /**
     * UC-008 A6: the remedy of a Blocked Reason starts its use case; of those, this build has Manage Service Credentials
     * and Fetch Transactions, both on the service's page in Settings ([service]: the service the Blocked Reason names,
     * or the Data Provider of the Bank Account it names). A remedy this build does not have says so plainly (P9-13).
     */
    private fun action(blocked: Blocked, service: ServiceKey?): RemedyAction? {
        val title = Texts.remedy(blocked.remedy) ?: return null
        return when (blocked.remedy) {
            Remedy.EnterCredentials -> RemedyAction(title, available = true, service = service, note = null)
            // UC-008 BR-006: A failed Fetch, a Bank Account not delivered and the Bank's sign-in (after signing in) are removed by fetching again.
            Remedy.FetchAgain, Remedy.SignInAtDataProvider -> RemedyAction(title, available = service != null, service = service, note = null)
            Remedy.GrantBankConsent -> RemedyAction(title, available = false, service = null, note = Texts.BANK_CONSENTS_NOT_IN_THIS_BUILD)
            Remedy.IdentifyBankAccount, Remedy.MoveAccountMapping ->
                RemedyAction(title, available = false, service = null, note = "comes with Manage Account Mappings")
            // A9 of UC-008: Start First Upload Matching needs Compare With the Budget App (UC-003), not in this build yet (O-P2-09).
            Remedy.StartFirstUploadMatching ->
                RemedyAction(title, available = false, service = null, note = "Comparing with the Budget App is not in this build yet")
            Remedy.WaitUntilItEnds -> null
        }
    }

    private fun balance(balance: BalanceComparison, formats: Formats): String = when (balance) {
        is BalanceComparison.Compared ->
            "Bank ${formats.amount(balance.accountBalance)} · Budget App ${formats.amount(balance.budgetAccountBalance)} · " +
                "Difference ${formats.difference(balance.difference)} · " +
                "Read ${formats.time(balance.accountBalanceReadAt)} / ${formats.time(balance.budgetAccountBalanceReadAt)}"
        is BalanceComparison.Outdated ->
            "Bank ${formats.amount(balance.accountBalance)} · " +
                (balance.budgetAccountBalance?.let { "Budget App ${formats.amount(it)} · " } ?: "") +
                "Account Balance outdated · difference hidden"
        is BalanceComparison.CurrencyMismatch ->
            "Bank Account in ${balance.bankAccountCurrency.code}, Budget in ${balance.budgetCurrency.code} · " +
                "currency mismatch · difference hidden"
        is BalanceComparison.NotKnown -> listOfNotNull(
            if (balance.accountBalanceKnown) balance.accountBalance?.let { "Bank ${formats.amount(it)}" } else "Account Balance not known",
            if (balance.budgetAccountBalanceKnown) {
                balance.budgetAccountBalance?.let { "Budget App ${formats.amount(it)}" }
            } else {
                "Budget App balance not known"
            },
        ).joinToString(" · ")
    }
}

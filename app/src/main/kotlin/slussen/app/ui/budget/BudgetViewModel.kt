package slussen.app.ui.budget

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
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

data class BlockedLine(val text: String, val action: RemedyAction?)

/** One Source of the Budget (UC-008 steps 5-6, BR-003). */
data class SourceCard(
    val name: String,
    val kind: String,
    val health: String,
    val freshness: String,
    val counts: String,
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
)

/** One Budget's workspace (UC-008 steps 4-8, BR-007). */
data class BudgetUi(
    val title: String,
    val subtitle: String,
    val counts: String,
    val sources: List<SourceCard>,
    val bankAccounts: List<BankAccountCard>,
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
            fun lines(blocked: List<Blocked>) = blocked.map { BlockedLine(Texts.blocked(it, formats), action(it, serviceByName)) }
            mutableState.value = BudgetUi(
                title = workspace.summary.name,
                subtitle = "Budget · ${workspace.summary.currency.code} · from stored data",
                counts = counts(workspace.summary.notYetUploaded),
                sources = workspace.sources.map { source ->
                    SourceCard(
                        name = source.name,
                        kind = if (source.isFileFormat) "Transaction Files" else "Data Provider",
                        health = Texts.health(source.health, formats),
                        freshness = freshness(source.freshness, formats),
                        counts = "Not yet uploaded: " + counts(source.notYetUploaded),
                        blocked = lines(source.blocked),
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
                        blocked = lines(account.blocked),
                    )
                },
            )
        }
    }

    private fun counts(counts: NotYetUploaded) = "Eligible ${counts.eligible} · waiting for a decision ${counts.waitingForDecision}"

    private fun freshness(at: Instant?, formats: Formats) =
        at?.let { "Freshness: data as of ${formats.dateTime(it)}" } ?: "Freshness: no data yet"

    /** UC-008 A6: the remedy of a Blocked Reason starts its use case; of those, this build has Manage Service Credentials. */
    private fun action(blocked: Blocked, serviceByName: Map<String, ServiceKey>): RemedyAction? {
        val title = Texts.remedy(blocked.remedy) ?: return null
        return when (blocked.remedy) {
            Remedy.EnterCredentials -> RemedyAction(title, available = true, service = serviceByName[blocked.subject], note = null)
            Remedy.GrantBankConsent -> RemedyAction(title, available = false, service = null, note = "comes with Grant Bank Consent")
            Remedy.IdentifyBankAccount, Remedy.MoveAccountMapping ->
                RemedyAction(title, available = false, service = null, note = "comes with Manage Account Mappings")
            Remedy.FetchAgain -> RemedyAction(title, available = false, service = null, note = "comes with Fetch Transactions")
            Remedy.CompareAndConfirm ->
                RemedyAction(title, available = false, service = null, note = "comes with Compare With the Budget App")
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

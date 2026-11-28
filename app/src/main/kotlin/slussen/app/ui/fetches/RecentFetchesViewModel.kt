package slussen.app.ui.fetches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.budget.remedyAction
import slussen.app.ui.UiServices
import slussen.core.port.ServiceKey
import slussen.core.status.AccountNeed
import slussen.core.status.FetchNeed
import slussen.core.status.RecentFetch
import slussen.core.status.RecentFetches
import slussen.core.status.Remedy

/** One recent Fetch in the list (`fetch-history.html#list`). */
data class FetchLineUi(
    val key: Long,
    val title: String,
    val outcome: String,
    /** What it recorded for its Bank Accounts, or null when it recorded none. */
    val summary: String?,
    /** Whether it still asks something of the Operator now, and what is current or past about it. */
    val need: String,
    val asksNow: Boolean,
    /** A line with Bank Accounts opens its detail. */
    val opens: Boolean,
    /** The actions that lead to the remedy of what it still asks (UC-008 A6), at its line. */
    val actions: List<RemedyAction> = emptyList(),
)

/** One Bank Account of an opened Fetch (`#fetch-detail`, `#fetch-detail-cooldown`). */
data class FetchBankAccountUi(
    val name: String,
    val result: String,
    /** Where it came from and that nothing is needed, or null while it still asks something. */
    val note: String?,
    /** What it still asks, with the Blocked Reason's advice and the action that leads to the remedy; null otherwise. */
    val asks: String?,
    val advice: String?,
    val action: RemedyAction?,
)

/** One opened Fetch, with Close. */
data class FetchDetailUi(val title: String, val subtitle: String, val need: String, val bankAccounts: List<FetchBankAccountUi>)

/** `#empty`: why there is no Fetch and what to do. */
data class FetchesEmptyUi(val title: String, val body: String, val action: String)

/** The Recent Fetches (UC-008 A5). */
data class RecentFetchesUi(
    val note: String,
    val lines: List<FetchLineUi>,
    val empty: FetchesEmptyUi?,
    val detail: FetchDetailUi? = null,
    /** An opened Fetch that is not among the recent Fetches kept any more; null otherwise. */
    val notListed: String? = null,
)

/** What the screen shows: the recent Fetches, and a failure to read them (NFR-023). */
data class RecentFetchesState(val ui: RecentFetchesUi?, val failure: String?)

/**
 * The recent Fetch outcomes (UC-008 A5, BR-008), in the device's local time, from stored data only (BR-001): the list, and
 * one Fetch opened in place of the list. It reads the stored data again whenever an operation ends, whichever screen
 * started it, so that a Fetch that ended elsewhere shows and one that was answered is no longer called needed (NFR-023),
 * and catches whatever the read throws.
 */
class RecentFetchesViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow(RecentFetchesState(ui = null, failure = null))
    val state: StateFlow<RecentFetchesState> = mutableState

    private var read: RecentFetches? = null
    private var opened = false

    /** The Fetch opened in place of the list, or null for the list. */
    private var selected: Long? = null
    private var notListed = false

    init {
        viewModelScope.launch {
            services.ended.drop(1).collect { if (opened) reload() }
        }
    }

    /** Opens the list; never a Fetch left open the last time it was shown. */
    fun load() {
        opened = true
        selected = null
        notListed = false
        publish()
        viewModelScope.launch { reload() }
    }

    /** Opens the Fetch [key]. */
    fun open(key: Long) {
        selected = key
        notListed = false
        publish()
    }

    /** System back: closes an open Fetch and answers true, or answers false when none is open. */
    fun back(): Boolean {
        if (selected == null) return false
        close()
        return true
    }

    fun close() {
        selected = null
        notListed = false
        publish()
    }

    private suspend fun reload() {
        try {
            val fetches = services.status.recentFetches()
            read = fetches
            // An opened Fetch that the stored data no longer holds: the list, with a line that says so.
            if (selected != null && fetches.fetches.none { it.fetchId == selected }) {
                selected = null
                notListed = true
            }
            mutableState.value = RecentFetchesState(ui(), failure = null)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // NFR-023: the stored data could not be read; said on the screen, which keeps what it showed, never a crash.
            mutableState.value = mutableState.value.copy(failure = FetchesTexts.NOT_READ)
        }
    }

    private fun publish() {
        if (read == null) return
        mutableState.value = mutableState.value.copy(ui = ui())
    }

    private fun ui(): RecentFetchesUi? {
        val fetches = read ?: return null
        val formats = services.formats
        val now = services.clock.instant()
        return RecentFetchesUi(
            note = FetchesTexts.LIST_NOTE,
            lines = fetches.fetches.map { fetch ->
                FetchLineUi(
                    key = fetch.fetchId,
                    title = FetchesTexts.title(fetch, formats),
                    outcome = slussen.app.ui.Texts.fetch(fetch.outcome),
                    summary = FetchesTexts.summary(fetch),
                    need = FetchesTexts.need(fetch, formats, now),
                    asksNow = fetch.need is FetchNeed.Needed,
                    opens = fetch.bankAccounts.isNotEmpty(),
                    actions = actions(fetch),
                )
            },
            empty = if (fetches.fetches.isEmpty()) FetchesEmptyUi(FetchesTexts.EMPTY_TITLE, FetchesTexts.empty(fetches.kept), FetchesTexts.EMPTY_ACTION) else null,
            detail = selected?.let { key -> fetches.fetches.firstOrNull { it.fetchId == key } }?.let { detail(it) },
            notListed = if (notListed) FetchesTexts.NO_LONGER_KEPT else null,
        )
    }

    /** UC-008 A6: the actions that lead to the remedy of what a Fetch still asks, at its line. */
    private fun actions(fetch: RecentFetch): List<RemedyAction> {
        val provider = ServiceKey.dataProvider(fetch.dataProviderId)
        return when (val need = fetch.need) {
            is FetchNeed.Needed -> need.blocked.mapNotNull { remedyAction(it, provider) }.distinct()
            is FetchNeed.NotReadYet -> if (need.freesUpAt == null) listOf(fetchAgain(provider)) else emptyList()
            is FetchNeed.NotNeeded -> emptyList()
        }
    }

    private fun fetchAgain(provider: ServiceKey) = RemedyAction(slussen.app.ui.Texts.remedy(Remedy.FetchAgain).orEmpty(), available = true, service = provider, note = null)

    private fun detail(fetch: RecentFetch): FetchDetailUi {
        val formats = services.formats
        val now = services.clock.instant()
        return FetchDetailUi(
            title = FetchesTexts.detailTitle(fetch, formats),
            subtitle = FetchesTexts.detailSubtitle(fetch),
            need = FetchesTexts.need(fetch, formats, now),
            bankAccounts = fetch.bankAccounts.map { line ->
                val still = (line.need as? AccountNeed.Still)?.blocked?.takeIf { it.remedy != Remedy.WaitUntilItEnds }
                val unread = (line.need as? AccountNeed.NotReadYet)?.takeIf { it.freesUpAt == null }
                FetchBankAccountUi(
                    name = line.name,
                    result = FetchesTexts.result(line),
                    note = FetchesTexts.accountNote(line, formats, now),
                    asks = still?.let { FetchesTexts.stillNeeded(it, formats, now) },
                    advice = still?.let { slussen.app.ui.Texts.advice(it, formats) },
                    // The action that removes the reason leads to the page that has it (UC-008 A6).
                    action = still?.let { remedyAction(it, ServiceKey.dataProvider(fetch.dataProviderId)) }
                        ?: unread?.let { fetchAgain(ServiceKey.dataProvider(fetch.dataProviderId)) },
                )
            },
        )
    }
}

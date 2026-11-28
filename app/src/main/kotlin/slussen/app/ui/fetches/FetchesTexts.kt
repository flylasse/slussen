package slussen.app.ui.fetches

import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.status.AccountNeed
import slussen.core.status.Blocked
import slussen.core.status.EarlierFetch
import slussen.core.status.FetchBankAccount
import slussen.core.status.FetchNeed
import slussen.core.status.FetchNote
import slussen.core.status.FetchedWhat
import slussen.core.status.RecentFetch
import slussen.core.status.Remedy
import java.time.Instant

/** The words of the Recent Fetches (UC-008 A5; `docs/mockups/fetch-history.html`), in the device's local time (BR-008). */
object FetchesTexts {
    const val SUBTITLE = "Source · time · started by · outcome"

    const val LIST_NOTE =
        "Newest first. Each line is the record of one Fetch; “still needed now” or “nothing needed now” says whether it asks " +
            "anything of you today. Tap a line for its Bank Accounts."

    const val NOT_READ = "The recent Fetches stored on this device could not be read; nothing was changed by it. Open the screen again to see them."

    const val NO_LONGER_KEPT = "That Fetch is not among the recent Fetches kept any more."

    const val EMPTY_TITLE = "No Fetches yet"

    const val EMPTY_ACTION = "Fetch all Data Providers on the Overview"

    const val CLOSE = "Close"

    fun empty(kept: Int): String =
        "No Fetch of a Data Provider is recorded on this device. Each Fetch is listed here, the most recent ones as the number of " +
            "recent Fetches kept, an Operator Setting ($kept now), allows."

    fun title(fetch: RecentFetch, formats: Formats): String =
        "${formats.dateTime(fetch.startedAt)} · ${fetch.sourceName} · ${Texts.startedBy(fetch.startedBy)}"

    fun detailTitle(fetch: RecentFetch, formats: Formats): String = "Fetch of ${formats.dateTime(fetch.startedAt)}"

    fun detailSubtitle(fetch: RecentFetch): String =
        listOfNotNull(fetch.sourceName, Texts.startedBy(fetch.startedBy), Texts.fetch(fetch.outcome), fetchedCount(fetch)).joinToString(" · ")

    private fun recorded(fetch: RecentFetch): List<Pair<FetchBankAccount, FetchResultOutcome>> =
        fetch.bankAccounts.mapNotNull { line -> (line.what as? FetchedWhat.Recorded)?.let { line to it.outcome } }

    private fun fetchedCount(fetch: RecentFetch): String? {
        if (fetch.outcome != FetchOutcome.Fetched || fetch.bankAccounts.isEmpty()) return null
        return "Bank Accounts fetched: ${recorded(fetch).count { it.second == FetchResultOutcome.Fetched }} of ${fetch.bankAccounts.size}"
    }

    /** What the line under a Fetch says about its Bank Accounts (UC-001 step 11), or null when it recorded none. */
    fun summary(fetch: RecentFetch): String? {
        if (fetch.bankAccounts.isEmpty()) return null
        val problems = recorded(fetch).filter { it.second != FetchResultOutcome.Fetched }
            .groupBy({ it.second }, { it.first.name })
            .map { (outcome, names) -> "${names.joinToString(", ")}: ${resultWords(outcome)}" }
        val head = if (fetch.outcome == FetchOutcome.ServedFromFetchCooldown) {
            val read = recorded(fetch).filter { it.second == FetchResultOutcome.Fetched }.map { it.first.name }
            val served = fetch.bankAccounts.count { it.what is FetchedWhat.Served }
            val held = fetch.bankAccounts.filter { it.what == FetchedWhat.HeldByRequestLimit }.map { it.name }
            listOfNotNull(
                if (read.isEmpty()) "No Bank Account read" else "Read: ${read.joinToString(", ")}",
                if (served > 0) "served from earlier Fetches: $served" else null,
                if (held.isNotEmpty()) "not read, held back by the Request Limit: ${held.joinToString(", ")}" else null,
            ).joinToString(" · ")
        } else {
            fetchedCount(fetch)
        }
        return (listOfNotNull(head) + problems).joinToString(" · ").ifEmpty { null }
    }

    private fun resultWords(outcome: FetchResultOutcome): String = when (outcome) {
        FetchResultOutcome.Fetched -> "fetched"
        FetchResultOutcome.Failed -> "failed"
        FetchResultOutcome.NotConsented -> "no valid Bank Consent"
        FetchResultOutcome.NotRecognised -> "not recognised"
        FetchResultOutcome.AmbiguousBankAccount -> "ambiguous Bank Account"
        FetchResultOutcome.AccessLimitReached -> "access limit reached"
        FetchResultOutcome.NotDelivered -> "not delivered"
        FetchResultOutcome.SignInNeeded -> "the Bank's sign-in needs you"
    }

    /** The action a Blocked Reason asks for, in words (UC-008 BR-006); null when it asks for none, only for time to pass. */
    fun action(blocked: Blocked): String? = when (blocked.remedy) {
        Remedy.GrantBankConsent -> "grant the Bank Consent"
        Remedy.IdentifyBankAccount -> "confirm or identify the Bank Account"
        Remedy.WaitUntilItEnds -> null
        Remedy.FetchAgain -> "fetch again"
        Remedy.EnterCredentials -> "enter the credentials again"
        Remedy.MoveAccountMapping -> "move the Account Mapping"
        Remedy.StartFirstUploadMatching -> "start First Upload Matching"
        Remedy.SignInAtDataProvider -> "sign in to the Bank again at ${blocked.dataProviderName ?: "the Data Provider"}, then fetch again"
    }

    /** A Blocked Reason that asks something now, in words: what is blocked, why, and what to do. */
    fun asks(blocked: Blocked, formats: Formats, now: Instant): String {
        val reason = Texts.blockedReason(blocked)
        val at = blocked.at?.let { " (${formats.moment(it, now)})" }.orEmpty()
        return "${blocked.subject}, $reason$at" + (action(blocked)?.let { ": $it" } ?: "")
    }

    /** A Blocked Reason that only waits for a time, in words. */
    fun waits(blocked: Blocked, formats: Formats, now: Instant): String {
        val until = blocked.at?.let { " until ${formats.moment(it, now)}" }.orEmpty()
        return "${blocked.subject}, ${Texts.blockedReason(blocked)}$until"
    }

    /** The line that says whether the Fetch asks something now, and what is current or past about it. */
    fun need(fetch: RecentFetch, formats: Formats, now: Instant): String = when (val need = fetch.need) {
        is FetchNeed.Needed -> "Still needed now: " + need.blocked.joinToString("; ") { asks(it, formats, now) }
        is FetchNeed.NotReadYet -> need.freesUpAt
            ?.let { "${need.names.joinToString(", ")}: not read, held back by the Request Limit until ${formats.moment(it, now)} · nothing needed from you now" }
            ?: "Still needed now: ${need.names.joinToString(", ")} not read, held back by the Request Limit; fetch again"
        is FetchNeed.NotNeeded -> listOfNotNull(note(fetch, need.note, formats, now), "nothing needed now").joinToString(" · ")
            .replaceFirstChar { it.uppercase() }
    }

    private fun note(fetch: RecentFetch, note: FetchNote?, formats: Formats, now: Instant): String? = when (note) {
        null -> null
        is FetchNote.WaitEnds -> if (note.over) "History: the wait ended ${formats.moment(note.at, now)}" else "Waiting for ${fetch.sourceName} until ${formats.moment(note.at, now)}"
        is FetchNote.RequestLimitUntil ->
            note.at?.let { "The Request Limit of ${fetch.sourceName} is reached until ${formats.moment(it, now)}" }
                ?: "History: the Request Limit of ${fetch.sourceName} is not reached now"
        is FetchNote.LaterFetch -> "History: the Fetch of ${formats.moment(note.at, now)} came after it and ended ${Texts.fetch(note.outcome)}"
        is FetchNote.CredentialsAcceptedSince -> "History: new credentials accepted ${formats.moment(note.at, now)}"
        FetchNote.NoConsentMissingNow -> "History: no Bank Consent is missing now"
        is FetchNote.WaitingOn -> note.blocked.joinToString("; ") { waits(it, formats, now) }
    }

    /** What a Fetch recorded for one Bank Account, as its detail line shows it. */
    fun result(line: FetchBankAccount): String = when (val what = line.what) {
        is FetchedWhat.Recorded -> Texts.fetchResult(what.outcome)
        is FetchedWhat.Served -> "Served"
        FetchedWhat.HeldByRequestLimit -> "Not read"
    }

    /** What the detail line of one Bank Account says about where it came from and whether it still asks something. */
    fun accountNote(line: FetchBankAccount, formats: Formats, now: Instant): String? {
        val served = (line.what as? FetchedWhat.Served)?.let { servedFrom(it.earlier, formats, now) }
        val need = when (val need = line.need) {
            AccountNeed.Delivered -> "Nothing needed now"
            is AccountNeed.Still -> if (need.blocked.remedy == Remedy.WaitUntilItEnds) "${waits(need.blocked, formats, now)} · nothing needed from you now" else null
            is AccountNeed.ReadSince -> "Read since by the Fetch of ${formats.moment(need.at, now)} · nothing needed now"
            is AccountNeed.LaterFetch ->
                "The Fetch of ${formats.moment(need.at, now)} came after it and recorded ${Texts.fetchResult(need.outcome)} for it · see that Fetch"
            AccountNeed.NoLongerFetched -> "No longer fetched · nothing needed now"
            AccountNeed.Ended -> "${if ((line.what as? FetchedWhat.Recorded)?.outcome == FetchResultOutcome.NotConsented) "No Bank Consent is missing now" else "Nothing blocks it now"} · nothing needed now"
            AccountNeed.SeeEarlier -> null
            is AccountNeed.NotReadYet ->
                need.freesUpAt?.let { "Held back by the Request Limit until ${formats.moment(it, now)} · nothing needed from you now" }
                    ?: "Held back by the Request Limit when this Fetch ran; the limit is not reached now"
        }
        return listOfNotNull(served, need).joinToString(" · ").ifEmpty { null }
    }

    private fun servedFrom(earlier: EarlierFetch?, formats: Formats, now: Instant): String =
        when {
            earlier == null -> "From an earlier Fetch"
            else -> "From the Fetch of ${formats.moment(earlier.startedAt, now)}"
        }

    /** The line that says what a Blocked Reason still asks of the Operator, with the action (UC-008 BR-006), for a Bank Account's detail. */
    fun stillNeeded(blocked: Blocked, formats: Formats, now: Instant): String = "Still needed: ${asks(blocked, formats, now)}"
}

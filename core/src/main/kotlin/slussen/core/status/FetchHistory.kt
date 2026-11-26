package slussen.core.status

import slussen.core.port.BackOffCause
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.StartedBy
import slussen.core.port.StoredBackOff
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredFetch
import slussen.core.port.StoredFetchResult
import java.time.Instant

/** The recent Fetches (UC-008 A5, BR-008), newest first, and how many the Operator Setting keeps (`fetch-history.html`). */
data class RecentFetches(val kept: Int, val fetches: List<RecentFetch>)

/**
 * One recent Fetch with its [outcome], what it recorded for each Bank Account ([bankAccounts], UC-001 step 11) and
 * whether it still asks something of the Operator now ([need]). The record itself never changes; [need] is read from
 * the stored data of now.
 */
data class RecentFetch(
    val fetchId: Long,
    val dataProviderId: Long,
    val sourceName: String,
    val startedAt: Instant,
    val startedBy: StartedBy,
    val outcome: FetchOutcome,
    val bankAccounts: List<FetchBankAccount>,
    val need: FetchNeed,
)

/** One Bank Account of a Fetch: what the Fetch recorded for it and whether that still asks something now. */
data class FetchBankAccount(val bankAccountId: Long, val name: String, val what: FetchedWhat, val need: AccountNeed)

/** What a Fetch did with a Bank Account. */
sealed interface FetchedWhat {
    /** The Fetch read it from the Data Provider and recorded this Fetch Result. */
    data class Recorded(val outcome: FetchResultOutcome) : FetchedWhat

    /**
     * A Fetch within the Fetch Cooldown did not contact the Data Provider for it (UC-001 A2): its stored data came from
     * [earlier], the latest earlier Fetch that recorded a Fetch Result for it, or from an earlier Fetch that is no longer
     * kept when [earlier] is null.
     */
    data class Served(val earlier: EarlierFetch?) : FetchedWhat
}

/** An earlier Fetch and the Fetch Result it recorded for one Bank Account. */
data class EarlierFetch(val startedAt: Instant, val outcome: FetchResultOutcome)

/** Whether what a Fetch recorded for a Bank Account still asks something of the Operator now. */
sealed interface AccountNeed {
    /** It was fetched: nothing is wrong. */
    data object Delivered : AccountNeed

    /** It is still blocked now by [blocked], whose remedy is the action; a remedy that only waits asks nothing. */
    data class Still(val blocked: Blocked) : AccountNeed

    /** A later Fetch of the Data Provider read it, at [at]. */
    data class ReadSince(val at: Instant) : AccountNeed

    /** A later Fetch of the Data Provider recorded [outcome] for it at [at]; whatever is needed is on that Fetch. */
    data class LaterFetch(val at: Instant, val outcome: FetchResultOutcome) : AccountNeed

    /** The Bank Account is a Retired Account, has no Account Mapping or its Budget is not served: it is not fetched now. */
    data object NoLongerFetched : AccountNeed

    /** The reason this Fetch Result gave no longer applies: nothing blocks the Bank Account now. */
    data object Ended : AccountNeed

    /** A Fetch within the Fetch Cooldown served it; the Fetch it came from carries what is needed. */
    data object SeeEarlier : AccountNeed
}

/** Whether a whole Fetch still asks something of the Operator now. */
sealed interface FetchNeed {
    /** [blocked] are the Blocked Reasons that still ask the Operator for an action. */
    data class Needed(val blocked: List<Blocked>) : FetchNeed

    /** Nothing is asked; [note] says what is current or past about it, or is null when there is nothing to add. */
    data class NotNeeded(val note: FetchNote?) : FetchNeed
}

/** What is current or past about a Fetch that asks nothing now. */
sealed interface FetchNote {
    /** A1: the Fetch met a Back-off that ended, or ends, at [at]. */
    data class WaitEnds(val at: Instant, val over: Boolean) : FetchNote

    /** A3: the Request Limit holds until [at], or is not reached now when [at] is null. */
    data class RequestLimitUntil(val at: Instant?) : FetchNote

    /** A later Fetch of the same Data Provider, at [at] with [outcome], is its latest word. */
    data class LaterFetch(val at: Instant, val outcome: FetchOutcome) : FetchNote

    /** A7: new credentials were accepted at [at], after the Fetch. */
    data class CredentialsAcceptedSince(val at: Instant) : FetchNote

    /** A4, A9: no Bank Account of the Data Provider lacks a valid Bank Consent now. */
    data object NoConsentMissingNow : FetchNote

    /** Bank Accounts of the Fetch are blocked until a time that ends by itself; nothing is asked. */
    data class WaitingOn(val blocked: List<Blocked>) : FetchNote
}

/**
 * The rules that give each recent Fetch its lines and its need (UC-008 A5; UC-001 step 11, A1-A9, A19, A20), from stored
 * data only. What is blocked now comes from [accountBlocked] and [sourceBlocked], the functions the Budget's Blocked
 * Reasons use, so that the two never disagree (UC-008 BR-006).
 */
internal class FetchHistoryRules(
    private val now: Instant,
    /** Every stored Fetch, newest first. */
    private val fetches: List<StoredFetch>,
    private val accounts: Map<Long, StoredBankAccount>,
    private val backOffs: List<StoredBackOff>,
    private val sourceName: (Long) -> String,
    /** When the credentials of a Data Provider were accepted, or null when they are not accepted. */
    private val credentialsAcceptedAt: (Long) -> Instant?,
    /** The Blocked Reasons of a Bank Account now, or null when it is not fetched now. */
    private val accountBlocked: (StoredBankAccount) -> List<Blocked>?,
    /** The Blocked Reasons of a Data Provider now, given its latest Fetch. */
    private val sourceBlocked: (Long, StoredFetch?) -> List<Blocked>,
) {
    fun entry(fetch: StoredFetch): RecentFetch {
        val lines = lines(fetch)
        return RecentFetch(
            fetch.id, fetch.dataProviderId, sourceName(fetch.dataProviderId), fetch.startedAt, fetch.startedBy, fetch.outcome,
            lines, need(fetch, lines),
        )
    }

    private fun isLater(other: StoredFetch, fetch: StoredFetch) =
        other.dataProviderId == fetch.dataProviderId &&
            (other.startedAt.isAfter(fetch.startedAt) || (other.startedAt == fetch.startedAt && other.id > fetch.id))

    private fun later(fetch: StoredFetch): List<StoredFetch> = fetches.filter { isLater(it, fetch) }

    private fun earlier(fetch: StoredFetch): List<StoredFetch> = fetches.filter { it.dataProviderId == fetch.dataProviderId && it !== fetch && !isLater(it, fetch) }

    private fun nameOf(bankAccountId: Long) = accounts[bankAccountId]?.name ?: UNKNOWN_BANK_ACCOUNT

    private fun lines(fetch: StoredFetch): List<FetchBankAccount> {
        val recorded = fetch.results.map { FetchBankAccount(it.bankAccountId, nameOf(it.bankAccountId), FetchedWhat.Recorded(it.outcome), recordedNeed(fetch, it)) }
        if (fetch.outcome != FetchOutcome.ServedFromFetchCooldown) return recorded
        // Within the Fetch Cooldown (UC-001 A2) the Bank Accounts of the Data Provider that were there when the Fetch ran and that it did not read were served from earlier Fetches.
        val served = accounts.values
            .filter { it.dataProviderId == fetch.dataProviderId && !it.identifiedAt.isAfter(fetch.startedAt) && fetch.results.none { result -> result.bankAccountId == it.id } }
            .sortedBy { it.id }
            .map { account ->
                val from = earlier(fetch).firstNotNullOfOrNull { f -> f.results.firstOrNull { it.bankAccountId == account.id }?.let { EarlierFetch(f.startedAt, it.outcome) } }
                FetchBankAccount(account.id, account.name, FetchedWhat.Served(from), AccountNeed.SeeEarlier)
            }
        return recorded + served
    }

    private fun recordedNeed(fetch: StoredFetch, result: StoredFetchResult): AccountNeed {
        if (result.outcome == FetchResultOutcome.Fetched) return AccountNeed.Delivered
        val laterWord = later(fetch).firstNotNullOfOrNull { f -> f.results.firstOrNull { it.bankAccountId == result.bankAccountId }?.let { f to it } }
        if (laterWord != null) {
            val (laterFetch, laterResult) = laterWord
            return if (laterResult.outcome == FetchResultOutcome.Fetched) AccountNeed.ReadSince(laterFetch.startedAt) else AccountNeed.LaterFetch(laterFetch.startedAt, laterResult.outcome)
        }
        val blocked = accounts[result.bankAccountId]?.let(accountBlocked) ?: return AccountNeed.NoLongerFetched
        return blocked.firstOrNull { it.reason in reasonsOf(result.outcome) }?.let { AccountNeed.Still(it) } ?: AccountNeed.Ended
    }

    /** The Blocked Reasons that carry on what a Fetch Result recorded (UC-008 BR-006). */
    private fun reasonsOf(outcome: FetchResultOutcome): Set<BlockedReason> = when (outcome) {
        FetchResultOutcome.Fetched -> emptySet()
        FetchResultOutcome.Failed -> setOf(BlockedReason.FailedFetch)
        FetchResultOutcome.NotConsented -> setOf(BlockedReason.MissingBankConsent, BlockedReason.ExpiredBankConsent)
        FetchResultOutcome.NotRecognised, FetchResultOutcome.AmbiguousBankAccount -> setOf(BlockedReason.UnrecognisedBankAccount)
        FetchResultOutcome.AccessLimitReached -> setOf(BlockedReason.AccessLimitReached)
        FetchResultOutcome.NotDelivered -> setOf(BlockedReason.NotDelivered)
        FetchResultOutcome.SignInNeeded -> setOf(BlockedReason.SignInNeeded)
    }

    private fun need(fetch: StoredFetch, lines: List<FetchBankAccount>): FetchNeed {
        val later = later(fetch)
        val laterNote = later.firstOrNull()?.let { FetchNote.LaterFetch(it.startedAt, it.outcome) }
        return when (fetch.outcome) {
            FetchOutcome.Fetched, FetchOutcome.ServedFromFetchCooldown -> fromBankAccounts(lines)
            FetchOutcome.WaitingForBackOff -> FetchNeed.NotNeeded(waitOf(fetch))
            FetchOutcome.RequestLimitReached -> {
                val limit = sourceBlocked(fetch.dataProviderId, fetches.firstOrNull { it.dataProviderId == fetch.dataProviderId })
                    .firstOrNull { it.reason == BlockedReason.RequestLimitReached }
                FetchNeed.NotNeeded(FetchNote.RequestLimitUntil(limit?.at))
            }
            FetchOutcome.Failed -> latestOnly(laterNote) { asking(fetch, BlockedReason.FailedFetch) }
            FetchOutcome.CredentialsRefused -> latestOnly(laterNote) {
                asking(fetch, BlockedReason.CredentialsRefused, BlockedReason.CredentialsMissing)
                    ?: credentialsAcceptedAt(fetch.dataProviderId)?.let { FetchNeed.NotNeeded(FetchNote.CredentialsAcceptedSince(it)) }
            }
            FetchOutcome.NotConsented -> latestOnly(laterNote) {
                val missing = accounts.values.filter { it.dataProviderId == fetch.dataProviderId }
                    .flatMap { accountBlocked(it).orEmpty() }
                    .filter { it.reason == BlockedReason.MissingBankConsent || it.reason == BlockedReason.ExpiredBankConsent }
                if (missing.isEmpty()) FetchNeed.NotNeeded(FetchNote.NoConsentMissingNow) else FetchNeed.Needed(missing)
            }
        }
    }

    /** A Fetch that is not the latest of its Data Provider is history and names the one after it; the latest one decides for itself. */
    private fun latestOnly(laterNote: FetchNote?, ofLatest: () -> FetchNeed?): FetchNeed =
        if (laterNote != null) FetchNeed.NotNeeded(laterNote) else ofLatest() ?: FetchNeed.NotNeeded(null)

    private fun asking(fetch: StoredFetch, vararg reasons: BlockedReason): FetchNeed? =
        sourceBlocked(fetch.dataProviderId, fetch).filter { it.reason in reasons }.takeIf { it.isNotEmpty() }?.let { FetchNeed.Needed(it) }

    /** A1: the Back-off of the whole Data Provider that held when the Fetch ran, and whether it has ended. */
    private fun waitOf(fetch: StoredFetch): FetchNote? =
        backOffs.filter {
            it.dataProviderId == fetch.dataProviderId && it.bankId == null &&
                it.cause in setOf(BackOffCause.RequestToWait, BackOffCause.RequestLimitWarning) &&
                !it.startsAt.isAfter(fetch.startedAt) && it.endsAt.isAfter(fetch.startedAt)
        }.maxByOrNull { it.endsAt }?.let { FetchNote.WaitEnds(it.endsAt, over = !it.endsAt.isAfter(now)) }

    private fun fromBankAccounts(lines: List<FetchBankAccount>): FetchNeed {
        val still = lines.mapNotNull { (it.need as? AccountNeed.Still)?.blocked }
        val asks = still.filter { it.remedy != Remedy.WaitUntilItEnds }
        val waits = still.filter { it.remedy == Remedy.WaitUntilItEnds }
        return when {
            asks.isNotEmpty() -> FetchNeed.Needed(asks)
            waits.isNotEmpty() -> FetchNeed.NotNeeded(FetchNote.WaitingOn(waits))
            else -> FetchNeed.NotNeeded(null)
        }
    }

    private companion object {
        /** A Fetch Result whose Bank Account is no longer stored by name. */
        const val UNKNOWN_BANK_ACCOUNT = "a Bank Account that is not stored any more"
    }
}

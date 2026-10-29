package slussen.pluginapi.provider

import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.BankAccountNumber
import slussen.pluginapi.value.CurrencyCode
import slussen.pluginapi.value.SensitiveApi
import java.time.Instant
import java.time.LocalDate

/** The Data Provider contract (ADR-001 §4.1). */
public interface DataProvider {
    public val profile: DataProviderProfile

    /** The credentials the Operator enters for this Data Provider and how they are checked (§4.5). */
    public val credentials: CredentialCapability

    /** Non-null exactly when `profile.needsBankConsent`. */
    public val bankConsent: BankConsentCapability?

    public suspend fun fetch(request: FetchRequest, context: RequestContext): FetchResponse
}

public data class DataProviderProfile(
    /** `DATA_PROVIDER.name`; the name on its entry in Settings and its page (§4.6). */
    val name: String,
    /** `DATA_PROVIDER.source_key`; fixed forever once released (ADR-008 §2). */
    val sourceKey: String,
    /** `DATA_PROVIDER.stable_reference` (FR-072, UC-012 BR-005). */
    val givesStableReference: Boolean,
    /** `DATA_PROVIDER.bank_consent_required`; its page shows its Bank Consents (UC-007 BR-008). */
    val needsBankConsent: Boolean,
    /**
     * Default of `DATA_PROVIDER.fetch_cooldown_minutes`, 0 to 1440, 0 switches it off; an Operator Setting the Operator
     * can change (FR-016, UC-001 BR-006, UC-010).
     */
    val defaultFetchCooldownMinutes: Int,
    /**
     * `DATA_PROVIDER.request_limit` and `request_limit_published` (FR-017, UC-001 BR-007, UC-010 BR-002, UC-012 BR-011):
     * the limit the Data Provider publishes, or a default the Operator can change.
     */
    val requestLimit: RequestLimitDeclaration,
)

/**
 * How a Data Provider's part declares its Request Limit (ADR-001 §4.1, S-31): requests in any rolling 24 hours, counted
 * from the Provider Requests.
 */
public sealed interface RequestLimitDeclaration {
    /**
     * The limit the Data Provider publishes, 1 to 1000, fixed: not an Operator Setting. [source] names where it is
     * published and is shown on the Data Provider's page (ADR-001 §4.6).
     */
    public data class Published(val requests: Int, val source: String) : RequestLimitDeclaration

    /**
     * The Data Provider publishes none: a cautious default inside [range], the range inside 1 to 1000; an Operator
     * Setting the Operator can change within [range] and reset to the default (UC-010 A11), shown as not published.
     */
    public data class NotPublished(val defaultRequests: Int, val range: IntRange) : RequestLimitDeclaration
}

/**
 * What one Fetch asks of a Data Provider (ADR-001 §4.1): the Bank Accounts to read and what to read of each, the
 * fetch period, whether the Operator is present, and the Bank Session of each Bank for a Data Provider that needs a
 * Bank Consent.
 */
public data class FetchRequest(
    val bankAccounts: BankAccountSelection,
    val periodFrom: LocalDate,
    val periodTo: LocalDate,
    val presence: OperatorPresence,
    /** `BANK_SESSION.session_reference` of each Bank, by the Bank's reference. */
    val sessions: Map<String, ConsentSession>,
)

public sealed interface BankAccountSelection {
    /**
     * The Bank Accounts to read, by `provider_reference`, or by `balance_provider_reference` for a Bank Account read
     * for its balance only (UC-001 BR-011); possibly none, and the response still lists every Bank Account the Data
     * Provider offers (UC-001 step 4).
     */
    public data class Selected(val bankAccounts: List<BankAccountToRead>) : BankAccountSelection

    /** Every Bank Account the Data Provider exposes, for identification. */
    public data class AllForIdentification(val read: ReadKind) : BankAccountSelection
}

public data class BankAccountToRead(val providerReference: String, val read: ReadKind)

public enum class ReadKind { Transactions, Balance, TransactionsAndBalance }

/** UC-001 BR-012. */
public enum class OperatorPresence { OperatorPresent, Unattended }

/** Results per scope, so that one Bank or Bank Account never stops another (UC-001 A5, A8, A9, BR-010). */
public data class FetchResponse(
    /** The whole Data Provider. */
    val sourceOutcome: SourceOutcome,
    /** One per Bank that did not complete normally. */
    val bankOutcomes: List<BankOutcome>,
    /** Every Bank Account the Data Provider listed. */
    val bankAccounts: List<DeliveredBankAccount>,
)

/** Completed, or why the whole Data Provider stopped. */
public sealed interface SourceOutcome {
    public data object Completed : SourceOutcome

    public data class AskedToWait(val until: Instant?) : SourceOutcome

    public data object RequestLimitWarning : SourceOutcome

    public data class TransientFailure(val kind: FailureKind) : SourceOutcome

    public data object CredentialsRefused : SourceOutcome

    public data class StoppedByGate(val reason: RefusalReason) : SourceOutcome
}

public sealed interface BankOutcome {
    public val bankReference: String

    public data class AskedToWait(override val bankReference: String, val until: Instant?) : BankOutcome

    public data class ConsentRefused(override val bankReference: String) : BankOutcome

    public data class StoppedByGate(override val bankReference: String, val reason: RefusalReason) : BankOutcome
}

public data class DeliveredBankAccount(
    val providerReference: String,
    val bankReference: String?,
    val name: String?,
    val kind: String?,
    val currency: CurrencyCode?,
    val number: BankAccountNumber?,
    val result: BankAccountResult,
)

public sealed interface BankAccountResult {
    public data class Read(
        val transactions: List<ProviderTransaction>,
        val balance: Amount?,
        /**
         * The time the Data Provider says [balance] was reported, or null when it gives none; the core stores it as
         * `ACCOUNT_BALANCE.reported_at`, or the time it received the answer when it is null (S-T-04, ADR-001 §4.1,
         * UC-001 step 8).
         */
        val balanceReportedAt: Instant? = null,
    ) : BankAccountResult

    /**
     * A failure for this Bank Account alone, carrying none of its transactions (UC-001 A8): a paged read that did not
     * finish, or a read the Data Provider reports as failed or incomplete, is `Failed(Incomplete)`.
     */
    public data class Failed(val kind: FailureKind) : BankAccountResult

    /**
     * The Operator must sign in to this Bank Account's Bank again at the Data Provider before it can be read, as the
     * Data Provider reports it. It carries nothing, no Data Provider text included (NFR-012); the core keeps the
     * stored data of the Bank Account unchanged and starts no Back-off (UC-001 A20, S-T-03, ADR-001 §4.1).
     */
    public data object SignInNeeded : BankAccountResult

    /**
     * Listed but not read. For [NotReadCause.NotSelected], [balance] is the balance the Data Provider gave with the list
     * in the same answer, or null when it gave none; the plug-in sends no further request for it. The core takes it as
     * the Offered Bank Account's balance and does not store it as an Account Balance (S-35, ADR-001 §4.1). For any other
     * cause [balance] is null.
     */
    public data class NotRead(val cause: NotReadCause, val balance: Amount? = null) : BankAccountResult
}

/** Why a delivered Bank Account was not read. */
public enum class NotReadCause {
    /** Only listed: the request did not ask to read it. */
    NotSelected,

    /** The gate refused it (scope BankAccount). */
    RefusedByGate,

    /** Its Bank stopped (a `BankOutcome`). */
    StoppedWithItsBank,

    /** The whole Data Provider stopped (a `SourceOutcome` other than Completed). */
    StoppedWithTheSource,
}

public sealed interface ProviderTransaction {
    public data class Interpretable(
        /** Passed on exactly as delivered; never normalised, trimmed or changed in case (ADR-008 §2). */
        val stableReference: String?,
        val bookedDate: LocalDate,
        val amount: Amount,
        val currency: CurrencyCode,
        val payee: String?,
        val memo: String?,
        val booked: Boolean,
    ) : ProviderTransaction

    /** A delivered item that cannot be interpreted, with whatever fields could be read (S-07, UC-001 BR-016). */
    public data class Uninterpretable(
        val bookedDate: LocalDate?,
        val amount: Amount?,
        val currency: CurrencyCode?,
        val payee: String?,
        val memo: String?,
        val booked: Boolean?,
        val reason: UnreadableReason,
    ) : ProviderTransaction
}

public enum class UnreadableReason {
    MissingDate,
    UnreadableDate,
    MissingAmount,
    UnreadableAmount,
    MissingCurrency,
    UnreadableStructure,
}

/**
 * The Bank Session of a granted Bank Consent (`BANK_SESSION.session_reference`), masked like a `Secret` (ADR-006 §2).
 */
public class ConsentSession(private val value: String) {
    @SensitiveApi
    public fun reveal(): String = value

    override fun toString(): String = MASK

    public companion object {
        public const val MASK: String = "ConsentSession(****)"
    }
}

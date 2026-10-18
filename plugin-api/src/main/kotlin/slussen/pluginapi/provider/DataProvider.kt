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
    /** `DATA_PROVIDER.name`; the name on its page in Connections (§4.6). */
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
     * `DATA_PROVIDER.request_limit`, 1 to 1000: the Data Provider's own limit, whose source the implementing part's
     * KDoc names; not an Operator Setting (FR-017, UC-001 BR-007, UC-012 BR-011).
     */
    val requestLimit: Int,
)

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
    public data class Read(val transactions: List<ProviderTransaction>, val balance: Amount?) : BankAccountResult

    /** A paged read that did not finish is `Failed(Incomplete)` and carries none of its transactions (UC-001 A8). */
    public data class Failed(val kind: FailureKind) : BankAccountResult

    public data class NotRead(val cause: NotReadCause) : BankAccountResult
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

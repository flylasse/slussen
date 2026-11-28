package slussen.core.consent

import slussen.core.operation.OperationRefusal
import slussen.pluginapi.provider.BankChoice
import slussen.pluginapi.provider.ConsentFailureKind
import slussen.pluginapi.request.FailureKind
import java.time.Instant

/**
 * The way to the Bank's Strong Customer Authentication that the Data Provider handed over (UC-007 step 3): the screen
 * opens [address] in the device's browser. It carries a value of the session and is never logged (UC-007 BR-006,
 * BR-011), so [toString] masks it.
 */
class ConsentAddress(val address: String) {
    override fun toString(): String = "ConsentAddress(****)"
}

/** Why an operation of UC-007 did not run: nothing was sent and nothing stored. */
sealed interface ConsentUnavailable {
    /** UC-014 BR-005: without stored credentials Slussen contacts no Data Provider. */
    data object CredentialsMissing : ConsentUnavailable

    /** UC-014 BR-005: the Data Provider refused the stored credentials; it is not contacted until new ones are accepted. */
    data object CredentialsRefused : ConsentUnavailable

    /** Precondition (UC-006 BR-005, ADR-007 §2): an Upload Plan awaits confirmation or an Upload is written. */
    data class Paused(val reason: OperationRefusal) : ConsentUnavailable
}

/** What held a request of UC-007 back at the request gate (A9), with the time it ends (UC-001 A3, A5). */
data class HeldBack(val by: HeldBackBy, val until: Instant)

enum class HeldBackBy {
    /** The Blocked Reason "Request Limit reached" (UC-001 BR-007). */
    RequestLimitReached,

    /** The Blocked Reason "waiting for the provider": a Back-off of the Data Provider or of the Bank (UC-001 BR-004). */
    WaitingForProvider,

    /** The operation had ended before the request was sent; no flow of UC-007 names it. */
    NotSent,
}

/** The outcome of listing the Banks of one country (UC-007 step 1, BR-010, A3, A8, A9). Nothing of it is stored. */
sealed interface BanksOutcome {
    /** The Banks the Data Provider lists for [country], each with the reference that names it and the name to show. */
    data class Listed(val country: String, val banks: List<BankChoice>) : BanksOutcome {
        // UC-007 BR-010: A search by name narrows the list on the device.
        /** The Banks whose name holds [search], in any case; all of them for an empty search. Sends nothing. */
        fun matching(search: String): List<BankChoice> =
            if (search.isBlank()) banks else banks.filter { it.name.contains(search.trim(), ignoreCase = true) }
    }

    data class Refused(val kind: ConsentFailureKind) : BanksOutcome

    /** A8 step 1: the Data Provider could not be reached at [at]; the Operator can try again. */
    data class NotReached(val kind: FailureKind, val at: Instant) : BanksOutcome

    data class HeldBackByGate(val heldBack: HeldBack) : BanksOutcome

    /** The country is not an ISO 3166 two-letter code; nothing was sent. */
    data object CountryNotValid : BanksOutcome

    data class NotAvailable(val reason: ConsentUnavailable) : BanksOutcome
}

/** The outcome of starting a Consent Request (UC-007 steps 2-4, A3, A8, A9, A11). */
sealed interface StartOutcome {
    /**
     * Steps 2-4: the Consent Request [requestId] for the Bank [bankReference] is stored with the digest of its state and
     * waits for its answer until [expiresAt], the Consent Request lifetime from its start (BR-002); the screen opens
     * [consentAddress] and offers the field into which the Operator pastes the address the browser shows (BR-011).
     * [returnAddress] is the address Slussen sent.
     */
    data class Started(
        val requestId: Long,
        val bankReference: String,
        val expiresAt: Instant,
        val consentAddress: ConsentAddress,
        val returnAddress: String,
    ) : StartOutcome

    /**
     * A3: the Data Provider refused to start; nothing stored, every earlier Bank Consent and Consent Request of the Bank
     * unchanged. For a refused return address, [returnAddress] is the address Slussen sent, to register with the Data
     * Provider (BR-011); null otherwise.
     */
    data class Refused(val bankReference: String, val kind: ConsentFailureKind, val returnAddress: String?) : StartOutcome

    /** A8 step 1: the Data Provider could not be reached at [at]; nothing stored; the Operator can try again. */
    data class NotReached(val bankReference: String, val kind: FailureKind, val at: Instant) : StartOutcome

    /** A9: no request was sent and nothing stored. */
    data class HeldBackByGate(val bankReference: String, val heldBack: HeldBack) : StartOutcome

    /** A11: the device has no app that opens the way to the Strong Customer Authentication; nothing started or sent. */
    data object NoBrowser : StartOutcome

    /**
     * The Bank's reference cannot be stored: it is empty or longer than `BANK.name` and `CONSENT_REQUEST.bank_reference`
     * hold (entity model, 100 characters); nothing sent or stored.
     */
    data object BankReferenceNotUsable : StartOutcome

    /**
     * The Data Provider started the Consent Request, but it could not be stored, so no answer to it can be accepted
     * (BR-002); the Operator starts again.
     */
    data class NotStored(val bankReference: String) : StartOutcome

    data class NotAvailable(val reason: ConsentUnavailable) : StartOutcome
}

/** Why an answer was not accepted (A2): the Operator starts the consent again; nothing is stored. */
enum class AnswerNotAccepted {
    /** It cannot be read: no `state` the Data Provider's part can find in it, or an answer the Data Provider does not understand. */
    Unreadable,

    /** No stored Consent Request has its state: it was abandoned, revoked, or started by another installation. */
    NoSuchRequest,

    /** A newer Consent Request of the Bank was started. */
    NotTheMostRecent,

    /** It arrived after the Consent Request lifetime ended. */
    LifetimeEnded,

    /** An answer to its Consent Request was accepted before. */
    AlreadyAnswered,
}

/** What happened to the earlier Bank Consent of the Bank when a renewal was stored (BR-003). */
enum class EarlierBankConsent {
    /** There was none: a first grant. */
    None,

    /** The Data Provider confirmed that it ended the earlier consent at the Bank. */
    Ended,

    /** The request to end it failed, was held back or was not confirmed at the Bank: it may stay there until it expires. */
    MayStayAtBank,
}

/** An Offered Bank Account the Bank Consent covers that is proposed as a likely match of an added Bank Account (A5). */
data class ProposedMatch(val offeredName: String?, val lastFourDigits: String?, val bankAccountName: String)

/**
 * Step 9 and 10: the Bank Accounts the Bank Consent covers, as Slussen identified them. A Bank Account is identified with
 * certainty only by its Data Provider's reference, which lasts (UC-009 BR-004, UC-013 BR-011); anything else waits for the
 * Operator.
 */
data class Coverage(
    /** How many Bank Accounts the Data Provider delivered with the Bank Consent. */
    val delivered: Int,
    /** The added Bank Accounts identified with certainty by their reference: nothing about them changes. */
    val identified: List<String>,
    /** A5: proposed as a likely match, to be confirmed by the Operator in Manage Account Mappings (UC-009). */
    val toConfirm: List<ProposedMatch>,
    /** A6: the added Bank Accounts Slussen stops for, with the Blocked Reason "unrecognised Bank Account". */
    val notIdentified: List<String>,
    /** UC-013 BR-012: imported and to map, how many. */
    val toMap: Int,
    /** UC-013 BR-011: Left-Out Bank Accounts, which stay not imported, how many. */
    val leftOut: Int,
    /**
     * The added Bank Accounts of this Bank and Data Provider whose reference the Data Provider no longer delivered: their
     * stored data stays unchanged, and the Operator checks at the Data Provider that they are still connected (UC-001 A19).
     */
    val noLongerDelivered: List<String>,
)

/** The outcome of an answer from the Bank's Strong Customer Authentication, returned or pasted (steps 6-10, A1, A2, A4, A8, A9). */
sealed interface CompletionOutcome {
    /**
     * Steps 8-10: the Bank Consent of [bankName] is stored until [expiresAt], the expiry the Data Provider reported
     * (BR-004), in place of the earlier one (BR-003). [coverage] is null when the Bank Consent was stored but its Bank
     * Accounts could not be: they are kept at the next grant or renewal.
     */
    data class Granted(
        val bankName: String,
        val expiresAt: Instant,
        val coverage: Coverage?,
        val earlier: EarlierBankConsent,
    ) : CompletionOutcome

    /** A1: the Operator cancelled the Strong Customer Authentication or the Bank refused it; the consent was not granted. */
    data class NotGranted(val bankReference: String) : CompletionOutcome

    /** A2: the answer was rejected; nothing stored; start again. */
    data class NotAccepted(val reason: AnswerNotAccepted) : CompletionOutcome

    /** A4: the Data Provider did not confirm the Bank Consent; the Consent Request is answered, so start again. */
    data class NotConfirmed(val bankReference: String, val kind: ConsentFailureKind) : CompletionOutcome

    /**
     * A8 step 2: the Data Provider could not be reached at [at], or gave an answer Slussen cannot read; the Bank may have
     * granted a consent that did not reach Slussen, which is never used and ends at its expiry or at the Bank; start again.
     */
    data class MayHaveBeenGranted(val bankReference: String, val kind: FailureKind, val at: Instant) : CompletionOutcome

    /** A9 step 3: nothing sent; continues as A8 step 2, and the Operator starts again after [HeldBack.until]. */
    data class HeldBackByGate(val bankReference: String, val heldBack: HeldBack) : CompletionOutcome

    /**
     * The stored data could not be read or written (NFR-023): nothing of the Bank Consent is stored and no Bank is left
     * behind; start again. [bankReference] is null when the failure came before the answer's Consent Request was found.
     */
    data class NotStored(val bankReference: String?) : CompletionOutcome

    data class NotAvailable(val reason: ConsentUnavailable) : CompletionOutcome
}

/** The outcome of abandoning a Consent Request (A10). */
sealed interface AbandonOutcome {
    /** Deleted: no answer to it can be accepted, and an Upload Plan can be prepared again (ADR-007 §3). */
    data object Abandoned : AbandonOutcome

    /** It is answered or no longer stored; nothing changed. */
    data object NothingToAbandon : AbandonOutcome

    data object NotStored : AbandonOutcome

    data class NotAvailable(val reason: ConsentUnavailable) : AbandonOutcome
}

/** The outcome of revoking a Bank Consent (A7, A8 step 3, A9 step 3, BR-007). */
sealed interface RevokeOutcome {
    /**
     * A7 steps 4-5: the Bank Consent of [bankName] with its Bank Session and every Consent Request of the Bank is deleted.
     * [endedAtBank] is true only when the Data Provider confirmed the end at the Bank; otherwise the Operator can also end
     * it at the Bank. [dataProviderContacted] is false when no request was sent ([heldBack], or no credentials).
     */
    data class Revoked(
        val bankName: String,
        val endedAtBank: Boolean,
        val dataProviderContacted: Boolean,
        val heldBack: HeldBack?,
    ) : RevokeOutcome

    /** The Bank has no Bank Consent with this Data Provider; nothing sent. */
    data object NothingToRevoke : RevokeOutcome

    /** The deletion could not be stored (NFR-023): the Bank Consent stays stored; [endedAtDataProvider] says whether the Data Provider ended it. */
    data class NotStored(val endedAtDataProvider: Boolean) : RevokeOutcome

    data class NotAvailable(val reason: ConsentUnavailable) : RevokeOutcome
}

/** The status of one Bank Consent as the Data Provider's page shows it (BR-008, UC-008 BR-002, BR-006). */
enum class BankConsentState { Valid, Expiring, Expired }

/** One Bank Consent of the Data Provider's page (BR-008): its Bank, status, expiry and the Bank Accounts it covers (BR-009). */
data class BankConsentLine(
    val bankId: Long,
    val bankName: String,
    val state: BankConsentState,
    /** The expiry the Bank or Data Provider reported (BR-004). */
    val expiresAt: Instant,
    /** The time from which it counts as expired: its refusal by the Bank when [refusedByBank] (UC-001 A9), else [expiresAt]. */
    val expiredFrom: Instant,
    val refusedByBank: Boolean,
    val coveredBankAccounts: List<String>,
)

/** A Consent Request that waits for its answer within its lifetime (step 4, A10). */
data class WaitingRequest(val requestId: Long, val bankReference: String, val expiresAt: Instant)

/**
 * The Bank Consents section of a Data Provider's page (BR-008, BR-011): the exact return address Slussen sends, to
 * register with the Data Provider ([ownAddress] when it is the application's own), each Bank Consent, and the Consent
 * Requests that wait for their answer.
 */
data class BankConsentsPage(
    val returnAddress: String,
    val ownAddress: Boolean,
    val consents: List<BankConsentLine>,
    val waiting: List<WaitingRequest>,
)

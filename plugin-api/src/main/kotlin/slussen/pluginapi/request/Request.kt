package slussen.pluginapi.request

import slussen.pluginapi.value.Secret
import java.time.Duration

/**
 * What every contract method that may send a request receives (ADR-001 §4.0): the request gate, the request timeout
 * and the stored service credential of the service (§4.5).
 */
public interface RequestContext {
    public val gate: RequestGate

    /** The request timeout Operator Setting (NFR-016). */
    public val timeout: RequestTimeout

    /** The stored service credential (§4.5); null only in `CredentialCapability.check`. */
    public val credential: Secret?
}

/** The plug-in calls [admit] before every request it sends, each page and every Bank Consent request included. */
public fun interface RequestGate {
    public suspend fun admit(request: RequestSummary): Admission
}

public data class RequestSummary(
    /** `PROVIDER_REQUEST.request_kind` for a Data Provider; BudgetAppRead or BudgetAppWrite for the Budget App. */
    val kind: RequestKind,
    /** The Bank the request concerns, when it concerns one. */
    val bankReference: String?,
    /** The Bank Account it reads, when it reads one. */
    val bankAccountReference: String?,
)

public enum class RequestKind { Transactions, Balance, Other, BudgetAppRead, BudgetAppWrite }

public sealed interface Admission {
    public data object Allowed : Admission

    public data class Refused(val scope: RefusalScope, val reason: RefusalReason) : Admission
}

/** Source is the whole Data Provider or Budget App. */
public enum class RefusalScope { BankAccount, Bank, Source }

/**
 * Why the gate refused a request (ADR-001 §4.0): a reached Request Limit, a Back-off of the Data Provider or of one
 * Bank, a reached Unattended Access limit, or an operation that has ended or been cancelled.
 */
public enum class RefusalReason { RequestLimitReached, BackOff, UnattendedAccessLimitReached, OperationEnded }

/** The request timeout of every request (OkHttp `callTimeout`), the Operator Setting of NFR-016. */
public data class RequestTimeout(val duration: Duration)

/**
 * Why a request or a read did not complete. A closed enumeration: a Data Provider's error text never leaves the
 * plug-in (ADR-001 §4.1, NFR-012).
 */
public enum class FailureKind {
    /** The request timed out or was cancelled (ADR-001 §4.0). */
    Timeout,

    /** The connection was lost before an answer arrived. */
    ConnectionLost,

    /** The service answered that it cannot serve the request now. */
    ServiceUnavailable,

    /** The answer could not be read. */
    UnreadableAnswer,

    /** A paged read did not finish (UC-001 A8). */
    Incomplete,
}

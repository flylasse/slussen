package slussen.pluginapi.diagnostic

import java.time.Duration
import java.time.Instant

/**
 * The one logging contract (ADR-006 §1), used by `:core` and every plug-in and implemented once in `:app`. There is no
 * `String` parameter on the always-written path.
 */
public interface SlussenLog {
    /** Always written. Only a [LogEvent] constant and [LogFact] values can be passed. */
    public fun event(event: LogEvent, vararg facts: LogFact)

    /** Written only while Detailed Logging is on; the lambda is not run otherwise. */
    public fun detail(event: LogEvent, message: () -> String)
}

/**
 * Implemented only by enum classes (Konsist L-06). The adapter writes the enum class's simple name and the constant's
 * name.
 */
public interface LogEvent

/** Sealed, so that no other module can add a fact; it has no constructor for free text. */
public sealed interface LogFact {
    public data class Count(val value: Long) : LogFact

    public data class Elapsed(val value: Duration) : LogFact

    public data class At(val value: Instant) : LogFact

    /** A Transaction Status, a Fetch outcome, a Blocked Reason, a refusal reason; written by the constant's name. */
    public data class Value(val value: Enum<*>) : LogFact

    /** An internal record id. */
    public data class RecordId(val kind: RecordKind, val id: Long) : LogFact
}

/** The stored records an internal record id can name: the entities of `docs/entity_model.md`. */
public enum class RecordKind {
    Budget,
    BudgetAccount,
    BudgetAppTransaction,
    DataProvider,
    FileFormat,
    Bank,
    BankAccount,
    AccountMapping,
    OfferedBankAccount,
    BankConsent,
    BankSession,
    ServiceCredential,
    ConsentRequest,
    Fetch,
    FetchResult,
    BackOff,
    ProviderRequest,
    AccountBalance,
    TransactionFile,
    BankTransaction,
    UnreadableTransaction,
    ReviewDecision,
    Transfer,
    UploadPlan,
    EligibleTransaction,
    SentRecord,
    TransferCounterpart,
    FirstUploadMatching,
    DecisionJournalEntry,
    OperatorSettings,
    TransferDescription,
}

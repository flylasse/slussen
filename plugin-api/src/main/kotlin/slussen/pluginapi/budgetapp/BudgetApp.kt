package slussen.pluginapi.budgetapp

import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.LocalDate

/**
 * The Budget App contract (ADR-001 §4.2), split so that the right to write can be confined: only
 * `slussen.core.upload` receives the [writer] (ADR-007 §7, Konsist K-05, bytecode rule B-04).
 */
public interface BudgetApp {
    public val profile: BudgetAppProfile

    /** The credentials the Operator enters for this Budget App and how they are checked (§4.5). */
    public val credentials: CredentialCapability

    public val reader: BudgetAppReader

    public val writer: BudgetAppWriter
}

/**
 * The numbers of the capability floor (§4.2.1), each with its source in the implementing part's KDoc, and the three
 * optional capabilities, each with one fixed behaviour of the core when false (§4.2.2).
 */
public data class BudgetAppProfile(
    /** The name on its entry in Settings and its page (§4.6). */
    val name: String,
    /** At least the length of every Import Identifier protocol version this build produces (ADR-008 §6). */
    val importIdentifierMaxLength: Int,
    val payeeMaxLength: Int,
    val memoMaxLength: Int,
    /** The glossary's Matching Date Window. */
    val matchingDateWindowDays: Int,
    /** NFR-015, UC-006 BR-007. */
    val refusesReusedImportIdentifier: Boolean,
    /** NFR-015, FR-090; Upload as Cleared is on its page only when true. */
    val supportsClearedMarker: Boolean,
    /** UC-006 A10. */
    val supportsLinkedTransfers: Boolean,
)

/** Reading (ADR-001 §4.2.3). Every read goes through the gate of the context. */
public interface BudgetAppReader {
    /** Every Budget the Budget App lists, with its Budget Accounts, their names, currency and balances. */
    public suspend fun readBudgets(context: RequestContext): ReadResult<List<BudgetRead>>

    /** One transaction by its reference; neither takes nor returns a cursor and stores nothing. */
    public suspend fun readTransaction(
        budgetReference: String,
        transactionReference: String,
        context: RequestContext,
    ): ReadResult<TransactionLookup>

    /** The changed Budget App Transactions of one Budget since [since], and the next cursor (F-3). */
    public suspend fun readChanges(
        budgetReference: String,
        since: ChangeCursor?,
        from: LocalDate,
        context: RequestContext,
    ): ReadResult<ChangeSet>
}

/** A read either answered or did not; refused credentials are a typed result, never an exception (F-8). */
public sealed interface ReadResult<out T> {
    public data class Answered<T>(val value: T) : ReadResult<T>

    public data object CredentialsRefused : ReadResult<Nothing>

    public data class TransientFailure(val kind: FailureKind) : ReadResult<Nothing>

    public data class StoppedByGate(val reason: RefusalReason) : ReadResult<Nothing>
}

public data class BudgetRead(
    val reference: String,
    val name: String,
    val currency: CurrencyCode,
    val budgetAccounts: List<BudgetAccountRead>,
)

public data class BudgetAccountRead(val reference: String, val name: String, val balance: Amount)

public sealed interface TransactionLookup {
    public data class Found(val transaction: BudgetAppTransactionRead) : TransactionLookup

    public data object NotFound : TransactionLookup
}

/** An opaque cursor that only the plug-in understands; the core stores it with the changes (ADR-002 §3 O-4). */
@JvmInline
public value class ChangeCursor(public val value: String)

public data class ChangeSet(val transactions: List<BudgetAppTransactionRead>, val next: ChangeCursor)

/** One Budget App Transaction in the normalized read model (ADR-001 §4.2.3); every field is stored by O-4. */
public data class BudgetAppTransactionRead(
    val reference: String,
    val budgetAccountReference: String,
    val date: LocalDate,
    val amount: Amount,
    val payee: String?,
    val memo: String?,
    val importIdentifier: String?,
    val entry: EntryKind,
    /** The other side's transaction reference and Budget Account reference, for both sides of a linked transfer. */
    val transferLink: TransferLink?,
    val state: ReadState,
    val cleared: Boolean,
    val approved: Boolean,
)

/**
 * Typed (entered by hand), Imported (written by an importer), TransferSide (created by the Budget App as the other side
 * of a linked transfer).
 */
public enum class EntryKind { Typed, Imported, TransferSide }

public data class TransferLink(val transactionReference: String, val budgetAccountReference: String)

public sealed interface ReadState {
    public data object Present : ReadState

    public data object Deleted : ReadState

    public data class Merged(val intoReference: String) : ReadState
}

/** Writing (ADR-001 §4.2.4). Every created transaction awaits the Operator's approval in the Budget App (F-5). */
public interface BudgetAppWriter {
    /** Creates the items of one Budget Account exactly as the Upload Plan states them; one F-2 outcome per item. */
    public suspend fun create(budgetReference: String, items: List<CreateItem>, context: RequestContext): CreateResult

    /** The only change to an existing Budget App Transaction the contract offers (FR-055, UC-006 BR-011). */
    public suspend fun markCleared(
        budgetReference: String,
        counterpartReference: String,
        context: RequestContext,
    ): ClearResult
}

/** One `upload_plan_item` payload; payee and memo are already shortened by the core (UC-006 BR-015). */
public data class CreateItem(
    val budgetAccountReference: String,
    val importIdentifier: String,
    val date: LocalDate,
    val amount: Amount,
    val payee: String?,
    val memo: String?,
    val cleared: Boolean,
    /** The other Budget Account, for a linked transfer. */
    val transferBudgetAccountReference: String?,
)

public sealed interface CreateResult {
    /** One outcome per item, in the order of the items. */
    public data class Answered(val outcomes: List<ItemOutcome>) : CreateResult

    /** Nothing was sent. */
    public data object CredentialsRefused : CreateResult

    /** Nothing was sent. */
    public data class StoppedByGate(val reason: RefusalReason) : CreateResult
}

/** F-2. A refusal because the Import Identifier is already held is [AlreadyPresent], never [Rejected]. */
public sealed interface ItemOutcome {
    public data class Created(val reference: String) : ItemOutcome

    public data class AlreadyPresent(val reference: String?) : ItemOutcome

    /** [reason] is the Budget App's text, shown to the Operator and never journaled or logged (UC-006 A9). */
    public data class Rejected(val reason: String) : ItemOutcome

    public data object Unknown : ItemOutcome
}

public enum class ClearResult { Cleared, NotCleared, Unknown }

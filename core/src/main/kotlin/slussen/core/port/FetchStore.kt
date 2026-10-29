package slussen.core.port

import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant
import java.time.LocalDate

/** One Fetch as it is recorded (`FETCH`, UC-001 step 11, A1, A2, A3, A5, A6, A7). */
data class NewFetch(
    val dataProviderId: Long,
    val startedAt: Instant,
    val startedBy: StartedBy,
    val outcome: FetchOutcome,
)

/** One Back-off as it is started (`BACK_OFF`, UC-001 A5, A15). */
data class NewBackOff(
    val dataProviderId: Long,
    /** Empty when the whole Data Provider waits. */
    val bankId: Long?,
    val cause: BackOffCause,
    val startsAt: Instant,
    val endsAt: Instant,
)

/** The balance of one Bank Account as its Data Provider reported it in a Fetch (`ACCOUNT_BALANCE`, UC-001 step 8). */
data class NewAccountBalance(
    val amount: Amount,
    /** The reported currency (`ACCOUNT_BALANCE.currency`). */
    val currency: CurrencyCode,
    /** When the Data Provider reported it. */
    val reportedAt: Instant,
)

/**
 * What O-1 stores for one identified Bank Account a Fetch read (ADR-002 §3; UC-001 steps 7, 8, 11, A8, A17, A19, A20):
 * its Fetch Result (`FETCH_RESULT`), when the Data Provider reported one in the Bank Account's own currency its Account
 * Balance in place of the one stored before (`ACCOUNT_BALANCE`, the most recent balance), and the transactions
 * delivered for it. [balance] is null when nothing new is stored: the earlier Account Balance stays unchanged (A8, A19,
 * A20, A21). [transactions] is null when no transaction is taken in: the Bank Account was not read (A8, A19, A20), or
 * it was read for its balance only (BR-011).
 */
data class BankAccountDelivery(
    /** The recorded Fetch the result belongs to ([FetchStore.recordFetch]). */
    val fetchId: Long,
    val bankAccountId: Long,
    val outcome: FetchResultOutcome,
    /** `FETCH_RESULT.transaction_count`: the booked Bank Transactions delivered. */
    val transactionCount: Int,
    val balance: NewAccountBalance?,
    val transactions: DeliveredTransactions? = null,
)

/**
 * The transactions a Data Provider delivered for one identified Bank Account, in the order it delivered them (UC-001
 * steps 7-8, A17): the booked ones with an amount other than zero (BR-002, BR-003), and those Slussen cannot interpret
 * (BR-016). O-1 allocates the `occurrence_number`s and computes the Import Identifiers inside its transaction (ADR-008
 * §4, §5).
 */
data class DeliveredTransactions(
    /** The Source Key of the Data Provider, which enters every Import Identifier (ADR-008 §2). */
    val sourceKey: String,
    /** When Slussen received them: the `first_seen_at` of what is new. */
    val receivedAt: Instant,
    val interpretable: List<DeliveredTransaction>,
    val unreadable: List<NewUnreadableTransaction>,
)

/** One booked transaction with an amount other than zero, as the Data Provider delivered it (UC-001 step 7). */
data class DeliveredTransaction(
    /** Opaque, exactly as delivered (ADR-008 §2, B-08); null when the transaction has none. */
    val stableReference: String?,
    val bookedDate: LocalDate,
    val amount: Amount,
    val currency: CurrencyCode,
    val payee: String?,
    val memo: String?,
)

/** A delivered transaction Slussen cannot interpret, with whatever fields could be read and the reason (UC-001 A17). */
data class NewUnreadableTransaction(
    val bookedDate: LocalDate?,
    val amount: Amount?,
    val currency: CurrencyCode?,
    val payee: String?,
    val memo: String?,
    val reason: UnreadableTransactionReason,
)

/** Why Slussen could not interpret a delivered transaction, as `UNREADABLE_TRANSACTION.reason` shows it to the Operator. */
enum class UnreadableTransactionReason(val text: String) {
    MissingDate("date missing"),
    UnreadableDate("date unreadable"),
    MissingAmount("amount missing"),
    UnreadableAmount("amount unreadable"),
    MissingCurrency("currency missing"),
    UnreadableStructure("transaction unreadable"),

    /** UC-003 BR-002, ADR-008 §5: its Import Identifier is held by a different stored Bank Transaction. */
    ImportIdentifierCollision("Import Identifier collision"),

    /** UC-001 A17 (S-T-16): its currency differs from its Bank Account's; no amount is ever converted. */
    OtherCurrency("currency differs from the Bank Account's"),
}

/** The `Values:` of `BANK_TRANSACTION.reference_kind`. */
enum class StableReferenceKind(val text: String) {
    Supplied("Supplied"),
    Derived("Derived"),
    None("None"),
}

/** The `Values:` of `BANK_TRANSACTION.status`, the Transaction Status a Comparison gives (UC-003). */
enum class TransactionStatus(val text: String) {
    New("New"),
    MatchForTypedEntry("Match For Typed Entry"),
    AlreadyInBudgetApp("Already In Budget App"),
    PossibleImportByOtherPath("Possible Import By Other Path"),
    Deferred("Deferred"),
    DeletedInBudgetApp("Deleted In Budget App"),
    MergedInBudgetApp("Merged In Budget App"),
    DecisionNoLongerHolds("Decision No Longer Holds"),
    NeedsReview("Needs Review"),
    BeforeStartDate("Before Start Date"),
    TooOld("Too Old"),
    InTheFuture("In The Future"),
    OutcomeUnknown("Outcome Unknown"),
    TransferLeg("Transfer Leg"),
    GuessedTransfer("Guessed Transfer"),
    HeldTransfer("Held Transfer"),
    ArrivedAsTransferCounterpart("Arrived As Transfer Counterpart"),
    TransferCounterpartChanged("Transfer Counterpart Changed"),
}

/** The `Values:` of `UNREADABLE_TRANSACTION.status`. */
enum class UnreadableTransactionStatus(val text: String) {
    NeedsReview("Needs Review"),
    Deferred("Deferred"),
}

/** `BANK_TRANSACTION` (entity model) as stored. */
data class StoredBankTransaction(
    val id: Long,
    val bankAccountId: Long,
    val stableReference: String?,
    val referenceKind: StableReferenceKind,
    val occurrenceNumber: Int,
    val importIdentifier: String,
    val bookedDate: LocalDate,
    val amount: Amount,
    val currency: CurrencyCode,
    val payee: String?,
    val memo: String?,
    val firstSeenAt: Instant,
    /** Empty until a Comparison has read its Budget (UC-003 A1, S-T-08). */
    val status: TransactionStatus?,
    val transferId: Long?,
)

/** `UNREADABLE_TRANSACTION` (entity model) as stored; [reason] is the text shown to the Operator. */
data class StoredUnreadableTransaction(
    val id: Long,
    val bankAccountId: Long,
    val bookedDate: LocalDate?,
    val amount: Amount?,
    val currency: CurrencyCode?,
    val payee: String?,
    val memo: String?,
    val reason: String,
    val firstSeenAt: Instant,
    val status: UnreadableTransactionStatus,
)

/** What made a delivered transaction one Slussen records in the Decision Journal (UC-001 step 9, UC-011 BR-001). */
enum class Reception { FirstTime, ChangedContent }

/** A field of a Bank Transaction whose content a later delivery changed. */
enum class TransactionField { BookedDate, Amount, Currency, Payee, Memo }

/** A Bank Transaction O-1 stored because it was received for the first time or with changed content. */
data class ReceivedBankTransaction(
    val importIdentifier: String,
    val reception: Reception,
    val bookedDate: LocalDate,
    val amount: Amount,
    val currency: CurrencyCode,
    /** For [Reception.ChangedContent], the fields that changed; empty for [Reception.FirstTime]. */
    val changed: List<TransactionField> = emptyList(),
)

/**
 * What O-1 stored of a delivery's transactions (UC-001 steps 8-9, A17): the Bank Transactions received for the first time
 * or with changed content, in the order they were delivered; the Import Identifiers that collided with a different
 * stored Bank Transaction and were kept as Unreadable Transactions (ADR-008 §5); and how many Unreadable Transactions
 * were added in all (BR-016).
 */
data class DeliveryStored(
    val received: List<ReceivedBankTransaction> = emptyList(),
    val collisions: List<String> = emptyList(),
    val unreadableAdded: Int = 0,
)

/**
 * The storage of the Fetches, Back-offs, Fetch Results, Account Balances, Bank Transactions and Unreadable Transactions
 * of Fetch Transactions (UC-001) in `slussen.db` (ADR-002 §1). Each method is one transaction.
 */
interface FetchStore {
    /** The Fetches of [dataProviderId] that are kept, most recent first, each with its Fetch Results. */
    suspend fun fetchesOf(dataProviderId: Long): List<StoredFetch>

    /**
     * Records [fetch] and keeps only the [keep] most recent Fetches of all Data Providers, with their Fetch Results, in
     * one transaction (`FETCH` constraint, `OPERATOR_SETTINGS.fetch_history_size`). Returns the id of the new Fetch.
     */
    suspend fun recordFetch(fetch: NewFetch, keep: Int): Long

    /**
     * O-1 `FetchStore.storeBankAccountDelivery` (ADR-002 §3): for one identified Bank Account, all or nothing, its new
     * Bank Transactions with their `occurrence_number` allocated and their Import Identifiers computed and checked for
     * collision, the changed content of known ones, its new Unreadable Transactions counted against the stored ones,
     * its Account Balance in place of the stored one when one is given, and its Fetch Result for the Fetch
     * [BankAccountDelivery.fetchId] (UC-001 steps 7-8, 11, A8, A17, BR-016; ADR-008 §4, §5). The rules of what is new,
     * changed, identical or colliding are the core's ([slussen.core.fetch.BankTransactionIntake]), applied inside the
     * transaction to what is stored.
     */
    suspend fun storeBankAccountDelivery(delivery: BankAccountDelivery): DeliveryStored

    /** The stored Bank Transactions of [bankAccountId], oldest first. */
    suspend fun bankTransactionsOf(bankAccountId: Long): List<StoredBankTransaction>

    /** The stored Unreadable Transactions of [bankAccountId], oldest first. */
    suspend fun unreadableTransactionsOf(bankAccountId: Long): List<StoredUnreadableTransaction>

    /** The Back-offs of [dataProviderId], also those that have ended. */
    suspend fun backOffsOf(dataProviderId: Long): List<StoredBackOff>

    suspend fun startBackOff(backOff: NewBackOff)
}

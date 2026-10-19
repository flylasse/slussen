package slussen.core.connection

import slussen.core.credential.CredentialStatus
import slussen.core.credential.ServiceCredentials
import slussen.core.journal.DecisionJournal
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationRefusal
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.BankAccountType
import slussen.core.port.ConnectionStore
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.RandomSource
import slussen.core.port.ServiceKey
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** UC-013 step 1: one service with whether its credentials are entered and accepted (BR-008). */
data class CredentialStep(val service: ServiceKey, val name: String, val isBudgetApp: Boolean, val status: CredentialStatus)

/** One Budget as the Budget App listed it, marked when Slussen already serves it (UC-013 step 2). */
data class ListedBudget(
    val budgetAppReference: String,
    val name: String,
    val currency: CurrencyCode,
    val served: Boolean,
    val budgetAccounts: List<ListedBudgetAccount>,
)

data class ListedBudgetAccount(val budgetAppReference: String, val name: String, val balance: Amount)

/** The outcome of reading the Budgets (UC-013 step 2, A1, A7, precondition). */
sealed interface BudgetsRead {
    data class Listed(val budgets: List<ListedBudget>) : BudgetsRead

    /** A7: Budgets can be chosen once the Budget App's credentials are accepted; the Budget App is not contacted. */
    data object BudgetAppCredentialsMissing : BudgetsRead

    /** A1: the Budget App refused Slussen's credentials; the Blocked Reason "credentials refused" with its remedy. */
    data object CredentialsRefused : BudgetsRead

    /** A1: the Budget App did not answer or was unavailable; the Budgets served stay unchanged. */
    data class CouldNotRead(val kind: FailureKind?) : BudgetsRead

    data class Unavailable(val reason: OperationRefusal) : BudgetsRead
}

/** The outcome of choosing the served Budgets (UC-013 steps 3-4, A2). */
sealed interface BudgetChoice {
    data object Stored : BudgetChoice

    /** A2: refused; the Bank Accounts mapped into that Budget, whose Account Mappings are removed first (UC-009 A11). */
    data class StillMapped(val budgetName: String, val bankAccountNames: List<String>) : BudgetChoice

    data class Unavailable(val reason: OperationRefusal) : BudgetChoice
}

/** An Offered Bank Account shown for pairing, with what UC-013 BR-002 shows of it. */
data class OfferedForPairing(
    val offeredBankAccountId: Long,
    val nameOrProduct: String?,
    val kind: String?,
    val currency: CurrencyCode?,
    val lastFourDigits: String?,
    val balance: Amount?,
    val bankName: String?,
)

/** UC-013 A6: an Offered Bank Account that is probably an already added Bank Account whose identification changed. */
data class ProposedMatch(val offered: OfferedForPairing, val bankAccountId: Long, val bankAccountName: String)

/** UC-013 A3: what Slussen offers when a Source has no Offered Bank Account stored yet. */
enum class OfferRemedy {
    /** Start a Fetch of that Data Provider (UC-001). */
    StartFetch,

    /** Grant the Bank Consent of a Bank (UC-007), for a Data Provider that needs one. */
    GrantBankConsent,
}

/** One Source on the pairing screen (UC-013 step 5). */
data class SourcePairing(
    val dataProviderId: Long,
    val name: String,
    val offered: List<OfferedForPairing>,
    val proposedMatches: List<ProposedMatch>,
    /** Set when no Offered Bank Account of the Source is stored (A3). */
    val remedy: OfferRemedy?,
)

/**
 * A free Budget Account: of a served Budget, receiving through no Account Mapping and not a Manually Kept Account
 * (UC-013 BR-002, BR-013).
 */
data class FreeBudgetAccount(
    val budgetAccountId: Long,
    val budgetName: String,
    val budgetCurrency: CurrencyCode,
    val name: String,
    val balance: Amount?,
)

/** A Left-Out Bank Account, shown apart by its name and Source (UC-013 step 5, BR-011; UC-009 step 1, BR-013). */
data class LeftOutBankAccount(
    val leftOutBankAccountId: Long,
    val dataProviderId: Long,
    val sourceName: String,
    /** The name it had when it was left out: its name or product with its last four digits. */
    val name: String,
    val leftOutAt: Instant,
)

/**
 * UC-013 step 5: the Offered Bank Accounts of each Source that are neither added nor left out (BR-002, BR-012), the
 * free Budget Accounts, and the Left-Out Bank Accounts, collapsed below (BR-011).
 */
data class Pairing(
    val sources: List<SourcePairing>,
    val freeBudgetAccounts: List<FreeBudgetAccount>,
    val leftOut: List<LeftOutBankAccount>,
    /** A11: every stored Offered Bank Account is added, left out or a proposed match of an added Bank Account. */
    val nothingToBringIn: Boolean,
)

/** UC-013 step 7, A12: how many of the shown Offered Bank Accounts are brought in and how many are left out (BR-012). */
data class BringInStatement(val broughtIn: List<OfferedForPairing>, val leftOut: List<OfferedForPairing>)

/** The outcome of confirming the stated numbers (UC-013 steps 8-9, A12). */
sealed interface BringIn {
    /** Step 9: the shown ones not chosen are left out; [queue] holds the chosen ones in the order shown, taken one at a time. */
    data class Confirmed(val queue: List<Long>, val leftOut: Int) : BringIn

    /** BR-012: the Offered Bank Accounts changed after they were shown; nothing is left out and the Operator chooses again. */
    data object ShownChanged : BringIn

    data class Unavailable(val reason: OperationRefusal) : BringIn
}

/**
 * Step 10: the card of the current Offered Bank Account: the free Budget Accounts of its currency, or every free one
 * when it states none, with the only one proposed (BR-013); every free Budget Account of each served Budget on request;
 * the proposed name (BR-004), Bank Account type and Bank (BR-009), and the currency it states (BR-010).
 */
data class PairingCard(
    val offered: OfferedForPairing,
    val sourceName: String,
    val listed: List<FreeBudgetAccount>,
    /** BR-013: set only when exactly one Budget Account is listed, the only free one of that currency. */
    val proposedBudgetAccountId: Long?,
    val allFree: List<FreeBudgetAccount>,
    val proposedName: String,
    val proposedType: BankAccountType,
    val proposedBank: KnownBank?,
    val types: List<BankAccountType>,
    val knownBanks: List<KnownBank>,
    /** BR-010: the currency the Offered Bank Account states, or null when the Operator states it. */
    val currency: CurrencyCode?,
)

/** An Offered Bank Account that can report the balance of a Bank Account of a File Format, also a Left-Out one (BR-006). */
data class BalanceSource(val offered: OfferedForPairing, val sourceName: String, val leftOut: Boolean)

/** The outcome of bringing back a Left-Out Bank Account (UC-013 A10, UC-009 A15). */
sealed interface BroughtBack {
    /** It is no longer left out; [offeredNow] when the Data Provider's latest delivery holds it, else from the next one. */
    data class Offered(val name: String, val offeredNow: Boolean) : BroughtBack

    data object NotFound : BroughtBack

    data class Unavailable(val reason: OperationRefusal) : BroughtBack
}

/** The outcome of marking or unmarking a Manually Kept Account (UC-013 A9, UC-009 A16). */
sealed interface KeepOutcome {
    data object Marked : KeepOutcome

    data object Unmarked : KeepOutcome

    /** UC-009 BR-001: the Budget Account receives through the Account Mapping of [bankAccountName]; it is not marked. */
    data class StillReceives(val bankAccountName: String) : KeepOutcome

    /** Only a free Budget Account of a served Budget is marked (UC-013 BR-014, UC-009 A16). */
    data object NotFree : KeepOutcome

    /** The Budget Account is not a Manually Kept Account, so there is nothing to unmark. */
    data object NotManuallyKept : KeepOutcome

    data class Unavailable(val reason: OperationRefusal) : KeepOutcome
}

/** A Manually Kept Account of a served Budget, shown apart from the Bank Accounts (UC-009 step 1, BR-013). */
data class KeptBudgetAccount(val budgetAccountId: Long, val name: String, val budgetName: String, val budgetCurrency: CurrencyCode)

/**
 * UC-009 step 1, BR-013: apart from the Bank Accounts, the Left-Out Bank Accounts of each Data Provider, the Manually
 * Kept Accounts of each served Budget, and the free Budget Accounts that can be marked (A16).
 */
data class ApartFromBankAccounts(
    val leftOut: List<LeftOutBankAccount>,
    val manuallyKept: List<KeptBudgetAccount>,
    val markable: List<FreeBudgetAccount>,
)

/** A Bank Slussen already knows (`BANK`), to choose for a Bank Account (UC-013 BR-009). */
data class KnownBank(val bankId: Long, val name: String)

/**
 * Step 10: what Slussen proposes for a paired Offered Bank Account before adding it: its name (BR-004), its Bank Account
 * type and its Bank (BR-009), with the Bank Account types and the known Banks the Operator chooses from at step 11.
 */
data class AdditionProposal(
    val offeredBankAccountId: Long,
    val budgetAccountId: Long,
    val proposedName: String,
    val proposedType: BankAccountType,
    /** The Bank the Data Provider names for it, or null when it names none. */
    val proposedBank: KnownBank?,
    val types: List<BankAccountType>,
    val knownBanks: List<KnownBank>,
    /** BR-010: the currency the Offered Bank Account states, or, when it states none, the chosen Budget Account's. */
    val proposedCurrency: CurrencyCode,
    /** BR-010: false when the Operator states the currency at step 11. */
    val currencyStatedBySource: Boolean,
)

/** The outcome of pairing (UC-013 steps 10-13, A4, A5). */
sealed interface PairOutcome {
    /** Step 10: the pairing passed the check of step 12; nothing is stored yet. */
    data class Proposed(val proposal: AdditionProposal) : PairOutcome

    /** Step 13: added with its generated identifier, its name, Bank Account type, Bank, Start Date and Account Mapping. */
    data class Added(val bankAccountId: Long, val generatedIdentifier: String, val name: String) : PairOutcome

    /** A5: the Budget Account already receives through the Account Mapping of [bankAccountName]. */
    data class BudgetAccountAlreadyMapped(val budgetAccountName: String, val bankAccountName: String) : PairOutcome

    /** A5: the Budget Account's Budget has another currency than the Bank Account; no amount is converted. */
    data class CurrencyDiffers(
        val budgetAccountName: String,
        val budgetCurrency: CurrencyCode,
        val bankAccountName: String,
        val bankAccountCurrency: CurrencyCode,
    ) : PairOutcome

    /** BR-010: the Offered Bank Account states no currency and the Operator stated none; no Bank Account is added without one. */
    data object CurrencyNotStated : PairOutcome

    /** The Offered Bank Account or the Budget Account is not offered for pairing (BR-002, BR-011, BR-014). */
    data object NotOffered : PairOutcome

    /** Step 11: the name is empty or longer than `BANK_ACCOUNT.name` allows (100). */
    data object NameNotValid : PairOutcome

    /** BR-009: no Bank Account is added without a Bank Account type. */
    data object TypeNotChosen : PairOutcome

    /** BR-009: no Bank Account is added without a Bank; a known one chosen or a new one named. */
    data object BankNotChosen : PairOutcome

    data class Unavailable(val reason: OperationRefusal) : PairOutcome
}

/** A File Format of this build that a Bank Account can take its transactions from (UC-013 A4). */
data class FileFormatChoice(val fileFormatId: Long, val name: String)

/** One added Bank Account with its Account Mapping, for the Bank Accounts and Account Mappings list. */
data class AddedBankAccount(
    val bankAccountId: Long,
    val name: String,
    val bankName: String,
    val sourceName: String,
    val type: BankAccountType,
    val budgetName: String?,
    val budgetAccountName: String?,
    val startDate: LocalDate?,
    val retired: Boolean,
)


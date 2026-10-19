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

/** A Budget Account of a served Budget that receives through no Account Mapping (UC-013 BR-002). */
data class FreeBudgetAccount(
    val budgetAccountId: Long,
    val budgetName: String,
    val budgetCurrency: CurrencyCode,
    val name: String,
    val balance: Amount?,
)

/** UC-013 step 5: both sides of the pairing. */
data class Pairing(val sources: List<SourcePairing>, val freeBudgetAccounts: List<FreeBudgetAccount>)

/** A Bank Slussen already knows (`BANK`), to choose for a Bank Account (UC-013 BR-009). */
data class KnownBank(val bankId: Long, val name: String)

/**
 * Step 8: what Slussen proposes for a paired Offered Bank Account before adding it: its name (BR-004), its Bank Account
 * type and its Bank (BR-009), with the Bank Account types and the known Banks the Operator chooses from at step 9.
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
)

/** The outcome of pairing (UC-013 steps 6-10, A4, A5). */
sealed interface PairOutcome {
    /** Step 8: the pairing passed step 7; nothing is stored yet. */
    data class Proposed(val proposal: AdditionProposal) : PairOutcome

    /** Step 10: added with its generated identifier, its name, Bank Account type, Bank, Start Date and Account Mapping. */
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

    /** The Offered Bank Account delivered no currency, so the currency rule of step 7 cannot be checked. */
    data object CurrencyNotKnown : PairOutcome

    /** The Offered Bank Account or the Budget Account is not offered for pairing (BR-002). */
    data object NotOffered : PairOutcome

    /** Step 9: the name is empty or longer than `BANK_ACCOUNT.name` allows (100). */
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


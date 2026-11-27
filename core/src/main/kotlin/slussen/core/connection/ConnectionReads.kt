package slussen.core.connection

import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.BankAccountType
import slussen.core.port.ConnectionStore
import slussen.core.port.OfferedBankAccountStore
import slussen.core.port.OtherSideKind
import slussen.core.port.SentThrough
import slussen.core.port.StoredBankAccount
import slussen.core.port.StoredBudget
import slussen.core.port.StoredLeftOutBankAccount
import slussen.core.port.StoredOfferedBankAccount
import slussen.core.transfer.TransferDetection

/**
 * What UC-013, UC-009 and the Settings read model of UC-010 share about the Offered Bank Accounts and the Budget
 * Accounts: how each Offered Bank Account stands (to map, not imported, added, a proposed match or a balance source;
 * UC-013 BR-012) and which Budget Accounts are free (UC-013 BR-002, BR-013). Reads only; nothing is stored here.
 */
internal class ConnectionReads(
    private val plugins: RegisteredPlugins,
    private val store: ConnectionStore,
    private val offered: OfferedBankAccountStore,
) {
    /** Everything the classification needs, read once. */
    class Snapshot(
        val budgets: List<StoredBudget>,
        val bankAccounts: List<StoredBankAccount>,
        val bankNames: Map<Long, String>,
        val offered: List<StoredOfferedBankAccount>,
        val leftOut: List<StoredLeftOutBankAccount>,
        /** What was sent through the Account Mapping of each Bank Account with a Sent Record (UC-009 BR-003). */
        val sentThrough: Map<Long, SentThrough> = emptyMap(),
    )

    suspend fun snapshot(): Snapshot = Snapshot(
        budgets = store.budgets(),
        bankAccounts = store.bankAccounts(),
        bankNames = store.banks().associate { it.id to it.name },
        offered = offered.offered(),
        leftOut = store.leftOutBankAccounts(),
        sentThrough = store.sentThroughMappings(),
    )

    fun sourceName(dataProviderId: Long): String =
        plugins.dataProviders.firstOrNull { it.stored.id == dataProviderId }?.stored?.name.orEmpty()

    /**
     * How [offer] stands (UC-013 BR-012): added when it is the Bank Account of a `BANK_ACCOUNT` of its Data Provider; a
     * balance source when it reports the balance of one (BR-006); not imported when it is left out (BR-011); a proposed
     * match when it is probably an added Bank Account (A6, UC-009 BR-004); otherwise imported and to map.
     */
    fun stateOf(offer: StoredOfferedBankAccount, snapshot: Snapshot): ImportState {
        val own = snapshot.bankAccounts.firstOrNull { it.dataProviderId == offer.dataProviderId && it.providerReference == offer.providerReference }
        if (own != null) return ImportState.Added(own.id, own.name, budgetAccountName(own, snapshot))
        // UC-013 BR-012: An Offered Bank Account that reports the balance of a Bank Account of a File Format has no import switch and is never to map.
        val balanceOf = snapshot.bankAccounts.firstOrNull {
            it.balanceDataProviderId == offer.dataProviderId && it.balanceProviderReference == offer.providerReference
        }
        if (balanceOf != null) return ImportState.ReportsBalance(balanceOf.name)
        val leftOut = snapshot.leftOut.firstOrNull { it.dataProviderId == offer.dataProviderId && it.providerReference == offer.providerReference }
        if (leftOut != null) return ImportState.NotImported(leftOut.id)
        // UC-009 BR-004: An Offered Bank Account that is probably an added Bank Account is proposed for confirmation, never guessed.
        val proposed = offer.proposedBankAccountId?.let { id -> snapshot.bankAccounts.firstOrNull { it.id == id } }
        if (proposed != null) return ImportState.ProbablyAdded(proposed.id, proposed.name)
        // UC-013 BR-012: Every Offered Bank Account that is neither added nor left out is imported by default and is a Bank Account to map.
        return ImportState.ToMap
    }

    private fun budgetAccountName(bankAccount: StoredBankAccount, snapshot: Snapshot): String? {
        val id = bankAccount.mapping?.budgetAccountId ?: return null
        return snapshot.budgets.flatMap { it.budgetAccounts }.firstOrNull { it.id == id }?.name
    }

    // UC-013 BR-002: Each Offered Bank Account is shown with its name or product, kind, currency, last four digits and balance.
    fun view(offer: StoredOfferedBankAccount, snapshot: Snapshot) = OfferedForPairing(
        offeredBankAccountId = offer.id,
        nameOrProduct = offer.name,
        kind = offer.bankAccountKind,
        currency = offer.currency,
        lastFourDigits = offer.lastFourDigits,
        balance = offer.balance,
        bankName = offer.bankId?.let { snapshot.bankNames[it] },
    )

    /** UC-013 BR-012, UC-009 BR-014: the Bank Accounts to map of every Data Provider, in the order of the build and of the delivery. */
    fun toMap(snapshot: Snapshot): List<BankAccountToMap> = plugins.dataProviders.flatMap { provider ->
        snapshot.offered.filter { it.dataProviderId == provider.stored.id && stateOf(it, snapshot) == ImportState.ToMap }
            .map { BankAccountToMap(view(it, snapshot), provider.stored.id, provider.stored.name) }
    }

    /** The free Budget Accounts: of a served Budget, receiving through no Account Mapping, not a Manually Kept Account. */
    fun freeBudgetAccounts(snapshot: Snapshot): List<FreeBudgetAccount> {
        val mapped = snapshot.bankAccounts.mapNotNull { it.mapping?.budgetAccountId }.toSet()
        // UC-013 BR-002: Only Budget Accounts of served Budgets that receive through no Account Mapping and are not Manually Kept Accounts are offered for pairing.
        return snapshot.budgets.filter { it.served }.flatMap { budget ->
            budget.budgetAccounts.filter { !it.manuallyKept && it.id !in mapped }.map {
                FreeBudgetAccount(it.id, budget.name, budget.currency, it.name, it.balance)
            }
        }
    }

    fun leftOutView(leftOut: StoredLeftOutBankAccount) = LeftOutBankAccount(
        leftOutBankAccountId = leftOut.id,
        dataProviderId = leftOut.dataProviderId,
        sourceName = sourceName(leftOut.dataProviderId),
        name = leftOut.name.orEmpty(),
        leftOutAt = leftOut.leftOutAt,
    )

    /** One added Bank Account with its Account Mapping, for the Account Mappings and a File Format's page. */
    fun added(bankAccount: StoredBankAccount, snapshot: Snapshot): AddedBankAccount {
        val accounts = snapshot.budgets.flatMap { b -> b.budgetAccounts.map { it.id to (b to it) } }.toMap()
        val mappedInto = bankAccount.mapping?.let { accounts[it.budgetAccountId] }
        return AddedBankAccount(
            bankAccountId = bankAccount.id,
            name = bankAccount.name,
            bankName = snapshot.bankNames[bankAccount.bankId].orEmpty(),
            sourceName = sourceNameOf(bankAccount),
            type = bankAccount.type,
            budgetName = mappedInto?.first?.name,
            budgetAccountName = mappedInto?.second?.name,
            startDate = bankAccount.startDate,
            retired = bankAccount.retired,
            otherSide = otherSide(bankAccount, snapshot.budgets, snapshot.bankAccounts),
            sentThrough = sentThroughShown(bankAccount, snapshot),
            // Step 1 of UC-009, whether it is identified (BR-004): a Data Provider identifies it by the reference recorded for it; Transaction Files need none.
            identified = bankAccount.dataProviderId == null || bankAccount.providerReference != null,
            waitsForConfirmation = snapshot.offered.any { it.proposedBankAccountId == bankAccount.id && stateOf(it, snapshot) is ImportState.ProbablyAdded },
        )
    }

    // UC-009 BR-003: A transaction counts as sent through the Account Mapping when it was sent from the Bank Account to the Budget Account the Account Mapping now names, also through an earlier Account Mapping of the Bank Account to that Budget Account.
    /**
     * Step 1 of UC-009: whether transactions were sent through the Account Mapping of [bankAccount], read from the Sent
     * Records of its own Bank Transactions ([SentThrough.of]), a rejected one included, or from the time of the first
     * send through it; `first_sent_at` is empty after a move or a new Account Mapping, so it never says "not sent" alone.
     */
    private fun sentThroughShown(bankAccount: StoredBankAccount, snapshot: Snapshot): Boolean {
        val through = snapshot.sentThrough[bankAccount.id] ?: SentThrough.NONE
        return bankAccount.mapping?.firstSentAt != null || through.sent > 0 || through.rejected > 0
    }

    // UC-009 BR-008: A credit card Bank Account can have a Funded By and every other Bank Account a Transfer Partner; both start empty.
    /**
     * Step 1 of UC-009, A9 step 1: the Funded By or the Transfer Partner of [bankAccount] as it is stored, and whether the
     * Transfer detection uses it, by the detection's own rule on the stored [budgets] and [bankAccounts] (UC-005 BR-014):
     * one whose Budget Account no Bank Account that is not a Retired Account is mapped to, or that is not a Budget
     * Account of the Budget the Bank Account is mapped into, is kept and shown as no longer applying.
     */
    fun otherSide(bankAccount: StoredBankAccount, budgets: List<StoredBudget>, bankAccounts: List<StoredBankAccount>): OtherSideShown {
        val creditCard = bankAccount.type == BankAccountType.CreditCard
        val stored = if (creditCard) bankAccount.fundedByBudgetAccountId else bankAccount.transferPartnerBudgetAccountId
        val mappedInto = budgets.firstOrNull { budget -> budget.budgetAccounts.any { it.id == bankAccount.mapping?.budgetAccountId } }
        val applying = mappedInto?.let { TransferDetection.otherSideThatApplies(bankAccount, it, bankAccounts) }
        return OtherSideShown(
            kind = if (creditCard) OtherSideKind.FundedBy else OtherSideKind.TransferPartner,
            budgetAccountId = stored,
            budgetAccountName = stored?.let { id -> budgets.flatMap { it.budgetAccounts }.firstOrNull { it.id == id }?.name.orEmpty() },
            applies = stored != null && applying == stored,
        )
    }

    fun sourceNameOf(bankAccount: StoredBankAccount): String =
        bankAccount.dataProviderId?.let(::sourceName)
            ?: plugins.fileFormats.firstOrNull { it.id == bankAccount.fileFormatId }?.name.orEmpty()
}

package slussen.testing

import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.ChangeSetApplied
import slussen.core.port.ComparisonConclusions
import slussen.core.port.ComparisonStore
import slussen.core.port.ConnectionStore
import slussen.core.port.DataProviderRegistration
import slussen.core.port.DataProviderSettingsStore
import slussen.core.port.KeptOutcome
import slussen.core.port.MappingStore
import slussen.core.port.NewBankAccount
import slussen.core.port.NewLeftOutBankAccount
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.PluginRegistryStore
import slussen.core.port.ServeOutcome
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant

/**
 * Every storage operation of the core's ports that changes what an Upload Plan was prepared from, and each of their
 * refusals, run once in a fixed order on invented data against the stores it is given: the in-memory fakes on the JVM and
 * the Room stores on a device run the same steps, so that both raise `coordinator_state.state_version` for the same
 * operations (ADR-002 §3; ADR-007 §6 step 2). A stored change must raise the version, so that a confirmation finds it and
 * writes nothing (UC-006 A5); a refusal stores nothing and must leave it. O-1, which raises it for a new or changed Bank
 * Transaction, has its own test with a delivery.
 */
class StateVersionScenario(
    private val registry: PluginRegistryStore,
    private val providerSettings: DataProviderSettingsStore,
    private val settings: OperatorSettingsStore,
    private val connections: ConnectionStore,
    private val mappings: MappingStore,
    private val copy: BudgetAppCopyStore,
    private val comparisons: ComparisonStore,
    private val stateVersion: suspend () -> Long,
) {
    /** One step: the [operation], whether what it does [mustRaise] the version, and whether the version [rose]. */
    data class Step(val operation: String, val mustRaise: Boolean, val rose: Boolean)

    private val steps = mutableListOf<Step>()
    private val usd = CurrencyCode("USD")
    private val eur = CurrencyCode("EUR")
    private val now: Instant = Instant.parse("2026-11-26T12:00:00Z")

    private suspend fun <T> step(operation: String, mustRaise: Boolean, run: suspend () -> T): T {
        val before = stateVersion()
        val result = run()
        steps += Step(operation, mustRaise, rose = stateVersion() > before)
        return result
    }

    private suspend fun <T> stored(operation: String, run: suspend () -> T): T = step(operation, mustRaise = true, run)

    private suspend fun <T> refused(operation: String, run: suspend () -> T): T = step("$operation (refused)", mustRaise = false, run)

    private fun bankAccount(generatedIdentifier: String, budgetAccountId: Long) = NewBankAccount(
        generatedIdentifier = generatedIdentifier,
        bank = BankChoice.Named("Kestrel Bank"),
        providerBankId = null,
        dataProviderId = null,
        fileFormatId = null,
        balanceDataProviderId = null,
        balanceProviderReference = null,
        name = "Kestrel Checking",
        currency = usd,
        type = BankAccountType.Checking,
        providerReference = null,
        bankAccountFingerprint = null,
        identifiedAt = now,
        startDate = null,
        budgetAccountId = budgetAccountId,
    )

    /** Runs every step and returns them in order; an outcome other than the one a step expects fails the run. */
    suspend fun run(): List<Step> {
        steps.clear()
        val dataProviderId = registry.reconcile(
            listOf(
                DataProviderRegistration(
                    name = "Kestrel Feeds",
                    sourceKey = "example-state-version-feeds",
                    givesStableReference = true,
                    needsBankConsent = false,
                    defaultFetchCooldownMinutes = 60,
                    requestLimit = RequestLimitDeclaration.NotPublished(defaultRequests = 20, range = 1..50),
                ),
            ),
            emptyList(),
        ).dataProviders.single { it.sourceKey == "example-state-version-feeds" }.id
        val listed = listOf(
            BudgetToServe("budget-state-version-household", "Household", usd, listOf(BudgetAccountToStore("ba-state-version-checking", "Checking", Amount(4_140_050)))),
            BudgetToServe("budget-state-version-summer", "Summer House", eur, listOf(BudgetAccountToStore("ba-state-version-current", "Current", Amount(3_402_300)))),
        )
        val both = listed.map { it.budgetAppReference }.toSet()

        check(stored("serve Budgets") { connections.serveBudgets(listed, both, now) } == ServeOutcome.Served)
        val budgets = connections.budgets().filter { it.budgetAppReference in both }
        val household = budgets.single { it.name == "Household" }
        val checking = household.budgetAccounts.single().id
        val current = budgets.single { it.name == "Summer House" }.budgetAccounts.single().id

        val added = stored("add a Bank Account with its Account Mapping") {
            connections.addBankAccount(bankAccount("6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a", checking))
        }
        check(added is AddOutcome.Added) { "$added" }
        check(
            refused("add a Bank Account to a Budget Account that already receives") {
                connections.addBankAccount(bankAccount("7a2d3b5f-1c8e-4d2f-8b66-3f9f4c2d1e5b", checking))
            } is AddOutcome.BudgetAccountAlreadyMapped,
        )
        check(
            refused("stop serving a Budget with a mapped Bank Account") {
                connections.serveBudgets(listed, setOf("budget-state-version-summer"), now)
            } is ServeOutcome.StillMapped,
        )

        val leftOut = listOf(NewLeftOutBankAccount(dataProviderId, "acct-2290", "Kids Saver ••2290", now))
        check(stored("leave out an Offered Bank Account") { connections.leaveOut(leftOut) })
        check(!refused("leave out a Bank Account that is left out") { connections.leaveOut(leftOut) })
        val leftOutId = connections.leftOutBankAccounts().single { it.providerReference == "acct-2290" }.id
        check(stored("bring back a Left-Out Bank Account") { connections.bringBack(leftOutId) } != null)
        check(refused("bring back a Bank Account that is not left out") { connections.bringBack(leftOutId) } == null)

        check(stored("mark a Manually Kept Account") { connections.setManuallyKept(current, true) } == KeptOutcome.Stored)
        check(
            refused("mark a Budget Account that receives through an Account Mapping as a Manually Kept Account") {
                connections.setManuallyKept(checking, true)
            } is KeptOutcome.StillReceives,
        )
        check(stored("unmark a Manually Kept Account") { connections.setManuallyKept(current, false) } == KeptOutcome.Stored)

        stored("change the type and the Bank of a Bank Account") {
            mappings.changeTypeAndBank(added.bankAccountId, BankAccountType.Savings, BankChoice.Named("Aurora Bank"), clearFundedBy = false, clearTransferPartner = false)
        }
        stored("change the fetch period") { settings.storeFetchPeriod(45) }
        stored("change the Request Limit of a Data Provider that publishes none") { providerSettings.storeRequestLimit(dataProviderId, 30) }
        stored("change the Fetch Cooldown of a Data Provider") { providerSettings.storeFetchCooldown(dataProviderId, 120) }

        val read = BudgetReadToStore(household.id, startedFrom = null, transactions = emptyList(), next = ChangeCursor("4182"), balances = emptyMap(), readAt = now)
        check(stored("store a read of the Budget App with its cursor (O-4)") { copy.applyChangeSet(read) } is ChangeSetApplied.Stored)
        check(refused("store a read that started from another cursor (O-4)") { copy.applyChangeSet(read) } == ChangeSetApplied.CursorMoved)
        stored("store the conclusions of a Comparison (O-5)") {
            comparisons.applyComparison(setOf(household.id)) { ComparisonConclusions(statuses = emptyMap()) }
        }
        return steps.toList()
    }

    companion object {
        /** The operations of [run] in its order, each with " (refused)" when it stores nothing: what a runner expects to see. */
        val OPERATIONS: List<String> = listOf(
            "serve Budgets",
            "add a Bank Account with its Account Mapping",
            "add a Bank Account to a Budget Account that already receives (refused)",
            "stop serving a Budget with a mapped Bank Account (refused)",
            "leave out an Offered Bank Account",
            "leave out a Bank Account that is left out (refused)",
            "bring back a Left-Out Bank Account",
            "bring back a Bank Account that is not left out (refused)",
            "mark a Manually Kept Account",
            "mark a Budget Account that receives through an Account Mapping as a Manually Kept Account (refused)",
            "unmark a Manually Kept Account",
            "change the type and the Bank of a Bank Account",
            "change the fetch period",
            "change the Request Limit of a Data Provider that publishes none",
            "change the Fetch Cooldown of a Data Provider",
            "store a read of the Budget App with its cursor (O-4)",
            "store a read that started from another cursor (O-4) (refused)",
            "store the conclusions of a Comparison (O-5)",
        )
    }
}

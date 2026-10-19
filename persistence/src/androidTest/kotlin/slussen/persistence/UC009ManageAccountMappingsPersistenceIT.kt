package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.core.port.DataProviderRegistration
import slussen.core.port.NewLeftOutBankAccount
import slussen.core.port.KeptOutcome
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BankChoice
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.NewBankAccount
import slussen.persistence.main.SlussenDatabase
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Instant

/** The storage operation of UC-009 A14 (O-13) on the platform's SQLite (ADR-002 §3). */
@DisplayName("UC-009: Manage Account Mappings, on the device")
class UC009ManageAccountMappingsPersistenceIT {
    private val usd = CurrencyCode("USD")
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private lateinit var main: SlussenDatabase
    private lateinit var connections: RoomConnectionStore

    @BeforeEach
    fun open() {
        DeviceFiles.wipe()
        main = SlussenDatabases.openMain(DeviceFiles.context)
        connections = RoomConnectionStore(main.connections())
    }

    @AfterEach
    fun close() = main.close()

    private val listed = listOf(
        BudgetToServe("budget-household", "Household", usd, listOf(BudgetAccountToStore("ba-checking", "Checking", Amount(4_140_050)))),
    )

    private fun newBankAccount(budgetAccountId: Long) = NewBankAccount(
        generatedIdentifier = "6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a",
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

    @Test
    @UseCase(id = "UC-009", scenario = "A14: Change the Bank Account Type or the Bank", businessRules = ["BR-012"])
    @DisplayName("A14: O-13 stores the type and a newly named Bank, clears a Transfer Partner as asked, and leaves the Account Mapping and identification as they are")
    fun typeAndBankAreChanged() = runBlocking {
        connections.serveBudgets(listed, setOf("budget-household"), now)
        val checking = connections.budgets().single().budgetAccounts.single().id
        val added = connections.addBankAccount(newBankAccount(checking)) as AddOutcome.Added
        main.openHelper.writableDatabase.execSQL(
            "UPDATE bank_account SET transfer_partner_budget_account_id = $checking WHERE id = ${added.bankAccountId}",
        )
        val before = connections.bankAccounts().single()

        RoomMappingStore(main.connections()).changeTypeAndBank(
            added.bankAccountId, BankAccountType.CreditCard, BankChoice.Named("Pinecrest Card Co."),
            clearFundedBy = false, clearTransferPartner = true,
        )

        val after = connections.bankAccounts().single()
        assertEquals(BankAccountType.CreditCard, after.type)
        assertEquals("Pinecrest Card Co.", connections.banks().single { it.id == after.bankId }.name)
        assertEquals(null, after.transferPartnerBudgetAccountId)
        assertEquals(before.copy(type = after.type, bankId = after.bankId, transferPartnerBudgetAccountId = null), after)
    }

    @Test
    @UseCase(id = "UC-009", scenario = "A16: Mark or Unmark a Manually Kept Account", businessRules = ["BR-001", "BR-013"])
    @DisplayName("A15, A16: O-13 brings back a Left-Out Bank Account and marks or unmarks a Manually Kept Account, refusing to mark one that receives through an Account Mapping")
    fun bringBackAndManuallyKept() = runBlocking {
        val feeds = RoomPluginRegistryStore(main.registry()).reconcile(
            listOf(DataProviderRegistration("Kestrel Feeds", "example-feeds", true, false, 60, RequestLimitDeclaration.Published(20, "an invented source"))),
            emptyList(),
        ).dataProviders.single().id
        connections.serveBudgets(listed, setOf("budget-household"), now)
        val checking = connections.budgets().single().budgetAccounts.single().id
        val mappings = RoomMappingStore(main.connections())
        connections.leaveOut(listOf(NewLeftOutBankAccount(feeds, "acct-2290", "Kids Saver ••2290", now)))

        assertEquals("Kids Saver ••2290", mappings.bringBack(connections.leftOutBankAccounts().single().id)?.name)
        assertTrue(connections.leftOutBankAccounts().isEmpty())
        assertEquals(KeptOutcome.Stored, mappings.setManuallyKept(checking, true))
        assertEquals(KeptOutcome.Stored, mappings.setManuallyKept(checking, false))
        connections.addBankAccount(newBankAccount(checking))
        assertEquals(KeptOutcome.StillReceives("Kestrel Checking"), mappings.setManuallyKept(checking, true))
        assertEquals(false, connections.budgets().single().budgetAccounts.single().manuallyKept)
    }
}

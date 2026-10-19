package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.AddOutcome
import slussen.core.port.BankAccountType
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.DataProviderRegistration
import slussen.core.port.NewBankAccount
import slussen.core.port.NewProviderRequest
import slussen.core.port.ProviderRequestKind
import slussen.core.port.ServeOutcome
import slussen.persistence.main.SlussenDatabase
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** The storage operations of UC-013 (O-15), O-11 and the Request Limit count on the platform's SQLite (ADR-002 §3). */
@DisplayName("UC-013: Connect Budgets and Bank Accounts, on the device")
class UC013ConnectBudgetsAndBankAccountsPersistenceIT {
    private val usd = CurrencyCode("USD")
    private val eur = CurrencyCode("EUR")
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
        BudgetToServe("budget-summer", "Summer House", eur, listOf(BudgetAccountToStore("ba-current", "Current", Amount(3_402_300)))),
    )

    private suspend fun newBankAccount(budgetAccountId: Long, currency: CurrencyCode, identifier: String) = NewBankAccount(
        generatedIdentifier = identifier,
        bankId = connections.bankNamed("Kestrel Bank").id,
        dataProviderId = null,
        fileFormatId = null,
        balanceDataProviderId = null,
        balanceProviderReference = null,
        name = "Kestrel Checking",
        currency = currency,
        type = BankAccountType.Checking,
        providerReference = null,
        bankAccountFingerprint = null,
        identifiedAt = now,
        budgetAccountId = budgetAccountId,
    )

    @Test
    @UseCase(id = "UC-013", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-003"])
    @DisplayName("Steps 4-10: served Budgets, Budget Account balances, the added Bank Account and its Account Mapping are stored and read back")
    fun servedBudgetsAndAddedBankAccountReadBack() = runBlocking {
        assertEquals(ServeOutcome.Served, connections.serveBudgets(listed, setOf("budget-household"), now))
        val checking = connections.budgets().single { it.served }.budgetAccounts.single()

        val added = connections.addBankAccount(newBankAccount(checking.id, usd, "6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a")) as AddOutcome.Added
        connections.nameAndStartDate(added.bankAccountId, "Kestrel Checking", LocalDate.parse("2026-09-01"))

        assertEquals(Amount(4_140_050), checking.balance)
        assertEquals(now, checking.balanceReadAt)
        val stored = connections.bankAccounts().single()
        assertEquals("6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a", stored.generatedIdentifier)
        assertEquals(LocalDate.parse("2026-09-01"), stored.startDate)
        assertEquals(checking.id, stored.mapping?.budgetAccountId)
        assertEquals(listOf("Household"), connections.budgets().filter { it.served }.map { it.name })
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A2: Stop Serving a Budget That Has Mapped Bank Accounts", businessRules = ["BR-001"])
    @DisplayName("A2: O-15 refuses to stop serving a Budget with a mapped Bank Account and changes nothing")
    fun stopServingMappedBudgetChangesNothing() = runBlocking {
        connections.serveBudgets(listed, setOf("budget-household"), now)
        val checking = connections.budgets().single().budgetAccounts.single()
        connections.addBankAccount(newBankAccount(checking.id, usd, "6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a"))
        val before = connections.budgets()

        val outcome = connections.serveBudgets(listed, setOf("budget-summer"), now.plus(Duration.ofMinutes(1)))

        assertEquals(ServeOutcome.StillMapped("Household", listOf("Kestrel Checking")), outcome)
        assertEquals(before, connections.budgets())
    }

    @Test
    @UseCase(id = "UC-013", scenario = "A5: Budget Account Already Mapped or in Another Currency", businessRules = ["BR-002"])
    @DisplayName("A5: O-15 refuses a second Account Mapping into one Budget Account and a Budget of another currency, storing nothing")
    fun secondMappingAndOtherCurrencyAreRefused() = runBlocking {
        connections.serveBudgets(listed, setOf("budget-household", "budget-summer"), now)
        val accounts = connections.budgets().associate { it.name to it.budgetAccounts.single().id }
        connections.addBankAccount(newBankAccount(accounts.getValue("Household"), usd, "6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a"))

        val second = connections.addBankAccount(newBankAccount(accounts.getValue("Household"), usd, "7a2d3b5f-1c8e-4d2f-8b66-3f9f4c2d1e5b"))
        val otherCurrency = connections.addBankAccount(newBankAccount(accounts.getValue("Summer House"), usd, "8b3e4c6a-2d9f-4e3a-9c77-4a0a5d3e2f6c"))

        assertEquals(AddOutcome.BudgetAccountAlreadyMapped("Kestrel Checking"), second)
        assertEquals(AddOutcome.CurrencyDiffers(eur), otherCurrency)
        assertEquals(1, connections.bankAccounts().size)
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-003"], requirements = ["NFR-020"])
    @DisplayName("BR-003: the generated identifier is unique; a second Bank Account with the same identifier is never stored")
    fun generatedIdentifierIsUnique() = runBlocking {
        connections.serveBudgets(listed, setOf("budget-household", "budget-summer"), now)
        val household = connections.budgets().single { it.name == "Household" }.budgetAccounts.single().id
        val summer = connections.budgets().single { it.name == "Summer House" }.budgetAccounts.single().id
        connections.addBankAccount(newBankAccount(household, usd, "6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a"))

        val duplicate = runCatching { connections.addBankAccount(newBankAccount(summer, eur, "6f1c2a4e-0b7d-4c1e-9a55-2f8e3b1c0d4a")) }

        assertTrue(duplicate.isFailure)
        assertEquals(1, connections.bankAccounts().size)
    }
}

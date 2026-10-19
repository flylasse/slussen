package slussen.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.testfixtures.UseCase
import java.io.File

/**
 * Which table lives in which database file, read from the exported Room schemas (ADR-002 §1, §7): only `slussen.db` is
 * backed up and transferred (ADR-002 §5, [BackupRulesTest]), so the tables in it are kept across a reinstall with restore
 * and the tables of `device.db` are not.
 */
class StoredPartsTest {
    /** The tables of the newest committed schema version of [database] (ADR-002 §7). */
    private fun tables(database: String): Set<String> {
        val versions = File("schemas/slussen.persistence.$database").listFiles().orEmpty().mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
        val schema = File("schemas/slussen.persistence.$database/${versions.max()}.json").readText()
        return Regex(""""tableName"\s*:\s*"([a-z_]+)"""").findAll(schema).map { it.groupValues[1] }.toSet()
    }

    private val main by lazy { tables("main.SlussenDatabase") }
    private val device by lazy { tables("device.DeviceDatabase") }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-007"], requirements = ["NFR-010", "NFR-017"])
    @DisplayName("BR-007: the chosen Budgets and each Bank Account with its identifier, name, Start Date and Account Mapping are in the backed-up slussen.db")
    fun connectionDataIsDecisionCritical() {
        assertTrue(main.containsAll(listOf("budget", "budget_account", "bank_account", "account_mapping", "bank")), "$main")
        assertTrue(device.none { it in setOf("budget", "bank_account", "account_mapping") })
    }

    @Test
    @UseCase(id = "UC-013", businessRules = ["BR-007", "BR-011", "BR-014"], requirements = ["NFR-017"])
    @DisplayName("BR-007, BR-011: the Left-Out Bank Accounts and the Manually Kept Accounts are in the backed-up slussen.db, kept like the Bank Accounts, not in device.db")
    fun leftOutAndManuallyKeptAreKeptWithTheBankAccounts() {
        assertTrue("left_out_bank_account" in main, "$main")
        assertTrue("left_out_bank_account" !in device)
        val budgetAccount = File("schemas/slussen.persistence.main.SlussenDatabase/2.json").readText()
            .substringAfter("\"tableName\": \"budget_account\"").substringBefore("\"tableName\"")
        assertTrue("`manually_kept` INTEGER NOT NULL" in budgetAccount, budgetAccount)
    }

    @Test
    @UseCase(id = "UC-014", businessRules = ["BR-003", "BR-004"], requirements = ["NFR-009", "NFR-021"])
    @DisplayName("BR-003, BR-004: service credentials and Offered Bank Accounts are only in device.db, which is never backed up or restored")
    fun credentialsAndOfferedBankAccountsStayOnTheDevice() {
        assertEquals(setOf("service_credential", "offered_bank_account"), device)
        assertTrue("service_credential" !in main && "offered_bank_account" !in main)
    }
}

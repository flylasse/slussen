package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.BudgetAccountToStore
import slussen.core.port.BudgetReadToStore
import slussen.core.port.BudgetToServe
import slussen.core.port.NumberSetting
import slussen.core.port.OperatorSettings
import slussen.core.port.SettingChange
import slussen.core.port.SettingFollowUp
import slussen.core.port.SettingValue
import slussen.core.port.SwitchSetting
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Amount
import slussen.pluginapi.value.CurrencyCode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * O-13 `SettingsStore.*` of Adjust Settings (UC-010) in `slussen.db` on a device or emulator (ADR-002 §3): one Operator
 * Setting in one transaction with `state_version` raised and what follows it, the Comparison marker (step 5, ADR-007
 * §3a) or `period_read_owed` on the existing row of each served Budget with a cursor (UC-003 BR-016), and every value
 * kept after a restart (BR-006). Invented data only (C-005).
 */
@DisplayName("UC-010: Adjust Settings, on the device")
class UC010AdjustSettingsPersistenceIT {
    private val now = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val usd = CurrencyCode("USD")

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    private suspend fun SlussenStorage.stateVersion(): Long = checkNotNull(main.coordinator().stateVersion())

    private fun budget(reference: String, name: String) = BudgetToServe(reference, name, usd, listOf(BudgetAccountToStore("ba-$reference", "Checking $name", Amount(0))))

    /** Household and Care Fund read once (each has a cursor), Summer House served and not read, Old Fund read once and then no longer served. */
    private suspend fun budgets(storage: SlussenStorage): Map<String, Long> {
        val all = listOf(budget("household", "Household"), budget("care", "Care Fund"), budget("summer", "Summer House"), budget("old", "Old Fund"))
        storage.connections.serveBudgets(all, all.map { it.budgetAppReference }.toSet(), now)
        val ids = storage.connections.budgets().associate { it.name to it.id }
        listOf("Household", "Care Fund", "Old Fund").forEach { name ->
            storage.comparisons.applyChangeSet(BudgetReadToStore(ids.getValue(name), null, emptyList(), ChangeCursor("c-$name"), emptyMap(), now))
        }
        storage.comparisons.applyComparison(ids.values.toSet()) { slussen.core.port.ComparisonConclusions(statuses = emptyMap()) }
        storage.connections.serveBudgets(all, setOf("household", "care", "summer"), now)
        return ids
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-002", "BR-006"])
    @DisplayName("Steps 4-5: a longer file upload window is stored with state_version raised and period_read_owed set on the existing row of every served Budget with a cursor, none for a Budget not read or not served, no Comparison marker, and kept after a restart")
    fun aLongerReadPeriodOwesTheReadInTheSameTransaction() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val ids = budgets(storage)
        val version = storage.stateVersion()

        storage.settings.store(SettingChange(SettingValue.Number(NumberSetting.FileUploadWindow, 120), SettingFollowUp.PeriodReadOwed))
        storage.close()
        val restarted = SlussenStorage.open(DeviceFiles.context, clock)

        assertEquals(120, restarted.settings.current().fileUploadWindowDays)
        assertEquals(OperatorSettings.DEFAULT.copy(fileUploadWindowDays = 120), restarted.settings.current())
        assertEquals(version + 1, restarted.stateVersion())
        assertTrue(restarted.comparisons.periodReadOwed(ids.getValue("Household")))
        assertTrue(restarted.comparisons.periodReadOwed(ids.getValue("Care Fund")))
        assertFalse(restarted.comparisons.periodReadOwed(ids.getValue("Summer House")))
        assertNull(restarted.comparisons.changeCursorOf(ids.getValue("Summer House")))
        assertFalse(restarted.comparisons.periodReadOwed(ids.getValue("Old Fund")))
        assertFalse(restarted.comparisons.comparisonPending())
        restarted.close()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "Main Success Scenario", businessRules = ["BR-006"])
    @DisplayName("Step 5: a longer Upload Window within the read period is stored with the Comparison marker in the same transaction and owes no read; a lower setting clears no read owed")
    fun aStatusSettingSetsTheMarker() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val ids = budgets(storage)
        storage.settings.store(SettingChange(SettingValue.Number(NumberSetting.FileUploadWindow, 120), SettingFollowUp.PeriodReadOwed))

        storage.settings.store(SettingChange(SettingValue.Number(NumberSetting.UploadWindow, 10), SettingFollowUp.StatusesAgain))
        storage.settings.store(SettingChange(SettingValue.Number(NumberSetting.FileUploadWindow, 60), SettingFollowUp.StatusesAgain))

        assertEquals(10, storage.settings.current().uploadWindowDays)
        assertTrue(storage.comparisons.comparisonPending())
        assertTrue(storage.comparisons.periodReadOwed(ids.getValue("Household")))
        storage.close()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A5: Switch On Detailed Logging", businessRules = ["BR-006"])
    @DisplayName("A5, A9, BR-006: Detailed Logging and Upload as Cleared are stored with state_version raised and nothing following them, and kept after a restart")
    fun switchesAreStoredAndKept() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        val version = storage.stateVersion()

        storage.settings.store(SettingChange(SettingValue.Switch(SwitchSetting.DetailedLogging, true), SettingFollowUp.None))
        storage.settings.store(SettingChange(SettingValue.Switch(SwitchSetting.UploadAsCleared, false), SettingFollowUp.None))
        storage.close()
        val restarted = SlussenStorage.open(DeviceFiles.context, clock)

        assertEquals(OperatorSettings.DEFAULT.copy(detailedLogging = true, uploadAsCleared = false), restarted.settings.current())
        assertEquals(version + 2, restarted.stateVersion())
        assertFalse(restarted.comparisons.comparisonPending())
        restarted.close()
    }
}

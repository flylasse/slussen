package slussen.persistence

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.DescriptionListChange
import slussen.core.port.TransferDescriptionKind
import slussen.core.settings.DescriptionRules
import slussen.pluginapi.testfixtures.UseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * O-13 `SettingsStore.*` for the description lists of Adjust Settings (UC-010 A7, A8) in `slussen.db` on a device or
 * emulator (ADR-002 §3): every `transfer_description` of one `kind` replaced in one transaction with `state_version`
 * raised and the Comparison marker set (step 5, BR-009, ADR-007 §3a), the other list left as it is, and the lists kept
 * after a restart (BR-006). Invented data only (C-005).
 */
@DisplayName("UC-010: Adjust Settings, the description lists on the device")
class UC010DescriptionListsPersistenceIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    private fun SlussenStorage.close() {
        main.close(); device.close(); journal.close()
    }

    private suspend fun SlussenStorage.stateVersion(): Long = checkNotNull(main.coordinator().stateVersion())

    private suspend fun SlussenStorage.texts(kind: TransferDescriptionKind): List<String> =
        settings.transferDescriptions().filter { it.kind == kind }.map { it.text }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-006", "BR-007", "BR-009"])
    @DisplayName("A7 step 2: a new slussen.db holds the default lists; a changed Card Payment list is stored in its order with state_version raised once and the Comparison marker set, the Bank Transfer list stays, and both are kept after a restart")
    fun aChangedListIsStoredWithTheMarkerInOneTransaction() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)
        assertEquals(DescriptionRules.defaults(TransferDescriptionKind.CardPayment), storage.texts(TransferDescriptionKind.CardPayment))
        assertFalse(storage.comparisons.comparisonPending())
        val version = storage.stateVersion()
        val changed = listOf("PINECREST PMT", "AUTOPAY", "VESTA * BETALNING")

        storage.settings.store(DescriptionListChange(TransferDescriptionKind.CardPayment, changed))
        storage.close()
        val restarted = SlussenStorage.open(DeviceFiles.context, clock)

        assertEquals(changed, restarted.texts(TransferDescriptionKind.CardPayment))
        assertEquals(DescriptionRules.defaults(TransferDescriptionKind.BankTransfer), restarted.texts(TransferDescriptionKind.BankTransfer))
        assertEquals(version + 1, restarted.stateVersion())
        assertTrue(restarted.comparisons.comparisonPending())
        restarted.close()
    }

    @Test
    @UseCase(id = "UC-010", scenario = "A7: Edit a Description List", businessRules = ["BR-008"])
    @DisplayName("BR-008: a list may hold no entry: an empty Bank Transfer list is stored, and the Card Payment list stays")
    fun aListMayBeEmpty() = runBlocking {
        val storage = SlussenStorage.open(DeviceFiles.context, clock)

        storage.settings.store(DescriptionListChange(TransferDescriptionKind.BankTransfer, emptyList()))

        assertEquals(emptyList<String>(), storage.texts(TransferDescriptionKind.BankTransfer))
        assertEquals(DescriptionRules.defaults(TransferDescriptionKind.CardPayment), storage.texts(TransferDescriptionKind.CardPayment))
        storage.close()
    }
}

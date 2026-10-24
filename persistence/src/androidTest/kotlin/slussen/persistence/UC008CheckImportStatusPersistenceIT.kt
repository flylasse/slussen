package slussen.persistence

import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.FirstUploadMatchingCause
import slussen.core.port.StartKind
import slussen.core.port.StoredPart
import slussen.persistence.start.SqliteDatabaseCheck
import slussen.pluginapi.testfixtures.UseCase
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * The start sequence (ADR-002 §6), the platform SQLite check of the last good copy (ADR-002 §8) and First Upload Matching
 * of a new installation (E-08) on a device or emulator.
 */
@DisplayName("UC-008: Check Import Status, on the device")
class UC008CheckImportStatusPersistenceIT {
    private val clock = Clock.fixed(Instant.parse("2026-10-15T12:00:00Z"), ZoneOffset.UTC)

    @BeforeEach
    fun fresh() = DeviceFiles.wipe()

    @Test
    @UseCase(id = "UC-008", scenario = "A8: First Upload Matching Open", businessRules = ["BR-009"])
    @DisplayName("E-08: a new installation opens a First Upload Matching with cause New Installation; the next start opens none")
    fun newInstallationOpensOneFirstUploadMatching() = runBlocking {
        val first = SlussenStorage.open(DeviceFiles.context, clock)
        assertEquals(StartKind.NewInstallation, first.startState.startKind)
        assertEquals(listOf(FirstUploadMatchingCause.NewInstallation), first.firstUploadMatching.openChecks().map { it.cause })
        assertEquals(slussen.core.port.OperatorSettings.DEFAULT, first.settings.current())
        first.main.close(); first.device.close(); first.journal.close()

        val next = SlussenStorage.open(DeviceFiles.context, clock)

        assertEquals(StartKind.Normal, next.startState.startKind)
        assertEquals(1, next.firstUploadMatching.openChecks().size)
        assertTrue(java.io.File(DeviceFiles.context.noBackupFilesDir, "slussen.lastgood.db").exists())
        next.main.close(); next.device.close(); next.journal.close()
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", requirements = ["NFR-005"])
    @DisplayName("A1: slussen.db with planted garbage bytes or an unknown Values text fails the check; a sound one passes")
    fun sqliteCheckFindsDamage() {
        val check = SqliteDatabaseCheck(SlussenStorage.MAIN_VALUES)
        val directory = DeviceFiles.context.cacheDir
        val sound = java.io.File(directory, "sound.db").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(sound, null).use { db ->
            db.execSQL("CREATE TABLE recovery_check (id INTEGER PRIMARY KEY, cause TEXT NOT NULL)")
            db.execSQL("INSERT INTO recovery_check (cause) VALUES ('New Installation')")
        }
        val unknownText = java.io.File(directory, "unknown.db").apply { delete() }
        sound.copyTo(unknownText)
        SQLiteDatabase.openOrCreateDatabase(unknownText, null).use { it.execSQL("UPDATE recovery_check SET cause = 'Mystery'") }
        val garbage = java.io.File(directory, "garbage.db").apply { delete() }
        val bytes = sound.readBytes()
        for (i in 100 until bytes.size) bytes[i] = 0x5A
        garbage.writeBytes(bytes)

        assertTrue(check.isReadable(sound))
        assertFalse(check.isReadable(unknownText))
        assertFalse(check.isReadable(garbage))
        assertTrue(garbage.exists(), "the unreadable original is never deleted")
    }

    @Test
    @UseCase(id = "UC-008", scenario = "A1: Stored Data Cannot Be Read", businessRules = ["BR-009"], requirements = ["NFR-005"])
    @DisplayName("A1: a damaged slussen.db is set aside, the last good copy is used, the part is named and a First Upload Matching opens")
    fun damagedMainDatabaseStartsFromTheLastGoodCopy() = runBlocking {
        SlussenStorage.open(DeviceFiles.context, clock).also { it.main.close(); it.device.close(); it.journal.close() }
        SlussenStorage.open(DeviceFiles.context, clock).also { it.main.close(); it.device.close(); it.journal.close() }
        val mainFile = SlussenDatabases.mainFile(DeviceFiles.context)
        val bytes = mainFile.readBytes()
        for (i in 100 until bytes.size) bytes[i] = 0x5A
        mainFile.writeBytes(bytes)

        val storage = SlussenStorage.open(DeviceFiles.context, clock)

        assertEquals(StartKind.LastGoodCopy, storage.startState.startKind)
        assertEquals(StoredPart.TransactionsDecisionsAndSettings, storage.startState.unreadableParts.single().part)
        assertTrue(storage.firstUploadMatching.openChecks().any { it.cause == FirstUploadMatchingCause.LastGoodCopy })
        assertTrue(mainFile.parentFile!!.listFiles()!!.any { it.name.startsWith("slussen.db.unreadable-") })
        storage.main.close(); storage.device.close(); storage.journal.close()
    }
}

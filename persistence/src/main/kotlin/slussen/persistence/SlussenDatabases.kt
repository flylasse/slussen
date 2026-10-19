package slussen.persistence

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import slussen.persistence.device.DeviceDatabase
import slussen.persistence.journal.JournalDatabase
import slussen.persistence.main.SlussenDatabase
import slussen.persistence.main.SlussenMigrations
import java.io.File

/**
 * The three Room databases of ADR-002 §1, split by backup policy, with their files and durability settings:
 * `slussen.db` in the app's database directory (backed up, and nothing else is), `device.db` and `journal.db` in
 * `noBackupFilesDir` (never backed up or transferred).
 */
object SlussenDatabases {
    const val MAIN_FILE = "slussen.db"
    const val DEVICE_FILE = "device.db"
    const val JOURNAL_FILE = "journal.db"

    fun mainFile(context: Context): File = context.getDatabasePath(MAIN_FILE)

    fun deviceFile(context: Context): File = File(context.noBackupFilesDir, DEVICE_FILE)

    fun journalFile(context: Context): File = File(context.noBackupFilesDir, JOURNAL_FILE)

    /**
     * `JournalMode.TRUNCATE` and `PRAGMA synchronous = FULL` (ADR-002 §4), with the Migration from every committed schema
     * version, so an installed `slussen.db` is upgraded and never cleared (ADR-002 §7, NFR-017).
     */
    fun openMain(context: Context): SlussenDatabase =
        Room.databaseBuilder(context, SlussenDatabase::class.java, mainFile(context).path)
            // UC-009 BR-013: The Left-Out Bank Accounts and the Manually Kept Accounts, like all stored data, are kept on an upgrade.
            .addMigrations(*SlussenMigrations.ALL)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(SynchronousFull)
            .build()

    /** `JournalMode.TRUNCATE` and `PRAGMA synchronous = FULL` (ADR-002 §4). */
    fun openDevice(context: Context): DeviceDatabase =
        Room.databaseBuilder(context, DeviceDatabase::class.java, deviceFile(context).path)
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .addCallback(SynchronousFull)
            .build()

    /** `auto_vacuum = INCREMENTAL`, so that the oldest entries' space can be returned (ADR-002 §9). */
    fun openJournal(context: Context): JournalDatabase =
        Room.databaseBuilder(context, JournalDatabase::class.java, journalFile(context).path)
            .addCallback(IncrementalAutoVacuum)
            .build()

    private object SynchronousFull : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            db.query("PRAGMA synchronous = FULL").close()
        }
    }

    private object IncrementalAutoVacuum : RoomDatabase.Callback() {
        override fun onOpen(db: SupportSQLiteDatabase) {
            val mode = db.query("PRAGMA auto_vacuum").use { if (it.moveToFirst()) it.getInt(0) else 0 }
            if (mode != INCREMENTAL) {
                db.query("PRAGMA auto_vacuum = INCREMENTAL").close()
                db.execSQL("VACUUM")
            }
        }

        private const val INCREMENTAL = 2
    }
}

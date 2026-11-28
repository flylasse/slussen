package slussen.persistence.journal

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import slussen.persistence.StorageConverters
import slussen.persistence.UnreadableStoredData
import java.time.Instant

/** `journal.db` (ADR-002 §1): the Decision Journal, never backed up or transferred. */
@Database(entities = [DecisionJournalEntryEntity::class], version = 1, exportSchema = true)
@TypeConverters(StorageConverters::class, JournalConverters::class)
abstract class JournalDatabase : RoomDatabase() {
    abstract fun entries(): DecisionJournalDao
}

/**
 * Writes Decision Journal entries, removes them by age or all at once, and reads them for the Journal Export only (ADR-002
 * §9, Konsist K-07). Every method blocks: it is called only on the journal's own single thread
 * ([slussen.persistence.RoomDecisionJournal]), so that writes, trimming and clearing run one after the other in the order
 * they were handed over.
 */
@Dao
abstract class DecisionJournalDao {
    @Insert
    abstract fun insert(entry: DecisionJournalEntryEntity): Long

    @Query("SELECT COUNT(*) FROM decision_journal_entry")
    abstract fun count(): Int

    /** The `id` of the oldest and newest entry, or null when there is none. Ids only grow (`AUTOINCREMENT`), also after a clearing. */
    @Query("SELECT MIN(id) FROM decision_journal_entry")
    abstract fun oldestId(): Long?

    @Query("SELECT MAX(id) FROM decision_journal_entry")
    abstract fun newestId(): Long?

    /** The oldest entry (lowest `id`), or null when there is none. */
    @Query("SELECT * FROM decision_journal_entry ORDER BY id LIMIT 1")
    abstract fun oldest(): DecisionJournalEntryEntity?

    /**
     * At most [limit] entries with an `id` above [afterId] up to and including [upToId], oldest first: one page of a Journal
     * Export (UC-011 BR-007), read in one short statement on the primary key.
     */
    @Query("SELECT * FROM decision_journal_entry WHERE id > :afterId AND id <= :upToId ORDER BY id LIMIT :limit")
    abstract fun page(afterId: Long, upToId: Long, limit: Int): List<DecisionJournalEntryEntity>

    /** How many entries have an `id` up to and including [upToId]. */
    @Query("SELECT COUNT(*) FROM decision_journal_entry WHERE id <= :upToId")
    abstract fun countUpTo(upToId: Long): Int

    /** Removes the [count] oldest entries (lowest `id`). */
    @Query("DELETE FROM decision_journal_entry WHERE id IN (SELECT id FROM decision_journal_entry ORDER BY id LIMIT :count)")
    abstract fun deleteOldest(count: Int): Int

    /**
     * Removes every entry. `sqlite_sequence` keeps the highest `id` given, so an entry recorded after a clearing never
     * takes the `id` of one recorded before it, and a read bounded by an `id` stays bounded (UC-011 BR-007).
     */
    @Query("DELETE FROM decision_journal_entry")
    abstract fun deleteAll(): Int
}

/** `DECISION_JOURNAL_ENTRY` (entity model). */
@Entity(tableName = "decision_journal_entry")
data class DecisionJournalEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "recorded_at") val recordedAt: Instant,
    @ColumnInfo(name = "event_type") val eventType: JournalEventType,
    @ColumnInfo(name = "import_identifier") val importIdentifier: String?,
    @ColumnInfo(name = "bank_account_name") val bankAccountName: String?,
    @ColumnInfo(name = "details") val details: String,
)

/** The `Values:` of `DECISION_JOURNAL_ENTRY.event_type`, each stored as exactly its text (ADR-002 §2). */
enum class JournalEventType(val text: String) {
    SessionStart("Session Start"),
    ConfigurationChange("Configuration Change"),
    TransactionSeen("Transaction Seen"),
    TransactionChanged("Transaction Changed"),
    StatusChanged("Status Changed"),
    OperatorDecision("Operator Decision"),
    UploadPlan("Upload Plan"),
    Upload("Upload"),
    BudgetAppOutcome("Budget App Outcome"),
    AccountMappingChange("Account Mapping Change"),
    JournalControl("Journal Control"),
}

/** One `TypeConverter` per value set; an unknown text read back is unreadable data (ADR-002 §2, §8). */
class JournalConverters {
    @TypeConverter
    fun eventTypeToText(value: JournalEventType): String = value.text

    @TypeConverter
    fun textToEventType(text: String): JournalEventType =
        JournalEventType.entries.firstOrNull { it.text == text } ?: throw UnreadableStoredData("journal.db")
}

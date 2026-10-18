package slussen.persistence.journal

import androidx.room.ColumnInfo
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
abstract class JournalDatabase : RoomDatabase()

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

package slussen.persistence

import androidx.room.TypeConverter
import java.time.Instant

/** `DateTime` is stored as INTEGER milliseconds since 1970-01-01 UTC (ADR-002 §2). */
class StorageConverters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)
}

/**
 * Stored data that cannot be read: an unknown text in a `Values:` column or a failed foreign key (ADR-002 §8 step 4).
 * The app does not continue on such data.
 */
class UnreadableStoredData(val part: String) : RuntimeException("Unreadable stored data in $part")

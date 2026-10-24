package slussen.persistence

import androidx.room.TypeConverter
import java.time.Instant
import java.time.LocalDate

/**
 * `DateTime` is stored as INTEGER milliseconds since 1970-01-01 UTC, `Date` as INTEGER days since 1970-01-01
 * (ADR-002 §2).
 */
class StorageConverters {
    @TypeConverter
    fun instantToMillis(value: Instant?): Long? = value?.toEpochMilli()

    @TypeConverter
    fun millisToInstant(value: Long?): Instant? = value?.let(Instant::ofEpochMilli)

    @TypeConverter
    fun dateToDays(value: LocalDate?): Long? = value?.toEpochDay()

    @TypeConverter
    fun daysToDate(value: Long?): LocalDate? = value?.let(LocalDate::ofEpochDay)
}

/**
 * Stored data that cannot be read: an unknown text in a `Values:` column or a failed foreign key (ADR-002 §8 step 4).
 * The app does not continue on such data.
 */
class UnreadableStoredData(val part: String) : RuntimeException("Unreadable stored data in $part")

/** Reads a `Values:` text back into its enum constant; an unknown text is unreadable data (ADR-002 §2, §8). */
inline fun <reified T : Enum<T>> valueOfText(text: String, part: String, textOf: (T) -> String): T =
    enumValues<T>().firstOrNull { textOf(it) == text } ?: throw UnreadableStoredData(part)

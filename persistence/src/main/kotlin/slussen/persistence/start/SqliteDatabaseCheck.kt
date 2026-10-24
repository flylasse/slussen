package slussen.persistence.start

import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import java.io.File

/**
 * The check of ADR-002 §8 step 1 on the platform's SQLite: open the file (which rolls back a leftover rollback journal),
 * `PRAGMA quick_check`, `PRAGMA foreign_key_check`, and a logical check that every `Values:` column holds only its
 * allowed texts. SQLite's default corruption handling, which deletes the file, is replaced by one that keeps it.
 */
class SqliteDatabaseCheck(private val valueColumns: List<ValueColumn>) : DatabaseCheck {
    /** One `Values:` column of the entity model with its allowed texts. */
    data class ValueColumn(val table: String, val column: String, val allowed: List<String>)

    override fun isReadable(file: File): Boolean {
        val keepTheFile = DatabaseErrorHandler { }
        return try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE, keepTheFile).use { db ->
                quickCheck(db) && noForeignKeyViolation(db) && valuesAllowed(db)
            }
        } catch (e: SQLiteException) {
            false
        }
    }

    private fun quickCheck(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA quick_check", null).use { it.moveToFirst() && it.getString(0) == "ok" && !it.moveToNext() }

    private fun noForeignKeyViolation(db: SQLiteDatabase): Boolean =
        db.rawQuery("PRAGMA foreign_key_check", null).use { it.count == 0 }

    private fun valuesAllowed(db: SQLiteDatabase): Boolean {
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
        }
        return valueColumns.filter { it.table in tables }.all { column ->
            val placeholders = column.allowed.joinToString(",") { "?" }
            val sql = "SELECT COUNT(*) FROM ${column.table} WHERE ${column.column} NOT IN ($placeholders)"
            db.rawQuery(sql, column.allowed.toTypedArray()).use { it.moveToFirst() && it.getInt(0) == 0 }
        }
    }
}

package slussen.persistence

import androidx.room.RoomDatabase
import androidx.room.RoomOpenDelegate
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import slussen.persistence.device.DeviceDatabase
import slussen.persistence.journal.JournalDatabase
import slussen.persistence.main.SlussenDatabase
import java.io.File
import kotlin.reflect.KClass

/**
 * The schema files of the three Room databases (ADR-002 §1, §7): the committed ones under `persistence/schemas/`, which
 * are final once committed, and the ones Room generates into the build directory (S-39). The build script passes both
 * directories to every unit test task.
 */
internal object RoomSchemas {
    /** Every Room database of `:persistence`; a database the build generates a schema for and this list misses fails the check. */
    val databases: List<KClass<out RoomDatabase>> = listOf(SlussenDatabase::class, DeviceDatabase::class, JournalDatabase::class)

    val generated: File = directory("slussen.schemas.generated")

    val committed: File = directory("slussen.schemas.committed")

    private fun directory(property: String): File =
        File(checkNotNull(System.getProperty(property)) { "The build script sets $property for every unit test task" })

    /** The version and identity hash the compiled database carries: what Room checks on the device when it opens it. */
    data class Current(val version: Int, val identityHash: String)

    fun currentOf(database: KClass<out RoomDatabase>): Current {
        val delegate = openDelegateOf(database)
        return Current(delegate.version, delegate.identityHash)
    }

    /** The generated `RoomOpenDelegate` of [database]: its tables, version, identity hash and schema validation. */
    fun openDelegateOf(database: KClass<out RoomDatabase>): RoomOpenDelegate {
        val implementation = Class.forName("${database.java.name}_Impl").getDeclaredConstructor().newInstance()
        val create = implementation.javaClass.getDeclaredMethod("createOpenDelegate").apply { isAccessible = true }
        return create.invoke(implementation) as RoomOpenDelegate
    }

    fun file(root: File, database: KClass<out RoomDatabase>, version: Int): File = File(root, "${database.java.name}/$version.json")

    fun committedVersions(database: KClass<out RoomDatabase>): List<Int> =
        File(committed, database.java.name).listFiles().orEmpty()
            .mapNotNull { it.name.removeSuffix(".json").takeIf { _ -> it.name.endsWith(".json") }?.toIntOrNull() }
            .sorted()

    fun identityHashOf(schema: File): String =
        checkNotNull(Regex(""""identityHash"\s*:\s*"([0-9a-f]+)"""").find(schema.readText())) { "$schema has no identityHash" }
            .groupValues[1]

    fun versionOf(schema: File): Int =
        checkNotNull(Regex(""""version"\s*:\s*(\d+)""").find(schema.readText())) { "$schema has no version" }.groupValues[1].toInt()

    /**
     * Creates the tables, indices and Room's identity row of [version] of [database] in [into], exactly as its committed
     * schema file states them: what a build that held that version created on the device.
     */
    fun create(database: KClass<out RoomDatabase>, version: Int, into: SQLiteConnection) {
        val schema = file(committed, database, version).readText()
        // Runs of ordinary characters are taken whole (possessive): one step per character overflowed the stack on long SQL texts.
        val token = Regex(""""(tableName|createSql)"\s*:\s*"((?:[^"\\]++|\\.)*+)"""")
        var table = ""
        token.findAll(schema.substringBefore("\"setupQueries\"")).forEach { match ->
            val value = match.groupValues[2].replace("\\\"", "\"")
            if (match.groupValues[1] == "tableName") table = value else into.execSQL(value.replace("\${TABLE_NAME}", table))
        }
        val setup = schema.substringAfter("\"setupQueries\"").substringAfter("[").substringBefore("]")
        Regex(""""((?:[^"\\]++|\\.)*+)"""").findAll(setup).forEach { into.execSQL(it.groupValues[1].replace("\\\"", "\"")) }
    }

    /** The `createSql` texts of [table] in [version] of [database], the table first and then its indices, with the table named [named]. */
    fun createSqlOf(database: KClass<out RoomDatabase>, version: Int, table: String, named: String = table): List<String> {
        val entity = file(committed, database, version).readText()
            .substringAfter("\"tableName\": \"$table\"").substringBefore("\"tableName\"")
        return Regex(""""createSql"\s*:\s*"([^"]*)"""").findAll(entity).map { it.groupValues[1].replace("\${TABLE_NAME}", named) }.toList()
    }

    /** Every row [sql] gives on [connection], each value as its text. */
    fun rows(connection: SQLiteConnection, sql: String): List<List<String?>> {
        val rows = mutableListOf<List<String?>>()
        connection.prepare(sql).use { statement ->
            while (statement.step()) {
                rows += (0 until statement.getColumnCount()).map { if (statement.isNull(it)) null else statement.getText(it) }
            }
        }
        return rows
    }
}

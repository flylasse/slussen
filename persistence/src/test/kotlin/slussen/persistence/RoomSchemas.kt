package slussen.persistence

import androidx.room.RoomDatabase
import androidx.room.RoomOpenDelegate
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
}

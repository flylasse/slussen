package slussen.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The schema check of ADR-002 §7 (S-39), a JVM test of `:persistence` run by `gradlew check` (ADR-001 §6.4): a
 * committed schema version is final, so the schema the build generates for the current version of each database must
 * be the committed file of that version, compared by Room's identity hash. An entity changed without a new version (an
 * in-place change) and a new version whose schema file is not committed both fail, naming the database and the version.
 */
class SchemaVersionCheckTest {
    @Test
    @DisplayName("NFR-017: the schema each database generates for its current version is the committed file of that version, by identity hash (ADR-002 §7)")
    fun generatedSchemaOfTheCurrentVersionIsTheCommittedOne() {
        val problems = RoomSchemas.databases.mapNotNull { database ->
            val name = database.java.name
            val current = RoomSchemas.currentOf(database)
            val generated = RoomSchemas.file(RoomSchemas.generated, database, current.version)
            val committed = RoomSchemas.file(RoomSchemas.committed, database, current.version)
            when {
                !generated.isFile -> "$name version ${current.version}: Room generated no schema file at $generated"
                RoomSchemas.identityHashOf(generated) != current.identityHash ->
                    "$name version ${current.version}: the generated schema file is not the one the build compiled; build again"
                !committed.isFile ->
                    "$name version ${current.version}: no committed schema file; commit $generated as " +
                        "persistence/schemas/$name/${current.version}.json together with its Migration (ADR-002 §7)"
                RoomSchemas.identityHashOf(committed) != current.identityHash ->
                    "$name version ${current.version}: the schema the build generates (identity hash ${current.identityHash}) " +
                        "differs from the committed file (identity hash ${RoomSchemas.identityHashOf(committed)}): a committed " +
                        "schema version is final; raise the version to ${current.version + 1} and add a Migration (ADR-002 §7)"
                else -> null
            }
        }
        assertEquals(emptyList<String>(), problems, problems.joinToString("\n"))
    }

    @Test
    @DisplayName("NFR-017: every database the build generates a schema for is checked, and no committed version is newer than the build's (ADR-002 §7)")
    fun everyDatabaseIsChecked() {
        val checked = RoomSchemas.databases.map { it.java.name }.toSet()
        val generatedDatabases = RoomSchemas.generated.listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.toSet()
        assertTrue(checked.containsAll(generatedDatabases), "Databases not in the schema check: ${generatedDatabases - checked}")
        RoomSchemas.databases.forEach { database ->
            val current = RoomSchemas.currentOf(database).version
            val committed = RoomSchemas.committedVersions(database)
            assertEquals((1..current).toList(), committed, "${database.java.name}: committed schema versions 1 to $current, none skipped or newer")
            committed.forEach { version ->
                assertEquals(version, RoomSchemas.versionOf(RoomSchemas.file(RoomSchemas.committed, database, version)))
            }
        }
    }
}

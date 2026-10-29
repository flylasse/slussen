package slussen.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.persistence.main.SlussenDatabase
import java.security.MessageDigest

/**
 * The schema check of ADR-002 §7 (S-39), a JVM test of `:persistence` run by `gradlew check` (ADR-001 §6.4): a
 * committed schema version is final, so the schema the build generates for the current version of each database must
 * be the committed file of that version, compared by Room's identity hash. An entity changed without a new version (an
 * in-place change) and a new version whose schema file is not committed both fail, naming the database and the version.
 * Every committed version older than the current one is pinned by the digest of its file, so that a change to an older
 * version fails as well.
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
    @DisplayName("NFR-017: every committed schema version older than the current one is final: its file is the one committed, by digest, and a version without a pin fails (ADR-002 §7)")
    fun olderCommittedVersionsAreFinal() {
        val problems = RoomSchemas.databases.flatMap { database ->
            val current = RoomSchemas.currentOf(database).version
            RoomSchemas.committedVersions(database).filter { it < current }.mapNotNull { version ->
                changeOfFinalVersion(database.java.name, version, RoomSchemas.file(RoomSchemas.committed, database, version).readText())
            }
        }
        assertEquals(emptyList<String>(), problems, problems.joinToString("\n"))
    }

    @Test
    @DisplayName("NFR-017: a planted change to committed version 3 of slussen.db, and a version without a pin, are refused (ADR-002 §7, ADR-001 §6.6)")
    fun aChangedOlderVersionIsRefused() {
        val name = "slussen.persistence.main.SlussenDatabase"
        val committed = RoomSchemas.file(RoomSchemas.committed, SlussenDatabase::class, 3).readText()
        val planted = committed.replace("`currency` TEXT NOT NULL, `reported_at`", "`currency` TEXT NOT NULL, `planted` TEXT, `reported_at`")

        assertEquals(null, changeOfFinalVersion(name, 3, committed.replace("\n", "\r\n")))
        assertTrue(planted != committed)
        assertTrue(changeOfFinalVersion(name, 3, planted)!!.contains("$name version 3"))
        assertTrue(changeOfFinalVersion(name, 99, committed)!!.contains("no pin"))
    }

    /** Why [text], the committed file of [version] of [database], is not the final one, or null when it is. */
    private fun changeOfFinalVersion(database: String, version: Int, text: String): String? {
        val pin = FINAL_VERSIONS[database]?.get(version)
            ?: return "$database version $version: no pin; add the digest of its committed file to FINAL_VERSIONS when a newer version is added (ADR-002 §7)"
        val digest = MessageDigest.getInstance("SHA-256").digest(text.replace("\r\n", "\n").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return if (digest == pin) {
            null
        } else {
            "$database version $version: the committed file was changed (digest $digest, pinned $pin): a committed schema version " +
                "is final; restore it and add the change as a new version with a Migration (ADR-002 §7)"
        }
    }

    private companion object {
        /**
         * The SHA-256 of the committed schema file of every version older than the current one, line endings read as LF
         * (`.gitattributes` checks text out with CRLF on Windows), taken from the file as committed: version 1 at the
         * baseline `96712aa`, versions 2, 3 and 4 as committed with their Migrations (ADR-002 §7, S-39).
         */
        val FINAL_VERSIONS: Map<String, Map<Int, String>> = mapOf(
            "slussen.persistence.main.SlussenDatabase" to mapOf(
                1 to "ba23f243badea28c2ee0f5c3c5d4639a16deeea0d4b9cfb70936e5ff9439b53d",
                2 to "4d4103016f183cca23a08edb31f2cb9db38009f3ed05bed19768c00fe78e4f3c",
                3 to "6d66b0b9ec8bc333eda57a05f393213a7e9dc62e37b748ca16fd06057a150e4d",
                4 to "fc49c92ba11a33a377f521f0ca8670b8d4738bd5cd3c95bf024e9fab5852f247",
            ),
        )
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

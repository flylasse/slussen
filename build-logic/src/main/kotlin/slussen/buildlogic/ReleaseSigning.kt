package slussen.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault
import java.io.File

/** The four release signing values of ADR-003 §1, each a Gradle property set outside the repository. */
object ReleaseSigning {
    const val STORE_FILE = "slussenReleaseStoreFile"
    const val STORE_PASSWORD = "slussenReleaseStorePassword"
    const val KEY_ALIAS = "slussenReleaseKeyAlias"
    const val KEY_PASSWORD = "slussenReleaseKeyPassword"

    val propertyNames: List<String> = listOf(STORE_FILE, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD)

    /** Where a property goes; the message names the property and never its value (ADR-003 §2, §5). */
    fun whereItGoes(property: String): String =
        "set it in `~/.gradle/gradle.properties` or as the environment variable `ORG_GRADLE_PROJECT_$property`; " +
            "see the README"

    // UC-012 BR-002: A release build without its signing values fails, naming each missing value and where it goes.
    /**
     * Returns one message per problem with the signing [values] (property name to value, null when unset), empty when
     * a release package may be signed. Messages name properties only, never a value.
     */
    fun problems(values: Map<String, String?>, repositoryRoot: File): List<String> {
        val problems = propertyNames
            .filter { values[it].isNullOrBlank() }
            .map { "Missing `$it`: ${whereItGoes(it)}." }
            .toMutableList()
        val storeFile = values[STORE_FILE]
        if (!storeFile.isNullOrBlank()) {
            val file = File(storeFile)
            when {
                !file.isAbsolute ->
                    problems += "`$STORE_FILE` must be the absolute path of the release keystore, outside the " +
                        "repository: ${whereItGoes(STORE_FILE)}."
                !file.isFile || !file.canRead() ->
                    problems += "`$STORE_FILE` names no readable file: ${whereItGoes(STORE_FILE)}."
                isInside(file, repositoryRoot) ->
                    problems += "`$STORE_FILE` names a file inside the repository working tree; keep the release " +
                        "keystore outside the repository (ADR-003 section 2): ${whereItGoes(STORE_FILE)}."
            }
        }
        return problems
    }

    private fun isInside(file: File, root: File): Boolean {
        val canonicalRoot = root.canonicalFile.toPath()
        return file.canonicalFile.toPath().startsWith(canonicalRoot)
    }
}

/**
 * `verifyReleaseSigning` of ADR-003 §2: a dependency of every packaging task of the `release` variant of the app, and
 * of nothing else. The values are `@Internal`, so Gradle never records them in its task history.
 */
@DisableCachingByDefault(because = "Checks values that must never be stored")
abstract class VerifyReleaseSigningTask : DefaultTask() {
    @get:Internal
    abstract val storeFile: Property<String>

    @get:Internal
    abstract val storePassword: Property<String>

    @get:Internal
    abstract val keyAlias: Property<String>

    @get:Internal
    abstract val keyPassword: Property<String>

    @get:Internal
    abstract val repositoryRoot: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun verify() {
        val values = mapOf(
            ReleaseSigning.STORE_FILE to storeFile.orNull,
            ReleaseSigning.STORE_PASSWORD to storePassword.orNull,
            ReleaseSigning.KEY_ALIAS to keyAlias.orNull,
            ReleaseSigning.KEY_PASSWORD to keyPassword.orNull,
        )
        val problems = ReleaseSigning.problems(values, repositoryRoot.get().asFile)
        if (problems.isNotEmpty()) {
            throw GradleException(
                "A release package needs the four release signing values (ADR-003 section 1, UC-012 A1):\n" +
                    problems.joinToString("\n") { "  - $it" },
            )
        }
    }
}

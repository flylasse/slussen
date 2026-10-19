package slussen.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.pluginapi.testfixtures.UseCase
import java.io.File

/**
 * The repository side of Set Up an Own Build (UC-012): a clone or fork is safe because the repository holds no signing
 * value and no keystore. The ignored, private locations (`build-config/local/`, `scratch/`, `local.properties`) and build
 * output are never read.
 */
@DisplayName("UC-012: Set Up an Own Build, the repository")
class UC012SetUpAnOwnBuildRepositoryTest {
    private val skipped = setOf(".git", ".gradle", "build", "scratch", ".kotlin", ".idea", "local")

    private fun repositoryFiles(): List<File> = Sources.repositoryRoot.walkTopDown()
        .onEnter { it.name !in skipped }
        .filter { it.isFile && it.name != "local.properties" }
        .toList()

    @Test
    @UseCase(id = "UC-012", scenario = "Main Success Scenario", businessRules = ["BR-001", "BR-008"], requirements = ["C-005"])
    @DisplayName("Step 1, BR-008: a clone or fork carries no release signing value and no keystore")
    fun repositoryHoldsNoSigningValue() {
        val assignment = Regex("""(?m)^\s*(slussenRelease(StoreFile|StorePassword|KeyAlias|KeyPassword))\s*[=:]\s*\S""")
        val files = repositoryFiles()
        val assigned = files.filter { it.extension in setOf("properties", "kts", "toml", "env") }
            .filter { assignment.containsMatchIn(it.readText()) }
        val keystores = files.filter { it.extension in setOf("jks", "keystore") }

        assertTrue(files.size > 50, "the walk found the repository")
        assertTrue(assigned.isEmpty(), "signing values assigned in $assigned")
        assertTrue(keystores.isEmpty(), "keystores in the repository: $keystores")
    }
}

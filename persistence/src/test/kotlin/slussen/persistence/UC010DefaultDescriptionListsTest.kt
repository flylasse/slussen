package slussen.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.port.TransferDescriptionKind
import slussen.core.settings.DescriptionRules
import slussen.persistence.main.SlussenMigrations
import slussen.pluginapi.testfixtures.UseCase

/**
 * The default lists of UC-010 BR-007 are written twice: once in the Migration that fills a new `slussen.db`, which never
 * changes once committed (ADR-002 §7), and once in the core, which restores them (A8) and works out which entries the
 * Operator added (S-B-20). Both must be the same list in the same order.
 */
@DisplayName("UC-010: Adjust Settings, the default description lists of a new slussen.db and of the core")
class UC010DefaultDescriptionListsTest {
    @Test
    @UseCase(id = "UC-010", scenario = "A8: Restore a Default Description List", businessRules = ["BR-007"])
    @DisplayName("BR-007, A8: the default lists a restore stores are the ones a new slussen.db starts with, entry by entry and in order")
    fun theCoreRestoresTheListsANewDatabaseStartsWith() {
        val core = TransferDescriptionKind.entries.flatMap { kind -> DescriptionRules.defaults(kind).map { kind.text to it } }

        assertEquals(SlussenMigrations.DEFAULT_TRANSFER_DESCRIPTIONS, core)
    }
}

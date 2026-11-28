package slussen.budgetapp.ynab

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.testfixtures.UseCase

/**
 * E-10 (14) of ADR-005 §7: YNAB's own Bank import writes Import Identifiers that do not begin with Slussen's marker.
 * It needs a Budget with a linked bank, so it runs, read-only, against the Operator's real Budget: it sends one read
 * of changes and nothing else, and prints only the first four characters of each Import Identifier it finds. Tagged
 * `live`, never run by `gradlew check` or an agent. The Operator runs it alone, with the two properties of ADR-003 §4
 * given for this one run for his real Budget, on the command line and nowhere in the repository:
 *
 * `gradlew :budgetapp-ynab:test -PincludeLive -PslussenLiveTestYnabBudgetId=<real Budget> --tests 'slussen.budgetapp.ynab.YnabOwnBankImportBudgetAppLiveTest'`
 */
@Tag("live")
class YnabOwnBankImportBudgetAppLiveTest {
    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-008"], requirements = ["C-009"])
    @DisplayName("E-10 (14): no Import Identifier YNAB's own Bank import wrote begins with Slussen's marker SLS (read only; prints only the first four characters of each)")
    fun ownBankImportIdentifiersDoNotBeginWithTheMarker() {
        val live = YnabLive()
        val imported = live.changes(null, from = live.today.minusDays(90)).transactions
            .filter { it.entry == EntryKind.Imported }.mapNotNull { it.importIdentifier }
        val beginnings = imported.groupingBy { it.take(4) }.eachCount()
        println("E-10 (14): ${imported.size} Import Identifiers in the last 90 days, by their first four characters: $beginnings")
        assertTrue(imported.isNotEmpty(), "no transaction of a Bank import was found in the last 90 days")
        assertTrue(beginnings.keys.none { it.startsWith("SLS") }, "an Import Identifier of another import path begins with Slussen's marker")
    }
}

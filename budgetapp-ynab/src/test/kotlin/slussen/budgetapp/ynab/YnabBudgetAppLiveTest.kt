package slussen.budgetapp.ynab

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ClearResult
import slussen.pluginapi.budgetapp.EntryKind
import slussen.pluginapi.budgetapp.ItemOutcome
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.testfixtures.UseCase
import java.io.File
import java.time.LocalDate
import java.util.Properties

/**
 * The live tests of ADR-005 §7, E-10, against the Operator's throwaway YNAB Budget (C-011): each proves a fact ADR-001
 * §4.2.5 marks as confirmed or not documented. Tagged `live`: `gradlew check` never runs them, and no agent runs them
 * (ADR-004 §2). The Operator runs them on his machine, one at a time or all, with the two properties of ADR-003 §4 set
 * outside the repository:
 *
 * `gradlew :budgetapp-ynab:test -PincludeLive --tests 'slussen.budgetapp.ynab.YnabBudgetAppLiveTest'` (or with
 * `--tests '…YnabBudgetAppLiveTest.<method>'` for one).
 *
 * Each test writes invented transactions, dated some days before today in UTC, into the throwaway Budget only: every
 * test that writes first checks that the Budget's name contains "throwaway"; case (17) needs a second Budget so named. The
 * whole class makes about 140 requests,
 * within YNAB's 200 an hour. Two tests need a step in YNAB's own screens or a second run on another day; each prints
 * what to do.
 */
@Tag("live")
class YnabBudgetAppLiveTest {
    private val live = YnabLive()

    private fun <T> present(value: T?, what: String): T = value ?: fail("E-10: $what")

    private fun created(outcome: ItemOutcome): String = (outcome as? ItemOutcome.Created)?.reference ?: fail("expected Created, was $outcome")

    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"], requirements = ["NFR-015"])
    @DisplayName("E-10 (1): a second create with an Import Identifier the Budget Account holds is refused, alone with 409 and in a list among duplicate_import_ids, and the writer answers AlreadyPresent")
    fun heldImportIdentifierIsRefused() {
        val accounts = live.openAccounts()
        val item = live.item(accounts.first, 1, 1, -12_340)
        created(live.create(item).single())
        assertEquals(409, live.singleCreateStatus(item), "a single create with a held Import Identifier")
        assertEquals(listOf(ItemOutcome.AlreadyPresent(null)), live.create(item))
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-017"])
    @DisplayName("E-10 (2): after the transaction is deleted, a create with the same Import Identifier is still refused (if not, a new installation or Start Over can bring back once a transaction the Operator deleted: UC-006 BR-017, G-44)")
    fun deletedImportIdentifierStaysRefused() {
        val accounts = live.openAccounts()
        val item = live.item(accounts.first, 2, 1, -23_450)
        live.delete(created(live.create(item).single()))
        val again = live.create(item).single()
        if (again is ItemOutcome.Created) live.delete(again.reference)
        assertEquals(ItemOutcome.AlreadyPresent(null), again, "YNAB accepted the Import Identifier of a deleted transaction (G-44 (3))")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions", businessRules = ["BR-020"])
    @DisplayName("E-10 (3): the same Import Identifier in two Budget Accounts of one Budget is accepted in both")
    fun importIdentifierIsUniquePerBudgetAccount() {
        val accounts = live.openAccounts()
        val first = live.item(accounts.first, 3, 1, -34_560)
        created(live.create(first).single())
        created(live.create(first.copy(budgetAccountReference = accounts.second)).single())
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions")
    @DisplayName("E-10 (4): a list holding one item YNAB refuses (a future date) is refused or settled so that only that item is Rejected, and nothing of the list is stored twice")
    fun refusedItemOfAListIsRejectedAlone() {
        val accounts = live.openAccounts()
        val items = listOf(
            live.item(accounts.first, 4, 1, -45_670),
            live.item(accounts.first, 4, 2, -45_680, date = live.today.plusDays(5)),
            live.item(accounts.first, 4, 3, -45_690),
        )
        val outcomes = live.create(*items.toTypedArray())
        println("E-10 (4): outcomes ${outcomes.map { it::class.simpleName }}")
        assertTrue(outcomes[0] is ItemOutcome.Created && outcomes[2] is ItemOutcome.Created, "$outcomes")
        assertTrue(outcomes[1] is ItemOutcome.Rejected, "the future date was not Rejected: ${outcomes[1]}")
        val stored = live.changes(null).transactions.filter { it.state == ReadState.Present }.groupBy { it.importIdentifier }
        assertEquals(1, stored[items[0].importIdentifier]?.size)
        assertEquals(1, stored[items[2].importIdentifier]?.size)
        assertNull(stored[items[1].importIdentifier], "the refused item is stored")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-009", "BR-010"])
    @DisplayName("E-10 (5): a linked transfer through the other Budget Account's transfer payee creates the other side, linked both ways, without an Import Identifier (its cleared and approved printed); readTransaction right after returns the leg with its link, and the counterpart is marked Cleared")
    fun linkedTransferCreatesItsCounterpart() {
        val accounts = live.openAccounts()
        val leg = live.item(accounts.first, 5, 1, -56_780, cleared = true, other = accounts.card)
        val reference = created(live.create(leg).single())
        val read = present(live.lookup(reference), "the leg is not found")
        val link = present(read.transferLink, "the leg carries no link")
        assertEquals(accounts.card, link.budgetAccountReference)
        val counterpart = present(live.lookup(link.transactionReference), "the other side is not found")
        println("E-10 (5): the other side YNAB created: cleared=${counterpart.cleared}, approved=${counterpart.approved}, entry=${counterpart.entry}")
        assertNull(counterpart.importIdentifier, "the other side carries an Import Identifier")
        assertEquals(EntryKind.TransferSide, counterpart.entry)
        assertEquals(reference, counterpart.transferLink?.transactionReference, "the other side does not link back")
        assertEquals(-leg.amount.thousandths, counterpart.amount.thousandths)
        assertEquals(ClearResult.Cleared, live.markCleared(counterpart.reference))
        assertTrue(present(live.lookup(counterpart.reference), "the other side is not found").cleared)
    }

    /**
     * E-10 (6), Q-04: passes when YNAB matched the linked transfer onto the transfer entered as by hand (one side names
     * the other by `matched_transaction_id`), fails when a second pair appears (two unmatched sides of the amount in the
     * first Budget Account), and otherwise is skipped with both counts, since what was read cannot tell.
     */
    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-009"])
    @DisplayName("E-10 (6), Q-04: a linked transfer sent where a transfer of the same amount entered as by hand is already in both Budget Accounts is matched by YNAB, not made a second pair")
    fun linkedTransferOntoATypedOne() {
        val accounts = live.openAccounts()
        val date = live.today.minusDays(4)
        val amount = live.runAmount(6, 0)
        live.typedEntry(accounts.first, amount, date, "E10 ${live.run} case 6 typed", payeeId = live.transferPayeeOf(accounts.card))
        val outcome = live.create(live.item(accounts.first, 6, 1, amount, date = date, other = accounts.card)).single()
        val read = live.rawTransactions(date.minusDays(1)).filter { it["deleted"].toString() == "false" }
        val inFirst = read.filter { it.text("account_id") == accounts.first && it["amount"].toString() == amount.toString() }
        val inCard = read.filter { it.text("account_id") == accounts.card && it["amount"].toString() == (-amount).toString() }
        val matched = inFirst.any { it["matched_transaction_id"].toString() != "null" }
        println("E-10 (6): outcome ${outcome::class.simpleName}; ${inFirst.size} side(s) in the first Budget Account, ${inCard.size} in the card's; matched: $matched")
        when {
            matched -> Unit
            inFirst.size >= 2 -> fail<Unit>("E-10 (6): a second pair appeared: YNAB did not match the linked transfer onto the one entered as by hand")
            else -> Assumptions.abort<Unit>("E-10 (6): what was read cannot tell whether YNAB matched: ${inFirst.size} and ${inCard.size} side(s)")
        }
    }

    /**
     * E-10 (7): each Typed Entry and its Import have an amount of their own, derived from this run (C2 review item 4), so
     * an earlier run cannot be counted; a match is told by `matched_transaction_id` on either of the two, not by how
     * many transactions remain, which does not depend on whether a match awaits approval.
     */
    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-006"], requirements = ["C-009"])
    @DisplayName("E-10 (7): an Import is matched to a Typed Entry of the same Budget Account and amount 10 days before and after, and not 11 days after")
    fun matchingDateWindow() {
        val accounts = live.openAccounts()
        val typed = live.today.minusDays(25)
        val cases = listOf(10L to true, -10L to true, 11L to false)
        cases.indices.forEach { n -> live.typedEntry(accounts.second, live.runAmount(7, n), typed, "E10 ${live.run} case 7 typed $n") }
        cases.forEachIndexed { n, (days, _) -> created(live.create(live.item(accounts.second, 7, n, live.runAmount(7, n), date = typed.plusDays(days))).single()) }
        val read = live.rawTransactions(typed.minusDays(15)).filter { it.text("account_id") == accounts.second }
        cases.forEachIndexed { n, (days, matches) ->
            val sides = read.filter { it["amount"].toString() == live.runAmount(7, n).toString() }
            val matched = sides.any { it["matched_transaction_id"].toString() != "null" }
            println("E-10 (7): ${if (days > 0) "+" else ""}$days days: ${sides.size} transaction(s), matched: $matched")
            assertEquals(matches, matched, "at $days days")
        }
    }

    /**
     * E-10 (8): both transactions are created waiting for approval, so the test waits for what only the Operator's
     * approval of the match in YNAB's screens can cause, a transaction of this run's amount reported approved; then it
     * prints what YNAB reported from the cursor taken before the Import, and passes only when the reader gives Merged.
     */
    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-005"])
    @DisplayName("E-10 (8): a match approved in YNAB's screens, then read by last_knowledge_of_server: prints which transaction remains and which is reported deleted, and the reader gives Merged")
    fun approvedMatchReadsAsMerged() {
        val accounts = live.openAccounts()
        val date = live.today.minusDays(6)
        val amount = live.runAmount(8, 0)
        val memo = "E10 ${live.run} case 8 typed"
        live.typedEntry(accounts.first, amount, date, memo)
        val cursor = live.changes(null).next
        created(live.create(live.item(accounts.first, 8, 1, amount, date = date)).single())
        live.waitFor("in YNAB, open the throwaway Budget, find the matched transaction of ${-amount / 1000.0} with the memo \"$memo\" and approve the match") {
            live.changes(cursor).transactions.firstOrNull { it.amount.thousandths == amount && it.approved }
        }
        val reported = live.changes(cursor).transactions.filter { it.amount.thousandths == amount }
        reported.forEach { println("E-10 (8): ${it.entry} with Import Identifier ${if (it.importIdentifier == null) "none" else "the import's"}: ${it.state::class.simpleName}, approved ${it.approved}") }
        assertTrue(reported.any { it.state is ReadState.Merged }, "no transaction read as Merged: ${reported.map { it.state }}")
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-004", "BR-016"])
    @DisplayName("E-10 (9): a deletion is reported by last_knowledge_of_server and not by since_date")
    fun deletionIsReportedByServerKnowledgeOnly() {
        val accounts = live.openAccounts()
        val item = live.item(accounts.first, 9, 1, -90_120)
        val reference = created(live.create(item).single())
        val cursor = live.changes(null).next
        live.delete(reference)
        assertEquals(ReadState.Deleted, live.changes(cursor).transactions.single { it.reference == reference }.state)
        assertTrue(live.changes(null).transactions.none { it.reference == reference }, "a read by since_date reports the deletion")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-011"])
    @DisplayName("E-10 (10): marking Cleared changes nothing else of the transaction: memo, payee, category, flag, approved, amount, date")
    fun markingClearedChangesNothingElse() {
        val accounts = live.openAccounts()
        val reference = live.typedEntry(accounts.second, -10_230, live.today.minusDays(2), "E10 ${live.run} case 10")
        val fields = listOf("memo", "payee_id", "payee_name", "category_id", "flag_color", "approved", "amount", "date", "account_id", "import_id")
        fun now() = live.data(live.get("budgets/${live.budget}/transactions/$reference")).getValue("transaction").jsonObject
        val before = now()
        assertEquals(ClearResult.Cleared, live.markCleared(reference))
        val after = now()
        fields.forEach { assertEquals(before[it], after[it], "$it changed") }
        assertEquals("cleared", after.text("cleared"))
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-004"])
    @DisplayName("E-10 (11): deleting one side of a linked transfer deletes the other, and both are reported")
    fun deletingOneSideDeletesTheOther() {
        val accounts = live.openAccounts()
        val reference = created(live.create(live.item(accounts.first, 11, 1, -11_340, other = accounts.second)).single())
        val other = present(live.lookup(reference)?.transferLink, "the leg carries no link").transactionReference
        val cursor = live.changes(null).next
        live.delete(reference)
        val reported = live.changes(cursor).transactions.associateBy { it.reference }
        assertEquals(ReadState.Deleted, reported[reference]?.state, "the deleted side")
        assertEquals(ReadState.Deleted, reported[other]?.state, "the other side")
    }

    @Test
    @UseCase(id = "UC-006", scenario = "Main Success Scenario", businessRules = ["BR-008"], requirements = ["C-009"])
    @DisplayName("E-10 (12): created transactions await approval")
    fun createdTransactionsAwaitApproval() {
        val accounts = live.openAccounts()
        val reference = created(live.create(live.item(accounts.second, 12, 1, -12_450, cleared = true)).single())
        val read = present(live.lookup(reference), "the created transaction is not found")
        assertEquals(false, read.approved)
        assertEquals(true, read.cleared)
    }

    @Test
    @UseCase(id = "UC-006", scenario = "A9: Budget App Rejects Transactions")
    @DisplayName("E-10 (13): a create to a closed Budget Account is refused, and the writer answers Rejected (the throwaway Budget needs one closed Budget Account)")
    fun closedBudgetAccountRefusesACreate() {
        live.openAccounts()
        val closed = live.accounts().firstOrNull { it["closed"].toString() == "true" && it["deleted"].toString() == "false" }
            ?: fail("Close one Budget Account of the throwaway Budget in YNAB's screens, then run this test again.")
        val outcome = live.create(live.item(closed.text("id"), 13, 1, -13_560)).single()
        if (outcome is ItemOutcome.Created) live.delete(outcome.reference)
        assertTrue(outcome is ItemOutcome.Rejected, "a closed Budget Account accepted a transaction: $outcome")
    }

    /**
     * E-10 (15): proves that a cursor about a day old still reports a deletion, not that a cursor as old as a real
     * restore can be (a backup may be weeks or months old); YNAB documents no age. The amounts are this run's own.
     */
    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("E-10 (15): a read from an old cursor, as after a restore: a first run saves a cursor under build/ and creates two transactions; the Operator deletes one; a run on a later day reads from the saved cursor and must report the deletion and the other transaction")
    fun oldCursorStillReportsDeletions() {
        val saved = File("build/live-e10/stale-cursor.properties")
        if (!saved.exists()) {
            val accounts = live.openAccounts()
            val cursor = live.changes(null).next
            val kept = created(live.create(live.item(accounts.first, 15, 1, live.runAmount(15, 1))).single())
            val deleted = created(live.create(live.item(accounts.first, 15, 2, live.runAmount(15, 2))).single())
            saved.parentFile.mkdirs()
            Properties().apply {
                setProperty("cursor", cursor.value)
                setProperty("kept", kept)
                setProperty("deleted", deleted)
                setProperty("day", live.today.toString())
                saved.outputStream().use { store(it, "E-10 (15): no secret; never committed") }
            }
            Assumptions.abort<Unit>("E-10 (15), first run done: in YNAB, delete the transaction of ${live.runAmount(15, 2) / 1000.0} with the memo \"E10 ${live.run} case 15 item 2\", then run this test again tomorrow or later.")
        }
        val stage = Properties().apply { saved.inputStream().use { load(it) } }
        if (LocalDate.parse(stage.getProperty("day")) >= live.today) {
            Assumptions.abort<Unit>("E-10 (15): run this test again on a later day than ${stage.getProperty("day")}.")
        }
        val changes = live.changes(ChangeCursor(stage.getProperty("cursor"))).transactions.associateBy { it.reference }
        saved.delete()
        assertEquals(ReadState.Present, changes[stage.getProperty("kept")]?.state, "the kept transaction")
        assertEquals(ReadState.Deleted, changes[stage.getProperty("deleted")]?.state, "the deletion is not reported from the old cursor")
    }

    @Test
    @UseCase(id = "UC-003", scenario = "Main Success Scenario", businessRules = ["BR-016"])
    @DisplayName("E-10 (16), G-44: the /budgets/{budget_id} paths the part uses and the documented /plans/{plan_id} paths answer the same transactions and Budget Accounts of the throwaway Budget")
    fun budgetsAndPlansPathsAnswerTheSame() {
        live.openAccounts()
        val since = live.today.minusDays(30)
        fun transactions(root: String) = live.data(live.get("$root/${live.budget}/transactions?since_date=$since")).getValue("transactions").jsonArray
            .map { it.jsonObject }.map { listOf(it.text("id"), it["import_id"].toString(), it["amount"].toString(), it["deleted"].toString()) }.toSet()
        fun accounts(root: String) = live.data(live.get("$root/${live.budget}/accounts")).getValue("accounts").jsonArray
            .map { it.jsonObject }.map { listOf(it.text("id"), it["transfer_payee_id"].toString(), it["closed"].toString()) }.toSet()
        assertEquals(transactions("budgets"), transactions("plans"))
        assertEquals(accounts("budgets"), accounts("plans"))
    }

    /**
     * E-10 (17), S-B-79: what YNAB answers to a create that names a Budget Account of another Budget. The writer never
     * sends one (it checks every item against the Budget Accounts it read for the Budget), so the test sends it as a
     * single create of YNAB's documentation. It needs a second Budget of the same YNAB user whose name also contains
     * "throwaway", with an open Budget Account; it passes when YNAB refuses, printing the answer's status and code, and
     * fails, deleting it again, when YNAB creates it.
     */
    @Test
    @UseCase(id = "UC-006", scenario = "A8: Outcome Unknown", businessRules = ["BR-007"])
    @DisplayName("E-10 (17): a create to the Budget named with a Budget Account of another Budget is refused by YNAB (status printed), and the writer sends no such item and answers Unknown")
    fun budgetAccountOfAnotherBudgetIsNeverCreated() {
        live.openAccounts()
        val other = live.budgetsListed().firstOrNull { it.text("id") != live.budget && it.text("name").contains("throwaway", ignoreCase = true) }
            ?: fail("E-10 (17) needs a second Budget of the same YNAB user whose name contains \"throwaway\", with an open Budget Account.")
        val account = other.getValue("accounts").jsonArray.map { it.jsonObject }.firstOrNull { it["closed"].toString() == "false" && it["deleted"].toString() == "false" }
            ?.text("id") ?: fail("E-10 (17): the second throwaway Budget has no open Budget Account.")
        val item = live.item(account, 17, 1, live.runAmount(17, 1))
        assertEquals(listOf(ItemOutcome.Unknown), live.create(item), "the writer sent or settled an item of another Budget's Budget Account")
        val (status, body) = live.singleCreate(item)
        val code = runCatching { live.data(body) }.fold({ "none" }, { body.substringAfter("\"id\":\"", "?").substringBefore('"') })
        println("E-10 (17): YNAB answered $status (error code $code) to a create naming another Budget's Budget Account")
        if (status == 201) {
            val created = live.data(body).getValue("transaction").jsonObject.text("id")
            runCatching { live.delete(created) }
            fail<Unit>("E-10 (17): YNAB created a transaction for a Budget Account of another Budget (deleted again where it could be)")
        }
    }
}

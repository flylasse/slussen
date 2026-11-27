package slussen.app.ui.transfers

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import slussen.app.ui.MainDispatcherExtension
import slussen.app.ui.UiFixture
import slussen.app.ui.bankaccount.BankAccountViewModel
import slussen.app.ui.budget.BudgetViewModel
import slussen.core.port.BankAccountType
import slussen.pluginapi.testfixtures.UseCase
import slussen.testing.EveryStatusScenario
import slussen.testing.ExampleData
import slussen.testing.ExampleData.amount
import java.time.Duration
import java.time.LocalDate

/**
 * The Transfers of a Budget on the screen (UC-005 step 1, A4, A5, A8; `docs/mockups/transfers.html`), read-only: what
 * the screen decides to show for each state of the list and of a selected Transfer, from the read model of `:core`. The
 * rules themselves are tested in `UC005TransfersOfABudgetTest`. Every name and amount is invented (C-005).
 */
@ExtendWith(MainDispatcherExtension::class)
@DisplayName("UC-005: Review Transfers, the screens")
class UC005ReviewTransfersViewModelTest {
    private val fixture = UiFixture()
    private val slice = fixture.slice
    private val scenario = EveryStatusScenario(slice)
    private val budgetApp = ExampleData.BUDGET_APP_NAME

    private fun household(): Long = runBlocking { slice.connectionStore.budgets().single { it.name == "Household" }.id }

    private fun transfers(selectLeg: Long? = null) = TransfersViewModel(fixture.services, household()).also { it.load(selectLeg) }

    private fun TransfersViewModel.ui(): TransfersUi = state.value.ui!!

    private fun TransfersUi.card(key: String): TransferCardUi = transfers.single { it.key == scenario.transaction(key) }

    private fun TransfersUi.detail(name: String): String = sheet!!.details.single { it.name == name }.text

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Step 1, BR-010 (transfers.html#list, UX-27): each Transfer shows its kinds, its two Budget Accounts, its amount, each side with its date, and whether both arrived and it is eligible; Guessed Transfers come first and are counted, and the Budget tab's entry counts the same list")
    fun listShowsEachTransfer() {
        runBlocking { scenario.compareEveryStatus(withLonelyTransfer = true) }

        val ui = transfers().ui()

        assertEquals("Household · Transfers", ui.title)
        assertEquals("Statuses as of 14:00", ui.statuses)
        assertEquals("Transfers in Household: 4 · of them Guessed Transfers: 2", ui.counts)
        assertEquals(
            "3 Bank Transactions of these Guessed Transfers are counted among those waiting for a decision. An eligible Transfer counts as " +
                "one Eligible Transaction, the side it is written through.",
            ui.countsNote,
        )
        assertEquals(TransfersTexts.DECISIONS_NOTE, ui.note)
        assertNull(ui.empty)
        assertEquals(listOf(true, true, false, false), ui.transfers.map { it.guessed })
        val pair = ui.card("guessed-pay")
        assertEquals(listOf("Guessed Transfer", "Card Payment"), pair.chips)
        assertEquals("Checking? → Rewards Card", pair.budgetAccounts)
        assertEquals("410.00", pair.amount)
        assertEquals(listOf("Kestrel Checking −410.00 · 10-12 · arrived", "Pinecrest Rewards Card +410.00 · 10-13 · arrived"), pair.sides)
        assertEquals("both sides arrived · not eligible until it is confirmed or corrected", pair.state)
        val bankTransfer = ui.card("leg-out")
        assertEquals(listOf("Bank Transfer"), bankTransfer.chips)
        assertEquals("Checking → Reserve Savings", bankTransfer.budgetAccounts)
        assertEquals("both sides arrived · eligible, through the Kestrel Checking side", bankTransfer.state)
        val lonely = ui.card("lonely-in")
        assertEquals(listOf("Bank Transfer", "Lonely Transfer"), lonely.chips)
        assertEquals(
            listOf("Kestrel Reserve +120.00 · 10-13 · arrived", "Checking −120.00 · expected by 2026-10-19 · a later Fetch may bring it"),
            lonely.sides,
        )
        assertEquals("one side arrived · eligible, through the Kestrel Reserve side", lonely.state)

        val tab = BudgetViewModel(fixture.services, household()).also { it.load() }.state.value!!
        assertEquals("Transfers: 4 · Guessed Transfers: 2", tab.transfers)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By")
    @DisplayName("A8 (transfers.html#selected): a selected Guessed Transfer shows its proposed paying side, its card side, why it is not certain and why it is not eligible, without any decision")
    fun selectedGuessedTransfer() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers()

        screen.open(scenario.transaction("guessed-pay"))
        val ui = screen.ui()

        assertEquals("Guessed Transfer · Card Payment · 410.00 USD", ui.sheet!!.title)
        assertEquals("Checking? → Rewards Card", ui.sheet!!.subtitle)
        assertEquals(
            "Kestrel Checking · Budget Account Checking · 2026-10-12 · −410.00 · PINECREST AUTOPAY · Guessed Transfer",
            ui.detail("Proposed outflow side"),
        )
        assertEquals("Pinecrest Rewards Card · Budget Account Rewards Card · 2026-10-13 · +410.00 · AUTOPAY RECEIVED · Guessed Transfer", ui.detail("Inflow side"))
        assertEquals("Pinecrest Rewards Card has no Funded By, so its paying side is only proposed.", ui.detail("Why not certain"))
        assertEquals("No: a Guessed Transfer is in no Upload Plan until you confirm it or choose another Budget Account.", ui.detail("Eligible"))
        assertEquals(TransfersTexts.CLOSING, ui.sheet!!.closing)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By")
    @DisplayName("A8, O-T-10 (transfers.html#first-card-payment): a card's Card Payment for which Slussen proposes no paying side is listed from its Bank Transaction with the paying side not known, and its details say why")
    fun firstCardPaymentWithoutProposal() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers()

        val card = screen.ui().card("guessed-alone")
        assertEquals("? → Rewards Card", card.budgetAccounts)
        assertEquals(listOf("Pinecrest Rewards Card +33.00 · 10-14 · arrived", "Paying side not known: Pinecrest Rewards Card has no Funded By"), card.sides)
        assertEquals("one side known · not eligible until it is confirmed or corrected", card.state)

        screen.open(card.key)
        assertEquals(
            "Pinecrest Rewards Card has no Funded By, and 2 Bank Accounts of Household that are not credit cards could pay it, so Slussen proposes none of them.",
            screen.ui().detail("Why not certain"),
        )
        assertEquals("Pinecrest Rewards Card · Budget Account Rewards Card · 2026-10-14 · +33.00 · AUTOPAY RECEIVED · Guessed Transfer", screen.ui().detail("Arrived"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A4: Lonely Transfer")
    @DisplayName("A4, BR-005 (transfers.html#lonely): a selected Lonely Transfer shows the arrived side, the expected other side with its Budget Account and the date by which it must arrive; once that date has passed it says that the next Comparison makes it ordinary, not that it is still expected")
    fun selectedLonelyTransfer() {
        runBlocking { scenario.compareEveryStatus(withLonelyTransfer = true) }
        val screen = transfers()

        screen.open(scenario.transaction("lonely-in"))
        assertEquals("Lonely Transfer".let { "Bank Transfer · $it · 120.00 USD" }, screen.ui().sheet!!.title)
        assertEquals("−120.00 in Budget Account Checking · not arrived yet", screen.ui().detail("Expected other side"))
        assertEquals("2026-10-19, the end of the Lonely Transfer grace period (6 days, an Operator Setting)", screen.ui().detail("Must arrive by"))
        assertEquals(
            "A later Fetch may bring the other side; then both are one Transfer. If none has arrived by 2026-10-19, the next Comparison " +
                "after that day makes this an ordinary Bank Transaction.",
            screen.ui().detail("What happens"),
        )
        assertTrue(screen.ui().detail("Eligible").startsWith("Yes, for a later Upload Plan, as one linked transfer written through its Kestrel Reserve side"))

        slice.clock.advance(Duration.ofDays(5))
        val later = transfers(selectLeg = scenario.transaction("lonely-in")).ui()
        assertEquals(
            "It ended 2026-10-19 (6 days, an Operator Setting) without the other side. The next Comparison, which a Fetch or reading " +
                "$budgetApp again runs, makes this an ordinary Bank Transaction.",
            later.detail("Grace period"),
        )
        assertFalse(later.sheet!!.details.any { it.name == "Must arrive by" })
        assertEquals("Checking −120.00 · not arrived; its grace period ended 10-19", later.card("lonely-in").sides.last())
        // Review 2a: never said to be eligible as a Transfer once its grace period has ended.
        assertEquals(
            "one side arrived · not eligible as a Transfer: the next Comparison makes it an ordinary Bank Transaction",
            later.card("lonely-in").state,
        )
        assertEquals(
            "No: its grace period ended without the other side, so the next Comparison makes this an ordinary Bank Transaction with a " +
                "Transaction Status of its own; it is never sent as a Transfer.",
            later.detail("Eligible"),
        )
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A5: Other Side Not Arrived Within the Grace Period")
    @DisplayName("A5 (transfers.html#list-this-build): a Bank Transaction whose other side did not arrive within the grace period is shown apart as an ordinary Bank Transaction, with the reason")
    fun otherSideNotArrivedInTimeIsShownApart() {
        runBlocking { scenario.compareEveryStatus() }

        val late = transfers().ui().noLongerTransfers.single()

        assertEquals("Reserve Savings · Kestrel Reserve · ordinary", late.title)
        assertEquals("+75.00", late.amount)
        assertEquals(
            "10-08 · Its other side, in Checking, did not arrive within the Lonely Transfer grace period (6 days, an Operator Setting, " +
                "until 10-14), so it is an ordinary Bank Transaction, New",
            late.reason,
        )
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A9: Transfer With a Doubtful Transfer Leg")
    @DisplayName("A9 step 1, BR-013 (transfers.html#doubtful-leg, review 1a): a Transfer with a Transfer Leg that is Needs Review says that the whole Transfer waits for the Review Decision on that Transfer Leg, and why that Transfer Leg is doubtful")
    fun doubtfulTransferLeg() {
        val paid = runBlocking {
            scenario.serve()
            val checking = scenario.addBankAccount("Kestrel Checking", "Checking")
            val reserve = scenario.addBankAccount("Kestrel Reserve", "Reserve Savings", BankAccountType.Savings)
            val paid = scenario.store("paid", checking, LocalDate.of(2026, 10, 12), amount(-200), "ONLINE TRANSFER TO RESERVE")
            scenario.store("received", reserve, LocalDate.of(2026, 10, 12), amount(200), "Deposit", reference = null)
            scenario.answerHousehold(emptyList())
            slice.comparison.readAgain()
            paid
        }
        val screen = transfers()

        // A9 step 1 (review 1a): with the reason it is doubtful, in the Bank Account screen's words.
        assertEquals(
            "both sides arrived · waits for the Review Decision on its Kestrel Reserve side, which is Needs Review: No Stable Reference, " +
                "so Slussen cannot recognise it again for sure",
            screen.ui().card("paid").state,
        )
        screen.open(paid)
        assertEquals(
            "No: its Kestrel Reserve side is Needs Review, so the whole Transfer waits for your Review Decision on that Transfer Leg; " +
                "decide on it, or split the Transfer.",
            screen.ui().detail("Eligible"),
        )
        assertTrue(screen.ui().detail("Inflow side").endsWith("· Needs Review"))
        assertEquals("No Stable Reference, so Slussen cannot recognise it again for sure", screen.ui().detail("Why it is doubtful"))
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Step 1 (transfers.html#no-transfers): a Budget without Transfers says so as an expected result with the dates it covers; before its first read it says that nothing could be detected yet and names the next action")
    fun noTransfers() {
        runBlocking {
            scenario.serve()
            val checking = scenario.addBankAccount("Kestrel Checking", "Checking")
            scenario.store("grocer", checking, LocalDate.of(2026, 10, 9), amount(-18), "Corner Grocer")
        }
        val before = transfers().ui()
        assertEquals(
            "No Transfers yet: Transfers are detected when Slussen compares the Bank Transactions with $budgetApp, and $budgetApp has not been read for Household.",
            before.empty,
        )
        assertEquals("Read $budgetApp again to bring them up to date; nothing else is needed.", before.readAgainNote)
        // Review 2b: the Budget tab's entry says the same, not a count of 0.
        assertEquals(
            "Transfers: none detected yet · $budgetApp has not been read for Household",
            BudgetViewModel(fixture.services, household()).also { it.load() }.state.value!!.transfers,
        )

        runBlocking {
            scenario.answerHousehold(emptyList())
            slice.comparison.readAgain()
        }
        val ui = transfers().ui()

        assertEquals("Transfers in Household: 0 · of them Guessed Transfers: 0", ui.counts)
        assertNull(ui.countsNote)
        assertTrue(ui.transfers.isEmpty())
        assertEquals(
            "No Transfers in Household. Among the stored Bank Transactions of 2026-10-09 to 2026-10-09 Slussen found no Transfer between " +
                "your Bank Accounts. Nothing is wrong and no action is needed; a later Fetch may bring one.",
            ui.empty,
        )
        assertNull(ui.readAgainNote)
        assertEquals("Transfers: 0 · Guessed Transfers: 0", BudgetViewModel(fixture.services, household()).also { it.load() }.state.value!!.transfers)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Step 1: a Transfer Leg's sheet on the Bank Account screen leads to its Transfer, which opens selected; Close and system back show the list, and opening the Transfers again shows the list, not the Transfer left open; a selected Transfer the stored data no longer holds is said, not shown")
    fun openFromATransferLegAndClose() {
        runBlocking { scenario.compareEveryStatus() }
        val bankAccount = BankAccountViewModel(fixture.services, scenario.bankAccount("Kestrel Checking")).also { it.load() }
        bankAccount.open(slussen.core.status.TransactionKey(unreadable = false, id = scenario.transaction("leg-out")))
        assertEquals(scenario.transaction("leg-out"), bankAccount.state.value.ui!!.sheet!!.transferLeg)
        bankAccount.open(slussen.core.status.TransactionKey(unreadable = false, id = scenario.transaction("new")))
        assertNull(bankAccount.state.value.ui!!.sheet!!.transferLeg)
        bankAccount.open(slussen.core.status.TransactionKey(unreadable = false, id = scenario.transaction("guessed-pay")))
        assertEquals(scenario.transaction("guessed-pay"), bankAccount.state.value.ui!!.sheet!!.transferLeg)

        val screen = transfers(selectLeg = scenario.transaction("leg-out"))
        assertEquals("Bank Transfer · 500.00 USD", screen.ui().sheet!!.title)
        assertTrue(screen.back())
        assertNull(screen.ui().sheet)
        assertFalse(screen.back())
        screen.open(scenario.transaction("leg-in"))
        assertEquals("Bank Transfer · 500.00 USD", screen.ui().sheet!!.title)
        screen.close()
        assertNull(screen.ui().sheet)
        screen.open(scenario.transaction("leg-in"))
        screen.load()
        assertNull(screen.ui().sheet)

        screen.load(selectLeg = scenario.transaction("new"))
        assertNull(screen.ui().sheet)
        assertEquals(TransfersTexts.NO_LONGER_LISTED, screen.ui().notListed)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023: when a read of the Budget App started elsewhere ends, the open Transfers show what it stored at once; stored data that cannot be read is said on the screen, which keeps what it showed, and on the Budget tab's entry, never a crash")
    fun followsOperationsAndNeverCrashes() {
        runBlocking { scenario.compareEveryStatus() }
        val screen = transfers()
        assertEquals("Transfers in Household: 3 · of them Guessed Transfers: 2", screen.ui().counts)

        scenario.store("lonely-in", scenario.bankAccount("Kestrel Reserve"), LocalDate.of(2026, 10, 13), amount(120), "INTERNET TRANSFER FROM CHECKING")
        runBlocking { slice.comparison.readAgain() }
        assertEquals("Transfers in Household: 4 · of them Guessed Transfers: 2", screen.ui().counts)

        slice.copyStore.failsToRead = true
        runBlocking { slice.comparison.readAgain() }
        assertEquals(TransfersTexts.NOT_READ, screen.state.value.failure)
        assertEquals("Transfers in Household: 4 · of them Guessed Transfers: 2", screen.ui().counts)

        val unread = transfers()
        assertNull(unread.state.value.ui)
        assertEquals(TransfersTexts.NOT_READ, unread.state.value.failure)
        val tab = BudgetViewModel(fixture.services, household()).also { it.load() }.state.value!!
        assertEquals(TransfersTexts.ENTRY_NOT_READ, tab.transfers)
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario", requirements = ["NFR-023"])
    @DisplayName("NFR-023 (review 4b): a Budget tab whose stored status cannot be read when it opens says so on the tab, never a crash; it shows the Budget once it can be read")
    fun budgetTabThatCannotBeReadSaysSo() {
        runBlocking { scenario.compareEveryStatus() }
        slice.importStatusStore.failsToRead = true

        val tab = BudgetViewModel(fixture.services, household()).also { it.load() }

        assertNull(tab.state.value)
        assertEquals(slussen.app.ui.budget.BudgetViewModel.NOT_READ, tab.failure.value)
        slice.importStatusStore.failsToRead = false
        tab.load()
        assertEquals("Household", tab.state.value!!.title)
        assertNull(tab.failure.value)
    }

    /** Household with a second credit card, Travel Card, beside the example Budget Accounts. */
    private fun householdWithTwoCards() = runBlocking {
        slice.enterCredentials()
        val listed = ExampleData.budgetsRead().map { budget ->
            val more = if (budget.name == "Household") listOf(slussen.core.port.BudgetAccountToStore("ba-travel", "Travel Card", amount(-90))) else emptyList()
            slussen.core.port.BudgetToServe(
                budget.reference, budget.name, budget.currency,
                budget.budgetAccounts.map { slussen.core.port.BudgetAccountToStore(it.reference, it.name, it.balance) } + more,
            )
        }
        slice.connectionStore.serveBudgets(listed, listed.map { it.budgetAppReference }.toSet(), slice.clock.instant().minus(Duration.ofDays(3)))
    }

    private fun budgetAccount(name: String): Long = runBlocking { slice.connectionStore.budgets().flatMap { it.budgetAccounts }.single { it.name == name }.id }

    private fun readHousehold() = runBlocking {
        scenario.answerHousehold(emptyList())
        slice.comparison.readAgain()
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Step 1 (review 5a): a Guessed Transfer with another possible inflow side marks the proposed inflow side and lists the other candidate; one whose card is one of two funded by the paying side names the other card")
    fun otherCandidatesAndOtherCards() {
        householdWithTwoCards()
        val (checking, rewards) = runBlocking {
            scenario.addBankAccount("Kestrel Checking", "Checking") to scenario.addBankAccount("Pinecrest Rewards Card", "Rewards Card", BankAccountType.CreditCard)
        }
        val reserve = runBlocking { scenario.addBankAccount("Kestrel Reserve", "Reserve Savings", BankAccountType.Savings) }
        val travel = runBlocking { scenario.addBankAccount("Pinecrest Travel Card", "Travel Card", BankAccountType.CreditCard) }
        slice.connectionStore.link(rewards, fundedBy = budgetAccount("Checking"))
        slice.connectionStore.link(travel, fundedBy = budgetAccount("Checking"))
        scenario.store("paid", checking, LocalDate.of(2026, 10, 12), amount(-100), "Payment")
        scenario.store("card", rewards, LocalDate.of(2026, 10, 12), amount(100), "Payment received")
        scenario.store("moved", reserve, LocalDate.of(2026, 10, 11), amount(100), "ONLINE TRANSFER FROM CHECKING")
        scenario.store("autopay", checking, LocalDate.of(2026, 10, 14), amount(-60), "PINECREST AUTOPAY")
        readHousehold()
        val screen = transfers()

        assertEquals("Checking → Rewards Card?", screen.ui().card("paid").budgetAccounts)
        screen.open(scenario.transaction("paid"))
        assertEquals(
            "Kestrel Reserve · Budget Account Reserve Savings · 2026-10-11 · +100.00 · ONLINE TRANSFER FROM CHECKING · New",
            screen.ui().detail("Also possible"),
        )
        assertTrue(screen.ui().detail("Proposed inflow side").startsWith("Pinecrest Rewards Card · Budget Account Rewards Card"))
        assertTrue(screen.ui().detail("Why not certain").startsWith("Another Bank Transaction of the same amount lies within the transfer pairing days (5 days"))

        screen.open(scenario.transaction("autopay"))
        assertEquals(
            "Checking is the Funded By of more than one credit card, so the card is only proposed: Rewards Card, the one added first; it could also be Travel Card.",
            screen.ui().detail("Why not certain"),
        )
    }

    @Test
    @UseCase(id = "UC-005", scenario = "Main Success Scenario")
    @DisplayName("Step 1 (review 5a): a Transfer with a Too Old side says that side keeps the whole Transfer out; a Guessed Transfer whose ground changed since the Comparison is shown as it left it; a card's inflow whose only proposed paying side did not arrive in time says it was only proposed; a Guessed Transfer without a stored Transfer is reached from its Bank Transaction")
    fun keptOutAsStoredAndOnlyProposed() {
        val (checking, card) = runBlocking {
            scenario.serve()
            scenario.addBankAccount("Kestrel Checking", "Checking") to scenario.addBankAccount("Pinecrest Rewards Card", "Rewards Card", BankAccountType.CreditCard)
        }
        scenario.store("old-out", checking, LocalDate.of(2026, 10, 5), amount(-60), "Payment")
        scenario.store("old-in", card, LocalDate.of(2026, 10, 9), amount(60), "Payment received")
        scenario.store("late-card", card, LocalDate.of(2026, 10, 8), amount(25), "AUTOPAY RECEIVED")
        readHousehold()

        val late = transfers().ui().noLongerTransfers.single()
        assertTrue(late.reason.startsWith("10-08 · No other side arrived in Checking, which Slussen only proposed, within the Lonely Transfer grace period"))

        slice.connectionStore.link(card, fundedBy = budgetAccount("Checking"))
        val screen = transfers()
        screen.open(scenario.transaction("old-in"))
        assertEquals(
            "The latest Comparison stored it as a Guessed Transfer. On the data stored now the rules no longer give it as one, so it is " +
                "shown as that Comparison left it until the next Comparison.",
            screen.ui().detail("Why not certain"),
        )

        readHousehold()
        val kept = transfers()
        assertEquals("both sides arrived · not eligible: its Kestrel Checking side is Too Old", kept.ui().card("old-out").state)
        kept.open(scenario.transaction("old-out"))
        assertEquals(
            "No: its Kestrel Checking side is Too Old, so the whole Transfer is in no Upload Plan, from either side, while it is.",
            kept.ui().detail("Eligible"),
        )
    }

    @Test
    @UseCase(id = "UC-005", scenario = "A8: First Card Payment Without Funded By")
    @DisplayName("A8, O-T-10 (review 5a): the sheet of a Guessed Transfer without a stored Transfer on the Bank Account screen leads to it")
    fun guessedWithoutStoredTransferIsReachedFromItsBankTransaction() {
        runBlocking { scenario.compareEveryStatus() }
        val bankAccount = BankAccountViewModel(fixture.services, scenario.bankAccount("Pinecrest Rewards Card")).also { it.load() }

        bankAccount.open(slussen.core.status.TransactionKey(unreadable = false, id = scenario.transaction("guessed-alone")))

        assertEquals(scenario.transaction("guessed-alone"), bankAccount.state.value.ui!!.sheet!!.transferLeg)
        assertEquals("? → Rewards Card", transfers(selectLeg = scenario.transaction("guessed-alone")).ui().sheet!!.subtitle)
    }
}

package slussen.core.journal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import slussen.core.comparison.BudgetAppCopy
import slussen.core.comparison.ComparisonRules
import slussen.core.credential.CredentialStatus
import slussen.core.credential.ServiceCredentials
import slussen.core.identity.ImportIdentifierProtocol
import slussen.core.operation.OperationCoordinator
import slussen.core.operation.OperationKind
import slussen.core.operation.OperationPermit
import slussen.core.operation.OperationResult
import slussen.core.operation.RunningOperation
import slussen.core.plugin.RegisteredPlugins
import slussen.core.port.BudgetAppCopyStore
import slussen.core.port.BudgetAppTransactionState
import slussen.core.port.ConnectionStore
import slussen.core.port.DecisionJournalReader
import slussen.core.port.EnteredBy
import slussen.core.port.JournalBounds
import slussen.core.port.JournalEntry
import slussen.core.port.JournalExportDocument
import slussen.core.port.OperatorSettingsStore
import slussen.core.port.ServiceKey
import slussen.core.port.StartedBy
import slussen.core.port.StoredBudget
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.ReadState
import slussen.pluginapi.request.RequestContext
import java.io.Writer
import java.time.Clock
import java.time.LocalDate
import kotlin.coroutines.CoroutineContext

/**
 * Export the Decision Journal (UC-011), `:core` `journal` (ADR-001 §7): the only reader of Decision Journal entries in
 * the core (Konsist K-07).
 *
 * The order of ADR-002 §9: the export notes the Decision Journal's bounds on its own thread (step 3), so that entries
 * handed over and not written yet are inside them; then reads the Budget App for every served Budget on the operation
 * lane, admitted as a Comparison's read is (ADR-007 §2), without storing anything of that read (BR-002); and only then,
 * with the lane free, streams the entries up to the noted one to the document page by page, each page one short read of
 * the journal's database, so that no write, trimming or clearing of the Decision Journal waits for the export and no
 * decision waits for its writing (UC-010 BR-004). A page holds at most [pageSize] entries; nothing holds the whole
 * Decision Journal in memory. The Budget App's transactions of the read period are held until they are written, as a
 * Comparison holds them.
 *
 * A file that cannot be written completely, or that could not hold the entries as the Decision Journal held them, is
 * removed again (A3); every outcome other than a complete Journal Export removes the document the Operator chose.
 */
class JournalExport(
    private val plugins: RegisteredPlugins,
    private val credentials: ServiceCredentials,
    private val connections: ConnectionStore,
    private val copies: BudgetAppCopyStore,
    private val settings: OperatorSettingsStore,
    private val coordinator: OperationCoordinator,
    private val reader: DecisionJournalReader,
    private val clock: Clock,
    /** The version name of the build that writes the file (UC-010 BR-011). */
    private val versionName: String,
    /** Where the document is written, off the caller's thread. */
    private val io: CoroutineContext = Dispatchers.IO,
    private val pageSize: Int = PAGE_SIZE,
) {
    companion object {
        /**
         * The most entries one read of the Decision Journal brings (ADR-002 §9): a bound on the memory an export takes and
         * on how long a write of the journal waits behind one read, not a number the Operator experiences.
         */
        const val PAGE_SIZE: Int = 500

        /** How many of the latest entries the complete export names for review (`journal-export.html#complete`). */
        const val LATEST_SHOWN: Int = 3
    }

    // UC-011 BR-007: Slussen proposes the name slussen-journal-<date of the export>.json.
    /** The name Slussen proposes for the file. */
    fun proposedName(): String = "slussen-journal-${today()}.json"

    /**
     * Precondition: whether the Decision Journal holds at least one entry; null when it cannot be read. An entry written
     * already is found at once, without waiting for the journal's own thread; only when none is found does it ask there,
     * behind the entries queued and not written yet.
     */
    suspend fun hasEntries(): Boolean? = safely { reader.holdsAnEntryNow() || reader.bounds() != null }

    /**
     * Steps 3-5, A2, A3: the Journal Export written to [document], the place the Operator chose (step 2), with payee and
     * memo text replaced by codes when [codes]. A1 (no place chosen) never reaches here: no document exists then. When
     * the export has to wait for the lane behind another operation, [waiting] is told which (ADR-007 §1), and [reading]
     * when it has the lane; a caller that cancels the export while it waits withdraws it, and the document is removed.
     */
    suspend fun export(
        codes: Boolean,
        document: JournalExportDocument,
        waiting: (RunningOperation) -> Unit = {},
        reading: () -> Unit = {},
    ): JournalExportOutcome {
        val outcome = try {
            exportTo(codes, document, waiting, reading)
        } catch (cancelled: CancellationException) {
            // A3 failure postconditions: an export that stops leaves no incomplete file presented as a Journal Export.
            withContext(NonCancellable + io) { removeSafely(document) }
            throw cancelled
        } catch (failure: Exception) {
            // What the steps above do not answer themselves, such as a coordinator whose stored mode cannot be read.
            failed(ExportFailure.StoredDataNotRead)
        }
        return when (outcome) {
            is JournalExportOutcome.Complete -> outcome
            is JournalExportOutcome.Failed -> outcome.copy(removed = withContext(io) { removeSafely(document) })
        }
    }

    private suspend fun exportTo(
        codes: Boolean,
        document: JournalExportDocument,
        waiting: (RunningOperation) -> Unit,
        reading: () -> Unit,
    ): JournalExportOutcome {
        // Step 3: the newest entry is noted first, through the journal's own thread (ADR-002 §9).
        val bounds = try {
            reader.bounds()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return failed(ExportFailure.JournalNotRead)
        }
        coordinator.running.value?.let(waiting)
        val read = when (
            val admitted = coordinator.run(OperationKind.JournalExport, StartedBy.Operator) { permit ->
                reading()
                safely { readBudgets(permit, bounds) }
            }
        ) {
            // UC-006 BR-005: While an Upload Plan awaits confirmation or an Upload is written, no Journal Export is made.
            is OperationResult.Refused -> return failed(ExportFailure.Unavailable(admitted.reason))
            is OperationResult.Ran -> admitted.value ?: return failed(ExportFailure.StoredDataNotRead)
        }
        return withContext(io) { write(codes, document, bounds, read) }
    }

    /** What step 3 read: the first date of BR-008, each served Budget's source and the transactions to export. */
    private class BudgetsRead(val firstDate: LocalDate, val budgets: List<ExportedBudget>, val transactions: List<ExportedTransaction>)

    /**
     * Step 3, A2, within the operation that holds [permit]: the present transactions of every served Budget over the dates
     * of BR-008, read now and not stored, or the stored copy where the Budget App cannot be read. Throws when Slussen's
     * own stored data cannot be read.
     */
    private suspend fun readBudgets(permit: OperationPermit, bounds: JournalBounds?): BudgetsRead {
        val current = settings.current()
        val today = today()
        // UC-011 BR-008: From the day of the oldest entry back by the read period of a Budget's first read, up to and including the day of the export; with no entry, from the day of the export back by that read period.
        val from = bounds?.let { LocalDate.ofInstant(it.oldestRecordedAt, clock.zone) } ?: today
        val firstDate = ComparisonRules.readFrom(current, plugins.budgetApp.profile.matchingDateWindowDays, from)
        val dates = firstDate..today
        val served = connections.budgets().filter { it.served }
        // A2 of UC-011: without entered credentials, or with credentials already refused, the Budget App is not contacted (UC-014 BR-005).
        val credential = credentials.stored(ServiceKey.budgetApp)
        var notContacted: BudgetNotRead? = when {
            credential == null -> BudgetNotRead.CredentialsMissing
            credentials.status(ServiceKey.budgetApp) == CredentialStatus.Refused -> BudgetNotRead.CredentialsRefused
            else -> null
        }
        val context = if (notContacted == null) credentials.context(ServiceKey.budgetApp, permit, credential) else null
        if (notContacted == null) permit.workingOn(plugins.budgetApp.profile.name)
        val budgets = mutableListOf<ExportedBudget>()
        val transactions = mutableListOf<ExportedTransaction>()
        for (budget in served) {
            val failed = notContacted ?: when (val answer = readNow(budget, firstDate, context!!)) {
                is ReadNow.Read -> {
                    budgets += ExportedBudget.Read(budget.name, budget.currency, answer.readAt)
                    transactions += answer.transactions.filter { it.date in dates }
                    continue
                }
                is ReadNow.NotRead -> answer.why
            }
            if (failed == BudgetNotRead.CredentialsRefused && notContacted == null) {
                // UC-011 BR-002: The one thing an export stores is a refusal of Slussen's credentials, recorded as UC-014 BR-005 says; the Budget App is not contacted again.
                credentials.markRefused(ServiceKey.budgetApp)
                notContacted = BudgetNotRead.CredentialsRefused
            }
            budgets += fromStoredCopy(budget, failed, dates, transactions)
        }
        return BudgetsRead(firstDate, budgets, transactions)
    }

    private sealed interface ReadNow {
        class Read(val readAt: java.time.Instant, val transactions: List<ExportedTransaction>) : ReadNow

        class NotRead(val why: BudgetNotRead) : ReadNow
    }

    /** Step 3 for one Budget: its present transactions from [firstDate] on, read from the Budget App without a cursor, and stored nowhere (BR-002). */
    private suspend fun readNow(budget: StoredBudget, firstDate: LocalDate, context: RequestContext): ReadNow {
        val accounts = budget.budgetAccounts.associateBy { it.budgetAppReference }
        return when (val read = plugins.budgetApp.reader.readChanges(budget.budgetAppReference, since = null, from = firstDate, context = context)) {
            is ReadResult.Answered -> ReadNow.Read(
                clock.instant(),
                read.value.transactions.mapNotNull { transaction ->
                    // UC-011 BR-008: The present transactions of the Budget Accounts Slussen knows of that Budget (UC-013).
                    val account = accounts[transaction.budgetAccountReference] ?: return@mapNotNull null
                    if (transaction.state != ReadState.Present) return@mapNotNull null
                    exported(
                        budget.name, account.name, transaction.date, transaction.payee, transaction.memo, transaction.amount,
                        transaction.importIdentifier, BudgetAppCopy.enteredBy(transaction, linkedEnteredBy = null),
                    )
                },
            )
            ReadResult.CredentialsRefused -> ReadNow.NotRead(BudgetNotRead.CredentialsRefused)
            is ReadResult.TransientFailure -> ReadNow.NotRead(BudgetNotRead.NoAnswer(read.kind))
            is ReadResult.StoppedByGate -> ReadNow.NotRead(BudgetNotRead.NotSent)
        }
    }

    /**
     * A2 steps 1-2: [budget]'s transactions from Slussen's stored copy, those present at its last successful read over
     * [dates] as far back as the copy reaches, added to [into]; nothing of the failed read is used. A Budget never read
     * has none.
     */
    private suspend fun fromStoredCopy(
        budget: StoredBudget,
        why: BudgetNotRead,
        dates: ClosedRange<LocalDate>,
        into: MutableList<ExportedTransaction>,
    ): ExportedBudget {
        val lastReadAt = budget.lastReadAt ?: return ExportedBudget.NeverRead(budget.name, budget.currency, why)
        val accounts = budget.budgetAccounts.associateBy { it.id }
        copies.budgetAppTransactionsOf(budget.id)
            .filter { it.state == BudgetAppTransactionState.Present && it.transactionDate in dates }
            .forEach { stored ->
                val account = accounts[stored.budgetAccountId] ?: return@forEach
                into += exported(
                    budget.name, account.name, stored.transactionDate, stored.payee, stored.memo, stored.amount,
                    stored.importIdentifier, stored.enteredBy,
                )
            }
        return ExportedBudget.FromStoredCopy(budget.name, budget.currency, lastReadAt, why)
    }

    // UC-011 BR-007: No identifier the Budget App gives a Budget, Budget Account or transaction is exported, and no Import Identifier of an Other Import Path.
    private fun exported(
        budget: String,
        budgetAccount: String,
        date: LocalDate,
        payee: String?,
        memo: String?,
        amount: slussen.pluginapi.value.Amount,
        importIdentifier: String?,
        enteredBy: EnteredBy,
    ) = ExportedTransaction(
        budget = budget,
        budgetAccount = budgetAccount,
        date = date,
        payee = payee,
        memo = memo,
        amount = amount,
        // UC-011 BR-008: Its Import Identifier only when that begins with Slussen's marker; an import of an Other Import Path is marked as such instead.
        importIdentifier = importIdentifier?.takeIf(ImportIdentifierProtocol::isSlussens),
        importedByOtherPath = enteredBy == EnteredBy.OtherImportPath,
    )

    /**
     * Step 4, with the operation lane free: the header, the entries streamed up to the newest noted at step 3, the Budget
     * App's transactions and the closing part. A3: a write that fails stops; the caller removes the document.
     */
    private suspend fun write(codes: Boolean, document: JournalExportDocument, bounds: JournalBounds?, read: BudgetsRead): JournalExportOutcome {
        val latest = ArrayDeque<JournalEntry>()
        val writer: Writer = try {
            document.open().buffered()
        } catch (failure: Exception) {
            return failed(ExportFailure.NotWritten)
        }
        val file = JournalExportJson(writer)
        try {
            writer.use {
                file.header(versionName, clock.instant(), codes, read.firstDate, read.budgets)
                val streamed = if (bounds == null) {
                    true
                } else {
                    streamEntries(bounds, afterPage = writer::flush) { entry ->
                        file.entry(entry)
                        latest.addFirst(entry)
                        if (latest.size > LATEST_SHOWN) latest.removeLast()
                    }
                }
                if (!streamed) return failed(ExportFailure.JournalNotRead)
                file.entriesDone()
                // UC-011 BR-005: When the Operator chose codes, payee and memo text are replaced by codes; nothing else is.
                val coded = if (codes) ExportCodes() else null
                read.transactions
                    .sortedWith(compareBy({ read.budgets.indexOfFirst { budget -> budget.name == it.budget } }, { it.date }, { it.budgetAccount }))
                    .forEach { transaction ->
                        file.transaction(if (coded == null) transaction else transaction.copy(payee = coded.payee(transaction.payee), memo = coded.memo(transaction.memo)))
                    }
                file.closing(removedWhileWritten(bounds, file.entriesWritten))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A3 of UC-011: the file cannot be written completely at the chosen place: Slussen stops writing.
            return failed(ExportFailure.NotWritten)
        }
        return JournalExportOutcome.Complete(
            file.entriesWritten, file.transactionsWritten, read.budgets, latest.toList(), removedWhileWritten(bounds, file.entriesWritten),
        )
    }

    /**
     * How many of the entries noted at step 3 are not in the file: no entry with an `id` up to the bound is added after it
     * is noted, so each one counted then and not written was removed meanwhile, by the size limit or a clearing.
     */
    private fun removedWhileWritten(bounds: JournalBounds?, written: Int): Int = (bounds?.count ?: 0) - written

    /**
     * The entries up to the one noted at step 3, oldest first, each handed to [write], one page of short reads at a time
     * with the page handed on ([afterPage]) before the next is read. Each page starts after the last entry written: an
     * entry removed meanwhile, by the size limit or a clearing, is left out, and the next entry still held follows
     * (UC-011 BR-007, S-B-28). Returns false when the Decision Journal could not be read.
     */
    private suspend fun streamEntries(bounds: JournalBounds, afterPage: () -> Unit, write: (JournalEntry) -> Unit): Boolean {
        var lastWritten = 0L
        while (true) {
            val page = try {
                // UC-011 BR-007: The entries are those the Decision Journal holds when they are written, up to the newest noted at step 3.
                reader.entriesAfter(lastWritten, bounds.newestId, pageSize)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                return false
            }
            page.forEach { write(it.entry) }
            afterPage()
            lastWritten = page.lastOrNull()?.id ?: return true
            if (page.size < pageSize || lastWritten >= bounds.newestId) return true
        }
    }

    private fun failed(why: ExportFailure) = JournalExportOutcome.Failed(why, removed = false)

    private fun removeSafely(document: JournalExportDocument): Boolean =
        try {
            document.remove()
        } catch (failure: Exception) {
            false
        }

    private suspend fun <T> safely(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        }

    private fun today(): LocalDate = LocalDate.ofInstant(clock.instant(), clock.zone)
}

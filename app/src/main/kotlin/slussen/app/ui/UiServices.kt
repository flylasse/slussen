package slussen.app.ui

import slussen.app.schedule.FetchSchedule
import slussen.app.schedule.FetchScheduleWork
import slussen.core.connection.BudgetsAndBankAccounts
import slussen.core.credential.ServiceCredentials
import slussen.core.comparison.Comparison
import slussen.core.consent.BankConsents
import slussen.app.ui.consent.ConsentProvider
import slussen.core.fetch.FetchTransactions
import slussen.core.fileimport.ImportTransactionFile
import slussen.core.journal.JournalControl
import slussen.core.journal.JournalExport
import kotlinx.coroutines.flow.StateFlow
import slussen.core.mapping.AccountMappings
import slussen.core.operation.RunningOperation
import slussen.core.settings.SettingsOverview
import slussen.core.settings.DataProviderSettings
import slussen.core.settings.GeneralSettings
import slussen.core.settings.DescriptionLists
import slussen.core.status.BankAccountTransactions
import slussen.core.status.BudgetTransfers
import slussen.core.status.ImportStatus
import slussen.core.review.ReviewDecisions
import slussen.core.transfer.TransferDecisions

/** The core services the screens of this build use, created once by the composition root (ADR-001 §5). */
class UiServices(
    val credentials: ServiceCredentials,
    val settingsOverview: SettingsOverview,
    val connect: BudgetsAndBankAccounts,
    val providerSettings: DataProviderSettings,
    /** The Operator Settings that apply to everything, on Settings (UC-010): the fetch period. */
    val generalSettings: GeneralSettings,
    /** The Card Payment Descriptions and Bank Transfer Descriptions (UC-010 A7, A8), on their own screen from Settings. */
    val descriptionLists: DescriptionLists,
    /** The Decision Journal's own control on Settings (UC-010 A2-A4, BR-003): its switch, size limit, warning threshold and clearing. */
    val journalControl: JournalControl,
    /** Export the Decision Journal (UC-011), from Settings: the Journal Export written to the document the Operator chooses. */
    val journalExport: JournalExport,
    val mappings: AccountMappings,
    val status: ImportStatus,
    /** One Bank Account's Bank Transactions with their Transaction Statuses and reasons (UC-008 step 5), from stored data only. */
    val bankAccountTransactions: BankAccountTransactions,
    /** The Transfers of one Budget with why each is guessed and whether it is eligible (UC-005 step 1), from stored data only. */
    val transfers: BudgetTransfers,
    val fetch: FetchTransactions,
    /** Import a Transaction File (UC-002), from a Budget tab's Transaction File Source or a File Format's page (A7). */
    val fileImport: ImportTransactionFile,
    /** The Review Decisions on doubtful Bank Transactions and Unreadable Transactions (UC-004), from the Bank Account screen. */
    val review: ReviewDecisions,
    /** The Transfer Decisions (UC-005 steps 2-6), from the Transfers of a Budget. */
    val transferDecisions: TransferDecisions,
    /** Reading the Budget App again and comparing (UC-003, FR-040), from the line of the Budget App of the Overview and every Budget tab. */
    val comparison: Comparison,
    val formats: Formats,
    val clock: java.time.Clock,
    /** The version of the build the Operator runs, shown on Settings (UC-010 BR-011). */
    val appVersion: AppVersion,
    /** The operation that runs now, from the operation coordinator, so that a screen shows it (ADR-007 §1, P9-16). */
    val running: StateFlow<RunningOperation?>,
    /** How many operations have ended, from the operation coordinator, so that a screen shows the stored data again (NFR-023). */
    val ended: StateFlow<Long>,
    /** The name of the Budget App of this build, as its part declares it (C-010). */
    val budgetAppName: String = "the Budget App",
    /** The Fetch Schedule's periodic work (UC-001 BR-013): set again on a change (UC-010 A6), and its approximate next time. */
    val fetchSchedule: FetchSchedule = FetchSchedule(FetchScheduleWork.None) { 0 },
    /** Whether the device allows Slussen's notifications now (UC-001 BR-014), for the line on Settings and the question on the Overview. */
    val notificationsAllowed: () -> Boolean = { true },
    /** UC-001 A12 step 3, BR-014: works the New Transaction Notification's number out now, for the Scheduled Fetch's worker before it answers. */
    val updateNotification: suspend () -> Unit = {},
    /** Grant Bank Consent (UC-007): on the page of each Data Provider that needs a Bank Consent; null in a build without one. */
    val bankConsents: BankConsents? = null,
    /** The Data Providers of this build that need a Bank Consent, by the name each part declares (UC-007 BR-008). */
    val consentProviders: List<ConsentProvider> = emptyList(),
    /** Slussen's own return address, `<application id>://consent` (UC-007 BR-011), the one the credentials' form names for an empty field. */
    val applicationReturnAddress: String = "",
    /** Start Over (UC-010 A12), at the end of Settings; null in a screen test that does not show it. */
    val startOver: slussen.core.settings.StartOver? = null,
    /**
     * The process's own scope, in which Start Over runs so that leaving the screen never cancels it and the lane stays
     * held until the process ends (ADR-002 §3 O-17 step 3).
     */
    val processScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default),
    /** O-17 step 3: cancels the Fetch Schedule's work, removes the notification and starts the app again, handing it [StartOverResult]. */
    val restartForStartOver: suspend (slussen.core.settings.StartOverResult) -> Nothing = { kotlinx.coroutines.awaitCancellation() },
    /** What this start read from the record of a Start Over it finished (ADR-002 §3 O-17 step 6); null for any other start. */
    val startOverRecord: slussen.core.port.StartOverRecord? = null,
)

package slussen.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.AppVersion
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.app.ui.components.SettingEditorUi
import slussen.core.port.OperatorSettings
import slussen.core.port.ServiceKey
import slussen.core.settings.FetchPeriodShown
import slussen.core.settings.GeneralSettingsShown
import slussen.core.settings.SettingOutcome
import slussen.core.settings.AccountMappingsEntry
import slussen.core.settings.EntryKind
import slussen.core.settings.EntryStatus
import slussen.core.settings.MappingsWaitFor
import slussen.core.settings.NextThing
import slussen.core.settings.SettingsEntry

/** What an entry at the top of Settings opens: a service's page or a File Format's page (UC-010 BR-010). */
sealed interface EntryPage {
    data class Service(val service: ServiceKey) : EntryPage

    data class FileFormat(val fileFormatId: Long) : EntryPage
}

/** One entry at the top of Settings with its kind and one status line (UC-014 BR-007), marked when it is what comes next (UC-013 BR-008). */
data class EntryLine(val page: EntryPage, val name: String, val kind: String, val status: String, val isNext: Boolean)

/** The Account Mappings entry: available, or not yet with the reason (UC-013 BR-015). */
data class MappingsEntryLine(val available: Boolean, val status: String, val isNext: Boolean)

/** One Operator Setting shown with the value that applies; changing it comes in a later version (`settings.html#list-this-build`). */
data class ShownSettingUi(val name: String, val hint: String, val value: String)

/**
 * The fetch period on Settings (UC-010 steps 1-4, 6, A1; `settings.html#list-this-build`, `#fetch-period-saved`): its
 * value, its default and range, and the answer of the last change, on its own line (NFR-023).
 */
data class FetchPeriodUi(val value: String, val hint: String, val answer: AnswerUi?)

/**
 * Settings (`settings.html#first-run`, `#list`, `#to-map`, `#list-this-build`): at its top the services and File Formats
 * of this build, each with its status, then the Account Mappings (UC-014 step 1, BR-007, UC-010 BR-010, UC-013 step 1,
 * BR-008, BR-015); the fetch period, which the Operator changes here, and the other Operator Settings with the values
 * that apply (UC-010 steps 1-4, 6, A1); and the version of the app in "This app" (UC-010 step 1, BR-011).
 */
data class SettingsUi(
    val entries: List<EntryLine>,
    val accountMappings: MappingsEntryLine,
    val version: String,
    val fetchPeriod: FetchPeriodUi? = null,
    /** Steps 2-4, A1: the dialog that changes the fetch period, or null. */
    val fetchPeriodEditor: SettingEditorUi? = null,
    val shownSettings: List<ShownSettingUi> = emptyList(),
    /** The line that says changing the other Operator Settings comes later and that no action is needed now (NFR-023). */
    val shownSettingsNote: String? = null,
)

/** The top of Settings, generated from the plug-ins' declarations (ADR-001 §4.6). */
class SettingsViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<SettingsUi?>(null)
    val state: StateFlow<SettingsUi?> = mutableState

    fun load() {
        fetchPeriodAnswer = null
        viewModelScope.launch {
            val top = services.settingsOverview.entries()
            val budgetAppName = top.entries.first { it.kind == EntryKind.BudgetApp }.name
            val general = services.generalSettings.shown().also { shown = it }
            mutableState.value = SettingsUi(
                entries = top.entries.map { entry -> line(entry, top.next) },
                accountMappings = mappingsLine(top.accountMappings, budgetAppName),
                version = versionLine(services.appVersion),
                fetchPeriod = fetchPeriodLine(general.fetchPeriod),
                shownSettings = shownSettings(general.values),
                shownSettingsNote = "Changing these Operator Settings comes in a later version; the values shown apply. No action is needed now.",
            )
        }
    }

    // UC-014 BR-007: Each service has one entry with one status line; it opens the service's page, also while it is not set up.
    private fun line(entry: SettingsEntry, next: NextThing?): EntryLine {
        val formats = services.formats
        val page = entry.service?.let { EntryPage.Service(it) } ?: EntryPage.FileFormat(requireNotNull(entry.fileFormatId))
        val lastFetch = (entry.status as? EntryStatus.Imported)?.lastFetchAt
        val kind = when (entry.kind) {
            EntryKind.BudgetApp -> "Budget App"
            EntryKind.FileFormat -> "File Format · Transaction Files"
            else -> "Data Provider" + when {
                lastFetch != null -> " · last Fetch ${formats.time(lastFetch)}"
                entry.needsBankConsent -> " · needs Bank Consent"
                else -> ""
            }
        }
        // UC-013 BR-008: Settings names what comes next on the first entry that still needs it.
        val nextText = if (!entry.isNext) "" else when (next) {
            is NextThing.EnterBudgetAppCredentials -> " · next: enter the ${entry.name} credentials"
            is NextThing.ChooseBudgets -> " · next: choose the Budgets"
            NextThing.EnterDataProviderCredentials -> " · next: enter its credentials"
            is NextThing.FetchOrGrantBankConsent -> if (entry.needsBankConsent) " · ${Texts.BANK_CONSENTS_NOT_IN_THIS_BUILD}" else " · next: fetch it"
            else -> ""
        }
        val status = when (val shown = entry.status) {
            EntryStatus.NotSetUp -> "Not set up"
            EntryStatus.CredentialsRefused -> "Credentials refused"
            is EntryStatus.CredentialsMissing ->
                "Credentials missing" + (shown.bankAccounts?.let { " · " + Texts.count(it, "Bank Account", "Bank Accounts") } ?: "")
            is EntryStatus.BudgetsServed -> if (shown.count == 0) "Connected" else Texts.count(shown.count, "Budget", "Budgets")
            is EntryStatus.NothingOffered -> if (shown.needsBankConsent) "No Bank Consent yet" else "Nothing offered yet"
            is EntryStatus.Imported -> "${shown.imported} of ${shown.of} imported"
            is EntryStatus.BankConsentExpires -> "A Bank Consent expires ${formats.date(shown.expiresAt)}"
            is EntryStatus.FileFormatBankAccounts ->
                if (shown.count == 0) "No Bank Account" else Texts.count(shown.count, "Bank Account", "Bank Accounts")
        }
        return EntryLine(page, entry.name, kind, status + nextText, entry.isNext)
    }

    // UC-013 BR-015: Until the Account Mappings are available, their entry says the first thing missing.
    private fun mappingsLine(entry: AccountMappingsEntry, budgetAppName: String): MappingsEntryLine {
        val status = when (entry.waitingFor) {
            MappingsWaitFor.BudgetAppCredentials -> "Not yet: enter the $budgetAppName credentials first"
            MappingsWaitFor.ChosenBudget -> "Not yet: choose a Budget in $budgetAppName first"
            MappingsWaitFor.ImportedBankAccount -> "Not yet: import a Bank Account from a Data Provider"
            null -> (if (entry.toMap == 0) "Nothing to map" else "${entry.toMap} to map") + if (entry.isNext) " · next" else ""
        }
        return MappingsEntryLine(entry.available, status, entry.isNext)
    }

    // UC-010 BR-011: Settings shows the version name of the build the Operator runs and says when it is a debug build.
    /** Shown, not an Operator Setting: nothing on Settings changes it (`settings.html#list-2`). */
    private fun versionLine(version: AppVersion): String =
        if (version.debugBuild) "${version.versionName} · debug build" else version.versionName

    // ---- The fetch period (UC-010 steps 1-4, 6, A1; O-T-07) --------------------------------------------------------

    /** The fetch period and the other Operator Settings as last read (step 1). */
    private var shown: GeneralSettingsShown? = null

    /** NFR-023: the answer of the last change of the fetch period, at its line. */
    private var fetchPeriodAnswer: AnswerUi? = null

    /** A value that could not be stored, offered again when the Operator taps the fetch period (failure postconditions). */
    private var fetchPeriodRetry: String? = null

    private fun days(value: Int) = "$value days"

    private fun still(period: FetchPeriodShown): String =
        "Still ${days(period.days)}" + if (period.days == period.defaultDays) ", the default." else "."

    private fun fetchPeriodLine(period: FetchPeriodShown) = FetchPeriodUi(
        value = days(period.days),
        hint = "Days back a Fetch asks for · default ${period.defaultDays} · ${period.range.first} to ${period.range.last}",
        answer = fetchPeriodAnswer,
    )

    /**
     * Step 1 (`settings.html#list-this-build`): the Operator Settings this build does not let the Operator change yet,
     * with the values that apply.
     */
    private fun shownSettings(values: OperatorSettings): List<ShownSettingUi> {
        val defaults = OperatorSettings.DEFAULT
        return listOf(
            ShownSettingUi(
                "Fetch Schedule",
                "Scheduled Fetches come in a later version; until then Slussen fetches only when you start a Fetch. No action is needed now.",
                "none yet",
            ),
            ShownSettingUi("Default wait", "Hours · default ${defaults.defaultWaitHours}", "${values.defaultWaitHours} h"),
            ShownSettingUi("Request timeout", "Seconds · default ${defaults.requestTimeoutSeconds}", "${values.requestTimeoutSeconds} s"),
            ShownSettingUi("Number of recent Fetches kept", "Default ${defaults.fetchHistorySize}", "${values.fetchHistorySize}"),
            ShownSettingUi("Upload Window", "Days back that could be uploaded · default ${defaults.uploadWindowDays}", days(values.uploadWindowDays)),
            ShownSettingUi("Transfer pairing days", "Default ${defaults.transferPairingDays}", "${values.transferPairingDays}"),
            ShownSettingUi("Lonely Transfer grace period", "Days · default ${defaults.lonelyTransferGraceDays}", days(values.lonelyTransferGraceDays)),
        )
    }

    /** Step 2 (`#fetch-period-edit`): the dialog with the current value, the default and the range, and what the change does. */
    fun startEditingFetchPeriod() {
        val current = mutableState.value ?: return
        val period = shown?.fetchPeriod ?: return
        mutableState.value = current.copy(
            fetchPeriodEditor = SettingEditorUi(
                title = "Fetch period",
                fieldName = "New value · days · default ${period.defaultDays}",
                value = fetchPeriodRetry ?: period.days.toString(),
                explanation = "How many days back a Fetch asks each Data Provider for Bank Transactions, an Operator Setting: " +
                    "${period.defaultDays} days by default, ${period.range.first} to ${period.range.last}. The new value is asked for " +
                    "from the next Fetch that contacts a Data Provider. Bank Transactions already stored stay. Start Dates and the " +
                    "Upload Window do not change, so which Bank Transactions could be uploaded stays the same.",
            ),
        )
    }

    /** The Operator types a new fetch period; a refusal at the field goes until Save is tapped again. */
    fun editFetchPeriod(text: String) {
        val current = mutableState.value ?: return
        val editor = current.fetchPeriodEditor ?: return
        if (editor.saving) return
        mutableState.value = current.copy(fetchPeriodEditor = editor.copy(value = text, error = null))
    }

    /** Steps 3-4, 6, A1 (`#fetch-period-saving`): Save shows Saving… before anything is stored; the answer follows at the line. */
    fun saveFetchPeriod(text: String) {
        val current = mutableState.value ?: return
        val editor = current.fetchPeriodEditor ?: return
        val period = shown?.fetchPeriod ?: return
        if (editor.saving) return
        val days = text.trim().toIntOrNull()
        if (days == null) {
            mutableState.value = current.copy(
                fetchPeriodEditor = editor.copy(
                    value = text,
                    error = "Enter a whole number of days, ${period.range.first} to ${period.range.last}. ${still(period)}",
                ),
            )
            return
        }
        mutableState.value = current.copy(fetchPeriodEditor = editor.copy(value = text, error = null, saving = true))
        viewModelScope.launch {
            val outcome = services.generalSettings.changeFetchPeriod(days)
            fun refusedAtTheField(error: String) {
                mutableState.value = mutableState.value?.let { ui -> ui.copy(fetchPeriodEditor = ui.fetchPeriodEditor?.copy(error = error, saving = false)) }
            }
            when (outcome) {
                // Steps 4, 6 (`#fetch-period-saved`): the line shows the new value and the result; no Transaction Status changes (step 5).
                is SettingOutcome.Stored -> {
                    val now = services.generalSettings.shown()
                    shown = now
                    fetchPeriodRetry = null
                    fetchPeriodAnswer = AnswerUi(
                        "Saved: ${days(outcome.value)}" +
                            (if (outcome.value == now.fetchPeriod.defaultDays) ", the default." else " (${now.fetchPeriod.defaultDays} by default).") +
                            " Asked for from the next Fetch that contacts a Data Provider; stored Bank Transactions stay; no Transaction " +
                            "Status changes." + if (now.journalOn) " Recorded in the Decision Journal." else "",
                        AnswerKind.Done,
                    )
                    closeFetchPeriodEditor(now)
                }
                // A1 (`#fetch-period-out-of-range`): refused in the dialog, at the field, with the range and the value that stays.
                is SettingOutcome.OutsideRange -> refusedAtTheField(
                    "${days(days)} is too ${if (days > outcome.range.last) "many" else "few"}. " +
                        "Enter ${outcome.range.first} to ${outcome.range.last} days. ${still(period)}",
                )
                // Failure postconditions (`#fetch-period-failed`): the line keeps the previous value and says so.
                SettingOutcome.NotStored -> {
                    fetchPeriodRetry = days.toString()
                    fetchPeriodAnswer = AnswerUi(
                        "Not saved: Slussen could not store ${days(days)}. ${still(period)}",
                        AnswerKind.Failed,
                        "Tap the fetch period to try again.",
                    )
                    closeFetchPeriodEditor(services.generalSettings.shown().also { shown = it })
                }
                SettingOutcome.NotAnOperatorSetting -> closeFetchPeriodEditor(services.generalSettings.shown().also { shown = it })
                is SettingOutcome.Unavailable -> refusedAtTheField(Texts.refusal(outcome.reason))
            }
        }
    }

    /** Step 2 (`#fetch-period-cancelled`): nothing is stored or recorded; the line says the value is unchanged. */
    fun cancelFetchPeriod() {
        val current = mutableState.value ?: return
        val period = shown?.fetchPeriod ?: return
        if (current.fetchPeriodEditor?.saving == true) return
        fetchPeriodAnswer = AnswerUi(
            "Not changed: still ${days(period.days)}" + if (period.days == period.defaultDays) ", the default." else ".",
            AnswerKind.Unchanged,
        )
        closeFetchPeriodEditor(shown ?: return)
    }

    private fun closeFetchPeriodEditor(now: GeneralSettingsShown) {
        mutableState.value = mutableState.value?.copy(fetchPeriodEditor = null, fetchPeriod = fetchPeriodLine(now.fetchPeriod))
    }
}

package slussen.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.AppVersion
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.app.ui.components.AnswerKind
import slussen.app.ui.components.AnswerUi
import slussen.app.ui.components.SettingEditorUi
import kotlinx.coroutines.CancellationException
import slussen.core.port.NumberSetting
import slussen.core.port.OperatorSettings
import slussen.core.port.ServiceKey
import slussen.core.port.SettingValue
import slussen.core.port.SwitchSetting
import slussen.core.settings.GeneralSettingsShown
import slussen.core.settings.NumberSettingShown
import slussen.core.settings.SettingOutcome
import slussen.core.settings.SwitchOutcome
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

/** A value shown on Settings that is not changed here: Transfer detection, and the Decision Journal's own settings. */
data class ShownSettingUi(val name: String, val hint: String, val value: String)

/**
 * The fetch period on Settings (UC-010 steps 1-4, 6, A1; `settings.html#fetch-period-saved`): its value, its default and
 * range, and the answer of the last change, on its own line (NFR-023).
 */
data class FetchPeriodUi(val value: String, val hint: String, val answer: AnswerUi?)

/**
 * One whole-number Operator Setting on Settings (UC-010 steps 1-4, 6, A1; `settings.html#list`, `#list-2`, `#saved`):
 * its name, its default and range, its value, and the answer of its last change at its own line (NFR-023). [tag] names
 * the line for the screen tests.
 */
data class SettingLineUi(val setting: NumberSetting, val name: String, val hint: String, val value: String, val answer: AnswerUi?, val tag: String)

/**
 * An Operator Setting that is on or off (UC-010 A5; `settings.html#list-2`): its state, "Switching on…" or "Switching
 * off…" while it is stored ([pending]), and the answer of its last change at its line.
 */
data class SwitchLineUi(val name: String, val hint: String, val on: Boolean, val pending: String?, val answer: AnswerUi?, val tag: String)

/** One item of a section of Settings. */
sealed interface SettingItemUi {
    data class Number(val line: SettingLineUi) : SettingItemUi

    data class Switch(val line: SwitchLineUi) : SettingItemUi

    data class Shown(val line: ShownSettingUi) : SettingItemUi
}

/** One section of the Operator Settings (`settings.html#list`, `#list-2`), with a line under it, or null. */
data class SettingSectionUi(val title: String, val items: List<SettingItemUi>, val note: String? = null)

/** Steps 2-4, A1, A6: the dialog that changes [setting]. */
data class NumberEditorUi(val setting: NumberSetting, val editor: SettingEditorUi)

/** A5 (`settings.html#detailed-logging`): the statement the Operator confirms before Detailed Logging is switched on. */
data class StatementUi(val title: String, val text: String, val confirm: String, val saving: Boolean = false)

/**
 * Settings (`settings.html#first-run`, `#list`, `#list-2`, `#to-map`): at its top the services and File Formats of this
 * build, each with its status, then the Account Mappings (UC-014 step 1, BR-007, UC-010 BR-010, UC-013 step 1, BR-008,
 * BR-015); the Operator Settings that apply to everything in the sections of the mockup, each changed at its own line
 * (UC-010 steps 1-6, A1, A5, A6); and the version of the app in "This app" (UC-010 step 1, BR-011).
 */
data class SettingsUi(
    val entries: List<EntryLine>,
    val accountMappings: MappingsEntryLine,
    val version: String,
    val sections: List<SettingSectionUi> = emptyList(),
    /** Steps 2-4, A1, A6: the dialog that changes one whole-number Operator Setting, or null. */
    val numberEditor: NumberEditorUi? = null,
    /** A5: the statement before Detailed Logging is switched on, or null. */
    val detailedLoggingStatement: StatementUi? = null,
    /** NFR-023: the settings could not be read again after a change; what is shown is what was read before. */
    val notReadAgain: String? = null,
) {
    /** The line of [setting]. */
    fun line(setting: NumberSetting): SettingLineUi? =
        sections.flatMap { it.items }.filterIsInstance<SettingItemUi.Number>().map { it.line }.firstOrNull { it.setting == setting }

    /** The fetch period's line (`#fetch-period-saved`). */
    val fetchPeriod: FetchPeriodUi? get() = line(NumberSetting.FetchPeriod)?.let { FetchPeriodUi(it.value, it.hint, it.answer) }

    /** The fetch period's dialog, while it is open. */
    val fetchPeriodEditor: SettingEditorUi? get() = numberEditor?.takeIf { it.setting == NumberSetting.FetchPeriod }?.editor

    /** Detailed Logging's line (A5). */
    val detailedLogging: SwitchLineUi?
        get() = sections.flatMap { it.items }.filterIsInstance<SettingItemUi.Switch>().map { it.line }.firstOrNull()
}

/**
 * Settings, generated from the plug-ins' declarations (ADR-001 §4.6) and the stored Operator Settings (UC-010). Every
 * change shows its state at its own control before anything is stored, and its answer at the same line; what a call
 * throws is answered there, never a crash (NFR-023).
 */
class SettingsViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<SettingsUi?>(null)
    val state: StateFlow<SettingsUi?> = mutableState

    /** NFR-023: why Settings could not be shown, with the next action, while its stored data cannot be read. */
    private val mutableNotOpened = MutableStateFlow<String?>(null)
    val notOpened: StateFlow<String?> = mutableNotOpened

    /** The Operator Settings as last read or stored (step 1). */
    private var shown: GeneralSettingsShown? = null

    /** NFR-023: the answer of the last change of each setting, at its line, until Settings is opened again. */
    private val answers = mutableMapOf<NumberSetting, AnswerUi>()

    /** A value that could not be stored, offered again when the Operator taps its line (failure postconditions). */
    private val retries = mutableMapOf<NumberSetting, String>()

    private var detailedLoggingAnswer: AnswerUi? = null
    private var detailedLoggingPending: String? = null

    init {
        viewModelScope.launch {
            // NFR-023: when an operation ends, whichever screen started it, the stored values are shown again; an open dialog and the answers stay.
            services.ended.drop(1).collect { if (mutableState.value != null) showAgain() }
        }
    }

    /** Reads the Operator Settings again and shows them, keeping every dialog and answer; one that cannot be read says so. */
    private suspend fun showAgain() {
        val notReadAgain = try {
            shown = services.generalSettings.shown()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            SETTINGS_NOT_READ_AGAIN
        }
        publish(notReadAgain = notReadAgain)
    }

    fun load() {
        answers.clear()
        detailedLoggingAnswer = null
        viewModelScope.launch {
            try {
                val top = services.settingsOverview.entries()
                val budgetAppName = top.entries.first { it.kind == EntryKind.BudgetApp }.name
                val general = services.generalSettings.shown().also { shown = it }
                mutableState.value = SettingsUi(
                    entries = top.entries.map { entry -> line(entry, top.next) },
                    accountMappings = mappingsLine(top.accountMappings, budgetAppName),
                    version = versionLine(services.appVersion),
                    sections = sections(general),
                    numberEditor = mutableState.value?.numberEditor,
                    detailedLoggingStatement = mutableState.value?.detailedLoggingStatement,
                )
                mutableNotOpened.value = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (mutableState.value == null) mutableNotOpened.value = SETTINGS_NOT_OPENED else publish(notReadAgain = SETTINGS_NOT_READ_AGAIN)
            }
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

    // ---- The Operator Settings that apply to everything (UC-010 steps 1-6, A1, A5, A6) ----------------------------

    /** Step 1 (`settings.html#list`, `#list-2`): the sections of the mockup, each Operator Setting with its value, default and range. */
    private fun sections(general: GeneralSettingsShown): List<SettingSectionUi> {
        fun number(setting: NumberSetting): SettingItemUi {
            val shown = general.number(setting)
            return SettingItemUi.Number(
                SettingLineUi(
                    setting, SettingTexts.name(setting), SettingTexts.hint(shown), SettingTexts.shortValue(setting, shown.value),
                    answers[setting], SettingTexts.tag(setting),
                ),
            )
        }
        val values = general.values
        val defaults = OperatorSettings.DEFAULT
        return listOf(
            SettingSectionUi(
                "Fetching",
                listOf(
                    NumberSetting.FetchSchedule, NumberSetting.FetchPeriod, NumberSetting.DefaultWait, NumberSetting.RequestLimitWarningPause,
                    NumberSetting.RequestTimeout, NumberSetting.RecentFetchesKept,
                ).map(::number),
            ),
            SettingSectionUi("Upload", listOf(NumberSetting.UploadWindow, NumberSetting.FileUploadWindow, NumberSetting.UploadPlanLifetime).map(::number)),
            SettingSectionUi(
                "Transfers",
                // UC-005 BR-001: Transfer detection is always on; it is not an Operator Setting.
                listOf(SettingItemUi.Shown(ShownSettingUi("Transfer detection", "Not an Operator Setting", "Always on"))) +
                    listOf(NumberSetting.TransferPairingDays, NumberSetting.LonelyTransferGracePeriod).map(::number),
            ),
            SettingSectionUi(
                "Status and warnings",
                listOf(
                    NumberSetting.OutdatedBalanceLimit, NumberSetting.ConsentWarningLeadTime, NumberSetting.ConsentRequestLifetime,
                    NumberSetting.BackupWarningThreshold,
                ).map(::number),
            ),
            SettingSectionUi(
                "Decision Journal and logs",
                listOf(
                    SettingItemUi.Shown(ShownSettingUi("Decision Journal", "On by default", if (values.journalOn) "On" else "Off")),
                    SettingItemUi.Shown(
                        ShownSettingUi("Size limit", "MB · default ${defaults.journalSizeLimitMb} · 10 to 500", "${values.journalSizeLimitMb} MB"),
                    ),
                    SettingItemUi.Shown(
                        ShownSettingUi(
                            "Warning threshold", "Share of the size limit · default ${defaults.journalWarningPercent}% · 50% to 95%",
                            "${values.journalWarningPercent}%",
                        ),
                    ),
                    SettingItemUi.Switch(
                        SwitchLineUi(
                            "Detailed Logging", "Off by default", values.detailedLogging, detailedLoggingPending, detailedLoggingAnswer,
                            "detailed-logging",
                        ),
                    ),
                ),
                note = "The Decision Journal's switch, size limit and warning threshold are not changed on this screen yet; the " +
                    "values shown apply. No action is needed now.",
            ),
        )
    }

    /** Shows the sections again from [shown] and the answers, keeping the open dialogs unless told otherwise. */
    private fun publish(
        numberEditor: NumberEditorUi? = mutableState.value?.numberEditor,
        statement: StatementUi? = mutableState.value?.detailedLoggingStatement,
        notReadAgain: String? = null,
    ) {
        val current = mutableState.value ?: return
        val general = shown ?: return
        mutableState.value = current.copy(
            sections = sections(general),
            numberEditor = numberEditor,
            detailedLoggingStatement = statement,
            notReadAgain = notReadAgain,
        )
    }

    /** Step 2 (`#fetch-period-edit`): the fetch period's dialog. */
    fun startEditingFetchPeriod() = startEditing(NumberSetting.FetchPeriod)

    fun editFetchPeriod(text: String) = edit(text)

    fun saveFetchPeriod(text: String) = save(text)

    fun cancelFetchPeriod() = cancel()

    /** Step 2: the dialog of [setting] with its value, the default and the range, and what the change does. */
    fun startEditing(setting: NumberSetting) {
        val current = mutableState.value ?: return
        val general = shown ?: return
        if (current.numberEditor != null || current.detailedLoggingStatement != null) return
        val number = general.number(setting)
        val value = retries[setting] ?: number.value.toString()
        mutableState.value = current.copy(
            numberEditor = NumberEditorUi(
                setting,
                SettingEditorUi(
                    title = SettingTexts.name(setting),
                    fieldName = SettingTexts.fieldName(number),
                    value = value,
                    explanation = explanationFor(number, value),
                ),
            ),
        )
    }

    /** A6 step 1: the Fetch Schedule's dialog says what the value typed means; every other dialog says what its setting is. */
    private fun explanationFor(number: NumberSettingShown, typed: String): String =
        if (number.setting == NumberSetting.FetchSchedule) {
            SettingTexts.fetchSchedule(typed.trim().toIntOrNull()?.takeIf { it in number.range }, number.defaultValue)
        } else {
            SettingTexts.explanation(number)
        }

    /** The Operator types a new value; a refusal at the field goes until Save is tapped again. */
    fun edit(text: String) {
        val current = mutableState.value ?: return
        val open = current.numberEditor ?: return
        if (open.editor.saving) return
        val number = shown?.number(open.setting) ?: return
        mutableState.value = current.copy(
            numberEditor = open.copy(editor = open.editor.copy(value = text, error = null, explanation = explanationFor(number, text))),
        )
    }

    /** Steps 3-4, 6, A1 (`#fetch-period-saving`): Save shows Saving… before anything is stored; the answer follows at the line. */
    fun save(text: String) {
        val current = mutableState.value ?: return
        val open = current.numberEditor ?: return
        val general = shown ?: return
        if (open.editor.saving) return
        val setting = open.setting
        val before = general.number(setting)
        val value = text.trim().toIntOrNull()
        if (value == null) {
            mutableState.value = current.copy(numberEditor = open.copy(editor = open.editor.copy(value = text, error = SettingTexts.notANumber(before))))
            return
        }
        mutableState.value = current.copy(numberEditor = open.copy(editor = open.editor.copy(value = text, error = null, saving = true)))
        viewModelScope.launch {
            val outcome = try {
                services.generalSettings.change(setting, value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SettingOutcome.NotStored
            }
            when (outcome) {
                // Steps 4-6 (`#saved`, `#saved-read-owed`, `#fetch-period-saved`): the line shows the new value and what followed.
                is SettingOutcome.Stored -> {
                    retries.remove(setting)
                    val notReadAgain = readAgain(stored = SettingValue.Number(setting, outcome.value))
                    val now = shown ?: return@launch
                    answers[setting] = AnswerUi(
                        SettingTexts.saved(now.number(setting), outcome.effect, now.journalOn, services.budgetAppName),
                        AnswerKind.Done,
                    )
                    publish(numberEditor = null, notReadAgain = notReadAgain)
                }
                // A1 (`#out-of-range`): refused in the dialog, at the field, with the range and the value that stays.
                // Steps 2-4: the value stored already; nothing was stored or recorded, and the line says so.
                is SettingOutcome.Unchanged -> {
                    retries.remove(setting)
                    answers[setting] = AnswerUi(
                        "Not changed: still ${SettingTexts.value(setting, before.value)}" + if (before.value == before.defaultValue) ", the default." else ".",
                        AnswerKind.Unchanged,
                    )
                    publish(numberEditor = null)
                }
                is SettingOutcome.OutsideRange -> refusedAtTheField(setting, SettingTexts.outsideRange(before, value, outcome.range))
                // Precondition (UC-006 BR-005): unavailable, said in the dialog; nothing is stored.
                is SettingOutcome.Unavailable -> refusedAtTheField(setting, Texts.refusal(outcome.reason))
                // Failure postconditions (`#fetch-period-failed`): the line keeps the previous value and says so.
                SettingOutcome.NotStored, SettingOutcome.NotAnOperatorSetting -> {
                    retries[setting] = value.toString()
                    val notReadAgain = readAgain(stored = null)
                    answers[setting] = AnswerUi(
                        "Not saved: Slussen could not store ${SettingTexts.value(setting, value)}. ${SettingTexts.still(before)}",
                        AnswerKind.Failed,
                        "Tap the ${SettingTexts.inText(setting)} to try again.",
                    )
                    publish(numberEditor = null, notReadAgain = notReadAgain)
                }
            }
        }
    }

    /** The dialog of [setting], if it is still the one open, says [error] at its field and can be used again. */
    private fun refusedAtTheField(setting: NumberSetting, error: String) {
        val current = mutableState.value ?: return
        val open = current.numberEditor?.takeIf { it.setting == setting } ?: return
        mutableState.value = current.copy(numberEditor = open.copy(editor = open.editor.copy(error = error, saving = false)))
    }

    /**
     * Reads the Operator Settings again after a change; when they cannot be read, the value [stored] (if any) is shown
     * as stored, and the line under the settings says that they could not be read again. Returns that line, or null.
     */
    private suspend fun readAgain(stored: SettingValue?): String? =
        try {
            shown = services.generalSettings.shown()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val before = shown
            if (before != null && stored != null) {
                val values = stored.appliedTo(before.values)
                shown = before.copy(
                    values = values,
                    fetchPeriod = before.fetchPeriod.copy(days = values.fetchPeriodDays),
                    numbers = before.numbers.map { it.copy(value = it.setting.valueIn(values)) },
                )
            }
            SETTINGS_NOT_READ_AGAIN
        }

    /** Step 2 (`#fetch-period-cancelled`): nothing is stored or recorded; the line says the value is unchanged. */
    fun cancel() {
        val current = mutableState.value ?: return
        val open = current.numberEditor ?: return
        if (open.editor.saving) return
        val number = shown?.number(open.setting) ?: return
        answers[open.setting] = AnswerUi(
            "Not changed: still ${SettingTexts.value(number.setting, number.value)}" + if (number.value == number.defaultValue) ", the default." else ".",
            AnswerKind.Unchanged,
        )
        publish(numberEditor = null)
    }

    /** Every action of the Operator Settings on Settings, as the screen wires them. */
    fun actions(): SettingsActions = SettingsActions(
        onEdit = ::startEditing,
        onValue = ::edit,
        onSave = ::save,
        onCancel = ::cancel,
        onTapDetailedLogging = ::tapDetailedLogging,
        onConfirmDetailedLogging = ::confirmDetailedLogging,
        onCancelDetailedLogging = ::cancelDetailedLogging,
    )

    // ---- A5: Detailed Logging ---------------------------------------------------------------------------------------

    /** A5: a tap on Detailed Logging: switching it on first states what it means; switching it off needs no statement. */
    fun tapDetailedLogging() {
        val current = mutableState.value ?: return
        val on = shown?.values?.detailedLogging ?: return
        if (detailedLoggingPending != null || current.numberEditor != null || current.detailedLoggingStatement != null) return
        if (on) {
            switchDetailedLogging(false)
        } else {
            publish(
                statement = StatementUi(
                    title = "Switch on Detailed Logging?",
                    text = "Device logs will then contain transaction data and Bank Account numbers, for troubleshooting. They stay " +
                        "on this device and are never in a device backup. Credentials, Bank Consent data and Bank Sessions are never " +
                        "logged, even then.",
                    confirm = "Switch on",
                ),
            )
        }
    }

    /** A5 step 2: the Operator confirms; "Switching on…" shows in the statement until the answer is at the line. */
    fun confirmDetailedLogging() {
        val statement = mutableState.value?.detailedLoggingStatement ?: return
        if (statement.saving) return
        switchDetailedLogging(true)
    }

    /** A5: the Operator cancels the statement; nothing is stored and the line says so. */
    fun cancelDetailedLogging() {
        val statement = mutableState.value?.detailedLoggingStatement ?: return
        if (statement.saving) return
        detailedLoggingAnswer = AnswerUi("Not changed: still off, the default.", AnswerKind.Unchanged)
        publish(statement = null)
    }

    private fun switchDetailedLogging(on: Boolean) {
        detailedLoggingPending = if (on) "Switching on…" else "Switching off…"
        publish(statement = mutableState.value?.detailedLoggingStatement?.copy(saving = true, confirm = "Switching on…"))
        viewModelScope.launch {
            val outcome = try {
                services.generalSettings.switch(SwitchSetting.DetailedLogging, on)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                SwitchOutcome.NotStored
            }
            detailedLoggingPending = null
            val wasOn = shown?.values?.detailedLogging ?: !on
            val notReadAgain = readAgain(stored = if (outcome is SwitchOutcome.Stored) SettingValue.Switch(SwitchSetting.DetailedLogging, on) else null)
            val journal = if (shown?.journalOn == true) " Recorded in the Decision Journal." else ""
            detailedLoggingAnswer = when (outcome) {
                is SwitchOutcome.Stored -> if (outcome.on) {
                    AnswerUi(
                        "Saved: on (off by default). Device logs now contain transaction data and Bank Account numbers, on this device " +
                            "only; never credentials or Bank Consent data.$journal",
                        AnswerKind.Done,
                    )
                } else {
                    AnswerUi(
                        "Saved: off, the default. From now on device logs contain no transaction amounts, payees, memos or Bank " +
                            "Account numbers.$journal",
                        AnswerKind.Done,
                    )
                }
                is SwitchOutcome.Unchanged -> AnswerUi("Not changed: still ${if (outcome.on) "on" else "off, the default"}.", AnswerKind.Unchanged)
                SwitchOutcome.NotStored -> AnswerUi(
                    "Not saved: Slussen could not store it. Still ${if (wasOn) "on" else "off, the default"}.",
                    AnswerKind.Failed,
                    "Tap Detailed Logging to try again.",
                )
                is SwitchOutcome.Unavailable -> AnswerUi(
                    "Not changed: ${Texts.refusal(outcome.reason)} Still ${if (wasOn) "on" else "off, the default"}.",
                    AnswerKind.Failed,
                )
            }
            publish(statement = null, notReadAgain = notReadAgain)
        }
    }

    private companion object {
        /** NFR-023: Settings could not be read; nothing was changed, and Try again reads it again. */
        const val SETTINGS_NOT_OPENED = "Settings could not be read from this device; nothing was changed. Try again."

        /** NFR-023: after a change, the stored settings could not be read again; the values shown are those last read or stored. */
        const val SETTINGS_NOT_READ_AGAIN =
            "The Operator Settings could not be read again from this device; the values shown are the last ones read or stored. Open Settings again to see them."
    }
}

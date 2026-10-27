package slussen.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.AppVersion
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.port.ServiceKey
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

/**
 * Settings (`settings.html#first-run`, `#list`, `#to-map`): at its top the services and File Formats of this build, each
 * with its status, then the Account Mappings (UC-014 step 1, BR-007, UC-010 BR-010, UC-013 step 1, BR-008, BR-015), and
 * the version of the app in "This app" (UC-010 step 1, BR-011).
 */
data class SettingsUi(val entries: List<EntryLine>, val accountMappings: MappingsEntryLine, val version: String)

/** The top of Settings, generated from the plug-ins' declarations (ADR-001 §4.6). */
class SettingsViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<SettingsUi?>(null)
    val state: StateFlow<SettingsUi?> = mutableState

    fun load() {
        viewModelScope.launch {
            val top = services.settingsOverview.entries()
            val budgetAppName = top.entries.first { it.kind == EntryKind.BudgetApp }.name
            mutableState.value = SettingsUi(
                entries = top.entries.map { entry -> line(entry, top.next) },
                accountMappings = mappingsLine(top.accountMappings, budgetAppName),
                version = versionLine(services.appVersion),
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
}

package slussen.app.ui

import slussen.core.connection.BudgetsAndBankAccounts
import slussen.core.credential.ServiceCredentials
import slussen.core.fetch.FetchTransactions
import kotlinx.coroutines.flow.StateFlow
import slussen.core.mapping.AccountMappings
import slussen.core.operation.RunningOperation
import slussen.core.settings.SettingsOverview
import slussen.core.settings.DataProviderSettings
import slussen.core.settings.GeneralSettings
import slussen.core.status.ImportStatus

/** The core services the screens of this build use, created once by the composition root (ADR-001 §5). */
class UiServices(
    val credentials: ServiceCredentials,
    val settingsOverview: SettingsOverview,
    val connect: BudgetsAndBankAccounts,
    val providerSettings: DataProviderSettings,
    /** The Operator Settings that apply to everything, on Settings (UC-010): the fetch period. */
    val generalSettings: GeneralSettings,
    val mappings: AccountMappings,
    val status: ImportStatus,
    val fetch: FetchTransactions,
    val formats: Formats,
    val clock: java.time.Clock,
    /** The version of the build the Operator runs, shown on Settings (UC-010 BR-011). */
    val appVersion: AppVersion,
    /** The operation that runs now, from the operation coordinator, so that a screen shows it (ADR-007 §1, P9-16). */
    val running: StateFlow<RunningOperation?>,
)

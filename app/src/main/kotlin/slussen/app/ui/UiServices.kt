package slussen.app.ui

import slussen.core.connection.BudgetsAndBankAccounts
import slussen.core.credential.ServiceCredentials
import slussen.core.fetch.FetchTransactions
import slussen.core.mapping.AccountMappings
import slussen.core.settings.ConnectionsOverview
import slussen.core.settings.DataProviderSettings
import slussen.core.status.ImportStatus

/** The core services the screens of this build use, created once by the composition root (ADR-001 §5). */
class UiServices(
    val credentials: ServiceCredentials,
    val connections: ConnectionsOverview,
    val connect: BudgetsAndBankAccounts,
    val providerSettings: DataProviderSettings,
    val mappings: AccountMappings,
    val status: ImportStatus,
    val fetch: FetchTransactions,
    val formats: Formats,
    val clock: java.time.Clock,
    /** The version of the build the Operator runs, shown on Settings (UC-010 BR-011). */
    val appVersion: AppVersion,
)

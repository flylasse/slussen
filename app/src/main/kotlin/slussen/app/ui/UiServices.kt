package slussen.app.ui

import slussen.core.connection.BudgetsAndBankAccounts
import slussen.core.credential.ServiceCredentials
import slussen.core.settings.ConnectionsOverview
import slussen.core.status.ImportStatus

/** The core services the screens of this build use, created once by the composition root (ADR-001 §5). */
class UiServices(
    val credentials: ServiceCredentials,
    val connections: ConnectionsOverview,
    val connect: BudgetsAndBankAccounts,
    val status: ImportStatus,
    val formats: Formats,
    val clock: java.time.Clock,
)

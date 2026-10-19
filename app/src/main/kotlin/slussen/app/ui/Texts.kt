package slussen.app.ui

import slussen.core.credential.CredentialOutcome
import slussen.core.credential.CredentialStatus
import slussen.core.operation.OperationRefusal
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.RecoveryCause
import slussen.core.port.StartedBy
import slussen.core.port.StoredPart
import slussen.core.port.TransactionFileOutcome
import slussen.core.settings.ServiceStatus
import slussen.core.status.Blocked
import slussen.core.status.BlockedReason
import slussen.core.status.Remedy
import slussen.core.status.SourceHealth
import slussen.pluginapi.request.FailureKind

/** The texts the screens show for the core's results, in Slussen's glossary terms. */
object Texts {
    fun refusal(reason: OperationRefusal): String = when (reason) {
        OperationRefusal.UploadPlanAwaitingConfirmation ->
            "Unavailable while an Upload Plan awaits confirmation. Confirm or cancel it first."
        OperationRefusal.UploadBeingWritten -> "Unavailable while an Upload is being written."
    }

    fun failure(kind: FailureKind?): String = when (kind) {
        FailureKind.Timeout -> "no answer within the request timeout, an Operator Setting"
        FailureKind.ConnectionLost -> "the connection was lost"
        FailureKind.ServiceUnavailable -> "the service is unavailable"
        FailureKind.UnreadableAnswer -> "its answer could not be read"
        FailureKind.Incomplete -> "the answer was incomplete"
        null -> "it did not answer"
    }

    fun credentialStatus(status: CredentialStatus, formats: Formats): String = when (status) {
        is CredentialStatus.Accepted -> "Accepted ${formats.date(status.acceptedAt)}"
        CredentialStatus.Refused -> "Credentials refused"
        CredentialStatus.Missing -> "Not entered"
    }

    fun outcome(service: String, outcome: CredentialOutcome): String = when (outcome) {
        is CredentialOutcome.Accepted -> "Accepted by $service"
        CredentialOutcome.Refused -> "Refused by $service · nothing stored"
        is CredentialOutcome.NotReached -> "$service could not be reached: ${failure(outcome.kind)} · nothing stored"
        is CredentialOutcome.Unavailable -> refusal(outcome.reason)
    }

    fun serviceStatus(status: ServiceStatus, formats: Formats): String = when (status) {
        is ServiceStatus.Connected -> when {
            status.budgetsInUse != null -> "Connected · ${status.budgetsInUse} Budgets in use"
            status.lastFetchAt != null -> "Connected · last Fetch ${formats.dateTime(status.lastFetchAt!!)}"
            else -> "Connected · no Fetch yet"
        }
        is ServiceStatus.BankConsentExpires -> "Connected · Bank Consent expires ${formats.date(status.expiresAt)}"
        ServiceStatus.CredentialsRefused -> "Credentials refused"
        ServiceStatus.CredentialsMissing -> "Credentials missing"
    }

    fun blockedReason(reason: BlockedReason): String = when (reason) {
        BlockedReason.MissingBankConsent -> "missing Bank Consent"
        BlockedReason.ExpiredBankConsent -> "Bank Consent expired"
        BlockedReason.UnrecognisedBankAccount -> "unrecognised Bank Account"
        BlockedReason.WaitingForProvider -> "waiting for the provider"
        BlockedReason.FailedFetch -> "Fetch failed"
        BlockedReason.RequestLimitReached -> "Request Limit reached"
        BlockedReason.AccessLimitReached -> "access limit reached"
        BlockedReason.CredentialsRefused -> "credentials refused"
        BlockedReason.CredentialsMissing -> "credentials missing"
        BlockedReason.CurrencyMismatch -> "currency mismatch"
        BlockedReason.RecoveryCheckOpen -> "Recovery Check open"
    }

    fun blocked(blocked: Blocked, formats: Formats): String {
        val at = blocked.at?.let {
            when (blocked.remedy) {
                Remedy.WaitUntilItEnds -> " · until ${formats.dateTime(it)}"
                else -> " · ${formats.dateTime(it)}"
            }
        }.orEmpty()
        return "Blocked: ${blocked.subject}, ${blockedReason(blocked.reason)}$at"
    }

    fun remedy(remedy: Remedy): String? = when (remedy) {
        Remedy.GrantBankConsent -> "Grant Bank Consent"
        Remedy.IdentifyBankAccount -> "Confirm the Bank Account"
        Remedy.WaitUntilItEnds -> null
        Remedy.FetchAgain -> "Fetch again"
        Remedy.EnterCredentials -> "Enter credentials"
        Remedy.MoveAccountMapping -> "Move the Account Mapping"
        Remedy.CompareAndConfirm -> "Compare and confirm"
    }

    fun cause(cause: RecoveryCause): String = when (cause) {
        RecoveryCause.NewInstallation -> "New installation · earlier sends unknown · check the Budget App, then confirm"
        RecoveryCause.RestoredBackup -> "Restored backup · sends after it unknown · check the Budget App, then confirm"
        RecoveryCause.LastGoodCopy -> "Started from the last good copy · sends after that copy unknown · check the Budget App, then confirm"
    }

    fun part(part: StoredPart): String = when (part) {
        StoredPart.TransactionsDecisionsAndSettings -> "stored transactions, decisions and settings"
        StoredPart.CredentialsConsentsAndOfferedBankAccounts -> "service credentials, Bank Consents and Offered Bank Accounts"
        StoredPart.DecisionJournal -> "Decision Journal"
    }

    fun startedBy(startedBy: StartedBy): String = when (startedBy) {
        StartedBy.Operator -> "started by you"
        StartedBy.Schedule -> "Fetch Schedule"
    }

    fun health(health: SourceHealth, formats: Formats): String = when (health) {
        is SourceHealth.FetchHealth ->
            "Source Health: ${fetch(health.outcome)} ${formats.dateTime(health.at)} · ${startedBy(health.startedBy)}"
        is SourceHealth.BankAccountFetchHealth ->
            "Source Health: ${fetchResult(health.outcome)} ${formats.dateTime(health.at)} · ${startedBy(health.startedBy)}"
        is SourceHealth.FileImportHealth ->
            "Source Health: ${if (health.outcome == TransactionFileOutcome.Imported) "Imported" else "Rejected"} ${formats.dateTime(health.at)}"
        SourceHealth.NoneYet -> "Source Health: no Fetch yet"
    }

    fun fetch(outcome: FetchOutcome): String = outcome.text

    fun fetchResult(outcome: FetchResultOutcome): String = outcome.text
}

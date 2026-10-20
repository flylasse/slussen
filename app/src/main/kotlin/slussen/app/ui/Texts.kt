package slussen.app.ui

import slussen.core.connection.PairOutcome
import slussen.core.credential.CredentialOutcome
import slussen.core.credential.CredentialStatus
import slussen.core.fetch.FetchReport
import slussen.app.ui.service.FetchWarningUi
import slussen.core.operation.OperationRefusal
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.port.RecoveryCause
import slussen.core.port.StartedBy
import slussen.core.port.StoredPart
import slussen.core.port.TransactionFileOutcome
import slussen.core.settings.NextThing
import slussen.core.settings.ServiceStatus
import slussen.core.status.Blocked
import slussen.core.status.BlockedReason
import slussen.core.status.Remedy
import slussen.core.status.SourceHealth
import slussen.pluginapi.request.FailureKind
import slussen.pluginapi.request.RefusalReason

/** The texts the screens show for the core's results, in Slussen's glossary terms. */
object Texts {
    /**
     * P9-13: Bank Consents (UC-007) are not built yet, said plainly wherever a Data Provider that needs one would act,
     * instead of naming a feature this build does not have.
     */
    const val BANK_CONSENTS_NOT_IN_THIS_BUILD = "Bank Consents are not in this build yet"

    /** P9-13: why a Data Provider that needs a Bank Consent cannot fetch in this build. */
    fun cannotFetchWithoutBankConsents(dataProviderName: String): String =
        "$BANK_CONSENTS_NOT_IN_THIS_BUILD; $dataProviderName cannot fetch until they are"

    /** P9-14: what a Fetch does in this build, while UC-001 steps 7-10 are not built and a Fetch asks for balances only (O-P2-54). */
    const val FETCH_IN_THIS_BUILD =
        "In this build a Fetch lists the Bank Accounts each Data Provider offers and stores the Account Balance of each " +
            "Bank Account you added; Bank Transactions are not fetched yet."

    /** P9-15: what the Budget tab does not show yet in this build. */
    const val TRANSACTIONS_NOT_IN_THIS_BUILD =
        "Bank Transactions are not fetched in this build yet: a Fetch stores the Account Balances, so the counts stay at 0."

    /** UC-008 A8 (P9-14): what an open Recovery Check holds back, in plain words. */
    const val RECOVERY_CHECK_HOLDS_UPLOADS_ONLY = "Only Uploads wait for the Recovery Check; a Fetch still works."

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

    fun outcome(service: String, outcome: CredentialOutcome, formats: Formats): String = when (outcome) {
        is CredentialOutcome.Accepted -> "Accepted by $service"
        CredentialOutcome.Refused -> "Refused by $service · nothing stored"
        is CredentialOutcome.NotReached -> "$service could not be reached: ${failure(outcome.kind)} · nothing stored"
        is CredentialOutcome.RequestLimitReached ->
            "Blocked: Request Limit reached · not checked · frees up from ${formats.dateTime(outcome.freesUpAt)} · " +
                "nothing stored, the earlier credentials stay unchanged"
        is CredentialOutcome.WaitingForProvider ->
            "Blocked: waiting for the provider · not checked · until ${formats.dateTime(outcome.until)} · " +
                "nothing stored, the earlier credentials stay unchanged"
        is CredentialOutcome.NotSent -> "Not checked: ${gateRefusal(outcome.reason)} · nothing stored, the earlier credentials stay unchanged"
        is CredentialOutcome.Unavailable -> refusal(outcome.reason)
    }

    fun gateRefusal(reason: RefusalReason): String = when (reason) {
        RefusalReason.RequestLimitReached -> "Request Limit reached"
        RefusalReason.BackOff -> "waiting for the provider"
        RefusalReason.UnattendedAccessLimitReached -> "access limit reached"
        RefusalReason.OperationEnded -> "the operation ended"
    }

    fun serviceStatus(status: ServiceStatus, formats: Formats): String = when (status) {
        ServiceStatus.NotSetUp -> "Not set up"
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

    /** What one Fetch of a Data Provider came to (UC-001), in one line, the way `budget.html` and `connections.html` show it. */
    fun fetchReport(report: FetchReport, formats: Formats): String {
        val name = report.dataProviderName
        return when (report) {
            is FetchReport.Fetched -> fetched(report, formats)
            is FetchReport.ServedFromFetchCooldown -> listOfNotNull(
                "$name · Served from the Fetch of ${formats.time(report.lastFetchAt)} · next ${formats.time(report.contactsAgainAt)}",
                report.read?.let { read ->
                    "read the Bank Accounts added since: " + read.identified.joinToString(", ").ifEmpty { "none delivered" }
                },
                report.requestLimitFreesUpAt?.let {
                    "Bank Accounts added since not read · Blocked: Request Limit reached · frees up from ${formats.dateTime(it)}"
                },
            ).joinToString(" · ")
            is FetchReport.WaitingForProvider ->
                "Blocked: $name, waiting for the provider · until ${formats.dateTime(report.until)}"
            is FetchReport.RequestLimitReached ->
                "Blocked: $name, Request Limit reached · frees up from ${formats.dateTime(report.freesUpAt)}"
            is FetchReport.Failed ->
                "$name could not be read: ${failure(report.kind)} · ${formats.time(report.failedAt)} · stored data unchanged · Fetch again"
            is FetchReport.CredentialsRefused -> "Blocked: $name, credentials refused · Enter credentials"
            is FetchReport.CredentialsMissing -> "Blocked: $name, credentials missing · Enter credentials"
            // P9-13: said plainly; no Bank is contacted without a valid Bank Consent (UC-001 BR-008), and this build grants none yet.
            is FetchReport.NeedsBankConsent -> "$name · not fetched · ${cannotFetchWithoutBankConsents(name)}"
            is FetchReport.WarningToConfirm -> fetchWarning(report, formats).title
            is FetchReport.NotSent -> "$name · not fetched · ${gateRefusal(report.reason)}"
        }
    }

    private fun fetched(report: FetchReport.Fetched, formats: Formats): String = listOfNotNull(
        "${report.dataProviderName} · Fetched ${formats.time(report.fetchedAt)}",
        "Bank Accounts offered: ${report.offered}",
        report.identified.size.takeIf { it > 0 }?.let { "added and identified: $it" },
        report.notAdded.takeIf { it > 0 }?.let { "not added: $it" },
        report.failed.takeIf { it.isNotEmpty() }?.let { "failed: ${it.joinToString(", ")}, stored data unchanged" },
        report.waitingUntil?.let { "Blocked: waiting for the provider · until ${formats.dateTime(it)}" },
        report.warning?.let {
            "${report.dataProviderName} warned that Slussen is near its Request Limit · Scheduled Fetches leave it alone " +
                "until ${formats.dateTime(it.endsAt)} · further requests can get Slussen's access to " +
                "${report.dataProviderName} disabled"
        },
    ).joinToString(" · ")

    /** UC-001 A16 step 1 (`budget.html#request-limit-warning`): the warning, its end and the risk. */
    fun fetchWarning(report: FetchReport.WarningToConfirm, formats: Formats): FetchWarningUi {
        val name = report.dataProviderName
        val shown = formats
        return FetchWarningUi(
            service = report.service,
            title = "Fetch $name despite its warning?",
            text = "$name warned at ${shown.time(report.warning.warnedAt)} that Slussen is near its Request Limit. " +
                "Scheduled Fetches leave it alone until ${shown.dateTime(report.warning.endsAt)} " +
                "(the Request Limit warning pause, an Operator Setting).",
            risk = "Further requests can get Slussen's access to $name disabled.",
        )
    }

    fun fetchResult(outcome: FetchResultOutcome): String = outcome.text

    /**
     * UC-013 BR-008, UC-008 BR-011: the next thing to do in setting up Slussen, as the first-run card names it, and what
     * follows it (`overview.html#new-installation`, `#first-run-next`).
     */
    fun next(next: NextThing): Pair<String, String?> = when (next) {
        is NextThing.EnterBudgetAppCredentials ->
            "Next: enter the ${next.budgetAppName} credentials" to "Then choose the Budgets, connect a Data Provider and map its Bank Accounts."
        is NextThing.ChooseBudgets ->
            "Next: choose the Budgets in ${next.budgetAppName}" to "Then connect a Data Provider and map its Bank Accounts."
        NextThing.EnterDataProviderCredentials ->
            "Next: enter the credentials of a Data Provider" to "Then fetch it or grant a Bank Consent, and map its Bank Accounts."
        is NextThing.FetchOrGrantBankConsent -> {
            val fetch = next.toFetch.joinToString(" or ").takeIf { it.isNotEmpty() }?.let { "fetch $it" }
            val grant = next.toGrant.takeIf { it.isNotEmpty() }?.let { "grant a Bank Consent in ${it.joinToString(" or ")}" }
            "Next: " + listOfNotNull(fetch, grant).joinToString(" or ") to "Its Bank Accounts are then imported and mapped in Account Mappings."
        }
        is NextThing.MapBankAccounts ->
            "Next: map the ${next.count} imported Bank ${if (next.count == 1) "Account" else "Accounts"} in Account Mappings" to null
    }

    /** "1 Budget", "3 Bank Accounts": a count with its noun. */
    fun count(count: Int, one: String, many: String): String = "$count ${if (count == 1) one else many}"

    /** A Start Date as the Operator enters it: empty, or a date like 2026-09-01 (UC-013 BR-005). */
    fun startDate(text: String): Result<java.time.LocalDate?> = try {
        Result.success(text.trim().takeIf { it.isNotEmpty() }?.let(java.time.LocalDate::parse))
    } catch (e: java.time.format.DateTimeParseException) {
        Result.failure(e)
    }

    /** An ISO 4217 currency code: three capital letters (UC-013 BR-010). */
    fun isCurrencyCode(text: String): Boolean = text.length == 3 && text.all { it in 'A'..'Z' }

    /** Why a Bank Account was not added (UC-013 A5, BR-009, BR-010). */
    fun pairRefusal(outcome: PairOutcome): String = when (outcome) {
        is PairOutcome.BudgetAccountAlreadyMapped -> "${outcome.budgetAccountName} already receives ${outcome.bankAccountName} · not added"
        is PairOutcome.CurrencyDiffers ->
            "${outcome.budgetAccountName} is in ${outcome.budgetCurrency.code}, ${outcome.bankAccountName} in ${outcome.bankAccountCurrency.code} · not added"
        PairOutcome.CurrencyNotStated -> "State the currency of the Bank Account · not added"
        PairOutcome.NotOffered -> "Not offered for pairing"
        PairOutcome.NameNotValid -> "A name is needed, at most 100 characters · not added"
        PairOutcome.TypeNotChosen -> "Choose the Bank Account type · not added"
        PairOutcome.BankNotChosen -> "Choose a Bank or name a new one · not added"
        is PairOutcome.Unavailable -> refusal(outcome.reason)
        is PairOutcome.Proposed, is PairOutcome.Added -> ""
    }
}

package slussen.app.ui

import slussen.core.connection.PairOutcome
import slussen.core.credential.CredentialOutcome
import slussen.core.credential.CredentialStatus
import slussen.core.fetch.FetchReport
import slussen.app.ui.service.FetchWarningUi
import slussen.core.operation.OperationRefusal
import slussen.core.port.FetchOutcome
import slussen.core.port.FetchResultOutcome
import slussen.core.firstupload.FirstUploadMatchingNotice
import slussen.app.ui.components.FirstUploadMatchingUi
import slussen.app.ui.components.FetchResultKind
import slussen.app.ui.components.FetchResultUi
import slussen.core.port.FirstUploadMatchingCause
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

    /** UC-008 BR-006, BR-009: the Blocked Reason of First Upload Matching. */
    const val UPLOADS_WAIT_FOR_FIRST_UPLOAD_MATCHING = "Uploads wait for First Upload Matching"

    /** UC-008 BR-006, BR-009, A9: the action of First Upload Matching. */
    const val START_FIRST_UPLOAD_MATCHING = "Start First Upload Matching"

    /** UC-008 A8, BR-009: the banner of the open First Upload Matchings, with the Budget App's name. */
    fun firstUploadMatching(notice: FirstUploadMatchingNotice, budgetAppName: String): FirstUploadMatchingUi = FirstUploadMatchingUi(
        title = UPLOADS_WAIT_FOR_FIRST_UPLOAD_MATCHING,
        explanation = firstUploadMatchingExplanation(budgetAppName),
        reasons = notice.causes.map { reason(it, budgetAppName) },
        tone = notice.tone,
        action = START_FIRST_UPLOAD_MATCHING,
        actionAvailable = notice.canStart,
        // O-P2-09 (`first-upload-matching.html#open`): while the Comparison is not built, the line says why nothing starts.
        buildNote = if (notice.canStart) null else "Matching with $budgetAppName comes in a later version; until then nothing is uploaded.",
    )

    /** UC-008 BR-009: the explanation, the same in every case, with the Budget App's name. */
    fun firstUploadMatchingExplanation(budgetAppName: String): String =
        "Before Slussen uploads anything, it compares the transactions it fetched with what is already in $budgetAppName " +
            "and marks every match, so that no transaction ends up in $budgetAppName twice. You look at the result and " +
            "confirm; until then nothing is uploaded. Fetching works as usual."

    /** UC-008 BR-009: the reason line of each cause, without a date (ADR-002 §6). */
    fun reason(cause: FirstUploadMatchingCause, budgetAppName: String): String = when (cause) {
        FirstUploadMatchingCause.NewInstallation ->
            "This is a new installation. Your earlier importer, $budgetAppName's own bank import or you by hand may already " +
                "have added some of these transactions to $budgetAppName."
        FirstUploadMatchingCause.RestoredBackup ->
            "Slussen was restored from a backup. It may have uploaded transactions after the backup was made that its " +
                "records no longer show."
        FirstUploadMatchingCause.LastGoodCopy ->
            "Slussen's records could not be read, so it started from its last good copy, or from empty records when it had " +
                "none. Anything uploaded after that copy was made is not in its records."
    }

    /** P9-16 (`budget.html#fetch-states`): the text of the Fetch button while a Fetch of [subject] runs. */
    fun fetching(subject: String?): String = if (subject == null) "Fetching…" else "Fetching $subject…"

    /** P9-16: the short message for the result of one tap of a Fetch of one Data Provider. */
    fun fetchResult(report: FetchReport, formats: Formats, tap: Int): FetchResultUi = when (report) {
        is FetchReport.Fetched -> FetchResultUi("Fetched ${formats.time(report.fetchedAt)}", fetchedDetail(report, formats), FetchResultKind.Fetched, tap)
        // A2 of UC-001: the data comes from the last Fetch, and when a new Fetch contacts the Data Provider again.
        is FetchReport.ServedFromFetchCooldown -> when (val read = report.read) {
            null -> FetchResultUi(
                "Not fetched again: the Fetch of ${formats.time(report.lastFetchAt)} is still fresh until " +
                    "${formats.time(report.contactsAgainAt)} (Fetch Cooldown)",
                report.requestLimitFreesUpAt?.let {
                    "Bank Accounts added since not read · Request Limit reached · frees up from ${formats.dateTime(it)}"
                },
                FetchResultKind.Cooldown,
                tap,
            )
            else -> FetchResultUi(
                "Fetched ${formats.time(read.fetchedAt)} the Bank Accounts added since the Fetch of " +
                    "${formats.time(report.lastFetchAt)}, which is still fresh until ${formats.time(report.contactsAgainAt)} (Fetch Cooldown)",
                "read: " + read.identified.joinToString(", ").ifEmpty { "none delivered" },
                FetchResultKind.Fetched,
                tap,
            )
        }
        // A1 of UC-001: "waiting for the provider" with the time the Back-off ends.
        is FetchReport.WaitingForProvider ->
            FetchResultUi("Not fetched: waiting for the provider until ${formats.dateTime(report.until)}", null, FetchResultKind.NotFetched, tap)
        // A3 of UC-001: "Request Limit reached" with the time the oldest counted Provider Request leaves the 24-hour window.
        is FetchReport.RequestLimitReached ->
            FetchResultUi("Not fetched: Request Limit reached · frees up from ${formats.dateTime(report.freesUpAt)}", null, FetchResultKind.NotFetched, tap)
        // A6 of UC-001: the failure with its time; stored data unchanged, no Back-off.
        is FetchReport.Failed -> FetchResultUi(
            "Failed ${formats.time(report.failedAt)}: ${failure(report.kind)}",
            "Stored data unchanged · you can fetch again at any time",
            FetchResultKind.Failed,
            tap,
        )
        // A7 of UC-001: "credentials refused" with the action that enters them again (UC-014).
        is FetchReport.CredentialsRefused ->
            FetchResultUi("Not fetched: credentials refused · enter them again", null, FetchResultKind.Failed, tap)
        is FetchReport.CredentialsMissing -> FetchResultUi("Not fetched: credentials missing · enter them", null, FetchResultKind.NotFetched, tap)
        // P9-13: said plainly; no Bank is contacted without a valid Bank Consent (UC-001 BR-008), and this build grants none yet.
        is FetchReport.NeedsBankConsent ->
            FetchResultUi("Not fetched: ${cannotFetchWithoutBankConsents(report.dataProviderName)}", null, FetchResultKind.NotFetched, tap)
        is FetchReport.WarningToConfirm -> FetchResultUi(fetchWarning(report, formats).title, null, FetchResultKind.NotFetched, tap)
        is FetchReport.NotSent -> FetchResultUi("Not fetched: ${gateRefusal(report.reason)}", null, FetchResultKind.NotFetched, tap)
    }

    /** What a Fetch that read the Data Provider came to besides its time (UC-001 steps 6, 11, A5, A8, A15, A18). */
    private fun fetchedDetail(report: FetchReport.Fetched, formats: Formats): String = listOfNotNull(
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
        BlockedReason.UploadsWaitForFirstUploadMatching -> UPLOADS_WAIT_FOR_FIRST_UPLOAD_MATCHING
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
        Remedy.StartFirstUploadMatching -> START_FIRST_UPLOAD_MATCHING
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
            "Next: enter the credentials of a Data Provider" to "Then fetch it and map its Bank Accounts."
        // P9-13 (#16): Bank Consents are not in this build, so granting one is never named as the next step.
        is NextThing.FetchOrGrantBankConsent -> {
            val waiting = next.toGrant.takeIf { it.isNotEmpty() }
                ?.let { " ${it.joinToString(" and ")} ${if (it.size == 1) "needs" else "need"} a Bank Consent: $BANK_CONSENTS_NOT_IN_THIS_BUILD." }
                .orEmpty()
            val title = if (next.toFetch.isEmpty()) "Next: enter the credentials of a Data Provider that needs no Bank Consent" else "Next: fetch ${next.toFetch.joinToString(" or ")}"
            title to "Its Bank Accounts are then imported and mapped in Account Mappings.$waiting"
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

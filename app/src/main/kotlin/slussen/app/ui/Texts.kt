package slussen.app.ui

import slussen.core.connection.PairOutcome
import slussen.core.credential.CredentialOutcome
import slussen.core.credential.CredentialStatus
import slussen.core.fetch.BalanceInOtherCurrency
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
import slussen.app.ui.budget.RemedyAction
import slussen.core.comparison.BudgetReadOutcome
import slussen.core.comparison.ComparisonReport
import slussen.core.comparison.ComparisonStart
import slussen.core.operation.OperationKind
import slussen.core.operation.RunningOperation
import slussen.core.port.ServiceKey
import slussen.core.status.BalanceComparison
import slussen.core.status.BudgetAppRead
import slussen.core.status.BudgetSummary
import slussen.core.status.NotYetUploaded
import slussen.core.status.StatusesAsOf
import slussen.pluginapi.value.Amount
import java.time.Instant

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

    /**
     * P9-14 (`overview.html#open-this-build`): what a Fetch does, since UC-001 step 10 compares with the Budget App, and
     * the one part it does not reach yet, Uploads (UC-006), for which no action is needed.
     */
    fun fetchNote(budgetAppName: String): String =
        "A Fetch brings in the Bank Transactions and compares them with $budgetAppName. Nothing is uploaded in this version; " +
            "no action is needed for Uploads now."

    /** UC-008 step 5: the line of a Bank Account card that opens its own screen (`budget.html` → `bank-account.html`). */
    const val OPEN_BANK_TRANSACTIONS = "Bank Transactions and their statuses ›"

    /**
     * `budget.html#household-this-build`: the one line of a Budget tab about the parts not built yet, Uploads (UC-006),
     * Review Decisions (UC-004) and Transfer Decisions (UC-005), for which no action is needed.
     */
    const val UPLOADS_NOT_IN_THIS_BUILD =
        "Nothing is uploaded in this version: Uploads, Review Decisions and Transfer Decisions come in a later version. " +
            "No action is needed for them now."

    /** `overview.html#open-this-build`: why Start First Upload Matching (UC-008 A9) cannot run yet; no action is needed. */
    const val FIRST_UPLOAD_MATCHING_COMES_WITH_UPLOADS =
        "First Upload Matching comes with Uploads in a later version. Nothing is uploaded until then, and no action is needed now."

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
        // `overview.html#open-this-build`: while its confirmation (A9) is not built, the line says why nothing starts and that no action is needed.
        buildNote = if (notice.canStart) null else FIRST_UPLOAD_MATCHING_COMES_WITH_UPLOADS,
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
        report.sharedReference.takeIf { it > 0 }?.let { "not used, ${report.dataProviderName} gave them the same identifier: $it" },
        report.failed.takeIf { it.isNotEmpty() }?.let { "failed: ${it.joinToString(", ")}, stored data unchanged" },
        // A19 of UC-001: the Bank Accounts the answer did not contain, with the advice to check them at the Data Provider.
        report.notDelivered.takeIf { it.isNotEmpty() }?.let {
            "not delivered by ${report.dataProviderName}: ${it.joinToString(", ")}, stored data unchanged · check at " +
                "${report.dataProviderName} that ${if (it.size == 1) "it is" else "they are"} still connected there, then fetch again"
        },
        // A20 of UC-001: in Slussen's own words, never the Data Provider's text (NFR-012).
        report.signInNeeded.takeIf { it.isNotEmpty() }?.let {
            "the Bank's sign-in at ${report.dataProviderName} needs you: ${it.joinToString(", ")}, stored data unchanged · " +
                "sign in to the Bank again at ${report.dataProviderName}, then fetch again"
        },
        // A21 of UC-001: both currencies named; nothing converted.
        report.balancesInOtherCurrency.takeIf { it.isNotEmpty() }?.let { notices ->
            notices.joinToString(" · ") { "${it.bankAccountName}: ${balanceInOtherCurrency(it)}, not stored" }
        },
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

    /**
     * UC-001 A21 step 2 (`bank-account.html#balance-other-currency`): the balance reported in another currency, naming
     * both currencies; nothing is converted.
     */
    fun balanceInOtherCurrency(notice: BalanceInOtherCurrency): String =
        "balance reported in ${notice.reported.code}, expected ${notice.expected.code}"

    /** UC-001 A21 step 2: what the Bank Account says, and that nothing is needed now. */
    fun balanceInOtherCurrencyNotice(notice: BalanceInOtherCurrency, formats: Formats): String =
        "${balanceInOtherCurrency(notice).replaceFirstChar { it.uppercase() }} · at the Fetch of ${formats.dateTime(notice.fetchedAt)} " +
            "${notice.dataProviderName} reported it in ${notice.reported.code}; it was not stored and no amount was converted. " +
            "Nothing is needed now. If it happens again, check at ${notice.dataProviderName} which currency it shows for " +
            "${notice.bankAccountName}."

    fun blockedReason(reason: BlockedReason, dataProviderName: String? = null): String = when (reason) {
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
        // A19 of UC-001.
        BlockedReason.NotDelivered -> "not delivered by ${dataProviderName ?: "the Data Provider"}"
        // A20 of UC-001: Slussen's own words, naming the Data Provider.
        BlockedReason.SignInNeeded -> "the Bank's sign-in at ${dataProviderName ?: "the Data Provider"} needs you"
    }

    /**
     * The advice a Blocked Reason carries beside its action (UC-008 BR-006): for not delivered, to check the Bank Account
     * at the Data Provider (UC-001 A19); for the Bank's sign-in, to sign in there again (UC-001 A20). Both keep the stored
     * data unchanged.
     */
    fun advice(blocked: Blocked): String? {
        val provider = blocked.dataProviderName ?: "the Data Provider"
        return when (blocked.reason) {
            BlockedReason.NotDelivered ->
                "$provider answered without this Bank Account; its Bank Transactions and Account Balance are kept unchanged. " +
                    "Check at $provider that ${blocked.subject} is still connected there, then fetch again."
            BlockedReason.SignInNeeded ->
                "$provider cannot read this Bank until you sign in to it again there; its Bank Transactions and Account " +
                    "Balance are kept unchanged. Sign in to the Bank of ${blocked.subject} again at $provider, then fetch " +
                    "again. Your $provider credentials in Slussen stay as they are."
            else -> null
        }
    }

    fun blocked(blocked: Blocked, formats: Formats): String {
        val at = blocked.at?.let {
            when (blocked.remedy) {
                Remedy.WaitUntilItEnds -> " · until ${formats.dateTime(it)}"
                else -> " · ${formats.dateTime(it)}"
            }
        }.orEmpty()
        return "Blocked: ${blocked.subject}, ${blockedReason(blocked.reason, blocked.dataProviderName)}$at"
    }

    fun remedy(remedy: Remedy): String? = when (remedy) {
        Remedy.GrantBankConsent -> "Grant Bank Consent"
        Remedy.IdentifyBankAccount -> "Confirm the Bank Account"
        Remedy.WaitUntilItEnds -> null
        Remedy.FetchAgain -> "Fetch again"
        Remedy.EnterCredentials -> "Enter credentials"
        Remedy.MoveAccountMapping -> "Move the Account Mapping"
        Remedy.StartFirstUploadMatching -> START_FIRST_UPLOAD_MATCHING
        // A20 of UC-001: the sign-in happens at the Data Provider; the action here is the Fetch after it.
        Remedy.SignInAtDataProvider -> "Fetch again"
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

    // ---- The line of the Budget App (UC-003 trigger, FR-040, A1, A2; `budget.html`, the states of Read again) -----------------

    /** FR-040: the action on the line of the Budget App, on the Overview and on every Budget tab. */
    fun readAgain(budgetAppName: String): String = "Read $budgetAppName again"

    /** NFR-023: shown from the tap on, before the first request is sent; no Data Provider or Bank is contacted. */
    fun reading(budgetAppName: String): String = "Reading $budgetAppName · no Bank Fetch"

    /** NFR-023, ADR-007 §1: pressed while another operation runs, the action waits for it and says so. */
    fun readWaits(budgetAppName: String, running: RunningOperation): String = "${readAgain(budgetAppName)} · waits for: ${operation(running)}"

    /** ADR-007 §1: the operation that runs now, as the screens name it. */
    fun operation(running: RunningOperation): String = when (running.kind) {
        OperationKind.Fetch -> "Fetching ${running.subject ?: "a Data Provider"}"
        OperationKind.Comparison -> "Reading ${running.subject ?: "the Budget App"}"
        OperationKind.ServiceCredentialChange -> "a change of service credentials"
        OperationKind.BudgetAndBankAccountChange -> "a change of Budgets and Bank Accounts"
        OperationKind.FirstUploadMatchingConfirmation -> "the confirmation of First Upload Matching"
        OperationKind.OperatorSettingChange -> "a change of an Operator Setting"
        OperationKind.AccountMappingChange -> "a change of an Account Mapping"
    }

    /** UC-003 A1: what the Budget App did when the read of a Budget failed; null when the read was not completed. */
    fun readFailure(kind: FailureKind?): String = if (kind == null) "the read was not completed" else failure(kind)

    /** UC-003 A1 step 2: the remedy of statuses that are not current, a read of the Budget App, nothing else. */
    fun readAgainNote(budgetAppName: String): String = "Read $budgetAppName again to bring them up to date; nothing else is needed."

    /**
     * The line of the Budget App on the Overview (FR-040, `overview.html#read-one-failed`): the latest successful read,
     * and the Budgets not read since, or that the read failed when no Budget was read.
     */
    fun budgetAppRead(read: BudgetAppRead, comparedBudgets: Int, budgetAppName: String, formats: Formats, now: Instant): String {
        val last = read.lastReadAt
        val failedAt = read.failedAt
        return when {
            failedAt == null -> if (last == null) "$budgetAppName · not read yet" else "$budgetAppName · read ${formats.moment(last, now)}"
            last == null -> "$budgetAppName · read failed ${formats.moment(failedAt, now)}"
            read.notRead.size >= comparedBudgets ->
                "$budgetAppName · last read ${formats.moment(last, now)} · read failed ${formats.moment(failedAt, now)}"
            else -> "$budgetAppName · read ${formats.moment(last, now)} · ${read.notRead.joinToString(", ")} not read"
        }
    }

    /** The line of the Budget App on a Budget tab (`budget.html#budget-app-read`, `#budget-app-unreadable`, `#first-read-failed`). */
    fun budgetAppReadOf(asOf: StatusesAsOf, budgetAppName: String, formats: Formats, now: Instant): String = when (asOf) {
        StatusesAsOf.NothingToCompare -> "$budgetAppName · nothing to read: no Bank Account of this Budget is compared"
        is StatusesAsOf.NotReadYet -> asOf.failedRead?.let {
            "$budgetAppName · read failed ${formats.moment(it.failedAt, now)} · ${readFailure(it.kind)}"
        } ?: "$budgetAppName · not read yet"
        is StatusesAsOf.LastRead -> asOf.failedRead?.let {
            "$budgetAppName · last read ${formats.moment(asOf.readAt, now)} · read failed ${formats.moment(it.failedAt, now)}"
        } ?: "$budgetAppName · read ${formats.moment(asOf.readAt, now)}"
    }

    /**
     * UC-003 step 7, A1 step 2: when a Budget's Transaction Statuses were given; never shown as current when the read since
     * failed (failure postcondition). Null for a Budget with nothing to compare.
     */
    fun statusesAsOf(asOf: StatusesAsOf, budgetAppName: String, formats: Formats, now: Instant): String? = when (asOf) {
        StatusesAsOf.NothingToCompare -> null
        is StatusesAsOf.NotReadYet -> asOf.failedRead?.let {
            "No Transaction Statuses yet: the read of ${formats.moment(it.failedAt, now)} failed: ${readFailure(it.kind)}"
        } ?: "No Transaction Statuses yet: $budgetAppName has not been read for this Budget"
        is StatusesAsOf.LastRead -> asOf.failedRead?.let {
            "Statuses as of ${formats.moment(asOf.readAt, now)}, its last successful read · the read of " +
                "${formats.moment(it.failedAt, now)} failed: ${readFailure(it.kind)}"
        } ?: "Statuses as of ${formats.moment(asOf.readAt, now)}"
    }

    /**
     * UC-003 A1 step 2: the next action for a Budget whose statuses are not current, or null while they are: a read of
     * the Budget App, after entering its credentials while they are missing or refused (UC-014, A2).
     */
    fun readAgainNote(summary: BudgetSummary, budgetAppName: String): String? {
        val waits = when (val asOf = summary.statusesAsOf) {
            StatusesAsOf.NothingToCompare -> false
            is StatusesAsOf.NotReadYet -> true
            is StatusesAsOf.LastRead -> asOf.failedRead != null || summary.notComparedYet > 0
        }
        if (!waits) return null
        return when (summary.budgetAppCredentials) {
            BlockedReason.CredentialsMissing ->
                "Enter the credentials of $budgetAppName in Settings, then read $budgetAppName again; nothing else is needed."
            BlockedReason.CredentialsRefused ->
                "$budgetAppName refused Slussen's credentials: enter them again in Settings, then read $budgetAppName again; " +
                    "nothing else is needed."
            else -> readAgainNote(budgetAppName)
        }
    }

    /** UC-008 BR-003, UC-003 A1 (S-T-08): the counts, with the Bank Transactions not compared yet apart from them. */
    fun counts(counts: NotYetUploaded, notComparedYet: Int): String =
        "Eligible ${counts.eligible} · waiting for a decision ${counts.waitingForDecision}" +
            if (notComparedYet > 0) " · not compared yet $notComparedYet" else ""

    /** UC-003 A1 step 2, S-T-08 (`budget.html#first-read-failed`): what not compared yet means, or null when none is. */
    fun notComparedYet(count: Int, budgetAppName: String): String? = when (count) {
        0 -> null
        1 -> "Not compared yet: 1 Bank Transaction, stored since the last successful read of $budgetAppName; it has no " +
            "Transaction Status and is counted neither as Eligible nor as waiting for a decision."
        else -> "Not compared yet: $count Bank Transactions, stored since the last successful read of $budgetAppName; they " +
            "have no Transaction Status and are counted neither as Eligible nor as waiting for a decision."
    }

    /**
     * The result of one tap of Read the Budget App again, at its line (NFR-023, UX-12): every outcome of the read in the
     * Operator's words, with the remedy of missing or refused credentials (UC-014). [now] is the time of the answer.
     */
    fun readResult(start: ComparisonStart, budgetAppName: String, formats: Formats, now: Instant, tap: Int): Pair<FetchResultUi, RemedyAction?> {
        val enter = RemedyAction("Enter credentials", available = true, service = ServiceKey.budgetApp, note = null)
        val report = when (start) {
            is ComparisonStart.Unavailable -> return FetchResultUi(refusal(start.reason), null, FetchResultKind.NotFetched, tap) to null
            is ComparisonStart.Ran -> start.report
        }
        return when (report) {
            ComparisonReport.NothingToCompare -> FetchResultUi(
                "Nothing to compare: no Bank Account is mapped to a Budget · map one in Account Mappings", null, FetchResultKind.NotFetched, tap,
            ) to null
            ComparisonReport.CredentialsMissing ->
                FetchResultUi("$budgetAppName not read: credentials missing · enter them", null, FetchResultKind.NotFetched, tap) to enter
            // A2 of UC-003: "credentials refused" with the action that enters them again (UC-014).
            ComparisonReport.CredentialsRefused ->
                FetchResultUi("Credentials refused by $budgetAppName · enter them again · statuses kept", null, FetchResultKind.Failed, tap) to enter
            is ComparisonReport.Compared -> compared(report, budgetAppName, formats, now, tap) to null
        }
    }

    private fun compared(report: ComparisonReport.Compared, budgetAppName: String, formats: Formats, now: Instant, tap: Int): FetchResultUi {
        val read = report.budgets.filterIsInstance<BudgetReadOutcome.Read>()
        val notRead = report.budgets.filterIsInstance<BudgetReadOutcome.NotRead>()
        fun asOf(outcome: BudgetReadOutcome.NotRead) =
            outcome.lastReadAt?.let { "statuses as of ${formats.moment(it, now)}" } ?: "no statuses yet"
        val readAt = read.maxOfOrNull { it.readAt }
        return when {
            // A1 of UC-003: no Budget read; each keeps its own last successful read, and each failure its own kind.
            readAt == null -> FetchResultUi(
                "$budgetAppName not read ${formats.moment(now, now)}" + if (notRead.map { it.kind }.distinct().size == 1) {
                    ": ${readFailure(notRead.first().kind)}"
                } else {
                    notRead.joinToString("") { " · ${it.budgetName}: ${readFailure(it.kind)}" }
                },
                notRead.joinToString(" · ") { "${it.budgetName}: ${asOf(it)}" } + " · " + READ_FAILED_DETAIL,
                FetchResultKind.Failed,
                tap,
            )
            notRead.isEmpty() -> FetchResultUi(
                "$budgetAppName read ${formats.moment(readAt, now)} · statuses as of ${formats.moment(readAt, now)}",
                "Transaction Statuses changed: ${report.statusesChanged} · no Bank Fetch",
                FetchResultKind.Fetched,
                tap,
            )
            // A1 of UC-003: the others are compared as usual; each one not read keeps the statuses of its last successful read.
            else -> FetchResultUi(
                "$budgetAppName read ${formats.moment(readAt, now)} · " +
                    notRead.joinToString(" · ") { "${it.budgetName} not read: ${readFailure(it.kind)}" },
                notRead.joinToString(" · ") { "${it.budgetName}: ${asOf(it)}" } + " · " + readAgainNote(budgetAppName),
                FetchResultKind.NotFetched,
                tap,
            )
        }
    }

    /** NFR-023: a read that could not be completed is an answer at its line, never a crash. */
    fun readNotCompleted(budgetAppName: String, tap: Int): FetchResultUi =
        FetchResultUi("$budgetAppName not read: the read could not be completed", READ_FAILED_DETAIL, FetchResultKind.Failed, tap)

    /** NFR-023: the stored status could not be read again after an action; said at the action's line, never a crash. */
    const val STATUS_NOT_READ = "The status stored on this device could not be read; nothing was changed by it. Open the screen again to see it."

    private const val READ_FAILED_DETAIL = "The stored statuses are kept; read again at any time. No Bank Fetch is needed."

    // ---- Balances (UC-008 step 7, BR-004, BR-005, A2-A4; UX-08, UX-24, UX-25) ------------------------------------------

    /**
     * The Account Balance next to its Budget Account's balance: the Bank balance as of the time the Data Provider reported
     * it, the Budget Account balance read at the time of the Budget App's read (UX-08).
     */
    fun balance(balance: BalanceComparison, formats: Formats, now: Instant): String {
        fun bank(amount: Amount, at: Instant?) = "Bank balance ${formats.amount(amount)}" + (at?.let { " as of ${formats.moment(it, now)}" } ?: "")
        fun budgetAccount(amount: Amount, at: Instant?) =
            "Budget Account balance ${formats.amount(amount)}" + (at?.let { " read ${formats.moment(it, now)}" } ?: "")
        return when (balance) {
            is BalanceComparison.Compared -> listOf(
                bank(balance.accountBalance, balance.accountBalanceReadAt),
                budgetAccount(balance.budgetAccountBalance, balance.budgetAccountBalanceReadAt),
                "Difference ${formats.difference(balance.difference)}",
            ).joinToString(" · ")
            is BalanceComparison.Outdated -> listOf(
                bank(balance.accountBalance, balance.accountBalanceReadAt),
                balance.budgetAccountBalance?.let { budgetAccount(it, balance.budgetAccountBalanceReadAt) } ?: "Budget Account balance not known",
                "Account Balance outdated · difference hidden",
            ).joinToString(" · ")
            is BalanceComparison.CurrencyMismatch ->
                "Bank Account in ${balance.bankAccountCurrency.code}, Budget in ${balance.budgetCurrency.code} · " +
                    "currency mismatch · difference hidden"
            is BalanceComparison.NotKnown -> listOf(
                balance.accountBalance?.takeIf { balance.accountBalanceKnown }?.let { bank(it, balance.accountBalanceReadAt) }
                    ?: "Account Balance not known",
                balance.budgetAccountBalance?.takeIf { balance.budgetAccountBalanceKnown }?.let { budgetAccount(it, balance.budgetAccountBalanceReadAt) }
                    ?: "Budget Account balance not known",
                "no difference shown",
            ).joinToString(" · ")
        }
    }

    /** UC-008 A2 (UX-24): the remedy of an outdated Account Balance, a Fetch of its Data Provider and nothing else; else null. */
    fun balanceRemedy(balance: BalanceComparison): String? = (balance as? BalanceComparison.Outdated)?.let {
        "Fetch ${it.dataProviderName?.let { name -> "to ask $name" } ?: "again"} for a newer one; nothing else is needed."
    }
}

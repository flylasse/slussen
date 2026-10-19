package slussen.testing

import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.budgetapp.BudgetAppProfile
import slussen.pluginapi.budgetapp.BudgetAppReader
import slussen.pluginapi.budgetapp.BudgetAppWriter
import slussen.pluginapi.budgetapp.BudgetRead
import slussen.pluginapi.budgetapp.ChangeCursor
import slussen.pluginapi.budgetapp.ChangeSet
import slussen.pluginapi.budgetapp.ClearResult
import slussen.pluginapi.budgetapp.CreateItem
import slussen.pluginapi.budgetapp.CreateResult
import slussen.pluginapi.budgetapp.ReadResult
import slussen.pluginapi.budgetapp.TransactionLookup
import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialField
import slussen.pluginapi.credential.CredentialForm
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.credential.FieldKind
import slussen.pluginapi.provider.BankAccountSelection
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.provider.DataProviderProfile
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.provider.ReadKind
import slussen.pluginapi.provider.SourceOutcome
import slussen.pluginapi.request.Admission
import slussen.pluginapi.request.RequestContext
import slussen.pluginapi.request.RequestKind
import slussen.pluginapi.request.RequestSummary
import slussen.pluginapi.value.Secret
import java.time.LocalDate

/**
 * A scripted [CredentialCapability]: it declares [form], asks the gate before its one request like a real part
 * (ADR-001 §4.0), and answers with [answer]. Every check and every context it was given is kept for assertions.
 */
class ScriptedCredentials(
    override val form: CredentialForm,
    private val requestKind: RequestKind,
    var answer: CredentialCheck,
) : CredentialCapability {
    val checkedInputs = mutableListOf<CredentialInput>()
    val contexts = mutableListOf<RequestContext>()
    var requestsSent = 0
        private set

    /**
     * When true, the part sees before sending anything that the entry cannot be valid, for example a malformed token,
     * and answers `Refused` without calling the gate (ADR-001 §4.5, UC-014 A1).
     */
    var refusesUnsent: Boolean = false

    override suspend fun check(input: CredentialInput, context: RequestContext): CredentialCheck {
        checkedInputs += input
        contexts += context
        if (refusesUnsent) return CredentialCheck.Refused
        val admission = context.gate.admit(RequestSummary(requestKind, null, null))
        if (admission is Admission.Refused) return CredentialCheck.StoppedByGate(admission.reason)
        requestsSent++
        return answer
    }
}

/** A scripted Budget App reader: answers [readBudgetsAnswer] and counts the requests it sent. */
class ScriptedBudgetAppReader(var readBudgetsAnswer: ReadResult<List<BudgetRead>>) : BudgetAppReader {
    var budgetReads = 0
        private set
    val contexts = mutableListOf<RequestContext>()

    override suspend fun readBudgets(context: RequestContext): ReadResult<List<BudgetRead>> {
        contexts += context
        val admission = context.gate.admit(RequestSummary(RequestKind.BudgetAppRead, null, null))
        if (admission is Admission.Refused) return ReadResult.StoppedByGate(admission.reason)
        budgetReads++
        return readBudgetsAnswer
    }

    override suspend fun readTransaction(
        budgetReference: String,
        transactionReference: String,
        context: RequestContext,
    ): ReadResult<TransactionLookup> = error("No slice of Phase 7 reads one transaction (UC-006).")

    override suspend fun readChanges(
        budgetReference: String,
        since: ChangeCursor?,
        from: LocalDate,
        context: RequestContext,
    ): ReadResult<ChangeSet> = error("No slice of Phase 7 compares with the Budget App (UC-003).")
}

/** A Budget App writer that records every call; Phase 7 writes nothing to the Budget App (UC-013 failure postcondition). */
class RecordingBudgetAppWriter : BudgetAppWriter {
    var calls = 0
        private set

    override suspend fun create(budgetReference: String, items: List<CreateItem>, context: RequestContext): CreateResult {
        calls++
        error("Nothing is written to the Budget App in this slice.")
    }

    override suspend fun markCleared(budgetReference: String, counterpartReference: String, context: RequestContext): ClearResult {
        calls++
        error("Nothing is written to the Budget App in this slice.")
    }
}

/** A fake Budget App with invented declarations; its name is the invented "Example Budget App". */
class FakeBudgetApp(
    override val credentials: ScriptedCredentials = ScriptedCredentials(
        CredentialForm(listOf(CredentialField("token", FieldKind.Secret, "Access token, from the example budget app's settings"))),
        RequestKind.BudgetAppRead,
        CredentialCheck.Accepted(Secret(ExampleData.BUDGET_APP_TOKEN)),
    ),
    override val reader: ScriptedBudgetAppReader = ScriptedBudgetAppReader(ReadResult.Answered(ExampleData.budgetsRead())),
    override val writer: RecordingBudgetAppWriter = RecordingBudgetAppWriter(),
) : BudgetApp {
    override val profile: BudgetAppProfile = BudgetAppProfile(
        name = ExampleData.BUDGET_APP_NAME,
        importIdentifierMaxLength = 36,
        payeeMaxLength = 200,
        memoMaxLength = 500,
        matchingDateWindowDays = 10,
        refusesReusedImportIdentifier = true,
        supportsClearedMarker = true,
        supportsLinkedTransfers = true,
    )
}

/**
 * A fake Data Provider with invented declarations. Its [fetch] asks the gate before its one request, like a part whose
 * Data Provider answers every Bank Account in one request (ADR-001 §4.0), and then answers with [fetchAnswer]; a test
 * that scripts no answer fails when it is asked, as opening Slussen must not fetch (UC-008 BR-001). Every request and
 * context it was given is kept for assertions.
 */
class FakeDataProvider(
    override val profile: DataProviderProfile,
    override val credentials: ScriptedCredentials = ScriptedCredentials(
        CredentialForm(listOf(CredentialField("setupKey", FieldKind.Secret, "Setup key, from the example provider's website"))),
        RequestKind.Other,
        CredentialCheck.Accepted(Secret(ExampleData.EXCHANGED_ACCESS_CREDENTIAL)),
    ),
) : DataProvider {
    /** How often [fetch] was called, whether or not the gate let its request out. */
    var fetches = 0
        private set

    /** How many requests the gate let out. */
    var requestsSent = 0
        private set

    val fetchRequests = mutableListOf<FetchRequest>()
    val fetchContexts = mutableListOf<RequestContext>()

    /** What the Data Provider answers once the gate admitted the request. */
    var fetchAnswer: (FetchRequest) -> FetchResponse = { error("No Fetch answer scripted (UC-001).") }

    override val bankConsent: BankConsentCapability? = null

    override suspend fun fetch(request: FetchRequest, context: RequestContext): FetchResponse {
        fetches++
        fetchRequests += request
        fetchContexts += context
        val admission = context.gate.admit(RequestSummary(kindOf(request), null, null))
        if (admission is Admission.Refused) {
            return FetchResponse(SourceOutcome.StoppedByGate(admission.reason), emptyList(), emptyList())
        }
        requestsSent++
        return fetchAnswer(request)
    }

    private fun kindOf(request: FetchRequest): RequestKind = when (val selection = request.bankAccounts) {
        is BankAccountSelection.AllForIdentification ->
            if (selection.read == ReadKind.Balance) RequestKind.Balance else RequestKind.Transactions
        is BankAccountSelection.Selected ->
            if (selection.bankAccounts.all { it.read == ReadKind.Balance }) RequestKind.Balance else RequestKind.Transactions
    }
}

/** A fake File Pre-processor with invented declarations; Phase 7 converts no file (UC-002). */
class FakeFilePreprocessor : slussen.pluginapi.fileformat.FilePreprocessor {
    override val profile: slussen.pluginapi.fileformat.FileFormatProfile =
        slussen.pluginapi.fileformat.FileFormatProfile(name = "Harbor Health statement", sourceKey = "example-format")

    override fun convert(file: ByteArray): slussen.pluginapi.fileformat.ConversionResult =
        error("No Transaction File is imported in this slice (UC-002).")
}

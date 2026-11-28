package slussen.app.ui.consent

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.UiServices
import slussen.core.consent.AbandonOutcome
import slussen.core.consent.BankConsentState
import slussen.core.consent.BankConsentsPage
import slussen.core.consent.BanksOutcome
import slussen.core.consent.ConsentAddress
import slussen.core.consent.ConsentUnavailable
import slussen.core.consent.RevokeOutcome
import slussen.core.consent.StartOutcome
import slussen.core.credential.CredentialStatus
import slussen.core.port.ServiceKey
import slussen.pluginapi.provider.BankChoice
import java.util.Locale

/** One country of the chooser (UC-007 BR-010), by its ISO 3166 code and its name. */
data class CountryUi(val code: String, val name: String)

/** `consent.html#choose-bank`: the country first, the Banks the Data Provider lists for it, the search on the device (BR-010). */
data class ChooserUi(
    val title: String,
    val subtitle: String,
    /** "SE · Sweden", or "Choose the country first" while none is chosen. */
    val country: String,
    val search: String,
    val banks: List<BankChoice>,
    val note: String?,
    /** NFR-023: the list is being asked for, said before anything is sent. */
    val listing: String?,
    val answer: ConsentResultUi?,
    /** The list of countries while the Operator chooses another one; null otherwise. */
    val countries: List<CountryUi>?,
)

/** `consent.html#grant`, `#grant-first-run`: granting or renewing, with the steps in order and Open my Bank. */
data class SheetUi(
    val title: String,
    val subtitle: String,
    val steps: List<String>,
    val openAction: String,
    /** NFR-023: Open my Bank shows that it runs before anything is sent. */
    val opening: Boolean,
    /** Step 2: a start for a Bank whose Consent Request waits makes that one unanswerable. */
    val note: String?,
    val answer: ConsentResultUi?,
)

/** `consent.html#waiting`: Slussen waits for the result until the lifetime ends, with the paste field and Cancel (step 4, BR-011, A10). */
data class WaitingCardUi(
    val requestId: Long,
    val title: String,
    val until: String,
    val steps: List<String>,
    val pasteNote: String,
    val pastePlaceholder: String,
    /** What the Operator pasted, a one-time value, masked in [toString]; cleared after use. */
    val pasted: OneTimeText,
    val cancelling: Boolean,
    val note: String? = null,
)

/** One Bank on the page: its Bank Consent (`#list`, `#expired`), a Bank whose Bank Accounts miss one, or one just revoked (`#revoked`). */
data class ConsentLineUi(
    val bankId: Long,
    val bankName: String,
    val status: String,
    val detail: String,
    /** Renew, Renew Bank Consent or Grant Bank Consent; null where it cannot be offered (BR-003, UC-014 BR-005). */
    val renewAction: String?,
    val canRevoke: Boolean,
    /** NFR-023: the confirmed revocation runs. */
    val revoking: Boolean = false,
    val answer: ConsentResultUi? = null,
)

/** `consent.html#revoke`: A7 steps 1-2. */
data class RevokeQuestionUi(val bankId: Long, val title: String, val text: String, val kept: String)

/** The Bank Consents part of a Data Provider's page (UC-007 BR-008, BR-011; `docs/mockups/consent.html`). */
data class BankConsentsUi(
    val heading: String,
    val returnAddressTitle: String,
    val returnAddress: String,
    val lines: List<ConsentLineUi>,
    val emptyText: String?,
    val grantAction: String?,
    /** Preconditions: why nothing that contacts the Data Provider is offered, with the action that removes it. */
    val unavailable: String?,
    val chooser: ChooserUi?,
    val sheet: SheetUi?,
    val waiting: List<WaitingCardUi>,
    /** The answer of the last Consent Request that ended, where Slussen showed that it waits. */
    val result: ConsentResultUi?,
    val revokeQuestion: RevokeQuestionUi?,
    /** NFR-023: an answer is being handed to the Data Provider. */
    val completing: String?,
    /** E4 review item 1: one neutral line about an address that answers no Consent Request, with OK; null otherwise. */
    val stray: String? = null,
)

/** Open the way to the Bank's Strong Customer Authentication in the browser (step 4): kept only until the screen opened it. */
class OpenInBrowser(val requestId: Long, val address: ConsentAddress, val bankReference: String) {
    override fun toString(): String = "OpenInBrowser(requestId=$requestId, address=****)"
}

/**
 * The Bank Consents of the Data Provider [service] on its page (UC-007, `docs/mockups/consent.html`): choosing the Bank
 * (BR-010), granting and renewing (steps 1-4, BR-003), waiting with the paste field and Cancel (BR-011, A10), revoking
 * (A7), and every outcome at its control (NFR-023). The answers themselves are handed in by [answers], which outlives
 * this page. [browserAvailable] says whether the device has an app that opens the Bank's page (A11).
 */
class BankConsentsViewModel(
    private val services: UiServices,
    private val service: ServiceKey,
    private val answers: ConsentAnswers,
    private val deviceCountry: String,
    private val browserAvailable: () -> Boolean,
) : ViewModel() {
    private val mutableState = MutableStateFlow<BankConsentsUi?>(null)
    val state: StateFlow<BankConsentsUi?> = mutableState

    private val mutableOpen = MutableStateFlow<OpenInBrowser?>(null)

    /** Step 4: the Bank's page to open in the browser, once; the screen calls [opened] with whether it opened. */
    val toOpen: StateFlow<OpenInBrowser?> = mutableOpen

    private val provider: ConsentProvider? = services.consentProviders.firstOrNull { it.service == service }
    private val name: String get() = provider?.name.orEmpty()
    private val formats = services.formats

    private var page: BankConsentsPage? = null
    private var credentials: CredentialStatus = CredentialStatus.Missing

    /** The page is shown: a result it shows goes when it is left (review D4 item 2). */
    private var active = false

    private data class Chooser(
        val country: String,
        val search: String = "",
        val listed: BanksOutcome.Listed? = null,
        val listing: Boolean = false,
        val answer: ConsentResultUi? = null,
        val choosingCountry: Boolean = false,
    )

    private data class Sheet(
        val bankId: Long?,
        val bankReference: String,
        val renewing: Boolean,
        val opening: Boolean = false,
        val answer: ConsentResultUi? = null,
    )

    private data class Revoked(val bankId: Long, val bankName: String, val answer: ConsentResultUi)

    private var chooser: Chooser? = null
    private var sheet: Sheet? = null
    private var question: RevokeQuestionUi? = null
    private var revoking: Long? = null
    private val revoked = mutableListOf<Revoked>()
    private val lineAnswers = mutableMapOf<Long, ConsentResultUi>()
    private var pasted = ""

    /** A11 (E4 review item 4): Consent Requests whose sign-in could not be shown and that could not be withdrawn. */
    private val notShown = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            answers.state.collect { render() }
        }
    }

    // UC-007 BR-008: Whether a Data Provider needs a Bank Consent is declared by its part; one that needs none has no Bank Consents on its page.
    /**
     * Reads the Bank Consents of the page from stored data only (UC-008 BR-001); nothing for a Data Provider that needs
     * none. [renew] opens the sheet of that Bank once it is read (the Overview's Renew, UC-008 BR-002).
     */
    fun load(renew: Long? = null) {
        if (provider == null || services.bankConsents == null) return
        active = true
        viewModelScope.launch {
            readPage()
            answers.readWaiting()
            render()
            renew?.let(::renew)
        }
    }

    private suspend fun readPage() {
        val consents = services.bankConsents ?: return
        try {
            page = consents.page(service)
            credentials = services.credentials.status(service)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // NFR-023: what was shown stays; the service page says that it could not be read again.
        }
    }

    /** The Operator left the page: an open choice, sheet or question ends, and a result shown on it goes (review D4 items 1-2). */
    fun left() {
        active = false
        chooser = null
        if (sheet?.opening != true) sheet = null
        question = null
        revoked.clear()
        lineAnswers.clear()
        pasted = ""
        answers.left(service)
        render()
    }

    private val accepted: Boolean get() = credentials is CredentialStatus.Accepted

    // ---- Choosing the Bank (BR-010) ---------------------------------------------------------------------------------

    /** Step 1, BR-010: the chooser opens at the device's country and asks the Data Provider for its Banks. */
    fun openChooser() {
        if (!accepted) return
        sheet = null
        val country = deviceCountry.uppercase(Locale.ROOT).takeIf { COUNTRY.matches(it) }.orEmpty()
        chooser = Chooser(country)
        render()
        if (country.isNotEmpty()) list(country)
    }

    fun cancelChooser() {
        chooser = null
        render()
    }

    fun openCountries() {
        chooser = chooser?.copy(choosingCountry = true)
        render()
    }

    fun cancelCountries() {
        chooser = chooser?.copy(choosingCountry = false)
        render()
    }

    // UC-007 BR-010: The list is asked of the Data Provider each time the choice opens or the country changes.
    /** The Operator chooses another country: the list is asked again. */
    fun chooseCountry(code: String) {
        val current = chooser ?: return
        chooser = current.copy(country = code, search = "", listed = null, answer = null, choosingCountry = false)
        render()
        list(code)
    }

    private fun list(country: String) {
        val consents = services.bankConsents ?: return
        val current = chooser ?: return
        if (current.listing) return
        chooser = current.copy(listing = true, answer = null)
        render()
        viewModelScope.launch {
            val outcome = orWhenThrown<BanksOutcome>(BanksOutcome.NotReached(slussen.pluginapi.request.FailureKind.ConnectionLost, services.clock.instant())) { consents.banks(service, country) }
            val now = chooser ?: return@launch
            if (now.country != country) return@launch
            chooser = now.copy(
                listing = false,
                listed = outcome as? BanksOutcome.Listed,
                answer = ConsentTexts.banks(outcome, name, formats),
            )
            if (outcome is BanksOutcome.Refused || outcome is BanksOutcome.NotAvailable) readPage()
            render()
        }
    }

    // UC-007 BR-010: A search by name narrows the list on the device; nothing is sent.
    fun search(text: String) {
        chooser = chooser?.copy(search = text)
        render()
    }

    /** The Operator chooses a Bank from the list: the sheet of step 1 for it. */
    fun chooseBank(choice: BankChoice) {
        chooser = null
        val bankId = page?.consents?.firstOrNull { it.bankName == choice.bankReference }?.bankId
        sheet = Sheet(bankId = null, bankReference = choice.bankReference, renewing = bankId != null)
        render()
    }

    // ---- Granting and renewing (steps 1-4, BR-003) ------------------------------------------------------------------

    // UC-007 BR-003: Renewing is offered for a Bank that has a Bank Consent, or whose Bank Accounts miss one; it needs no choice.
    /** The sheet for the Bank [bankId]: its Bank Consent, a Bank whose Bank Accounts miss one, or one just revoked. */
    fun renew(bankId: Long) {
        if (!accepted) return
        val shown = page ?: return
        chooser = null
        val consent = shown.consents.firstOrNull { it.bankId == bankId }
        val missing = shown.withoutConsent.firstOrNull { it.bankId == bankId }
        val gone = revoked.firstOrNull { it.bankId == bankId }
        sheet = when {
            consent != null -> Sheet(bankId, consent.bankName, renewing = true)
            missing != null -> Sheet(bankId, missing.bankName, renewing = false)
            gone != null -> Sheet(null, gone.bankName, renewing = false)
            else -> return
        }
        render()
    }

    fun cancelSheet() {
        if (sheet?.opening == true) return
        sheet = null
        render()
    }

    /** Steps 2-4: Open my Bank: Slussen starts the Consent Request, then the screen opens the Bank's page. */
    fun openMyBank() {
        val consents = services.bankConsents ?: return
        val current = sheet ?: return
        if (current.opening) return
        sheet = current.copy(opening = true, answer = null)
        render()
        viewModelScope.launch {
            // A11 of UC-007: without a browser no Consent Request is started and nothing is sent.
            val browser = try {
                browserAvailable()
            } catch (failure: Exception) {
                false
            }
            val outcome = orWhenThrown<StartOutcome>(StartOutcome.NotStored(current.bankReference)) {
                if (current.bankId != null) consents.renew(service, current.bankId, browser) else consents.start(service, current.bankReference, browser)
            }
            if (outcome is StartOutcome.Started) {
                if (!active) {
                    // Review D4 item 1: the page was left before the Bank's page could open; nothing waits unseen.
                    orWhenThrown(Unit) { consents.abandon(outcome.requestId) }
                    sheet = null
                    render()
                    return@launch
                }
                mutableOpen.value = OpenInBrowser(outcome.requestId, outcome.consentAddress, outcome.bankReference)
                return@launch
            }
            sheet = sheet?.copy(opening = false, answer = ConsentTexts.start(outcome, current.bankReference, name, formats))
            if (outcome is StartOutcome.Refused || outcome is StartOutcome.NotAvailable) readPage()
            render()
        }
    }

    /** Step 4: the screen tried to open the Bank's page; [ok] is false when no app opened it (A11). */
    fun opened(ok: Boolean) {
        val open = mutableOpen.value ?: return
        mutableOpen.value = null
        if (ok) {
            sheet = null
            answers.started(service)
            render()
            return
        }
        // A11 of UC-007: a browser that does not open the way leaves nothing that could be answered: the Consent Request is withdrawn.
        viewModelScope.launch {
            val consents = services.bankConsents
            val withdrawn = consents != null &&
                orWhenThrown<AbandonOutcome>(AbandonOutcome.NotStored) { consents.abandon(open.requestId) } == AbandonOutcome.Abandoned
            if (withdrawn) {
                sheet = sheet?.copy(opening = false, answer = ConsentTexts.notShown(open.bankReference))
            } else {
                // E4 review item 4: not withdrawn, it still waits, with its paste field and Cancel.
                sheet = null
                notShown += open.requestId
            }
            answers.started(service)
            render()
        }
    }

    // ---- Waiting (step 4, BR-011, A10) ------------------------------------------------------------------------------

    /** BR-011: the Operator types or pastes the address the browser shows; Slussen never reads the clipboard itself. */
    fun paste(text: String) {
        pasted = text
        render()
    }

    // UC-007 BR-011: A pasted address is checked exactly as a returned one; the field is cleared after use.
    fun usePasted() {
        val text = pasted.trim()
        if (text.isEmpty() || answers.state.value.completing > 0) return
        pasted = ""
        answers.pasted(service, text)
        render()
    }

    /** E4 review item 1: OK on the line about an address that answers no Consent Request. */
    fun acknowledgeStray() = answers.acknowledgeStray()

    /** A10: Cancel while Slussen waits for the result. */
    fun cancelWaiting(requestId: Long) = answers.cancel(service, requestId)

    // ---- Revoking (A7) ----------------------------------------------------------------------------------------------

    /** A7 step 1: the confirmation, stating that the Bank Accounts of this Bank will not be fetched until a new Bank Consent. */
    fun requestRevoke(bankId: Long) {
        val line = page?.consents?.firstOrNull { it.bankId == bankId } ?: return
        if (revoking != null) return
        val accounts = line.coveredBankAccounts
        val who = when (accounts.size) {
            0 -> "Its Bank Accounts"
            1 -> accounts.single()
            else -> accounts.dropLast(1).joinToString(", ") + " and " + accounts.last()
        }
        question = RevokeQuestionUi(
            bankId,
            "Revoke the Bank Consent of ${line.bankName}?",
            "$who will not be fetched until you grant a new Bank Consent.",
            "Your Bank Accounts, Account Mappings, Bank Transactions and Sent Record are kept.",
        )
        render()
    }

    /** A7 step 2: the Operator cancels; nothing changes. */
    fun cancelRevoke() {
        question = null
        render()
    }

    /** A7 steps 3-5: Revoke shows that it runs, then the answer at the line of the Bank. */
    fun confirmRevoke() {
        val consents = services.bankConsents ?: return
        val asked = question ?: return
        val bankName = page?.consents?.firstOrNull { it.bankId == asked.bankId }?.bankName.orEmpty()
        question = null
        revoking = asked.bankId
        lineAnswers.remove(asked.bankId)
        render()
        viewModelScope.launch {
            val outcome = orWhenThrown<RevokeOutcome>(RevokeOutcome.NotStored(endedAtDataProvider = false)) { consents.revoke(service, asked.bankId) }
            revoking = null
            val answer = ConsentTexts.revoked(outcome, bankName, name, formats)
            if (outcome is RevokeOutcome.Revoked) revoked += Revoked(asked.bankId, bankName, answer) else lineAnswers[asked.bankId] = answer
            readPage()
            answers.readWaiting()
            render()
        }
    }

    // ---- The answers ------------------------------------------------------------------------------------------------

    /** What the Operator does with an answer: Done ends it, Try again and Start again begin anew, Another Bank opens the chooser. */
    fun act(result: ConsentResultUi, action: ResultAction) {
        val inSheet = sheet?.answer == result
        val inChooser = chooser?.answer == result
        when (action) {
            ResultAction.Done -> if (!inSheet && !inChooser) done()
            ResultAction.TryAgain, ResultAction.StartAgain -> when {
                inSheet -> openMyBank()
                inChooser -> chooser?.country?.takeIf { it.isNotEmpty() }?.let(::list)
                else -> {
                    answers.dismiss()
                    val bank = result.bankReference
                    if (bank == null || !accepted) {
                        openChooser()
                    } else {
                        val renewing = page?.consents?.any { it.bankName == bank } == true
                        chooser = null
                        sheet = Sheet(null, bank, renewing)
                        render()
                    }
                }
            }
            ResultAction.AnotherBank -> {
                answers.dismiss()
                openChooser()
            }
            ResultAction.CopyAddress, ResultAction.AccountMappings -> Unit
        }
    }

    /** Done: the answer of the last Consent Request goes, and the page is read again. */
    fun done() {
        answers.dismiss()
        viewModelScope.launch {
            readPage()
            render()
        }
    }

    // ---- What the page shows ----------------------------------------------------------------------------------------

    private fun render() {
        val shown = page
        if (provider == null || shown == null) {
            mutableState.value = null
            return
        }
        val answered = answers.state.value
        val result = answered.result?.takeIf { it.service == service }
        if (result != null && active && !result.shownOnPage) answers.shown(service)
        mutableState.value = BankConsentsUi(
            heading = "Bank Consents · ${shown.consents.size}",
            // UC-007 BR-011: the Data Provider's page shows the exact address Slussen sends, as the address to register, also before any Bank Consent.
            returnAddressTitle = if (shown.ownAddress) "Return address to register" else "Return address registered with the credentials",
            returnAddress = shown.returnAddress,
            lines = lines(shown),
            emptyText = if (shown.consents.isEmpty() && shown.withoutConsent.isEmpty() && revoked.isEmpty()) "No Bank Consent yet." else null,
            grantAction = when {
                !accepted -> null
                shown.consents.isEmpty() && shown.withoutConsent.isEmpty() -> "Grant Bank Consent"
                else -> "Grant Bank Consent for another Bank"
            },
            unavailable = when (credentials) {
                is CredentialStatus.Accepted -> null
                CredentialStatus.Missing -> ConsentTexts.unavailable(ConsentUnavailable.CredentialsMissing, name)
                CredentialStatus.Refused -> ConsentTexts.unavailable(ConsentUnavailable.CredentialsRefused, name)
            },
            chooser = chooser?.let(::chooserUi),
            sheet = sheet?.let { sheetUi(it, answered) },
            waiting = answered.waiting.filter { it.service == service }.map { waitingUi(it, shown, answered) },
            result = result?.let { ConsentTexts.end(it.end, it.bankReference, name, formats, shown.returnAddress.takeUnless { shown.ownAddress }) },
            revokeQuestion = question,
            completing = if (answered.completing > 0) "Handing the result to $name…" else null,
            stray = if (answered.strayAddress && active) ConsentTexts.STRAY_ADDRESS else null,
        )
    }

    private fun lines(shown: BankConsentsPage): List<ConsentLineUi> {
        val consentLines = shown.consents.map { line ->
            val expired = line.state == BankConsentState.Expired
            val covered = line.coveredBankAccounts
            ConsentLineUi(
                bankId = line.bankId,
                bankName = line.bankName,
                status = when (line.state) {
                    BankConsentState.Valid -> "Valid until ${formats.date(line.expiresAt)}"
                    BankConsentState.Expiring -> "Expires ${formats.date(line.expiresAt)}"
                    // UC-008 BR-006: an expired Bank Consent the Bank refused before its expiry shows that time and that the Bank refused it.
                    BankConsentState.Expired -> if (line.refusedByBank) {
                        "Bank Consent expired: the Bank refused it ${formats.date(line.expiredFrom)}"
                    } else {
                        "Bank Consent expired ${formats.date(line.expiresAt)}"
                    }
                },
                detail = when {
                    expired -> "${if (covered.isEmpty()) "Its Bank Accounts are" else covered.joinToString(", ")} not fetched"
                    covered.isEmpty() -> "Covers no added Bank Account yet"
                    else -> "Covers ${covered.joinToString(", ")}"
                },
                renewAction = if (!accepted) null else if (expired) "Renew Bank Consent" else "Renew",
                canRevoke = true,
                revoking = revoking == line.bankId,
                answer = lineAnswers[line.bankId],
            )
        }
        val missing = shown.withoutConsent.map { bank ->
            ConsentLineUi(
                bankId = bank.bankId,
                bankName = bank.bankName,
                status = if (revoked.any { it.bankId == bank.bankId }) "Revoked" else "No Bank Consent",
                detail = "${bank.bankAccounts.joinToString(", ")} not fetched",
                renewAction = if (accepted) "Grant Bank Consent" else null,
                canRevoke = false,
                answer = revoked.firstOrNull { it.bankId == bank.bankId }?.answer,
            )
        }
        val gone = revoked.filter { r -> shown.consents.none { it.bankId == r.bankId } && shown.withoutConsent.none { it.bankId == r.bankId } }.map {
            ConsentLineUi(it.bankId, it.bankName, "Revoked", "", renewAction = if (accepted) "Grant Bank Consent" else null, canRevoke = false, answer = it.answer)
        }
        return gone + consentLines + missing
    }

    private fun chooserUi(state: Chooser): ChooserUi = ChooserUi(
        title = "Grant Bank Consent · choose the Bank",
        subtitle = "Banks $name reaches",
        country = if (state.country.isEmpty()) "Choose the country first" else "${state.country} · ${countryName(state.country)}",
        search = state.search,
        banks = state.listed?.matching(state.search).orEmpty(),
        note = when {
            state.listed == null -> null
            state.listed.banks.isEmpty() -> "$name lists no Bank for ${countryName(state.country)}"
            state.listed.matching(state.search).isEmpty() -> "No Bank of ${countryName(state.country)} matches \"${state.search.trim()}\""
            // UC-007 BR-010: The Data Provider names each Bank so that two Banks of one name in two countries are two Banks.
            else -> "Each Bank is named with its country."
        },
        listing = if (state.listing) "Asking $name for the Banks of ${countryName(state.country)}…" else null,
        answer = state.answer,
        countries = if (state.choosingCountry) COUNTRIES else null,
    )

    private fun sheetUi(state: Sheet, answered: ConsentAnswersState): SheetUi = SheetUi(
        title = "${if (state.renewing) "Renew" else "Grant"} Bank Consent · ${state.bankReference}",
        subtitle = "$name · one sign-in for every Bank Account at the Bank",
        steps = listOf(
            "1 Open the Strong Customer Authentication of ${state.bankReference}",
            "2 Complete it with your Bank, for example with BankID",
            "3 Return to Slussen with the result",
        ),
        openAction = if (state.opening) "Opening your Bank…" else "Open my Bank",
        opening = state.opening,
        // UC-007 BR-002: a new Consent Request makes every earlier one of this Bank unanswerable.
        note = if (answered.waiting.any { it.service == service && it.bankReference == state.bankReference }) {
            "Starting again makes the Consent Request that waits now unanswerable."
        } else {
            null
        },
        answer = state.answer,
    )

    private fun waitingUi(waiting: WaitingConsent, shown: BankConsentsPage, answered: ConsentAnswersState): WaitingCardUi {
        val renewing = shown.consents.any { it.bankName == waiting.bankReference }
        return WaitingCardUi(
            requestId = waiting.requestId,
            title = "${if (renewing) "Renew" else "Grant"} Bank Consent · ${waiting.bankReference}",
            until = "Waiting for the result · until ${formats.time(waiting.expiresAt)}",
            steps = listOf("1 Complete the sign-in with your Bank in the browser", "2 Back to Slussen"),
            // UC-007 BR-011: with Slussen's own address the field is for when the browser does not hand the answer back; with another, pasting is the way back.
            pasteNote = if (shown.ownAddress) {
                "Not back by itself? Paste the address your browser shows after the sign-in."
            } else {
                "Your browser ends on the page of ${shown.returnAddress}: paste the address it shows there."
            },
            pastePlaceholder = "${shown.returnAddress}?…",
            pasted = OneTimeText(pasted),
            cancelling = waiting.requestId in answered.cancelling,
            note = if (waiting.requestId in notShown) ConsentTexts.NOT_SHOWN_NOT_WITHDRAWN else null,
        )
    }

    private suspend fun <T> orWhenThrown(otherwise: T, call: suspend () -> T): T = try {
        call()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        otherwise
    }

    private companion object {
        val COUNTRY = Regex("[A-Z]{2}")

        fun countryName(code: String): String = Locale("", code).getDisplayCountry(Locale.ENGLISH).ifEmpty { code }

        val COUNTRIES: List<CountryUi> = Locale.getISOCountries().map { CountryUi(it, countryName(it)) }.sortedBy { it.name }
    }
}

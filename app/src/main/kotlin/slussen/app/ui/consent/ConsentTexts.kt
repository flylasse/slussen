package slussen.app.ui.consent

import slussen.app.ui.Formats
import slussen.app.ui.Texts
import slussen.app.ui.components.AnswerKind
import slussen.core.consent.AbandonOutcome
import slussen.core.consent.AnswerNotAccepted
import slussen.core.consent.BanksOutcome
import slussen.core.consent.CompletionOutcome
import slussen.core.consent.ConsentUnavailable
import slussen.core.consent.Coverage
import slussen.core.consent.EarlierBankConsent
import slussen.core.consent.HeldBack
import slussen.core.consent.HeldBackBy
import slussen.core.consent.RevokeOutcome
import slussen.core.consent.StartOutcome
import slussen.pluginapi.provider.ConsentFailureKind
import slussen.pluginapi.request.FailureKind
import java.time.Instant

/** What the Operator can do with an answer of UC-007, each at the answer it follows. */
enum class ResultAction { Done, TryAgain, StartAgain, CopyAddress, AccountMappings, AnotherBank }

/**
 * One answer of UC-007 at the control that started it (NFR-023), in the words of `docs/mockups/consent.html`:
 * [bankReference] is the Bank a Try again or Start again is for, when known, and [address] the address Copy copies.
 */
data class ConsentResultUi(
    val title: String,
    val lines: List<String>,
    val kind: AnswerKind,
    val actions: List<ResultAction>,
    val bankReference: String? = null,
    val address: String? = null,
)

/**
 * The texts of Grant Bank Consent (UC-007), in Slussen's glossary terms and the mockup's words. Every Bank is named as
 * its Data Provider names it, with its country (BR-010); a Data Provider only by the name its part declares (K-04).
 */
object ConsentTexts {
    /** A8 step 2, A9 step 3: what the Operator is told when a completion may have left a consent at the Bank. */
    private const val MAY_HAVE_BEEN_GRANTED = "Your Bank may have granted a consent that did not reach Slussen: it is never used and ends at its expiry, or end it at your Bank."

    fun unavailable(reason: ConsentUnavailable, provider: String): String = when (reason) {
        ConsentUnavailable.CredentialsMissing -> "Enter the credentials of $provider first · its Bank Consents are granted and renewed once they are accepted"
        ConsentUnavailable.CredentialsRefused ->
            "$provider refused Slussen's credentials · enter new credentials first; its Bank Consents are granted and renewed once they are accepted"
        is ConsentUnavailable.Paused -> Texts.refusal(reason.reason)
    }

    /** A9 step 2: the Blocked Reason that held the request back, with the time it ends (UC-001 A3, A5). */
    fun heldBack(heldBack: HeldBack, formats: Formats): String = when (heldBack.by) {
        HeldBackBy.RequestLimitReached -> "Request Limit reached until ${formats.time(heldBack.until)}"
        HeldBackBy.WaitingForProvider -> "Waiting for the provider until ${formats.time(heldBack.until)}"
        HeldBackBy.NotSent -> "Not sent: the operation ended before it was sent"
    }

    private fun notAnswered(provider: String, kind: FailureKind?, at: Instant, formats: Formats): String = when (kind) {
        FailureKind.UnreadableAnswer, FailureKind.Incomplete -> "The answer of $provider could not be read · ${formats.time(at)}"
        else -> "$provider did not answer · ${formats.time(at)}"
    }

    /** Step 1, BR-010, A3, A8, A9: the answer of the list of Banks at the chooser; null when it was listed. */
    fun banks(outcome: BanksOutcome, provider: String, formats: Formats): ConsentResultUi? = when (outcome) {
        is BanksOutcome.Listed -> null
        is BanksOutcome.Refused -> ConsentResultUi("$provider refused to list the Banks", listOf("Nothing stored. Try again later."), AnswerKind.Failed, listOf(ResultAction.TryAgain))
        is BanksOutcome.NotReached ->
            ConsentResultUi(notAnswered(provider, outcome.kind, outcome.at, formats), listOf("Nothing stored. Try again."), AnswerKind.Failed, listOf(ResultAction.TryAgain))
        is BanksOutcome.HeldBackByGate -> ConsentResultUi(
            "Banks not listed",
            listOf(heldBack(outcome.heldBack, formats), "Nothing sent. Try again once it ends."),
            AnswerKind.Unchanged,
            listOf(ResultAction.TryAgain),
        )
        BanksOutcome.CountryNotValid -> ConsentResultUi("Choose the country first", emptyList(), AnswerKind.Unchanged, emptyList())
        is BanksOutcome.NotAvailable -> ConsentResultUi("Banks not listed", listOf(unavailable(outcome.reason, provider)), AnswerKind.Unchanged, emptyList())
    }

    /** Steps 2-4, A3, A8, A9, A11: the answer of Open my Bank at the sheet; null when the Consent Request was started. */
    fun start(outcome: StartOutcome, bank: String, provider: String, formats: Formats): ConsentResultUi? {
        val notStarted = "Bank Consent not started · $bank"
        fun failed(title: String, vararg lines: String, actions: List<ResultAction> = listOf(ResultAction.TryAgain)) =
            ConsentResultUi(title, lines.toList(), AnswerKind.Failed, actions, bankReference = bank)
        return when (outcome) {
            is StartOutcome.Started -> null
            // A3 of UC-007: the Data Provider's refusal at the action that started the request; a refused return address names it (BR-011).
            is StartOutcome.Refused -> when (outcome.kind) {
                ConsentFailureKind.ReturnAddressRefused -> {
                    val address = outcome.returnAddress.orEmpty()
                    ConsentResultUi(
                        "$bank · Return address not accepted",
                        listOf("Register $address as a return address of your application at $provider, then try again · nothing stored"),
                        AnswerKind.Failed,
                        listOf(ResultAction.CopyAddress, ResultAction.TryAgain),
                        bankReference = bank,
                        address = address,
                    )
                }
                ConsentFailureKind.CredentialsRefused ->
                    failed("$bank · $provider refused Slussen's credentials", "Nothing stored · enter new credentials on this page", actions = emptyList())
                ConsentFailureKind.RefusedByBank -> failed("$bank · the Bank refused", "$bank refused · nothing stored · try again later")
                ConsentFailureKind.RefusedByDataProvider, ConsentFailureKind.AnswerNotUnderstood ->
                    failed("$bank · $provider refused", "$provider refused · nothing stored · try again later")
            }
            is StartOutcome.NotReached -> failed(
                notStarted,
                notAnswered(provider, outcome.kind, outcome.at, formats),
                "Nothing stored; any earlier Bank Consent of $bank is unchanged. Try again.",
            )
            is StartOutcome.HeldBackByGate ->
                failed(notStarted, heldBack(outcome.heldBack, formats), "Nothing sent and nothing stored. Try again once it ends.")
            // A11 of UC-007: Slussen shows at the action that a browser is needed on the device.
            StartOutcome.NoBrowser ->
                failed(notStarted, "A browser is needed to open your Bank's sign-in.", "Install or enable a browser on this phone, then try again. Nothing was sent.")
            StartOutcome.BankReferenceNotUsable -> failed(notStarted, "Slussen cannot keep the name of this Bank · nothing sent", actions = emptyList())
            StartOutcome.NotABankOfTheDataProvider -> failed(notStarted, "Choose the Bank from the list of $provider · nothing sent", actions = emptyList())
            is StartOutcome.NotStored -> failed(notStarted, "Slussen could not store the Consent Request, so nothing can answer it · try again")
            is StartOutcome.NotAvailable -> failed(notStarted, unavailable(outcome.reason, provider), actions = emptyList())
        }
    }

    /**
     * A11 (E4 review item 4): the Consent Request was started, but no app could show the Bank's sign-in, so it was
     * withdrawn.
     */
    fun notShown(bank: String): ConsentResultUi = ConsentResultUi(
        "Bank Consent not opened · $bank",
        listOf(
            "The Consent Request was started, but no app on this phone could show your Bank's sign-in, so it was withdrawn.",
            "Install or enable a browser on this phone, then try again.",
        ),
        AnswerKind.Failed,
        listOf(ResultAction.TryAgain),
        bankReference = bank,
    )

    /** A11 (E4 review item 4): the sign-in could not be shown, and withdrawing the Consent Request failed: it still waits. */
    const val NOT_SHOWN_NOT_WITHDRAWN =
        "Your Bank's sign-in could not be shown, and the Consent Request could not be withdrawn: paste the address your browser shows, or Cancel."

    /** E4 review item 1: an address was opened that answers no Consent Request. */
    const val STRAY_ADDRESS = "An address was opened that answers no Consent Request · nothing changed"

    /**
     * What ended a Consent Request, where Slussen showed that it waits (steps 6-10, A1, A2, A4, A8-A10). [pasteAddress] is
     * the registered return address when it is not the application's own, the only case in which the browser can have
     * shown an address to paste again (E4 review item 6).
     */
    fun end(end: ConsentEnd, bank: String?, provider: String, formats: Formats, pasteAddress: String? = null): ConsentResultUi = when (end) {
        is ConsentEnd.Completed -> completion(end.outcome, bank, provider, formats, pasteAddress)
        is ConsentEnd.Abandoned -> ConsentResultUi(
            "Bank Consent not granted · ${end.bankReference}",
            listOf(
                // A10 of UC-007 (E4 review item 5): found at a start, the time for the answer had ended while Slussen was stopped.
                if (end.afterStart) "The time for the answer ended before a result reached Slussen." else "The Consent Request was cancelled, or no result arrived in time.",
                "Any earlier Bank Consent of ${end.bankReference} is unchanged.",
            ),
            AnswerKind.Unchanged,
            listOf(ResultAction.StartAgain),
            bankReference = end.bankReference,
        )
        is ConsentEnd.NotAbandoned -> ConsentResultUi(
            "Not cancelled · ${end.bankReference}",
            listOf(
                when (val outcome = end.outcome) {
                    AbandonOutcome.NothingToAbandon -> "It was answered meanwhile or is no longer stored · nothing changed"
                    AbandonOutcome.NotStored -> "Slussen could not store it · it still waits; try again"
                    is AbandonOutcome.NotAvailable -> unavailable(outcome.reason, provider)
                    AbandonOutcome.Abandoned -> "Cancelled"
                },
            ),
            AnswerKind.Failed,
            listOf(ResultAction.Done),
            bankReference = end.bankReference,
        )
    }

    private fun completion(outcome: CompletionOutcome, known: String?, provider: String, formats: Formats, pasteAddress: String?): ConsentResultUi {
        fun notGranted(bank: String, vararg lines: String) =
            ConsentResultUi("Bank Consent not granted · $bank", lines.toList(), AnswerKind.Failed, listOf(ResultAction.StartAgain), bankReference = bank)
        return when (outcome) {
            is CompletionOutcome.Granted -> granted(outcome, provider, formats)
            // A1 of UC-007: Slussen shows that the consent was not granted; the earlier one stays.
            is CompletionOutcome.NotGranted -> ConsentResultUi(
                "Bank Consent not granted · ${outcome.bankReference}",
                listOf("The Strong Customer Authentication was cancelled or refused.", "Any earlier Bank Consent of ${outcome.bankReference} is unchanged."),
                AnswerKind.Failed,
                listOf(ResultAction.TryAgain),
                bankReference = outcome.bankReference,
            )
            // A2 of UC-007: Slussen rejects the result and asks the Operator to start the consent again.
            is CompletionOutcome.NotAccepted -> ConsentResultUi(
                "Result not accepted",
                listOfNotNull(known, notAccepted(outcome.reason)),
                AnswerKind.Failed,
                listOf(ResultAction.StartAgain),
                bankReference = known,
            )
            // A4 of UC-007: the Consent Request was answered and cannot be answered again, so Slussen asks the Operator to start again.
            is CompletionOutcome.NotConfirmed -> {
                val bank = outcome.bankReference
                val (title, line) = when (outcome.kind) {
                    ConsentFailureKind.RefusedByBank -> "$bank · the Bank refused" to "$bank refused the Bank Consent"
                    ConsentFailureKind.CredentialsRefused -> "$bank · $provider refused Slussen's credentials" to "Enter new credentials on this page"
                    else -> "$bank · $provider refused" to "$provider did not confirm the Bank Consent"
                }
                ConsentResultUi(title, listOf("$line · nothing stored · the Consent Request was answered, so start again"), AnswerKind.Failed, listOf(ResultAction.StartAgain), bankReference = bank)
            }
            // A8 step 2 of UC-007: the Bank may have granted a consent that did not reach Slussen.
            is CompletionOutcome.MayHaveBeenGranted -> notGranted(
                outcome.bankReference,
                notAnswered(provider, outcome.kind, outcome.at, formats),
                "Nothing stored; any earlier Bank Consent of ${outcome.bankReference} is unchanged. $MAY_HAVE_BEEN_GRANTED",
            )
            is CompletionOutcome.HeldBackByGate -> notGranted(
                outcome.bankReference,
                heldBack(outcome.heldBack, formats),
                "Nothing sent and nothing stored. $MAY_HAVE_BEEN_GRANTED Start again once it ends.",
            )
            is CompletionOutcome.NotStored -> ConsentResultUi(
                listOfNotNull("Bank Consent not granted", outcome.bankReference ?: known).joinToString(" · "),
                listOf("Slussen could not use the data stored on this device · nothing of the Bank Consent is stored"),
                AnswerKind.Failed,
                listOf(ResultAction.StartAgain),
                bankReference = outcome.bankReference ?: known,
            )
            // S-B-52: an answer that arrives without accepted credentials is left unconsumed; with Slussen's own address it is lost (E4 review item 6).
            is CompletionOutcome.NotAvailable -> ConsentResultUi(
                "Result not handed in",
                listOfNotNull(
                    unavailable(outcome.reason, provider),
                    "The answer is lost: start again once they are accepted.",
                    pasteAddress?.let {
                        "Or, once they are accepted, paste the address your browser shows on $it again, within the Consent Request lifetime."
                    },
                ),
                AnswerKind.Unchanged,
                listOf(ResultAction.StartAgain),
                bankReference = known,
            )
        }
    }

    private fun notAccepted(reason: AnswerNotAccepted): String = when (reason) {
        AnswerNotAccepted.Unreadable -> "Unreadable · nothing stored"
        AnswerNotAccepted.NoSuchRequest -> "It answers no Consent Request that waits: cancelled, revoked or of another installation · nothing stored"
        AnswerNotAccepted.NotTheMostRecent -> "Outdated: a newer Consent Request of this Bank was started · nothing stored"
        AnswerNotAccepted.LifetimeEnded -> "Outdated: it arrived after the Consent Request lifetime · nothing stored"
        AnswerNotAccepted.AlreadyAnswered -> "Reused: it was used before · nothing stored"
    }

    /** Steps 8-10, A5, A6, BR-003: granted, with what happened to each covered Bank Account and to the earlier consent. */
    private fun granted(outcome: CompletionOutcome.Granted, provider: String, formats: Formats): ConsentResultUi {
        val bank = outcome.bankName
        val coverage = outcome.coverage
        val firstRun = coverage != null && coverage.identified.isEmpty() && coverage.toConfirm.isEmpty() && coverage.notIdentified.isEmpty() &&
            coverage.noLongerDelivered.isEmpty()
        val lines = buildList {
            // UC-007 BR-004: the expiry date is the one the Bank or Data Provider reports.
            add("Valid until ${formats.date(outcome.expiresAt)}, the expiry date the Bank reported.")
            if (coverage == null) {
                add("Its Bank Accounts could not be kept now: they are offered again with the next grant or renewal of this Bank Consent.")
            } else {
                addAll(coverageLines(coverage, provider, firstRun))
            }
            when (outcome.earlier) {
                // UC-007 BR-003: when ending the earlier Bank Session fails, the renewal stands and Slussen says that the earlier consent may stay at the Bank.
                EarlierBankConsent.MayStayAtBank -> add("The earlier consent may stay at $bank until it expires; you can end it at your Bank.")
                EarlierBankConsent.Ended, EarlierBankConsent.None -> Unit
            }
        }
        val toMappings = coverage != null && (coverage.toConfirm.isNotEmpty() || coverage.notIdentified.isNotEmpty() || (!firstRun && coverage.toMap > 0))
        val actions = listOf(ResultAction.Done) + (if (toMappings) listOf(ResultAction.AccountMappings) else emptyList()) +
            (if (firstRun) listOf(ResultAction.AnotherBank) else emptyList())
        return ConsentResultUi("✓ Bank Consent granted · $bank", lines, AnswerKind.Done, actions, bankReference = bank)
    }

    private fun coverageLines(coverage: Coverage, provider: String, firstRun: Boolean): List<String> = buildList {
        if (coverage.identified.isNotEmpty()) add("Covers ${coverage.identified.joinToString(", ")} · identified")
        // A5 of UC-007: a Bank Account only probably recognised waits for the Operator's confirmation.
        coverage.toConfirm.forEach { match ->
            val name = listOfNotNull(match.offeredName ?: "A Bank Account", match.lastFourDigits?.let { "••$it" }).joinToString(" ")
            add("$name · only a likely match of ${match.bankAccountName}: its transactions are not used until you confirm it")
        }
        // A6 of UC-007: Slussen stops for that Bank Account and shows the Blocked Reason "unrecognised Bank Account".
        coverage.notIdentified.forEach { add("$it · unrecognised Bank Account: Slussen stops for it until you identify it") }
        if (coverage.toMap > 0) {
            add(
                if (firstRun) {
                    "Bank Accounts offered ${coverage.toMap} · none added yet: import and map them on this page"
                } else {
                    "${Texts.count(coverage.toMap, "new Bank Account", "new Bank Accounts")} offered · to map in Account Mappings"
                },
            )
        }
        if (coverage.leftOut > 0) add("${coverage.leftOut} not imported, as you left them out")
        if (coverage.noLongerDelivered.isNotEmpty()) {
            add("No longer delivered: ${coverage.noLongerDelivered.joinToString(", ")} · check at $provider that they are still connected")
        }
        if (coverage.delivered == 0) add("$provider delivered no Bank Account with this Bank Consent")
    }

    /** A7 steps 4-5, A8 step 3, A9 step 3: what revoking did, at the line of its Bank. */
    fun revoked(outcome: RevokeOutcome, bank: String, provider: String, formats: Formats): ConsentResultUi = when (outcome) {
        is RevokeOutcome.Revoked -> ConsentResultUi(
            "Revoked",
            buildList {
                add("Deleted · its Bank Accounts are not fetched")
                // A7 step 5 of UC-007: unless the Data Provider confirmed that it ended the consent at the Bank, the Operator can also end it there.
                if (!outcome.endedAtBank) {
                    val heldBack = outcome.heldBack
                    add(
                        when {
                            heldBack != null -> "$provider was not contacted: ${heldBack(heldBack, formats)} · end it at $bank too"
                            outcome.dataProviderContacted -> "Not confirmed by $provider · end it at $bank too"
                            else -> "$provider was not contacted · end it at $bank too"
                        },
                    )
                }
            },
            AnswerKind.Done,
            emptyList(),
            bankReference = bank,
        )
        RevokeOutcome.NothingToRevoke -> ConsentResultUi("Not revoked", listOf("$bank has no Bank Consent of $provider · nothing sent"), AnswerKind.Unchanged, emptyList())
        is RevokeOutcome.NotStored -> ConsentResultUi(
            "Not revoked",
            listOf(
                "Slussen could not delete the stored Bank Consent · it stays stored; try again" +
                    if (outcome.endedAtDataProvider) " · $provider has ended it already" else "",
            ),
            AnswerKind.Failed,
            emptyList(),
        )
        is RevokeOutcome.NotAvailable -> ConsentResultUi("Not revoked", listOf(unavailable(outcome.reason, provider)), AnswerKind.Unchanged, emptyList())
    }
}

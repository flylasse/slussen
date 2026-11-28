package slussen.testing

import slussen.core.port.ConsentStore
import slussen.core.port.ConsumedAnswer
import slussen.core.port.GrantedBankConsent
import slussen.core.port.NewConsentRequest
import slussen.core.port.ReplacedConsent
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.value.Secret
import java.time.Duration
import java.time.Instant

/**
 * The cases of O-10 (ADR-002 §3; UC-007 BR-002, BR-003, BR-007, A2, A7, A10; UC-001 A9) on one [ConsentStore]: the same
 * steps run on the in-memory fake and on the Room store of `device.db` (on the JVM over a list-backed DAO, and on a
 * device in `UC007GrantBankConsentPersistenceIT`), and each must give exactly [EXPECTED], so that the fake every core
 * test uses answers as `device.db` does. Invented data only: two Banks by their references, two Data Providers by id,
 * `state` digests of 64 hex characters, and Bank Sessions and handles whose text a test of `:persistence` reads back.
 */
class ConsentStoreScenario(private val store: ConsentStore) {
    private val transcript = mutableListOf<String>()

    private fun note(line: String) {
        transcript += line
    }

    private fun digest(n: Int): String = n.toString().padStart(64, 'a')

    private fun request(bank: String, dataProvider: Long, digest: Int, startedAt: Instant, handle: String? = null) =
        NewConsentRequest(bank, dataProvider, startedAt, startedAt.plus(LIFETIME), digest(digest), handle?.let(::Secret))

    private fun ConsumedAnswer.text(): String = when (this) {
        is ConsumedAnswer.Accepted -> "accepted request ${request.id} answered=${request.answered} handle=${providerHandle != null}"
        is ConsumedAnswer.Rejected -> "rejected $reason"
    }

    private fun ReplacedConsent.text(): String = when (this) {
        is ReplacedConsent.Stored ->
            "stored consent bank=${consent.bankId} provider=${consent.dataProviderId} granted=${consent.grantedAt} " +
                "expires=${consent.expiresAt} refused=${consent.refusedAt} session of consent=${session.bankConsentId == consent.id} opened=${session.openedAt}"
        is ReplacedConsent.Refused -> "refused $reason"
    }

    /** What is stored now: every Bank Consent, Bank Session and Consent Request, without the identifiers each store chooses. */
    private suspend fun state(moment: String) {
        val consents = store.bankConsents()
        val sessions = store.bankSessions()
        note(
            "$moment: consents " + consents.joinToString { "(${it.bankId}, ${it.dataProviderId}, ${it.expiresAt}, refused ${it.refusedAt})" } +
                "; sessions " + sessions.joinToString { session -> "(of ${consents.singleOrNull { it.id == session.bankConsentId }?.let { "${it.bankId}/${it.dataProviderId}" }}, ${session.openedAt})" } +
                "; requests " + store.consentRequests().joinToString { "(${it.bankReference}, ${it.dataProviderId}, ${it.startedAt}, answered ${it.answered})" },
        )
    }

    private fun granted(bankId: Long, at: Instant, session: String) =
        GrantedBankConsent(bankId, at, at.plus(Duration.ofDays(180)), ConsentSession(session), at)

    /** Runs every step and returns what each gave, in order. */
    suspend fun run(): List<String> {
        val first = store.startRequest(request(AURORA, OPEN_BANKING, 1, T0, handle = "handle-one"))
        note("start Aurora: answered=${first.answered} expires=${first.expiresAt}")
        val vesta = store.startRequest(request(VESTA, OPEN_BANKING, 2, T0))
        note("start Vesta: ${vesta.bankReference}")
        note("start with a stored digest: " + runCatching { store.startRequest(request(VESTA, OTHER_PROVIDER, 1, T0)) }.exceptionOrNull()?.javaClass?.simpleName)
        state("after the starts")

        note("unknown digest: " + store.consumeAnswer(digest(9), T0).text())
        note("after its lifetime: " + store.consumeAnswer(digest(1), T0.plus(LIFETIME).plusSeconds(1)).text())
        note("after the late answer, answered stays ${store.consentRequests().single { it.id == first.id }.answered}")

        val second = store.startRequest(request(AURORA, OPEN_BANKING, 3, T1, handle = "handle-three"))
        note("start Aurora again: the earlier one ${if (store.consentRequests().none { it.id == first.id }) "is replaced" else "is kept"}")
        note("the earlier one's answer: " + store.consumeAnswer(digest(1), T1).text())
        note("replace before an answer: " + store.replaceConsent(second.id, granted(AURORA_BANK, T1, "session-one")).text())
        note("abandon an unknown one: ${store.abandonRequest(9999)}")
        note("answer within its lifetime: " + store.consumeAnswer(digest(3), T1.plus(LIFETIME)).text())
        note("the same answer again: " + store.consumeAnswer(digest(3), T1.plus(LIFETIME)).text())
        note("abandon an answered one: ${store.abandonRequest(second.id)}")
        note("replace: " + store.replaceConsent(second.id, granted(AURORA_BANK, T1, "session-one")).text())
        state("after the grant")

        note("mark refused: ${store.markRefused(AURORA_BANK, OPEN_BANKING, T1.plus(Duration.ofDays(10)))}")
        note("mark refused earlier: ${store.markRefused(AURORA_BANK, OPEN_BANKING, T1.plus(Duration.ofDays(9)))}")
        note("mark refused later: ${store.markRefused(AURORA_BANK, OPEN_BANKING, T1.plus(Duration.ofDays(11)))}")
        note("mark refused without a Bank Consent: ${store.markRefused(VESTA_BANK, OPEN_BANKING, T1)}")
        state("after the refusals")

        val renewal = store.startRequest(request(AURORA, OPEN_BANKING, 4, T2))
        note("renewal answered: " + store.consumeAnswer(digest(4), T2).text())
        val newer = store.startRequest(request(AURORA, OPEN_BANKING, 5, T2.plusSeconds(60)))
        note("replace after a newer start: " + store.replaceConsent(renewal.id, granted(AURORA_BANK, T2, "session-two")).text())
        note("newer answered: " + store.consumeAnswer(digest(5), T2.plusSeconds(120)).text())
        note("renew: " + store.replaceConsent(newer.id, granted(AURORA_BANK, T2.plusSeconds(120), "session-three")).text())
        state("after the renewal")

        note("abandon Vesta: ${store.abandonRequest(vesta.id)}")
        note("Vesta's answer after it: " + store.consumeAnswer(digest(2), T0).text())
        note("abandon Vesta again: ${store.abandonRequest(vesta.id)}")

        val other = store.startRequest(request(AURORA, OTHER_PROVIDER, 6, T2))
        note("other provider answered: " + store.consumeAnswer(digest(6), T2).text())
        note("other provider: " + store.replaceConsent(other.id, granted(AURORA_BANK, T2, "session-four")).text())
        store.startRequest(request(AURORA, OPEN_BANKING, 7, T2.plusSeconds(300)))
        state("before the revocation")

        store.revoke(AURORA_BANK, OPEN_BANKING, AURORA)
        state("after the revocation")
        note("revoke again: " + runCatching { store.revoke(AURORA_BANK, OPEN_BANKING, AURORA) }.isSuccess)
        return transcript.toList()
    }

    companion object {
        const val AURORA = "Aurora Bank (FI)"
        const val VESTA = "Vesta Sparbank (SE)"
        const val AURORA_BANK = 7L
        const val VESTA_BANK = 8L
        const val OPEN_BANKING = 2L
        const val OTHER_PROVIDER = 3L
        val LIFETIME: Duration = Duration.ofMinutes(15)
        val T0: Instant = Instant.parse("2026-11-28T10:00:00Z")
        val T1: Instant = Instant.parse("2026-11-28T10:20:00Z")
        val T2: Instant = Instant.parse("2027-05-01T09:00:00Z")

        /** What every [ConsentStore] must give, step by step. */
        val EXPECTED: List<String> = listOf(
            "start Aurora: answered=false expires=2026-11-28T10:15:00Z",
            "start Vesta: Vesta Sparbank (SE)",
            "start with a stored digest: IllegalArgumentException",
            "after the starts: consents ; sessions ; requests (Aurora Bank (FI), 2, 2026-11-28T10:00:00Z, answered false), (Vesta Sparbank (SE), 2, 2026-11-28T10:00:00Z, answered false)",
            "unknown digest: rejected NoSuchRequest",
            "after its lifetime: rejected LifetimeEnded",
            "after the late answer, answered stays false",
            "start Aurora again: the earlier one is replaced",
            "the earlier one's answer: rejected NoSuchRequest",
            "replace before an answer: refused NotAnswered",
            "abandon an unknown one: false",
            "answer within its lifetime: accepted request 3 answered=true handle=true",
            "the same answer again: rejected AlreadyAnswered",
            "abandon an answered one: false",
            "replace: stored consent bank=7 provider=2 granted=2026-11-28T10:20:00Z expires=2027-05-27T10:20:00Z refused=null session of consent=true opened=2026-11-28T10:20:00Z",
            "after the grant: consents (7, 2, 2027-05-27T10:20:00Z, refused null); sessions (of 7/2, 2026-11-28T10:20:00Z); requests (Vesta Sparbank (SE), 2, 2026-11-28T10:00:00Z, answered false), (Aurora Bank (FI), 2, 2026-11-28T10:20:00Z, answered true)",
            "mark refused: true",
            "mark refused earlier: true",
            "mark refused later: true",
            "mark refused without a Bank Consent: false",
            "after the refusals: consents (7, 2, 2027-05-27T10:20:00Z, refused 2026-12-07T10:20:00Z); sessions (of 7/2, 2026-11-28T10:20:00Z); requests (Vesta Sparbank (SE), 2, 2026-11-28T10:00:00Z, answered false), (Aurora Bank (FI), 2, 2026-11-28T10:20:00Z, answered true)",
            "renewal answered: accepted request 4 answered=true handle=false",
            "replace after a newer start: refused NoSuchRequest",
            "newer answered: accepted request 5 answered=true handle=false",
            "renew: stored consent bank=7 provider=2 granted=2027-05-01T09:02:00Z expires=2027-10-28T09:02:00Z refused=null session of consent=true opened=2027-05-01T09:02:00Z",
            "after the renewal: consents (7, 2, 2027-10-28T09:02:00Z, refused null); sessions (of 7/2, 2027-05-01T09:02:00Z); requests (Vesta Sparbank (SE), 2, 2026-11-28T10:00:00Z, answered false), (Aurora Bank (FI), 2, 2027-05-01T09:01:00Z, answered true)",
            "abandon Vesta: true",
            "Vesta's answer after it: rejected NoSuchRequest",
            "abandon Vesta again: false",
            "other provider answered: accepted request 6 answered=true handle=false",
            "other provider: stored consent bank=7 provider=3 granted=2027-05-01T09:00:00Z expires=2027-10-28T09:00:00Z refused=null session of consent=true opened=2027-05-01T09:00:00Z",
            "before the revocation: consents (7, 2, 2027-10-28T09:02:00Z, refused null), (7, 3, 2027-10-28T09:00:00Z, refused null); sessions (of 7/2, 2027-05-01T09:02:00Z), (of 7/3, 2027-05-01T09:00:00Z); requests (Aurora Bank (FI), 3, 2027-05-01T09:00:00Z, answered true), (Aurora Bank (FI), 2, 2027-05-01T09:05:00Z, answered false)",
            "after the revocation: consents (7, 3, 2027-10-28T09:00:00Z, refused null); sessions (of 7/3, 2027-05-01T09:00:00Z); requests (Aurora Bank (FI), 3, 2027-05-01T09:00:00Z, answered true)",
            "revoke again: true",
        )
    }
}

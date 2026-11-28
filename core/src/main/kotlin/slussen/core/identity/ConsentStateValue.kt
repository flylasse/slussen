package slussen.core.identity

import slussen.core.port.RandomSource
import slussen.pluginapi.value.Secret

/**
 * The `state` value of a Consent Request and its digest (ADR-001 §4.1, ADR-002 §2 `consent_request_correlation`, ADR-008
 * §7): the core makes the value, the plug-in sends it and reads it back from the answer, and only its digest is stored.
 * It lives here with the other one-way values of the core (Konsist K-15). The core never reveals a [Secret] (ADR-006
 * L-04): the value is digested through [Secret.sha256], one way.
 */
object ConsentStateValue {
    private const val HEX = "0123456789abcdef"

    /** 256 bits (ADR-001 §4.1): a guess of another installation's value, or of a later one, is as likely as none. */
    const val STATE_BYTES: Int = 32

    // UC-007 BR-002: A Consent Request is accepted at most once, so its state value is new for every request and cannot be guessed.
    /** A new `state` value: [STATE_BYTES] bytes from [random], in lower-case hex (64 characters, safe in any address). */
    fun newState(random: RandomSource): Secret {
        val bytes = ByteArray(STATE_BYTES)
        random.nextBytes(bytes)
        return Secret(hex(bytes))
    }

    /** The SHA-256 digest of [state], in lower-case hex (64 characters): what `state_digest` stores, never [state] itself. */
    fun digestOf(state: Secret): String = hex(state.sha256())

    private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { byte -> append(HEX[(byte.toInt() shr 4) and 0x0f]).append(HEX[byte.toInt() and 0x0f]) }
    }
}

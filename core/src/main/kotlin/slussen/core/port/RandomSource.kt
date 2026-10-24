package slussen.core.port

/**
 * The source of every random value the core generates (ADR-001 §3): a `java.security.SecureRandom` in the app, a
 * fixed sequence in tests.
 */
fun interface RandomSource {
    fun nextBytes(bytes: ByteArray)
}

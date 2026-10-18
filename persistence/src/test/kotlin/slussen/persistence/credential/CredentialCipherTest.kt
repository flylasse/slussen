package slussen.persistence.credential

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64
import javax.crypto.KeyGenerator

/**
 * ADR-002 §2a on the JVM with a software AES-256 key in place of the Android Keystore key, which only a device has
 * (evidence E-13 of ADR-005 §7 covers the Keystore).
 */
class CredentialCipherTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val cipher = CredentialCipher { key }
    private val invented = "invented-credential-4711-for-a-test"

    @Test
    fun aStoredCredentialReadsBackUnchanged() {
        assertEquals(invented, cipher.decrypt(cipher.encrypt(invented)))
    }

    @Test
    fun storedFormIsBase64OfIvCiphertextAndTagWithoutThePlaintext() {
        val stored = cipher.encrypt(invented)
        val bytes = Base64.getDecoder().decode(stored)
        assertEquals(CredentialCipher.IV_BYTES + invented.length + CredentialCipher.TAG_BYTES, bytes.size)
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains(invented))
        assertFalse(stored.contains(invented))
    }

    @Test
    fun everyEncryptionUsesAFreshIv() {
        val first = Base64.getDecoder().decode(cipher.encrypt(invented)).copyOfRange(0, CredentialCipher.IV_BYTES)
        val second = Base64.getDecoder().decode(cipher.encrypt(invented)).copyOfRange(0, CredentialCipher.IV_BYTES)
        assertNotEquals(first.toList(), second.toList())
    }

    @Test
    fun aChangedCiphertextIsUnreadable() {
        val bytes = Base64.getDecoder().decode(cipher.encrypt(invented))
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 1).toByte()
        assertThrows<CredentialUnreadable> { cipher.decrypt(Base64.getEncoder().encodeToString(bytes)) }
    }

    @Test
    fun anotherKeyCannotRead() {
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val stored = cipher.encrypt(invented)
        assertThrows<CredentialUnreadable> { CredentialCipher { otherKey }.decrypt(stored) }
    }

    @Test
    fun textThatIsNoCiphertextIsUnreadable() {
        assertThrows<CredentialUnreadable> { cipher.decrypt("not base64 at all!") }
        assertThrows<CredentialUnreadable> { cipher.decrypt("AAAA") }
    }
}

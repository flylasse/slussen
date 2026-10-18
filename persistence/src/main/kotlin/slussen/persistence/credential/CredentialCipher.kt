package slussen.persistence.credential

import java.security.GeneralSecurityException
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AES-256 in GCM mode with a fresh 96-bit IV per encryption, stored as Base64 of IV, ciphertext and tag (ADR-002 §2a).
 * The [key] is the Android Keystore key in the app, a software key in JVM tests. No library is added: the platform's
 * `javax.crypto` only.
 */
class CredentialCipher(private val key: () -> SecretKey) {
    // UC-014 BR-003: Credentials are stored only on the device, encrypted with a key that never leaves it.
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv != null && iv.size == IV_BYTES) { "The cipher did not produce a fresh 96-bit IV." }
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + sealed)
    }

    /** Throws [CredentialUnreadable] when [stored] cannot be decrypted, for example because the key is gone. */
    fun decrypt(stored: String): String {
        val bytes = try {
            Base64.getDecoder().decode(stored)
        } catch (e: IllegalArgumentException) {
            throw CredentialUnreadable()
        }
        if (bytes.size < IV_BYTES + TAG_BYTES) throw CredentialUnreadable()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BYTES * 8, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        } catch (e: GeneralSecurityException) {
            throw CredentialUnreadable()
        }
    }

    companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BYTES = 16
    }
}

/** A stored credential that cannot be decrypted; it carries no part of the stored value. */
class CredentialUnreadable : Exception("The stored service credential cannot be decrypted.")

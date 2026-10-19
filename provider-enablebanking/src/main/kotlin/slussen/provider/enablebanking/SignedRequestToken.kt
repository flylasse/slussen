package slussen.provider.enablebanking

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The signed token Enable Banking requires on every request of an application: a JWT signed with RS256 by the
 * application's private key, whose `kid` is the application id, with the issuer and audience Enable Banking names.
 */
internal object SignedRequestToken {
    /**
     * How long one token is valid. Enable Banking accepts tokens valid for at most 24 hours (its documentation, "JWT
     * format"); each check signs a fresh one, so one hour is well inside that limit.
     */
    private val lifetime: Duration = Duration.ofHours(1)

    fun sign(applicationId: String, key: PrivateKey, now: Instant): String {
        val header = buildJsonObject {
            put("typ", "JWT")
            put("alg", "RS256")
            put("kid", applicationId)
        }
        val claims = buildJsonObject {
            put("iss", "enablebanking.com")
            put("aud", "api.enablebanking.com")
            put("iat", now.epochSecond)
            put("exp", now.plus(lifetime).epochSecond)
        }
        val signingInput = encode(header.toString().toByteArray()) + "." + encode(claims.toString().toByteArray())
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(signingInput.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return signingInput + "." + encode(signature)
    }

    private fun encode(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/** Reads the private key file of an Enable Banking application: PKCS#8 (`BEGIN PRIVATE KEY`) or PKCS#1 (`BEGIN RSA PRIVATE KEY`). */
internal object PrivateKeyFile {
    private val block = Regex("""-----BEGIN (RSA )?PRIVATE KEY-----([A-Za-z0-9+/=\s]+)-----END (RSA )?PRIVATE KEY-----""")

    /** The key, or null when [pem] holds no readable RSA private key. */
    fun read(pem: String): PrivateKey? {
        val found = block.find(pem) ?: return null
        val der = try {
            Base64.getMimeDecoder().decode(found.groupValues[2])
        } catch (e: IllegalArgumentException) {
            return null
        }
        val pkcs8 = if (found.groupValues[1].isNotEmpty()) wrapPkcs1(der) else der
        return try {
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(pkcs8))
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** PKCS#8 around a PKCS#1 RSA key: version 0, the rsaEncryption algorithm identifier, the key as an octet string. */
    private fun wrapPkcs1(pkcs1: ByteArray): ByteArray {
        val version = byteArrayOf(0x02, 0x01, 0x00)
        val rsaEncryption = byteArrayOf(
            0x30, 0x0d, 0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00,
        )
        val octets = byteArrayOf(0x04) + length(pkcs1.size) + pkcs1
        val body = version + rsaEncryption + octets
        return byteArrayOf(0x30) + length(body.size) + body
    }

    /** A DER length. */
    private fun length(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size < 0x100 -> byteArrayOf(0x81.toByte(), size.toByte())
        size < 0x10000 -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
        else -> byteArrayOf(0x83.toByte(), (size shr 16).toByte(), (size shr 8).toByte(), size.toByte())
    }
}

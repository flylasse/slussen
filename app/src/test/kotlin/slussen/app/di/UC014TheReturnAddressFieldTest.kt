package slussen.app.di

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.core.credential.RefusedFields
import slussen.pluginapi.credential.CredentialCheck
import slussen.pluginapi.credential.CredentialInput
import slussen.pluginapi.testfixtures.RecordingRequestGate
import slussen.pluginapi.testfixtures.TestRequestContext
import slussen.pluginapi.testfixtures.UseCase
import slussen.pluginapi.value.Secret
import slussen.provider.enablebanking.EnableBankingDataProvider
import slussen.testing.RecordingSlussenLog
import java.security.KeyPairGenerator
import java.util.Base64

/**
 * UC-014 BR-002 with the real part of the Data Provider of this build that needs a Bank Consent, as the composition root
 * lists it (ADR-001 §5): its credentials' form marks the return address as optional (S-B-51), a value the part refuses
 * is named at its field, and an entry without it goes on to the check. The key is made for the test; nothing is sent.
 */
@DisplayName("UC-014: Manage Service Credentials, the optional return address with the real part")
class UC014TheReturnAddressFieldTest {
    private val part = EnableBankingDataProvider(RecordingSlussenLog())
    private val fields = part.credentials.form.fields

    private val pem: String = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded.let {
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it) + "\n-----END PRIVATE KEY-----\n"
    }

    /** The entry as the screen gives it: an optional field left empty is left out. */
    private fun input(returnAddress: String) = CredentialInput(
        mapOf("applicationId" to Secret("00000000-0000-4000-8000-00000000e4e4"), "privateKey" to Secret(pem)) +
            (if (returnAddress.isEmpty()) emptyMap() else mapOf("returnAddress" to Secret(returnAddress))),
    )

    @Test
    @UseCase(id = "UC-014", scenario = "Main Success Scenario", businessRules = ["BR-002"])
    @DisplayName("BR-002 (S-B-51): the return address is the one optional field of the form")
    fun theReturnAddressIsOptional() {
        assertEquals(listOf(false, false, true), fields.map { it.optional })
        assertEquals("returnAddress", fields.single { it.optional }.key)
    }

    @Test
    @UseCase(id = "UC-014", scenario = "A1: Credentials Refused", businessRules = ["BR-002"])
    @DisplayName("A1 (E4 review item 3): only the part's own check of the return address, before anything is sent, names it at its field; left empty, the entry goes on to the check")
    fun aRefusedReturnAddressAtItsField() = runBlocking {
        val gate = RecordingRequestGate.refusing()
        assertEquals(CredentialCheck.Refused, part.credentials.check(input("not an address"), TestRequestContext(gate)))
        assertTrue(gate.requests.isEmpty())

        val timeout = TestRequestContext().timeout
        assertEquals(setOf("returnAddress"), RefusedFields.of(part.credentials, input("not an address"), timeout, sentAnything = false))
        // Another field the part refused: the return address is not named.
        val badKey = CredentialInput(input("not an address").values + ("privateKey" to Secret("not a key")))
        assertEquals(emptySet<String>(), RefusedFields.of(part.credentials, badKey, timeout, sentAnything = false))
        // A refusal by the Data Provider, after the check was sent, is a refusal of the credentials at no field.
        assertEquals(emptySet<String>(), RefusedFields.of(part.credentials, input("https://household.example/return"), timeout, sentAnything = true))

        val withoutIt = part.credentials.check(input(""), TestRequestContext(gate))
        assertTrue(withoutIt is CredentialCheck.StoppedByGate)
        assertEquals(1, gate.requests.size)
        assertEquals(emptySet<String>(), RefusedFields.of(part.credentials, input(""), timeout, sentAnything = false))
    }
}

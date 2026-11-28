package slussen.app.di

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import slussen.app.ui.service.FieldUi
import slussen.app.ui.service.refusedOptionalFields
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
    private val fields = part.credentials.form.fields.map { FieldUi(it.key, it.kind, it.description, it.optional) }

    private val pem: String = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair().private.encoded.let {
        "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(it) + "\n-----END PRIVATE KEY-----\n"
    }

    private fun input(returnAddress: String) = CredentialInput(
        mapOf("applicationId" to Secret("00000000-0000-4000-8000-00000000e4e4"), "privateKey" to Secret(pem), "returnAddress" to Secret(returnAddress)),
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
    @DisplayName("A1 (E3 review item 3): a return address the part refuses is refused without contacting the service and named at its field; left empty, the entry goes on to the check")
    fun aRefusedReturnAddressAtItsField() = runBlocking {
        val gate = RecordingRequestGate.refusing()

        assertEquals(CredentialCheck.Refused, part.credentials.check(input("not an address"), TestRequestContext(gate)))
        assertTrue(gate.requests.isEmpty())
        assertEquals(setOf("returnAddress"), refusedOptionalFields(fields, mapOf("applicationId" to "x", "returnAddress" to "not an address")).keys)

        val withoutIt = part.credentials.check(input(""), TestRequestContext(gate))
        assertTrue(withoutIt is CredentialCheck.StoppedByGate)
        assertEquals(1, gate.requests.size)
        assertTrue(refusedOptionalFields(fields, mapOf("applicationId" to "x", "returnAddress" to "")).isEmpty())
    }
}

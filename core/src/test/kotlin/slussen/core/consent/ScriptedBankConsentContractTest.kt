package slussen.core.consent

import org.junit.jupiter.api.DisplayName
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankAccountResult
import slussen.pluginapi.provider.BankChoice
import slussen.pluginapi.provider.BankList
import slussen.pluginapi.provider.ConsentAnswer
import slussen.pluginapi.provider.ConsentCompletion
import slussen.pluginapi.provider.ConsentFailureKind
import slussen.pluginapi.provider.ConsentSession
import slussen.pluginapi.provider.ConsentStart
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.provider.DeliveredBankAccount
import slussen.pluginapi.provider.NotReadCause
import slussen.pluginapi.testfixtures.BankConsentContractTest
import slussen.pluginapi.testfixtures.ConsentAnswerScenario
import slussen.pluginapi.testfixtures.ConsentRequestScenario
import slussen.pluginapi.value.Secret
import slussen.testing.ExampleData
import slussen.testing.FakeDataProvider
import slussen.testing.ScriptedBankConsent
import java.time.Instant

/**
 * The contract test of the Bank Consent operations run against the scripted fake of `:testing`, so that the fake the
 * core's tests use answers as the contract requires of every plug-in, and so that the contract test itself runs before
 * any plug-in implements the capability (phase E3 of the build-out).
 */
@DisplayName("ADR-001 §4.4: the scripted Bank Consent operations pass the contract test")
class ScriptedBankConsentContractTest : BankConsentContractTest() {
    private val fake = ScriptedBankConsent()
    private val provider = FakeDataProvider(ExampleData.openBankingProfile, bankConsent = fake)
    private val session = ConsentSession("PLANTED-CONTRACT-SESSION-4e1a")
    private val cancelled = ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "error=access_denied&state=contract-a")
    private val unreadable = ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "state=contract-b")
    private val granting = ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "code=PLANTED-CONTRACT-CODE-0c3f&state=contract-c")

    init {
        fake.banksAnswer = { BankList.Listed(listOf(BankChoice("Aurora Sparbank (FI)", "Aurora Sparbank"))) }
        fake.startAnswer = { ConsentStart.Started("https://aurora.example/sca?ticket=PLANTED-CONTRACT-ADDRESS-8a7e", Secret("handle")) }
        fake.completeAnswer = { answer: ConsentAnswer, _ ->
            when {
                answer === cancelled -> ConsentCompletion.NotCompleted
                answer === unreadable -> ConsentCompletion.NotConfirmed(ConsentFailureKind.AnswerNotUnderstood)
                else -> ConsentCompletion.Completed(
                    Instant.parse("2027-04-13T12:00:00Z"),
                    listOf(
                        DeliveredBankAccount("lasting-1", "Aurora Sparbank (FI)", "Current", null, ExampleData.EUR, null, BankAccountResult.NotRead(NotReadCause.NotSelected)),
                    ),
                    session,
                )
            }
        }
    }

    override fun dataProvider(log: SlussenLog): DataProvider = provider

    override fun credential(): Secret = Secret(ExampleData.EXCHANGED_ACCESS_CREDENTIAL)

    override fun requestsReceived(): Int = fake.sent.size

    override fun banksListed(): String = "FI"

    override fun consentStarted(): ConsentRequestScenario = ConsentRequestScenario("Aurora Sparbank (FI)", ExampleData.CONSENT_RETURN_ADDRESS)

    override fun consentCompleted(): ConsentAnswerScenario = ConsentAnswerScenario(granting, Secret("handle"))

    override fun consentCancelled(): ConsentAnswerScenario = ConsentAnswerScenario(cancelled, Secret("handle"))

    override fun unreadableAnswer(): ConsentAnswerScenario = ConsentAnswerScenario(unreadable, Secret("handle"))

    override fun answerCarrying(state: Secret): ConsentAnswer =
        ScriptedBankConsent.answer(ExampleData.CONSENT_RETURN_ADDRESS, "code=x&state=${revealed(state)}")

    @OptIn(slussen.pluginapi.value.SensitiveApi::class)
    private fun revealed(state: Secret): String = state.reveal()

    override fun sessionToEnd(): ConsentSession = session

    override fun plantedValues(): List<String> =
        listOf("PLANTED-CONTRACT-SESSION-4e1a", "PLANTED-CONTRACT-ADDRESS-8a7e", "PLANTED-CONTRACT-CODE-0c3f", ExampleData.EXCHANGED_ACCESS_CREDENTIAL)
}

package slussen.provider.enablebanking

import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.provider.DataProviderProfile
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.request.RequestContext

/**
 * The Enable Banking Data Provider (ADR-001 §1, §4.1), the only Data Provider of this build that needs a Bank Consent
 * (UC-007). Module skeleton: the class the composition root registers, without behaviour. Its profile, credential form,
 * Bank Consent capability and requests come with the slices that build UC-014, UC-007 and UC-001.
 */
class EnableBankingDataProvider(private val log: SlussenLog) : DataProvider {
    override val profile: DataProviderProfile
        get() = TODO("Declared by the slice that builds UC-001 (ADR-001 §4.1).")

    override val credentials: CredentialCapability
        get() = TODO("Built by the slice that builds UC-014 (ADR-001 §4.5).")

    override val bankConsent: BankConsentCapability?
        get() = TODO("Built by the slice that builds UC-007 (ADR-001 §4.1).")

    override suspend fun fetch(request: FetchRequest, context: RequestContext): FetchResponse =
        TODO("Built by the slice that builds UC-001 (ADR-001 §4.1).")
}

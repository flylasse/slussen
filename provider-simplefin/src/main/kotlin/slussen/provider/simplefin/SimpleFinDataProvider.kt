package slussen.provider.simplefin

import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.provider.DataProviderProfile
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.request.RequestContext

/**
 * The SimpleFIN Data Provider (ADR-001 §1, §4.1). Module skeleton: the class the composition root registers, without
 * behaviour. Its profile, with the Request Limit and its source, its credential form with the setup-token exchange
 * (ADR-001 §4.5) and its requests come with the slices that build UC-014 and UC-001.
 */
class SimpleFinDataProvider(private val log: SlussenLog) : DataProvider {
    override val profile: DataProviderProfile
        get() = TODO("Declared by the slice that builds UC-001 (ADR-001 §4.1).")

    override val credentials: CredentialCapability
        get() = TODO("Built by the slice that builds UC-014 (ADR-001 §4.5).")

    /** SimpleFIN needs no Bank Consent. */
    override val bankConsent: BankConsentCapability? = null

    override suspend fun fetch(request: FetchRequest, context: RequestContext): FetchResponse =
        TODO("Built by the slice that builds UC-001 (ADR-001 §4.1).")
}

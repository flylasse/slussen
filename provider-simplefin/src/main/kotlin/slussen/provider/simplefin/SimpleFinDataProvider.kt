package slussen.provider.simplefin

import okhttp3.OkHttpClient
import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.provider.DataProviderProfile
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.request.RequestContext

/**
 * The SimpleFIN Data Provider (ADR-001 §1, §4.1). This slice builds its credentials (UC-014 BR-002, ADR-001 §4.5): a
 * setup token, exchanged once for the access credential. Its profile and its requests come with the slice that builds
 * UC-001, once the Operator has decided the declared values.
 */
class SimpleFinDataProvider internal constructor(
    private val log: SlussenLog,
    http: OkHttpClient,
    allowPlainHttp: Boolean,
) : DataProvider {
    constructor(log: SlussenLog) : this(log, OkHttpClient(), allowPlainHttp = false)

    override val profile: DataProviderProfile
        get() = TODO(
            "Operator decision owed (Phase 7, Open): source_key, Request Limit with its source and default Fetch " +
                "Cooldown are not stated in the specification or the ADRs.",
        )

    override val credentials: CredentialCapability = SimpleFinCredentials(http, allowPlainHttp)

    /** SimpleFIN needs no Bank Consent. */
    override val bankConsent: BankConsentCapability? = null

    override suspend fun fetch(request: FetchRequest, context: RequestContext): FetchResponse =
        TODO("Built by the slice that builds UC-001 (ADR-001 §4.1).")
}

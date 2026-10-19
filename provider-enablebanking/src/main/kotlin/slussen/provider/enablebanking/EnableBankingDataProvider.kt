package slussen.provider.enablebanking

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.provider.BankConsentCapability
import slussen.pluginapi.provider.DataProvider
import slussen.pluginapi.provider.DataProviderProfile
import slussen.pluginapi.provider.FetchRequest
import slussen.pluginapi.provider.FetchResponse
import slussen.pluginapi.request.RequestContext
import java.time.Clock

/**
 * The Enable Banking Data Provider (ADR-001 §1, §4.1), the only Data Provider of this build that needs a Bank Consent
 * (UC-007). This slice builds its credentials (UC-014 BR-002, ADR-001 §4.5): the application id and private key file of
 * the household's own registered Enable Banking application (C-008). Its profile, its Bank Consent capability and its
 * requests come with the slices that build UC-007 and UC-001, once the Operator has decided the declared values.
 */
class EnableBankingDataProvider internal constructor(
    private val log: SlussenLog,
    base: HttpUrl,
    http: OkHttpClient,
    clock: Clock,
) : DataProvider {
    constructor(log: SlussenLog) : this(log, PUBLIC_INTERFACE.toHttpUrl(), OkHttpClient(), Clock.systemUTC())

    override val profile: DataProviderProfile
        get() = TODO(
            "Operator decision owed (Phase 7, Open): source_key, Request Limit with its source and default Fetch " +
                "Cooldown are not stated in the specification or the ADRs.",
        )

    override val credentials: CredentialCapability = EnableBankingCredentials(base, http, clock)

    override val bankConsent: BankConsentCapability?
        get() = TODO("Built by the slice that builds UC-007 (ADR-001 §4.1).")

    override suspend fun fetch(request: FetchRequest, context: RequestContext): FetchResponse =
        TODO("Built by the slice that builds UC-001 (ADR-001 §4.1).")

    companion object {
        /** Enable Banking's public interface (C-008). */
        const val PUBLIC_INTERFACE: String = "https://api.enablebanking.com/"
    }
}

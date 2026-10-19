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
import slussen.pluginapi.provider.RequestLimitDeclaration
import slussen.pluginapi.request.RequestContext
import java.time.Clock

/**
 * The Enable Banking Data Provider (ADR-001 §1, §4.1), the only Data Provider of this build that needs a Bank Consent
 * (UC-007): its declarations (ADR-001 §4.1, O-P2-42, S-31) and its credentials (UC-014 BR-002, ADR-001 §4.5): the
 * application id and private key file of the household's own registered Enable Banking application (C-008). Its Bank
 * Consent capability and its requests come with the slices that build UC-007 and UC-001.
 */
class EnableBankingDataProvider internal constructor(
    private val log: SlussenLog,
    base: HttpUrl,
    http: OkHttpClient,
    clock: Clock,
) : DataProvider {
    constructor(log: SlussenLog) : this(log, PUBLIC_INTERFACE.toHttpUrl(), OkHttpClient(), Clock.systemUTC())

    /**
     * The values ADR-001 §4.1 states for this part. Enable Banking publishes no quota of its own (Enable Banking FAQ,
     * https://enablebanking.com/docs/faq/: quotas are set by contract), so its Request Limit is a default of 50 requests
     * in any 24 hours, an Operator Setting between 10 and 500; the Banks' PSD2 cap is the Unattended Access limit
     * (C-006), counted separately. Its default Fetch Cooldown of 360 minutes, an Operator Setting, follows the same FAQ's
     * advice to continue after 6 hours once a Bank answers that its rate limit is exceeded. `givesStableReference` is not
     * stated in the specification (Phase 7a, Open): Enable Banking passes on each Bank's own references, which not every
     * Bank gives every transaction.
     */
    override val profile: DataProviderProfile = DataProviderProfile(
        name = "Enable Banking",
        sourceKey = "enable-banking",
        givesStableReference = false,
        needsBankConsent = true,
        defaultFetchCooldownMinutes = 360,
        // UC-001 BR-007: Enable Banking publishes no Request Limit, so its part declares a cautious default the Operator can change within its range.
        requestLimit = RequestLimitDeclaration.NotPublished(defaultRequests = 50, range = 10..500),
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

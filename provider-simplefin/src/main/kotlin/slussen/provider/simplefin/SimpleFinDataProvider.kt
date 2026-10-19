package slussen.provider.simplefin

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

/**
 * The SimpleFIN Data Provider (ADR-001 §1, §4.1): its declarations (ADR-001 §4.1, O-P2-42, S-31, S-34), its
 * credentials (UC-014 BR-002, ADR-001 §4.5): a setup token, exchanged once for the access credential, and its Fetch
 * (UC-001), one request that lists every account.
 */
class SimpleFinDataProvider internal constructor(
    private val log: SlussenLog,
    http: OkHttpClient,
    allowPlainHttp: Boolean,
) : DataProvider {
    constructor(log: SlussenLog) : this(log, OkHttpClient(), allowPlainHttp = false)

    /**
     * The values ADR-001 §4.1 states for this part. Its Request Limit is the one SimpleFIN publishes, 24 requests in any
     * 24 hours, fixed (SimpleFIN Bridge Developer Guide, https://beta-bridge.simplefin.org/info/developers: "you are
     * expected to make 24 requests or fewer per day"); its default Fetch Cooldown of 60 minutes is an Operator Setting.
     * It gives a Stable Reference (S-34): each transaction's `id` (SimpleFIN protocol, https://www.simplefin.org/protocol.html:
     * an `id` "uniquely describes a transaction within an Account", and an organization "may never reuse a transaction id"
     * within the same account), so its Import Identifiers are of kind `R` (ADR-008 §2).
     */
    override val profile: DataProviderProfile = DataProviderProfile(
        name = "SimpleFIN",
        sourceKey = "simplefin",
        // UC-012 BR-005: SimpleFIN gives every transaction an id unique within its account, its Stable Reference (ADR-001 §4.1).
        givesStableReference = true,
        needsBankConsent = false,
        defaultFetchCooldownMinutes = 60,
        // UC-001 BR-007: SimpleFIN publishes its Request Limit, so its part declares it with its source and the Operator cannot change it.
        requestLimit = RequestLimitDeclaration.Published(
            requests = 24,
            source = "SimpleFIN Bridge Developer Guide, https://beta-bridge.simplefin.org/info/developers",
        ),
    )

    override val credentials: CredentialCapability = SimpleFinCredentials(http, allowPlainHttp)

    private val fetching = SimpleFinFetch(http, allowPlainHttp)

    /** SimpleFIN needs no Bank Consent. */
    override val bankConsent: BankConsentCapability? = null

    override suspend fun fetch(request: FetchRequest, context: RequestContext): FetchResponse = fetching.fetch(request, context)
}

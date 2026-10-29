package slussen.budgetapp.ynab

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.budgetapp.BudgetAppProfile
import slussen.pluginapi.budgetapp.BudgetAppReader
import slussen.pluginapi.budgetapp.BudgetAppWriter
import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.diagnostic.SlussenLog

/**
 * The YNAB Budget App (ADR-001 §1, §4.2): everything that depends on YNAB's behaviour (C-009), reached through YNAB's
 * public interface (C-008). It builds its profile, its credentials (UC-014), the reading of Budgets (UC-013) and of the
 * changes of a Budget (UC-003); the reading of one transaction and the writer come with the slice that builds UC-006.
 */
class YnabBudgetApp internal constructor(
    private val log: SlussenLog,
    base: HttpUrl,
    http: OkHttpClient,
) : BudgetApp {
    constructor(log: SlussenLog) : this(log, PUBLIC_INTERFACE.toHttpUrl(), OkHttpClient())

    /** The numbers and capabilities of C-009 (YNAB Rules) and ADR-001 §4.2. */
    override val profile: BudgetAppProfile = BudgetAppProfile(
        name = "YNAB",
        // C-009: YNAB accepts an Import Identifier of at most 36 characters.
        importIdentifierMaxLength = 36,
        // C-009: YNAB accepts a payee of at most 200 characters.
        payeeMaxLength = 200,
        // C-009: YNAB accepts a memo of at most 500 characters.
        memoMaxLength = 500,
        // C-009: YNAB matches an Import to a Typed Entry within 10 days before or after.
        matchingDateWindowDays = 10,
        // C-009: YNAB refuses a second transaction with an Import Identifier it already holds in the Budget Account.
        refusesReusedImportIdentifier = true,
        // C-009: YNAB marks each transaction as Cleared or uncleared.
        supportsClearedMarker = true,
        // ADR-001 §4.2: YNAB links the two sides of a transfer; proven by the live tests (ADR-005 §7, E-10).
        supportsLinkedTransfers = true,
    )

    override val credentials: CredentialCapability = YnabCredentials(base, http)

    override val reader: BudgetAppReader = YnabReader(base, http)

    override val writer: BudgetAppWriter
        get() = TODO("Built by the slice that builds UC-006 (ADR-001 §4.2.4).")

    companion object {
        /** YNAB's public interface (C-008), version 1. */
        const val PUBLIC_INTERFACE: String = "https://api.ynab.com/v1/"
    }
}

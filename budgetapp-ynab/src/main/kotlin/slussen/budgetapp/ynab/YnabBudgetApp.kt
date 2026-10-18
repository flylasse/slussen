package slussen.budgetapp.ynab

import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.budgetapp.BudgetAppProfile
import slussen.pluginapi.budgetapp.BudgetAppReader
import slussen.pluginapi.budgetapp.BudgetAppWriter
import slussen.pluginapi.credential.CredentialCapability
import slussen.pluginapi.diagnostic.SlussenLog

/**
 * The YNAB Budget App (ADR-001 §1, §4.2): everything that depends on YNAB's behaviour (C-009). Module skeleton: the
 * class the composition root registers, without behaviour. Its profile with the numbers of C-009, its credential form,
 * its reader and its writer come with the slices that build UC-014, UC-013, UC-003 and UC-006.
 */
class YnabBudgetApp(private val log: SlussenLog) : BudgetApp {
    override val profile: BudgetAppProfile
        get() = TODO("Declared by the slice that builds UC-003 (ADR-001 §4.2, C-009).")

    override val credentials: CredentialCapability
        get() = TODO("Built by the slice that builds UC-014 (ADR-001 §4.5).")

    override val reader: BudgetAppReader
        get() = TODO("Built by the slices that build UC-013 and UC-003 (ADR-001 §4.2.3).")

    override val writer: BudgetAppWriter
        get() = TODO("Built by the slice that builds UC-006 (ADR-001 §4.2.4).")
}

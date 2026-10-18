package slussen.app.di

import slussen.budgetapp.ynab.YnabBudgetApp
import slussen.fileformat.hsacsv.HsaCsvFilePreprocessor
import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.fileformat.FilePreprocessor
import slussen.pluginapi.provider.DataProvider
import slussen.provider.enablebanking.EnableBankingDataProvider
import slussen.provider.simplefin.SimpleFinDataProvider

/**
 * The only composition root, written by hand (ADR-001 §5): it lists the plug-ins of this build explicitly. Each
 * plug-in receives only the diagnostic [SlussenLog] at construction. The operation coordinator and the composition
 * checks of ADR-001 §4.2.4 and ADR-008 §6 are added by the slices that build them.
 */
class CompositionRoot(log: SlussenLog) {
    val plugins: Plugins = Plugins(
        dataProviders = listOf(SimpleFinDataProvider(log), EnableBankingDataProvider(log)),
        budgetApp = YnabBudgetApp(log),
        filePreprocessors = listOf(HsaCsvFilePreprocessor(log)),
    )
}

/** The plug-ins of this build: every Data Provider, the one Budget App and the one File Pre-processor (C-010). */
class Plugins(
    val dataProviders: List<DataProvider>,
    val budgetApp: BudgetApp,
    val filePreprocessors: List<FilePreprocessor>,
)

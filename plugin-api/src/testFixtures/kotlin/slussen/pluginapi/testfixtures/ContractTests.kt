package slussen.pluginapi.testfixtures

import slussen.pluginapi.budgetapp.BudgetApp
import slussen.pluginapi.diagnostic.SlussenLog
import slussen.pluginapi.fileformat.FilePreprocessor
import slussen.pluginapi.provider.DataProvider

/**
 * The abstract contract tests of ADR-001 §4.4. Each plug-in module's tests extend the matching one with invented
 * recorded answers served by a local test server. The checks of §4.4 are added together with the first slice that
 * implements a plug-in; the module skeleton holds the classes only.
 */
public abstract class DataProviderContractTest {
    /** The Data Provider under test, constructed with [log] as the composition root constructs it (ADR-001 §5). */
    protected abstract fun dataProvider(log: SlussenLog): DataProvider
}

/** See [DataProviderContractTest]; checks F-1 to F-8 of ADR-001 §4.2.1 once a slice adds them. */
public abstract class BudgetAppContractTest {
    protected abstract fun budgetApp(log: SlussenLog): BudgetApp
}

/** See [DataProviderContractTest]. */
public abstract class FilePreprocessorContractTest {
    protected abstract fun filePreprocessor(log: SlussenLog): FilePreprocessor
}

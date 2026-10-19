package slussen.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import slussen.app.SlussenApplication
import slussen.app.ui.bankaccounts.BankAccountsScreen
import slussen.app.ui.bankaccounts.BankAccountsViewModel
import slussen.app.ui.budget.BudgetScreen
import slussen.app.ui.budget.BudgetViewModel
import slussen.app.ui.components.Muted
import slussen.app.ui.connections.ConnectionsViewModel
import slussen.app.ui.connections.SettingsScreen
import slussen.app.ui.fetches.RecentFetchesScreen
import slussen.app.ui.fetches.RecentFetchesViewModel
import slussen.app.ui.overview.OverviewScreen
import slussen.app.ui.overview.OverviewViewModel
import slussen.app.ui.service.ServicePageScreen
import slussen.app.ui.service.ServicePageViewModel
import slussen.app.ui.setup.SetupScreen
import slussen.app.ui.setup.SetupViewModel
import slussen.app.ui.theme.SlussenColors
import slussen.app.ui.theme.SlussenTheme
import slussen.core.port.ServiceKey

/** The launcher Activity: the screens of this build on the core's services, once the composition root has started. */
class SlussenActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val application = application as SlussenApplication
        setContent {
            SlussenTheme {
                val services by application.services.collectAsState()
                Box(Modifier.fillMaxSize().background(SlussenColors.Background).safeDrawingPadding()) {
                    val started = services
                    if (started == null) Muted("Opening Slussen…") else SlussenScreens(started)
                }
            }
        }
    }
}

/** The screen the Operator is on. */
sealed interface Screen {
    data object Overview : Screen

    data class Budget(val budgetId: Long, val name: String) : Screen

    data object RecentFetches : Screen

    data object Settings : Screen

    data class Service(val service: ServiceKey, val fromSetup: Boolean) : Screen

    data object Setup : Screen

    data object BankAccounts : Screen
}

/** The screens with their bottom navigation: Overview, one tab per Budget, Settings (`docs/mockups/overview.html`). */
@Composable
fun SlussenScreens(services: UiServices) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Overview) }
    val current = stack.last()
    fun go(screen: Screen) = stack.add(screen)
    fun tab(screen: Screen) {
        stack.clear()
        stack.add(screen)
    }
    fun back() {
        if (stack.size > 1) stack.removeAt(stack.lastIndex)
    }
    BackHandler(enabled = stack.size > 1) { back() }

    val overview: OverviewViewModel = viewModel(key = "overview") { OverviewViewModel(services) }
    val overviewUi by overview.state.collectAsState()
    Scaffold(
        containerColor = SlussenColors.Background,
        bottomBar = {
            NavigationBar(containerColor = SlussenColors.Surface) {
                NavigationBarItem(selected = current == Screen.Overview, onClick = { tab(Screen.Overview) }, icon = { Text("◎") }, label = { Text("Overview") })
                overviewUi?.budgets.orEmpty().forEach { budget ->
                    NavigationBarItem(
                        selected = (current as? Screen.Budget)?.budgetId == budget.budgetId,
                        onClick = { tab(Screen.Budget(budget.budgetId, budget.name)) },
                        icon = { Text(budget.currency) },
                        label = { Text(budget.name) },
                    )
                }
                NavigationBarItem(selected = current == Screen.Settings, onClick = { tab(Screen.Settings) }, icon = { Text("⚙") }, label = { Text("Settings") })
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).padding(top = 8.dp)) {
            when (current) {
                Screen.Overview -> {
                    LaunchedEffect(current) { overview.load() }
                    overviewUi?.let { ui ->
                        OverviewScreen(
                            ui = ui,
                            onBudget = { id -> go(Screen.Budget(id, ui.budgets.single { it.budgetId == id }.name)) },
                            onSetUp = { go(Screen.Setup) },
                            onConfirmRecoveryCheck = { overview.confirmRecoveryCheck() },
                            onRecentFetches = { go(Screen.RecentFetches) },
                        )
                    }
                }
                is Screen.Budget -> {
                    val vm: BudgetViewModel = viewModel(key = "budget-${current.budgetId}") { BudgetViewModel(services, current.budgetId) }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { ui ->
                        BudgetScreen(ui) { action -> action.service?.let { go(Screen.Service(it, fromSetup = false)) } }
                    }
                }
                Screen.RecentFetches -> {
                    val vm: RecentFetchesViewModel = viewModel(key = "fetches") { RecentFetchesViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { RecentFetchesScreen(it, ::back) }
                }
                Screen.Settings -> {
                    val vm: ConnectionsViewModel = viewModel(key = "connections") { ConnectionsViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { ui ->
                        SettingsScreen(
                            ui = ui,
                            onService = { go(Screen.Service(it, fromSetup = false)) },
                            onBankAccounts = { go(Screen.BankAccounts) },
                            onSetUp = { go(Screen.Setup) },
                        )
                    }
                }
                is Screen.Service -> {
                    val vm: ServicePageViewModel = viewModel(key = "service-${current.service.dataProviderId}-${current.fromSetup}") {
                        ServicePageViewModel(services, current.service, current.fromSetup)
                    }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { ui ->
                        // Step 6 of UC-014: accepted credentials return to Connect Budgets and Bank Accounts when that started it.
                        LaunchedEffect(ui.returnToSetup) { if (ui.returnToSetup) back() }
                        ServicePageScreen(
                            ui = ui,
                            onBack = ::back,
                            onStartEntering = vm::startEntering,
                            onCancelEntering = vm::cancelEntering,
                            onSave = vm::save,
                            onRequestRemoval = vm::requestRemoval,
                            onCancelRemoval = vm::cancelRemoval,
                            onConfirmRemoval = vm::confirmRemoval,
                            onChooseBudgets = { go(Screen.Setup) },
                            onEditRequestLimit = vm::startEditingRequestLimit,
                            onSaveRequestLimit = vm::saveRequestLimit,
                            onResetRequestLimit = vm::resetRequestLimit,
                            onCancelRequestLimit = vm::cancelRequestLimit,
                        )
                    }
                }
                Screen.Setup -> {
                    val vm: SetupViewModel = viewModel(key = "setup") { SetupViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { ui ->
                        SetupScreen(
                            ui = ui,
                            onBack = ::back,
                            onStep = vm::goTo,
                            onCredentials = { go(Screen.Service(it, fromSetup = true)) },
                            onToggleBudget = vm::toggleBudget,
                            onSaveBudgets = vm::saveBudgets,
                            onSelectOffered = vm::selectOffered,
                            onPair = vm::pairWith,
                            onSaveNaming = vm::saveNaming,
                            onCancelNaming = vm::cancelNaming,
                            onAddFromFiles = { entry ->
                                vm.addFromTransactionFiles(
                                    entry.fileFormatId, entry.type, entry.bank, entry.balanceOfferedBankAccountId, entry.budgetAccountId, entry.name,
                                    entry.startDate,
                                )
                            },
                        )
                    }
                }
                Screen.BankAccounts -> {
                    val vm: BankAccountsViewModel = viewModel(key = "bank-accounts") { BankAccountsViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let {
                        BankAccountsScreen(
                            ui = it,
                            onBack = ::back,
                            onAdd = { go(Screen.Setup) },
                            onOpenTypeAndBank = vm::openTypeAndBank,
                            onSaveTypeAndBank = vm::saveTypeAndBank,
                            onConfirmClearing = vm::confirmClearing,
                            onCancelClearing = vm::cancelClearing,
                            onCancelTypeAndBank = vm::cancelTypeAndBank,
                        )
                    }
                }
            }
        }
    }
}

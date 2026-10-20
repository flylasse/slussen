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
import slussen.app.ui.budget.BudgetScreen
import slussen.app.ui.budget.BudgetViewModel
import slussen.app.ui.components.Muted
import slussen.app.ui.fetches.RecentFetchesScreen
import slussen.app.ui.fetches.RecentFetchesViewModel
import slussen.app.ui.fileformat.FileFormatScreen
import slussen.app.ui.fileformat.FileFormatViewModel
import slussen.app.ui.mappings.AccountMappingsScreen
import slussen.app.ui.mappings.AccountMappingsViewModel
import slussen.app.ui.mappings.cardActionsOf
import slussen.app.ui.overview.OverviewScreen
import slussen.app.ui.overview.OverviewViewModel
import slussen.app.ui.service.ConnectActions
import slussen.app.ui.service.ServicePageScreen
import slussen.app.ui.service.ServicePageViewModel
import slussen.app.ui.settings.EntryPage
import slussen.app.ui.settings.SettingsScreen
import slussen.app.ui.settings.SettingsViewModel
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

    data class Service(val service: ServiceKey) : Screen

    data class FileFormat(val fileFormatId: Long) : Screen

    data object AccountMappings : Screen
}

/**
 * The screens with their bottom navigation: Overview, one tab per Budget, Settings (`docs/mockups/overview.html`). A fresh
 * install opens on the Overview with the card that leads to Settings (UC-008 A10); every setting up starts at the top of
 * Settings, whose entries open the service pages, the File Format pages and the Account Mappings (UC-013 BR-008, S-40).
 */
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
                            onOpenSettings = { tab(Screen.Settings) },
                            onConfirmRecoveryCheck = { overview.confirmRecoveryCheck() },
                            onRecentFetches = { go(Screen.RecentFetches) },
                            onFetchAll = overview::fetchAll,
                            onConfirmFetch = overview::confirmFetch,
                            onCancelFetch = overview::cancelFetch,
                        )
                    }
                }
                is Screen.Budget -> {
                    val vm: BudgetViewModel = viewModel(key = "budget-${current.budgetId}") { BudgetViewModel(services, current.budgetId) }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { ui ->
                        BudgetScreen(ui) { action -> action.service?.let { go(Screen.Service(it)) } }
                    }
                }
                Screen.RecentFetches -> {
                    val vm: RecentFetchesViewModel = viewModel(key = "fetches") { RecentFetchesViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { RecentFetchesScreen(it, ::back) }
                }
                Screen.Settings -> {
                    val vm: SettingsViewModel = viewModel(key = "settings") { SettingsViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { ui ->
                        SettingsScreen(
                            ui = ui,
                            onEntry = { page ->
                                when (page) {
                                    is EntryPage.Service -> go(Screen.Service(page.service))
                                    is EntryPage.FileFormat -> go(Screen.FileFormat(page.fileFormatId))
                                }
                            },
                            onAccountMappings = { go(Screen.AccountMappings) },
                            onRecentFetches = { go(Screen.RecentFetches) },
                        )
                    }
                }
                is Screen.Service -> {
                    val vm: ServicePageViewModel = viewModel(key = "service-${current.service.dataProviderId}") {
                        ServicePageViewModel(services, current.service)
                    }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { ui ->
                        ServicePageScreen(
                            ui = ui,
                            onBack = ::back,
                            onStartEntering = vm::startEntering,
                            onCancelEntering = vm::cancelEntering,
                            onSave = vm::save,
                            onRequestRemoval = vm::requestRemoval,
                            onCancelRemoval = vm::cancelRemoval,
                            onConfirmRemoval = vm::confirmRemoval,
                            connect = ConnectActions(
                                onChooseBudgets = vm::chooseBudgets,
                                onToggleBudget = vm::toggleBudget,
                                onServeBudgets = vm::serveBudgets,
                                onCancelChoosing = vm::cancelChoosing,
                                onReceives = vm::setReceives,
                                onSwitchImport = vm::switchImport,
                                onUndo = vm::undo,
                                onMap = { go(Screen.AccountMappings) },
                                onDismissSnack = vm::dismissSnack,
                            ),
                            onEditRequestLimit = vm::startEditingRequestLimit,
                            onSaveRequestLimit = vm::saveRequestLimit,
                            onResetRequestLimit = vm::resetRequestLimit,
                            onCancelRequestLimit = vm::cancelRequestLimit,
                            onFetch = vm::fetch,
                            onConfirmFetch = vm::confirmFetch,
                            onCancelFetch = vm::cancelFetch,
                        )
                    }
                }
                is Screen.FileFormat -> {
                    val vm: FileFormatViewModel = viewModel(key = "file-format-${current.fileFormatId}") {
                        FileFormatViewModel(services, current.fileFormatId)
                    }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { ui ->
                        FileFormatScreen(
                            ui = ui,
                            onBack = ::back,
                            onOpenForm = vm::openForm,
                            onCancelForm = vm::cancelForm,
                            onChoose = vm::choose,
                            onAdd = vm::add,
                        )
                    }
                }
                Screen.AccountMappings -> {
                    val vm: AccountMappingsViewModel = viewModel(key = "account-mappings") { AccountMappingsViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    vm.state.collectAsState().value?.let { ui ->
                        AccountMappingsScreen(
                            ui = ui,
                            onBack = ::back,
                            onMapAll = vm::mapAll,
                            onMapOne = vm::mapOne,
                            onService = { go(Screen.Service(it)) },
                            card = cardActionsOf(vm),
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

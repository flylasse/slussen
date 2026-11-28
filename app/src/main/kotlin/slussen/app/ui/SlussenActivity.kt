package slussen.app.ui

import android.content.ActivityNotFoundException
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import slussen.app.platform.document.ChosenTransactionFile
import slussen.app.platform.document.PickedDocument
import slussen.app.ui.fileimport.FileImportActions
import slussen.app.ui.fileimport.FileImportControl
import slussen.app.ui.fileimport.FileImportTexts
import slussen.app.ui.fileimport.ImportPlace
import slussen.app.ui.fileimport.TransactionFileImportViewModel
import slussen.app.ui.fileimport.control
import slussen.app.ui.journal.JournalExportScreen
import slussen.app.ui.journal.JournalExportViewModel
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import slussen.app.SlussenApplication
import slussen.app.ui.bankaccount.BankAccountScreen
import slussen.app.ui.bankaccount.BankAccountViewModel
import slussen.app.ui.bankaccount.reviewActionsOf
import slussen.app.ui.budget.RemedyAction
import slussen.app.ui.mappings.mappingChangeActionsOf
import slussen.app.ui.transfers.transferDecisionActionsOf
import slussen.core.status.TransactionKey
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
import slussen.app.ui.components.NotShown
import slussen.app.ui.service.ConnectActions
import slussen.app.ui.service.PageSettingActions
import slussen.app.ui.service.ServicePageScreen
import slussen.app.ui.service.ServicePageViewModel
import slussen.app.ui.settings.EntryPage
import slussen.app.ui.settings.SettingsScreen
import slussen.app.ui.settings.SettingsViewModel
import slussen.app.ui.settings.TransferDescriptionsScreen
import slussen.app.ui.settings.TransferDescriptionsViewModel
import slussen.app.ui.settings.descriptionActionsOf
import slussen.app.ui.theme.SlussenColors
import slussen.app.ui.transfers.TransfersScreen
import slussen.app.ui.transfers.TransfersViewModel
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

    /**
     * UC-008 step 5: one Bank Account of the Budget [budgetId], reached from its card on the Budget tab, or from a Transfer
     * whose Transfer Leg [openTransaction] waits for its Review Decision (UC-005 A9), with that line open.
     */
    data class BankAccount(val bankAccountId: Long, val budgetId: Long, val openTransaction: Long? = null) : Screen

    /**
     * UC-005 step 1: the Transfers of the Budget [budgetId], from its tab, or from a Transfer Leg on the Bank Account screen
     * with the Transfer of the Bank Transaction [selectLeg] open.
     */
    data class Transfers(val budgetId: Long, val selectLeg: Long?) : Screen

    data object RecentFetches : Screen

    data object Settings : Screen

    data class Service(val service: ServiceKey) : Screen

    data class FileFormat(val fileFormatId: Long) : Screen

    data object AccountMappings : Screen

    /** UC-010 A7, A8: the Card Payment Descriptions and Bank Transfer Descriptions, from Settings. */
    data object TransferDescriptions : Screen

    /** UC-011: Export the Decision Journal, from Settings. */
    data object JournalExport : Screen
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
    // Review B6 F7: the screen that back returned to, so that it shows again what was open on it; null after go and tab.
    var returnedTo by remember { mutableStateOf<Screen?>(null) }
    fun go(screen: Screen) {
        returnedTo = null
        stack.add(screen)
    }
    fun tab(screen: Screen) {
        returnedTo = null
        stack.clear()
        stack.add(screen)
    }
    fun back() {
        if (stack.size > 1) {
            stack.removeAt(stack.lastIndex)
            returnedTo = stack.last()
        }
    }
    BackHandler(enabled = stack.size > 1) { back() }
    // A6 of UC-008: a remedy starts its use case, on a service's page or in Account Mappings (UC-009).
    fun remedy(action: RemedyAction) {
        if (action.accountMappings) go(Screen.AccountMappings) else action.service?.let { go(Screen.Service(it)) }
    }

    val overview: OverviewViewModel = viewModel(key = "overview") { OverviewViewModel(services) }
    val overviewUi by overview.state.collectAsState()

    // Import a Transaction File (UC-002): one import for the whole app, so that no second one starts and no answer is lost when a screen is left.
    val fileImport: TransactionFileImportViewModel = viewModel(key = "transaction-file-import") { TransactionFileImportViewModel(services) }
    val fileImportUi by fileImport.state.collectAsState()
    val resolver = LocalContext.current.contentResolver
    // Steps 2-3 of UC-002: Android's document dialog (`ACTION_OPEN_DOCUMENT`, no permission needed); no file chosen is A1.
    val documentDialog = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) fileImport.noFileChosen() else fileImport.import(ChosenTransactionFile(resolver, uri))
    }
    fun openDocumentDialog() {
        // NFR-023: a device with no app to choose a file is answered at the control, never a crash.
        try {
            documentDialog.launch(arrayOf("*/*"))
        } catch (noApp: ActivityNotFoundException) {
            fileImport.noFileCanBeChosen()
        }
    }
    fun fileImportActions(onImport: () -> Unit) = FileImportActions(
        onImport = onImport,
        onChoose = { if (fileImport.choose(it)) openDocumentDialog() },
        onCancelChoice = fileImport::cancelChoice,
        onWithdraw = fileImport::withdraw,
        onAccountMappings = { go(Screen.AccountMappings) },
    )
    Scaffold(
        containerColor = SlussenColors.Background,
        bottomBar = {
            NavigationBar(containerColor = SlussenColors.Surface) {
                NavigationBarItem(selected = current == Screen.Overview, onClick = { tab(Screen.Overview) }, icon = { Text("◎") }, label = { Text("Overview") })
                overviewUi?.budgets.orEmpty().forEach { budget ->
                    NavigationBarItem(
                        selected = (current as? Screen.Budget)?.budgetId == budget.budgetId ||
                            (current as? Screen.BankAccount)?.budgetId == budget.budgetId ||
                            (current as? Screen.Transfers)?.budgetId == budget.budgetId,
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
                    val overviewNotOpened = overview.notOpened.collectAsState().value
                    // NFR-023: an Overview whose stored status cannot be read says so, with Try again.
                    if (overviewUi == null && overviewNotOpened != null) NotShown(overviewNotOpened, onTryAgain = overview::load)
                    overviewUi?.let { ui ->
                        OverviewScreen(
                            ui = ui,
                            onBudget = { id -> go(Screen.Budget(id, ui.budgets.single { it.budgetId == id }.name)) },
                            onOpenSettings = { tab(Screen.Settings) },
                            onStartFirstUploadMatching = { overview.startFirstUploadMatching() },
                            onRecentFetches = { go(Screen.RecentFetches) },
                            onFetchAll = overview::fetchAll,
                            onConfirmFetch = overview::confirmFetch,
                            onCancelFetch = overview::cancelFetch,
                            onReadAgain = overview::readAgain,
                            onRemedy = ::remedy,
                        )
                    }
                }
                is Screen.Budget -> {
                    val vm: BudgetViewModel = viewModel(key = "budget-${current.budgetId}") { BudgetViewModel(services, current.budgetId) }
                    LaunchedEffect(current) { vm.load() }
                    // Review D4 items 1-2: leaving the tab ends a choice of the Bank Account and an answer of an import shown on it.
                    DisposableEffect(current) {
                        onDispose { fileImport.left { it is ImportPlace.Source && it.budgetId == current.budgetId } }
                    }
                    val budgetUi = vm.state.collectAsState().value
                    val budgetFailure = vm.failure.collectAsState().value
                    // NFR-023: a Budget whose stored status cannot be read says so on its tab.
                    if (budgetUi == null && budgetFailure != null) Muted(budgetFailure)
                    budgetUi?.let { ui ->
                        BudgetScreen(
                            ui,
                            onRemedy = ::remedy,
                            onStartFirstUploadMatching = { overview.startFirstUploadMatching() },
                            onReadAgain = vm::readAgain,
                            onBankAccount = { id -> go(Screen.BankAccount(id, current.budgetId)) },
                            onTransfers = { go(Screen.Transfers(current.budgetId, selectLeg = null)) },
                            fileImport = { entry ->
                                FileImportControl(
                                    fileImportUi.control(entry.place),
                                    fileImportActions { fileImport.start(entry.place, entry.bankAccounts)?.let { openDocumentDialog() } },
                                )
                            },
                        )
                    }
                }
                is Screen.BankAccount -> {
                    val vm: BankAccountViewModel = viewModel(key = "bank-account-${current.bankAccountId}") {
                        BankAccountViewModel(services, current.bankAccountId)
                    }
                    LaunchedEffect(current) { vm.load(current.openTransaction?.let { TransactionKey(unreadable = false, id = it) }, returning = returnedTo == current) }
                    // NFR-023 (review B6 F1): a decision on its way when the screen is left is shown with its answer on return.
                    DisposableEffect(current) { onDispose { vm.left() } }
                    BankAccountScreen(
                        state = vm.state.collectAsState().value,
                        onBack = ::back,
                        onOpen = vm::open,
                        onStatuses = vm::openStatuses,
                        onClose = vm::close,
                        onRemedy = ::remedy,
                        onTransfer = { leg -> go(Screen.Transfers(current.budgetId, leg)) },
                        review = reviewActionsOf(vm),
                    )
                }
                is Screen.Transfers -> {
                    val vm: TransfersViewModel = viewModel(key = "transfers-${current.budgetId}") { TransfersViewModel(services, current.budgetId) }
                    LaunchedEffect(current) { vm.load(current.selectLeg, returning = returnedTo == current) }
                    DisposableEffect(current) { onDispose { vm.left() } }
                    TransfersScreen(
                        state = vm.state.collectAsState().value,
                        onBack = ::back,
                        onOpen = vm::open,
                        onClose = vm::close,
                        decisions = transferDecisionActionsOf(
                            vm,
                            // A9 step 2 of UC-005: to the Review Decision of the Transfer Leg that waits for it.
                            onReviewLeg = { leg -> go(Screen.BankAccount(leg.bankAccountId, current.budgetId, leg.storedBankTransaction)) },
                            // A8 step 1 of UC-005: a Funded By that no longer applies is changed or cleared in Account Mappings.
                            onAccountMappings = { go(Screen.AccountMappings) },
                        ),
                    )
                }
                Screen.RecentFetches -> {
                    val vm: RecentFetchesViewModel = viewModel(key = "fetches") { RecentFetchesViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    RecentFetchesScreen(
                        state = vm.state.collectAsState().value,
                        onBack = ::back,
                        onOpen = vm::open,
                        onClose = vm::close,
                        onFetchAll = { tab(Screen.Overview) },
                        onRemedy = ::remedy,
                    )
                }
                Screen.Settings -> {
                    val vm: SettingsViewModel = viewModel(key = "settings") { SettingsViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    val notOpened = vm.notOpened.collectAsState().value
                    val settingsUi = vm.state.collectAsState().value
                    // NFR-023: Settings whose stored data cannot be read says so, with Try again.
                    if (settingsUi == null && notOpened != null) NotShown(notOpened, onTryAgain = vm::load)
                    settingsUi?.let { ui ->
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
                            actions = vm.actions(),
                            onTransferDescriptions = { go(Screen.TransferDescriptions) },
                            onJournalExport = { go(Screen.JournalExport) },
                        )
                    }
                }
                is Screen.Service -> {
                    val vm: ServicePageViewModel = viewModel(key = "service-${current.service.dataProviderId}") {
                        ServicePageViewModel(services, current.service)
                    }
                    LaunchedEffect(current) { vm.load() }
                    val pageUi = vm.state.collectAsState().value
                    val pageNotOpened = vm.notOpened.collectAsState().value
                    // NFR-023: a page whose stored data cannot be read says so, with Try again and Back.
                    if (pageUi == null && pageNotOpened != null) NotShown(pageNotOpened, onTryAgain = vm::load, onBack = ::back)
                    pageUi?.let { ui ->
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
                            settings = PageSettingActions(
                                onEditFetchCooldown = vm::startEditingFetchCooldown,
                                onFetchCooldownValue = vm::editFetchCooldown,
                                onSaveFetchCooldown = vm::saveFetchCooldown,
                                onResetFetchCooldown = vm::resetFetchCooldown,
                                onCancelFetchCooldown = vm::cancelFetchCooldown,
                                onRequestLimitValue = vm::editRequestLimit,
                            ),
                            onFetch = vm::fetch,
                            onConfirmFetch = vm::confirmFetch,
                            onCancelFetch = vm::cancelFetch,
                            onUploadAsCleared = vm::switchUploadAsCleared,
                        )
                    }
                }
                is Screen.FileFormat -> {
                    val vm: FileFormatViewModel = viewModel(key = "file-format-${current.fileFormatId}") {
                        FileFormatViewModel(services, current.fileFormatId)
                    }
                    LaunchedEffect(current) { vm.load() }
                    val place = ImportPlace.FileFormatPage(current.fileFormatId)
                    // A7 of UC-002: leaving the page ends its answer, and no file choice it asked for opens at a later visit.
                    DisposableEffect(current) {
                        onDispose {
                            vm.left()
                            fileImport.left { it == place }
                        }
                    }
                    vm.state.collectAsState().value?.let { ui ->
                        // A7 step 2 of UC-002: once the Bank Account is added, the import is started first, then the request consumed.
                        LaunchedEffect(ui.importFor) {
                            if (ui.importFor != null) {
                                vm.goOnWithImport { added -> fileImport.start(place, listOf(added))?.also { openDocumentDialog() } != null }
                            }
                        }
                        FileFormatScreen(
                            ui = ui,
                            onBack = ::back,
                            onOpenForm = vm::openForm,
                            onCancelForm = vm::cancelForm,
                            onChoose = vm::choose,
                            onAdd = vm::add,
                            fileImport = { showButton ->
                                FileImportControl(
                                    fileImportUi.control(place, FileImportTexts.IMPORT_FIRST),
                                    fileImportActions(onImport = vm::openFormToImport),
                                    showButton = showButton,
                                )
                            },
                        )
                    }
                }
                Screen.TransferDescriptions -> {
                    val vm: TransferDescriptionsViewModel = viewModel(key = "transfer-descriptions") { TransferDescriptionsViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    val listsUi = vm.state.collectAsState().value
                    val listsNotOpened = vm.notOpened.collectAsState().value
                    // NFR-023: lists whose stored data cannot be read say so, with Try again and Back.
                    if (listsUi == null && listsNotOpened != null) NotShown(listsNotOpened, onTryAgain = vm::load, onBack = ::back)
                    listsUi?.let { ui -> TransferDescriptionsScreen(ui, onBack = ::back, actions = descriptionActionsOf(vm)) }
                }
                Screen.JournalExport -> JournalExportRoute(services, onBack = ::back)
                Screen.AccountMappings -> {
                    val vm: AccountMappingsViewModel = viewModel(key = "account-mappings") { AccountMappingsViewModel(services) }
                    LaunchedEffect(current) { vm.load() }
                    DisposableEffect(current) { onDispose { vm.left() } }
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
                            changes = mappingChangeActionsOf(vm),
                        )
                    }
                }
            }
        }
    }
}

/**
 * UC-011 steps 1-2: the Journal Export screen with Android's save dialog (`ACTION_CREATE_DOCUMENT`, no permission needed),
 * proposing the name of BR-007; no place chosen is A1, a place chosen starts the export to that document.
 */
@Composable
private fun JournalExportRoute(services: UiServices, onBack: () -> Unit) {
    val vm: JournalExportViewModel = viewModel(key = "journal-export") { JournalExportViewModel(services) }
    LaunchedEffect(Unit) { vm.load() }
    val resolver = LocalContext.current.contentResolver
    var proposed by rememberSaveable { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) {
            vm.noPlaceChosen()
        } else {
            val document = PickedDocument(resolver, uri)
            // The document's name is asked of its provider off the main thread.
            vm.export(document, proposed) { document.name() }
        }
    }
    // ADR-007 §1: leaving the screen while the export still waits for the lane withdraws it; once it reads, it goes on.
    DisposableEffect(Unit) { onDispose { vm.withdraw() } }
    JournalExportScreen(
        ui = vm.state.collectAsState().value,
        onBack = onBack,
        onCodes = vm::setCodes,
        onChoose = {
            vm.choosePlace()?.let { name ->
                proposed = name
                // NFR-023: a device with no app to choose a place is answered at the control, never a crash.
                try {
                    picker.launch(name)
                } catch (noApp: ActivityNotFoundException) {
                    vm.noPlaceCanBeChosen()
                }
            }
        },
        onWithdraw = vm::withdraw,
    )
}

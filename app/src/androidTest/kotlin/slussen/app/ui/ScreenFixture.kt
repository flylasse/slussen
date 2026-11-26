package slussen.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import slussen.app.ui.service.ConnectActions
import slussen.app.ui.service.PageSettingActions
import slussen.app.ui.service.ServicePageScreen
import slussen.app.ui.service.ServicePageViewModel
import slussen.testing.TestSlice
import java.time.ZoneId

/**
 * The slice on the fakes of `:testing` for the screen tests on a device or emulator, with invented data only, and an
 * invented version of the app unless a test gives the installed one.
 */
class ScreenFixture(appVersion: AppVersion = AppVersion.of(packageName = "slussen.app", versionName = "9.8.7")) {
    val slice = TestSlice()
    val services = UiServices(
        credentials = slice.credentials,
        settingsOverview = slice.settingsOverview,
        connect = slice.connect,
        providerSettings = slice.providerSettings,
        generalSettings = slice.generalSettings,
        mappings = slice.mappings,
        status = slice.status,
        fetch = slice.fetch,
        comparison = slice.comparison,
        formats = Formats(ZoneId.of("Europe/Stockholm")),
        clock = slice.clock,
        appVersion = appVersion,
        running = slice.coordinator.running,
    )
}

/** Settings of [viewModel] with every action wired as `SlussenScreens` wires it; the entries open nothing. */
@Composable
fun Settings(viewModel: slussen.app.ui.settings.SettingsViewModel) {
    viewModel.state.collectAsState().value?.let {
        slussen.app.ui.settings.SettingsScreen(
            it, onEntry = {}, onAccountMappings = {}, onRecentFetches = {},
            onEditFetchPeriod = viewModel::startEditingFetchPeriod, onFetchPeriodValue = viewModel::editFetchPeriod,
            onSaveFetchPeriod = viewModel::saveFetchPeriod, onCancelFetchPeriod = viewModel::cancelFetchPeriod,
        )
    }
}

/** The page of [viewModel] with every action wired as `SlussenScreens` wires it; Map does [onMap]. */
@Composable
fun ServicePage(viewModel: ServicePageViewModel, onMap: () -> Unit = {}) {
    viewModel.state.collectAsState().value?.let {
        ServicePageScreen(
            it, onBack = {}, onStartEntering = viewModel::startEntering, onCancelEntering = viewModel::cancelEntering,
            onSave = viewModel::save, onRequestRemoval = viewModel::requestRemoval, onCancelRemoval = viewModel::cancelRemoval,
            onConfirmRemoval = viewModel::confirmRemoval,
            connect = ConnectActions(
                onChooseBudgets = viewModel::chooseBudgets,
                onToggleBudget = viewModel::toggleBudget,
                onServeBudgets = viewModel::serveBudgets,
                onCancelChoosing = viewModel::cancelChoosing,
                onReceives = viewModel::setReceives,
                onSwitchImport = viewModel::switchImport,
                onUndo = viewModel::undo,
                onMap = onMap,
                onDismissSnack = viewModel::dismissSnack,
            ),
            onEditRequestLimit = viewModel::startEditingRequestLimit, onSaveRequestLimit = viewModel::saveRequestLimit,
            onResetRequestLimit = viewModel::resetRequestLimit, onCancelRequestLimit = viewModel::cancelRequestLimit,
            settings = PageSettingActions(
                onEditFetchCooldown = viewModel::startEditingFetchCooldown,
                onFetchCooldownValue = viewModel::editFetchCooldown,
                onSaveFetchCooldown = viewModel::saveFetchCooldown,
                onResetFetchCooldown = viewModel::resetFetchCooldown,
                onCancelFetchCooldown = viewModel::cancelFetchCooldown,
                onRequestLimitValue = viewModel::editRequestLimit,
            ),
            onFetch = viewModel::fetch, onConfirmFetch = viewModel::confirmFetch, onCancelFetch = viewModel::cancelFetch,
        )
    }
}

/** The Account Mappings of [viewModel] with every action wired as `SlussenScreens` wires them; a list line opens [onService]. */
@Composable
fun AccountMappings(viewModel: slussen.app.ui.mappings.AccountMappingsViewModel, onService: (slussen.core.port.ServiceKey) -> Unit = {}) {
    viewModel.state.collectAsState().value?.let {
        slussen.app.ui.mappings.AccountMappingsScreen(
            it, onBack = {}, onMapAll = viewModel::mapAll, onMapOne = viewModel::mapOne, onService = onService,
            card = slussen.app.ui.mappings.cardActionsOf(viewModel), onOpenTypeAndBank = viewModel::openTypeAndBank,
            onSaveTypeAndBank = viewModel::saveTypeAndBank, onConfirmClearing = viewModel::confirmClearing,
            onCancelClearing = viewModel::cancelClearing, onCancelTypeAndBank = viewModel::cancelTypeAndBank,
        )
    }
}

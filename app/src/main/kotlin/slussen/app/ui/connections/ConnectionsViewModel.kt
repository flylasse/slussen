package slussen.app.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices
import slussen.core.port.ServiceKey
import slussen.core.settings.ServiceLine

/** One service in Connections with its kind and one status line (UC-014 BR-007). */
data class ServiceEntry(val service: ServiceKey, val name: String, val kind: String, val status: String)

/** Connections: the services in use, and the Data Providers of this build not set up (UC-014 step 1, BR-007). */
data class ConnectionsUi(val inUse: List<ServiceEntry>, val notSetUp: List<ServiceEntry>)

/** The Connections section of Settings (UC-014, UC-010 BR-010), generated from the plug-ins' declarations. */
class ConnectionsViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<ConnectionsUi?>(null)
    val state: StateFlow<ConnectionsUi?> = mutableState

    fun load() {
        viewModelScope.launch {
            val list = services.connections.connections()
            fun entry(line: ServiceLine, notSetUp: Boolean) = ServiceEntry(
                service = line.service,
                name = line.name,
                kind = if (line.isBudgetApp) "Budget App" else "Data Provider",
                status = if (notSetUp) "Not set up" else Texts.serviceStatus(line.status, services.formats),
            )
            mutableState.value = ConnectionsUi(list.inUse.map { entry(it, false) }, list.notSetUp.map { entry(it, true) })
        }
    }
}

package slussen.app.ui.fetches

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import slussen.app.ui.Texts
import slussen.app.ui.UiServices

/** The recent Fetch outcomes (UC-008 A5, BR-008), in the device's local time. */
class RecentFetchesViewModel(private val services: UiServices) : ViewModel() {
    private val mutableState = MutableStateFlow<List<String>?>(null)
    val state: StateFlow<List<String>?> = mutableState

    fun load() {
        viewModelScope.launch {
            val formats = services.formats
            mutableState.value = services.status.recentFetches().map {
                "${it.sourceName} · ${formats.dateTime(it.startedAt)} · ${Texts.startedBy(it.startedBy)} · ${Texts.fetch(it.outcome)}"
            }
        }
    }
}

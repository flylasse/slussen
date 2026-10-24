package slussen.app.ui.fetches

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import slussen.app.ui.components.Muted
import slussen.app.ui.components.ScreenTitle

/** The recent Fetch outcomes (UC-008 A5; `docs/mockups/fetch-history.html`). */
@Composable
fun RecentFetchesScreen(lines: List<String>, onBack: () -> Unit) {
    LazyColumn(modifier = Modifier.padding(horizontal = 12.dp)) {
        item { ScreenTitle("Recent Fetches", "Source · time · started by · outcome", onBack) }
        if (lines.isEmpty()) item { Muted("No Fetch yet") }
        items(lines) { Muted(it) }
    }
}

package slussen.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import slussen.app.ui.budget.RemedyAction

/**
 * The line of the Budget App on the Overview and on every Budget tab (FR-040, UC-003; `budget.html`, the states of Read again):
 * when it was last read and which Budgets were not ([status]), the action that reads it again with its state ([button]),
 * the result of the last tap in the same place ([result]), and the remedy of missing or refused credentials ([remedy]).
 */
data class BudgetAppLineUi(
    val status: String,
    val button: FetchButtonUi,
    val result: FetchResultUi? = null,
    val remedy: RemedyAction? = null,
)

/** NFR-023: the line of the Budget App, whose action answers on the line itself. */
@Composable
fun BudgetAppLine(ui: BudgetAppLineUi, onReadAgain: () -> Unit, onRemedy: (RemedyAction) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Muted(ui.status)
        FetchButton(ui.button, onReadAgain)
        ui.result?.let { FetchResultLine(it) }
        ui.remedy?.let { remedy -> OutlinedButton(onClick = { onRemedy(remedy) }, enabled = remedy.available) { Text(remedy.title) } }
    }
}

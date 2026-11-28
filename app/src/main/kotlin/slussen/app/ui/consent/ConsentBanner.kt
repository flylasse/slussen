package slussen.app.ui.consent

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import slussen.app.ui.UiServices
import slussen.app.ui.components.Muted
import slussen.app.ui.components.RunningText
import slussen.app.ui.components.SlussenCard
import slussen.app.ui.theme.SlussenColors
import slussen.core.port.ServiceKey

/**
 * What a Consent Request under way, or the result of one that ended, shows on a screen other than its Data Provider's
 * page (review D4 items 1-2): the Operator returns from the browser to wherever Slussen was, and sees it there, with Open
 * (the page where it waits or was answered) and Cancel or OK.
 */
data class ConsentBannerUi(
    val title: String,
    val detail: String?,
    val service: ServiceKey?,
    /** The waiting Consent Request that Cancel abandons (A10), or null for a result. */
    val requestId: Long?,
    val cancelling: Boolean,
    /** An answer is being handed in: shown as running. */
    val running: Boolean,
)

object ConsentBannerTexts {
    /** The banner for a screen that is not the page of [current] (null: no Data Provider's page is shown); null when nothing is to say there. */
    fun of(state: ConsentAnswersState, services: UiServices, current: ServiceKey?): ConsentBannerUi? {
        val result = state.result
        if (result != null) {
            if (result.service != null && result.service == current) return null
            val name = services.consentProviders.firstOrNull { it.service == result.service }?.name ?: services.consentProviders.firstOrNull()?.name.orEmpty()
            val shown = ConsentTexts.end(result.end, result.bankReference, name, services.formats)
            return ConsentBannerUi(shown.title, shown.lines.firstOrNull(), result.service, null, cancelling = false, running = false)
        }
        if (state.completing > 0) {
            return ConsentBannerUi("Checking the result of your Bank's sign-in…", null, null, null, cancelling = false, running = true)
        }
        val waiting = state.waiting.firstOrNull { it.service != current } ?: return null
        return ConsentBannerUi(
            "Waiting for the result of ${waiting.bankReference} · until ${services.formats.time(waiting.expiresAt)}",
            "Complete the sign-in with your Bank in the browser, or open the page to paste the address it shows.",
            waiting.service,
            waiting.requestId,
            cancelling = waiting.requestId in state.cancelling,
            running = false,
        )
    }
}

/** The banner above every screen (NFR-023): a Consent Request under way or its result, with Open and Cancel or OK. */
@Composable
fun ConsentBanner(ui: ConsentBannerUi, onOpen: (ServiceKey) -> Unit, onCancel: (ServiceKey, Long) -> Unit, onDismiss: () -> Unit) {
    SlussenCard(modifier = Modifier.testTag("consent-banner"), border = SlussenColors.Teal) {
        if (ui.running) RunningText(ui.title, running = true) else Text(ui.title, color = SlussenColors.Text, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
        ui.detail?.let { Muted(it) }
        Row {
            ui.service?.let { service -> TextButton(onClick = { onOpen(service) }) { Text("Open") } }
            val requestId = ui.requestId
            when {
                ui.running -> Unit
                requestId != null && ui.service != null ->
                    TextButton(onClick = { onCancel(ui.service, requestId) }, enabled = !ui.cancelling) {
                        RunningText(if (ui.cancelling) "Cancelling…" else "Cancel", ui.cancelling)
                    }
                else -> TextButton(onClick = onDismiss) { Text("OK") }
            }
        }
    }
}

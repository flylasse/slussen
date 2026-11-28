package slussen.app.platform.browser

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * The device's browser for the Bank's Strong Customer Authentication (UC-007 step 4, A11), with a plain view intent: no
 * library and no device permission (NFR-022, ADR-001 §5). Whether a browser exists is asked through the manifest's
 * `<queries>` element, which is no permission either. The address is handed to the browser only, never logged (BR-006).
 */
class ConsentBrowser(private val context: Context) {
    // A11 of UC-007: the device may have no app that can open the way to the Bank's Strong Customer Authentication.
    /** Whether an app on the device opens an https address. */
    fun available(): Boolean = try {
        view(Uri.parse("https://")).resolveActivity(context.packageManager) != null
    } catch (failure: RuntimeException) {
        false
    }

    /** Opens [address]; false when no app opened it (A11), never a crash (NFR-023). */
    fun open(address: String): Boolean = try {
        context.startActivity(view(Uri.parse(address)))
        true
    } catch (noApp: ActivityNotFoundException) {
        false
    } catch (refused: SecurityException) {
        false
    }

    private fun view(uri: Uri): Intent = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
}

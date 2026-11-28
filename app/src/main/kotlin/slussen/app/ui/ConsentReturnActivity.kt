package slussen.app.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import slussen.app.SlussenApplication
import slussen.app.ui.consent.ArrivedIntent
import slussen.app.ui.consent.ConsentReturn

/**
 * The receiver of Slussen's own return address (UC-007 step 6, BR-011; E4 review item 2): an activity without a screen
 * that takes the answer as [ConsentReturn.answerIn] decides, hands it with its time of arrival to the process-wide
 * holder of answers, brings Slussen's task forward as it is (the launcher's way: nothing above it is closed and no
 * second main screen is made) and ends. It keeps nothing in saved state, has no history and is not kept in recents, so
 * the main screen's own launch mode and saved state play no part in the return.
 */
class ConsentReturnActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val arrived = intent?.let {
            ArrivedIntent(
                action = it.action,
                address = it.dataString,
                restored = savedInstanceState != null,
                fromRecents = it.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0,
            )
        }
        // UC-007 BR-011: Slussen's return address hands the answer back to Slussen, at once and once; the address is not kept or logged.
        arrived?.let { ConsentReturn.answerIn(it, packageName) }?.let { (application as SlussenApplication).consentAnswers.returned(it) }
        intent?.data = null
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            try {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (noApp: ActivityNotFoundException) {
                // NFR-023: never a crash; the answer is handed in already and Slussen shows it when opened.
            }
        }
        finish()
    }
}

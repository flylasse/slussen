package slussen.app.platform.notification

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import slussen.app.R
import slussen.app.ui.SlussenActivity

/**
 * The New Transaction Notification on Android (UC-001 BR-014): one silent, low-priority notification on its own channel,
 * with its number also as the badge on the app icon, under one tag and id, so that a later one replaces it. Tapping it
 * opens Slussen on the Overview.
 */
class AndroidNotificationDevice(private val context: Context) : NotificationDevice {
    private val manager = NotificationManagerCompat.from(context)

    override fun allowed(): Boolean = manager.areNotificationsEnabled() && permitted()

    /** Android 13 and later ask the Operator before an app shows notifications (POST_NOTIFICATIONS). */
    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun shownNumber(): Int? =
        context.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.firstOrNull { it.id == ID && it.tag == TAG }
            ?.notification?.number

    override fun show(number: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        // UC-001 BR-014: One silent, low-priority New Transaction Notification, also shown as a badge on the app icon; a later update replaces it instead of adding another one.
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName(NotificationTexts.CHANNEL_NAME)
                .setShowBadge(true)
                .setVibrationEnabled(false)
                .setSound(null, null)
                .build(),
        )
        val text = NotificationTexts.text(number)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(text)
            .setNumber(number)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(overview())
            .build()
        manager.notify(TAG, ID, notification)
    }

    override fun remove() = manager.cancel(TAG, ID)

    // UC-001 BR-014: Tapping the notification opens the Overview.
    /** Slussen started afresh, on the Overview, its first screen. */
    private fun overview(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, SlussenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val CHANNEL_ID = "new-transactions"
        const val TAG = "new-transactions"
        const val ID = 1
    }
}

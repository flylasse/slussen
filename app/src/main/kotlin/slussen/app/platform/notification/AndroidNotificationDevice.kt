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
 * opens Slussen on the Overview. Everything it shows comes from [NotificationContent]; this class only renders it.
 */
class AndroidNotificationDevice(private val context: Context) : NotificationDevice {
    private val manager = NotificationManagerCompat.from(context)

    override fun allowed(): Boolean = NotificationRules.allowed(
        appEnabled = manager.areNotificationsEnabled(),
        permissionHeld = permitted(),
        // A channel not created yet is not switched off: it is created with the first notification.
        channelSwitchedOff = manager.getNotificationChannelCompat(CHANNEL_ID)?.importance == NotificationManagerCompat.IMPORTANCE_NONE,
    )

    /** Android 13 and later ask the Operator before an app shows notifications (POST_NOTIFICATIONS). */
    private fun permitted(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    override fun shownNumber(): Int? =
        context.getSystemService(NotificationManager::class.java)?.activeNotifications
            ?.firstOrNull { it.id == ID && it.tag == TAG }
            ?.notification?.number

    /** Renders [content] field for field; it decides nothing of its own. */
    override fun show(content: NotificationContent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val channel = NotificationChannelCompat.Builder(content.channelId, importanceOf(content.importance))
            .setName(content.channelName)
            .setShowBadge(content.badge)
            .setVibrationEnabled(content.vibrates)
        if (content.silent) channel.setSound(null, null)
        manager.createNotificationChannel(channel.build())
        val notification = NotificationCompat.Builder(context, content.channelId)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(content.text)
            .setNumber(content.number)
            .setPriority(priorityOf(content.importance))
            .setSilent(content.silent)
            .setOnlyAlertOnce(content.silent)
            .setContentIntent(opened(content.opens))
            .build()
        manager.notify(TAG, ID, notification)
    }

    override fun remove() = manager.cancel(TAG, ID)

    private fun importanceOf(importance: NotificationImportance): Int = when (importance) {
        NotificationImportance.Low -> NotificationManagerCompat.IMPORTANCE_LOW
    }

    private fun priorityOf(importance: NotificationImportance): Int = when (importance) {
        NotificationImportance.Low -> NotificationCompat.PRIORITY_LOW
    }

    /** What a tap opens: for the Overview, Slussen started afresh on its first screen. */
    private fun opened(tap: NotificationTap): PendingIntent = when (tap) {
        NotificationTap.Overview -> PendingIntent.getActivity(
            context,
            0,
            Intent(context, SlussenActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        /** The channel of [NotificationContent.of], read back to see whether the Operator switched it off. */
        val CHANNEL_ID = NotificationContent.of(0).channelId
        const val TAG = "new-transactions"
        const val ID = 1
    }
}

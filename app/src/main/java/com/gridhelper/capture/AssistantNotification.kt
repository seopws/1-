package com.gridhelper.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import com.gridhelper.R
import com.gridhelper.ui.MainActivity

/** Ongoing notification of the foreground service with [Pause/Resume] and [Stop] actions. */
object AssistantNotification {

    const val ID = 1001
    private const val CHANNEL_ID = "assistant"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun build(context: Context, paused: Boolean): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggle = PendingIntent.getService(
            context,
            1,
            Intent(context, AssistantService::class.java).setAction(AssistantService.ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context,
            2,
            Intent(context, AssistantService::class.java).setAction(AssistantService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val toggleAction = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_action_pause),
            context.getString(if (paused) R.string.action_resume else R.string.action_pause),
            toggle,
        ).build()
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(context, R.drawable.ic_action_stop),
            context.getString(R.string.action_stop),
            stop,
        ).build()
        return Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(if (paused) R.string.notification_title_paused else R.string.notification_title_running))
            .setContentText(context.getString(R.string.notification_text))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .addAction(toggleAction)
            .addAction(stopAction)
            .build()
    }
}

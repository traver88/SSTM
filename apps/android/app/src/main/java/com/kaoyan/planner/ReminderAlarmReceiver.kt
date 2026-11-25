package com.kaoyan.planner

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.kaoyan.planner.domain.reminder.ReminderScheduler

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return

        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val title = intent.getStringExtra(EXTRA_TITLE) ?: return
        val text = intent.getStringExtra(EXTRA_TEXT) ?: return
        val channel = when (intent.getStringExtra(EXTRA_TYPE)) {
            ReminderScheduler.TYPE_PHASE -> PlannerApplication.CHANNEL_PHASE
            ReminderScheduler.TYPE_MONTHLY -> PlannerApplication.CHANNEL_MONTHLY
            else -> PlannerApplication.CHANNEL_DAILY
        }

        val contentIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.setData(Uri.parse("yantu://notification/${Uri.encode(key)}"))
            ?.let {
                PendingIntent.getActivity(
                    context,
                    key.hashCode(),
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            }

        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
    }

    companion object {
        const val ACTION_REMIND = "com.kaoyan.planner.action.REMIND"
        const val EXTRA_KEY = "reminder_key"
        const val EXTRA_TYPE = "reminder_type"
        const val EXTRA_TITLE = "reminder_title"
        const val EXTRA_TEXT = "reminder_text"
    }
}

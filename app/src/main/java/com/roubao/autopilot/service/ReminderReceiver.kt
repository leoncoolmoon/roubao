package com.roubao.autopilot.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.roubao.autopilot.MainActivity
import com.roubao.autopilot.R

/**
 * 定时任务到点后发一条系统通知；点通知打开肉包并预填 prompt。
 * 不直接后台启动 Agent，避免 Android 14 的后台启动限制。
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prompt = intent.getStringExtra(EXTRA_PROMPT)
        Log.i(TAG, "onReceive: prompt=${prompt?.take(40)}")
        if (prompt.isNullOrBlank()) {
            Log.w(TAG, "onReceive without prompt")
            return
        }
        val channelId = "roubao_scheduled"
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    "定时任务",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "肉包定时任务提醒"
                }
            )
        }

        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            Log.w(TAG, "notification permission disabled, cannot post reminder")
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_SCHEDULED_PROMPT, prompt)
            putExtra(MainActivity.EXTRA_SCHEDULED_AUTO_RUN, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            prompt.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("肉包定时任务")
            .setContentText("点击打开并执行：" + prompt.take(60))
            .setStyle(NotificationCompat.BigTextStyle().bigText(prompt))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(prompt.hashCode(), notification)
            Log.i(TAG, "notification posted id=${prompt.hashCode()}")
        } catch (e: SecurityException) {
            Log.e(TAG, "notification permission denied", e)
        } catch (e: Exception) {
            Log.e(TAG, "notification failed", e)
        }
    }

    companion object {
        private const val TAG = "ReminderReceiver"
        const val EXTRA_PROMPT = "reminder_prompt"
    }
}

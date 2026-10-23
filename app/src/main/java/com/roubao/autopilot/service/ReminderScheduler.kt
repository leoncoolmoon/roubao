package com.roubao.autopilot.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationManagerCompat

enum class ReminderMode {
    EXACT,
    INEXACT,
    FAILED
}

data class ReminderResult(
    val mode: ReminderMode,
    val triggerAtMillis: Long,
    val message: String = ""
)

object ReminderScheduler {

    private const val TAG = "ReminderScheduler"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return alarmManager.canScheduleExactAlarms()
    }

    fun notificationsEnabled(context: Context): Boolean {
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * 设定一个系统提醒。到点后由 ReminderReceiver 发通知，点通知打开 App 并预填 prompt。
     * 不能使用精确闹钟时会退回 setAndAllowWhileIdle，并明确告诉调用方是近似模式。
     */
    fun schedule(context: Context, triggerAtMillis: Long, prompt: String): ReminderResult {
        if (triggerAtMillis <= System.currentTimeMillis()) {
            return ReminderResult(ReminderMode.FAILED, triggerAtMillis, "提醒时间已经过去了")
        }

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_PROMPT, prompt)
        }
        val requestCode = (prompt.hashCode() * 31) xor (triggerAtMillis / 1000).toInt()
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canExact = canScheduleExact(context)
        Log.i(TAG, "schedule: triggerAt=$triggerAtMillis exact=$canExact notifications=${notificationsEnabled(context)}")

        return try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                ReminderResult(ReminderMode.EXACT, triggerAtMillis, "精确提醒已设置")
            } else {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                ReminderResult(
                    ReminderMode.INEXACT,
                    triggerAtMillis,
                    "未授予精确闹钟权限，已设置近似提醒，可能延迟几分钟"
                )
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "exact schedule denied, fallback to inexact", e)
            try {
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                ReminderResult(
                    ReminderMode.INEXACT,
                    triggerAtMillis,
                    "系统拒绝精确闹钟，已退回近似提醒"
                )
            } catch (e2: Exception) {
                Log.e(TAG, "schedule failed", e2)
                ReminderResult(ReminderMode.FAILED, triggerAtMillis, "设置提醒失败：${e2.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "schedule failed", e)
            ReminderResult(ReminderMode.FAILED, triggerAtMillis, "设置提醒失败：${e.message}")
        }
    }
}

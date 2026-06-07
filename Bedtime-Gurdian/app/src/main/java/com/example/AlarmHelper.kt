package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmHelper {
    fun scheduleBedtimeAlarms(context: Context) {
        val prefs = context.getSharedPreferences("bedtime_prefs", Context.MODE_PRIVATE)
        val isActive = prefs.getBoolean("is_active", false)
        if (!isActive) {
            cancelAlarms(context)
            return
        }

        val bedtimeHour = prefs.getInt("bedtime_hour", 23)
        val bedtimeMinute = prefs.getInt("bedtime_minute", 30)
        val wakeupHour = prefs.getInt("wakeup_hour", 6)
        val wakeupMinute = prefs.getInt("wakeup_minute", 0)
        val savedTimezoneId = prefs.getString("bedtime_timezone", java.util.TimeZone.getDefault().id) ?: java.util.TimeZone.getDefault().id
        val targetZone = java.util.TimeZone.getTimeZone(savedTimezoneId)

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // 1. Bedtime Start Alarm
        val bedtimeCalendar = Calendar.getInstance(targetZone).apply {
            set(Calendar.HOUR_OF_DAY, bedtimeHour)
            set(Calendar.MINUTE, bedtimeMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance(targetZone))) {
                add(Calendar.DATE, 1)
            }
        }

        val startIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "ACTION_START_BEDTIME"
        }
        val startPendingIntent = PendingIntent.getBroadcast(
            context,
            1001,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 2. Bedtime End Alarm (Wakeup)
        val wakeupCalendar = Calendar.getInstance(targetZone).apply {
            set(Calendar.HOUR_OF_DAY, wakeupHour)
            set(Calendar.MINUTE, wakeupMinute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (before(Calendar.getInstance(targetZone))) {
                add(Calendar.DATE, 1)
            }
        }

        val endIntent = Intent(context, AlarmReceiver::class.java).apply {
            action = "ACTION_END_BEDTIME"
        }
        val endPendingIntent = PendingIntent.getBroadcast(
            context,
            1002,
            endIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Schedule start
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedtimeCalendar.timeInMillis, startPendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedtimeCalendar.timeInMillis, startPendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedtimeCalendar.timeInMillis, startPendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, bedtimeCalendar.timeInMillis, startPendingIntent)
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, bedtimeCalendar.timeInMillis, startPendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, bedtimeCalendar.timeInMillis, startPendingIntent)
            }
        }

        // Schedule end
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeupCalendar.timeInMillis, endPendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeupCalendar.timeInMillis, endPendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeupCalendar.timeInMillis, endPendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, wakeupCalendar.timeInMillis, endPendingIntent)
            }
        } catch (e: SecurityException) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, wakeupCalendar.timeInMillis, endPendingIntent)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, wakeupCalendar.timeInMillis, endPendingIntent)
            }
        }
    }

    fun cancelAlarms(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val startIntent = Intent(context, AlarmReceiver::class.java).apply { action = "ACTION_START_BEDTIME" }
        val startPendingIntent = PendingIntent.getBroadcast(context, 1001, startIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (startPendingIntent != null) {
            alarmManager.cancel(startPendingIntent)
            startPendingIntent.cancel()
        }

        val endIntent = Intent(context, AlarmReceiver::class.java).apply { action = "ACTION_END_BEDTIME" }
        val endPendingIntent = PendingIntent.getBroadcast(context, 1002, endIntent, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (endPendingIntent != null) {
            alarmManager.cancel(endPendingIntent)
            endPendingIntent.cancel()
        }
    }
}

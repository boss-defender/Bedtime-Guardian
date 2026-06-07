package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == "ACTION_START_BEDTIME" || action == "ACTION_END_BEDTIME") {
            
            // Acquire a WakeLock to wake up the CPU instantly
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            val wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "BedtimeLock:WakeLockAlarm"
            )
            wakeLock?.acquire(10000) // Keep the CPU awake for up to 10 seconds to process start-up

            if (action == "ACTION_START_BEDTIME") {
                // Instantly force launcher/home activity to minimize current app (YouTube shorts, browser, games)
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                try {
                    context.startActivity(homeIntent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            val serviceIntent = Intent(context, BedtimeService::class.java).apply {
                this.action = action
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }
}

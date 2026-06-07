package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            val prefs = context.getSharedPreferences("bedtime_prefs", Context.MODE_PRIVATE)
            val isActive = prefs.getBoolean("is_active", false)
            if (isActive) {
                // Ensure alarm scheduling survives reboot
                AlarmHelper.scheduleBedtimeAlarms(context)

                val serviceIntent = Intent(context, BedtimeService::class.java).apply {
                    this.action = "ACTION_BOOT_REDEPLOY"
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }
}

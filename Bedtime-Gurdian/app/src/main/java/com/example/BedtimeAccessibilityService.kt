package com.example

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Build
import android.view.accessibility.AccessibilityEvent

/**
 * High-performance Accessibility Watchdog to guard and validate exception whitelists.
 * Allows whitelisted apps (WhatsApp, Dialer, Clock) to run on top of Kiosk, but snaps back
 * immediately if the user attempts to exit them.
 */
class BedtimeAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!BedtimeService.isInBedtime(this)) {
            return
        }

        val currentPkg = event.packageName?.toString() ?: ""
        val myAppPkg = this.packageName
        val isWhitelisted = BedtimeService.isWhitelistedPackage(this, currentPkg)
        val ourApp = (currentPkg == myAppPkg)

        // Accessibility Watchdog Complete Silence: if the foreground app is whitelisted or our app,
        // we must immediately exit and perform zero blocking actions.
        if (isWhitelisted || ourApp) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                val className = event.className?.toString() ?: ""
                if (currentPkg == "com.whatsapp" && className.lowercase().contains("voip")) {
                    BedtimeService.updateWhatsAppCallState(this, true)
                } else if (currentPkg == "com.whatsapp") {
                    BedtimeService.updateWhatsAppCallState(this, false)
                }
            }
            return
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return
            
            // If they navigated elsewhere, WhatsApp call state drops to inactive
            BedtimeService.updateWhatsAppCallState(this, false)

            val isWhitelisted = BedtimeService.isWhitelistedPackage(this, packageName)
            val ourApp = (packageName == this.packageName)

            // If the user is currently on WhatsApp, Dialer, Alarm Clock, or our own app, ALLOW it immediately.
            if (isWhitelisted || ourApp) {
                return
            }

            // If they are on any other non-whitelisted app (e.g. settings, launcher, other apps), lock them out.
            if (BedtimeService.isTemporaryUnlockFlowActive) {
                // If they with an active temporary exception exit the exception flow, cancel exception and lock down.
                BedtimeService.cancelTemporaryUnlock(this)
            }
            blockAccess()
        }
    }

    private fun blockAccess() {
        val lockdownIntent = Intent(this, LockdownActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        try {
            startActivity(lockdownIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val serviceIntent = Intent(this, BedtimeService::class.java).apply {
            action = "ACTION_FORCE_OVERLAY"
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onInterrupt() {}
}

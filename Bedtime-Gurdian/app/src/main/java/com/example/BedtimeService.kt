package com.example

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlinx.coroutines.*

/**
 * Expert-architected background lockdown coordinator service.
 * Eliminates background threads/polling layers, managing Keyguard aware-state and launching the Lockdown Kiosk.
 */
class BedtimeService : Service() {

    companion object {
        @Volatile
        var isTemporaryUnlockFlowActive = false
        @Volatile
        var remainingUnlockSeconds = 0
        @Volatile
        var isPhoneCallActive = false
        @Volatile
        var isWhatsAppCallActive = false
        
        private var instance: BedtimeService? = null

        fun setOverlayVisibility(visible: Boolean) {
            // Overlays removed for Unified UI Architecture
        }

        fun startWhatsAppTracking(context: Context) {
            // Tracks WhatsApp VoIP exits perfectly via Accessibility Watchdog
        }

        fun isInBedtime(context: Context): Boolean {
            val prefs = context.getSharedPreferences("bedtime_prefs", MODE_PRIVATE)
            val isActive = prefs.getBoolean("is_active", false)
            if (!isActive) return false

            val bedtimeHour = prefs.getInt("bedtime_hour", 23)
            val bedtimeMinute = prefs.getInt("bedtime_minute", 30)
            val wakeupHour = prefs.getInt("wakeup_hour", 6)
            val wakeupMinute = prefs.getInt("wakeup_minute", 0)

            val savedTimezoneId = prefs.getString("bedtime_timezone", java.util.TimeZone.getDefault().id) ?: java.util.TimeZone.getDefault().id
            val calendar = Calendar.getInstance(java.util.TimeZone.getTimeZone(savedTimezoneId)).apply {
                timeInMillis = System.currentTimeMillis()
            }
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            val currentMinute = calendar.get(Calendar.MINUTE)

            val currentMins = currentHour * 60 + currentMinute
            val bedtimeMins = bedtimeHour * 60 + bedtimeMinute
            val wakeupMins = wakeupHour * 60 + wakeupMinute

            return isTimeInWindow(currentMins, bedtimeMins, wakeupMins)
        }

        private fun isTimeInWindow(current: Int, start: Int, end: Int): Boolean {
            return if (start < end) {
                current in start until end
            } else {
                current >= start || current < end
            }
        }

        fun getSystemClockPackage(context: Context): String? {
            val pm = context.packageManager
            try {
                val intent = Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                if (info != null && info.activityInfo.packageName != null && info.activityInfo.packageName.isNotEmpty()) {
                    return info.activityInfo.packageName
                }
            } catch (e: Exception) {
                // Ignore, try fullback list below
            }

            val clockPackages = arrayOf(
                "com.google.android.deskclock",    // Google / Pixel / Motorola / Asus
                "com.android.deskclock",           // Xiaomi / Redmi / POCO / AOSP
                "com.sec.android.app.clockpackage",// Samsung
                "com.huawei.deskclock",            // Huawei / Honor
                "com.coloros.alarm",               // Oppo / Realme / OnePlus
                "com.htc.calendar"                 // HTC
            )
            for (pkg in clockPackages) {
                try {
                    pm.getPackageInfo(pkg, 0)
                    return pkg
                } catch (e: Exception) {
                    // Ignore, try next
                }
            }
            return null
        }

        fun isWhitelistedPackage(context: Context, pkgName: String?): Boolean {
            if (pkgName == null) return false
            val lower = pkgName.lowercase()
            val defaultDialer = getDefaultDialerPackageName(context)?.lowercase()
            if (defaultDialer != null && lower == defaultDialer) {
                return true
            }
            val clockPkg = getSystemClockPackage(context)?.lowercase()
            if (clockPkg != null && lower == clockPkg) {
                return true
            }
            return lower == "com.whatsapp" ||
                   lower == "com.android.phone" ||
                   lower == "com.android.server.telecom" ||
                   lower == "com.android.systemui" ||
                   lower.contains("telephony") ||
                   lower.contains("incallui") ||
                   lower.contains("phone") ||
                   lower.contains("dialer")
        }

        fun getDefaultDialerPackageName(context: Context): String? {
            return try {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    telecomManager?.defaultDialerPackage
                } else {
                    null
                }
            } catch (e: Exception) {
                null
            }
        }

        fun setupLockTaskPackages(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager ?: return
            val adminComponent = ComponentName(context, BedtimeDeviceAdminReceiver::class.java)
            try {
                if (dpm.isDeviceOwnerApp(context.packageName) || dpm.isAdminActive(adminComponent)) {
                    val dialer = getDefaultDialerPackageName(context)
                    val clockPkg = getSystemClockPackage(context)
                    val packagesList = mutableListOf(context.packageName, "com.whatsapp", "com.android.systemui")
                    if (dialer != null) {
                        packagesList.add(dialer)
                    }
                    if (clockPkg != null) {
                        packagesList.add(clockPkg)
                    }
                    packagesList.add("com.google.android.dialer")
                    packagesList.add("com.android.dialer")
                    packagesList.add("com.google.android.deskclock")
                    packagesList.add("com.android.deskclock")
                    packagesList.add("com.sec.android.app.clockpackage")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        dpm.setLockTaskPackages(adminComponent, packagesList.toSet().toTypedArray())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun cancelTemporaryUnlock(context: Context) {
            isTemporaryUnlockFlowActive = false
            remainingUnlockSeconds = 0
            val serviceIntent = Intent(context, BedtimeService::class.java).apply {
                action = "ACTION_UPDATE_STATUS"
            }
            try {
                context.startService(serviceIntent)
            } catch (e: Exception) {
                // ignore
            }
        }

        fun updatePhoneCallState(context: Context, active: Boolean) {
            val changed = (isPhoneCallActive != active)
            isPhoneCallActive = active
            if (changed) {
                broadcastCallTransition(context)
            }
        }

        fun updateWhatsAppCallState(context: Context, active: Boolean) {
            val changed = (isWhatsAppCallActive != active)
            isWhatsAppCallActive = active
            if (changed) {
                broadcastCallTransition(context)
            }
        }

        private fun broadcastCallTransition(context: Context) {
            val isCallActive = isPhoneCallActive || isWhatsAppCallActive
            if (isCallActive) {
                val suspendIntent = Intent("ACTION_SUSPEND_KIOSK")
                context.sendBroadcast(suspendIntent)
            } else {
                val resumeIntent = Intent("ACTION_RESUME_KIOSK")
                context.sendBroadcast(resumeIntent)

                if (isInBedtime(context) && !isTemporaryUnlockFlowActive) {
                    val rIntent = Intent(context, LockdownActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    try {
                        context.startActivity(rIntent)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var countdownTimerRunnable: Runnable? = null

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.Main)

    private var phoneStateReceiver: BroadcastReceiver? = null
    private var telephonyCallback: Any? = null
    private var phoneStateListener: android.telephony.PhoneStateListener? = null
    private var userPresentReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(1, createNotification())

        checkBedtimeStatus()
        registerPhoneStateListener()
        registerUserPresentReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())

        val action = intent?.action
        if (action == "ACTION_START_BEDTIME" || action == "ACTION_FORCE_OVERLAY") {
            checkBedtimeStatus()
        } else if (action == "ACTION_END_BEDTIME") {
            isTemporaryUnlockFlowActive = false
            stopCountdownTimer()
            suspendNonWhitelistedApps(this, false)
            unmuteMusicStream(this)
            
            val closeIntent = Intent("ACTION_END_LOCKDOWN_ACTIVITY")
            sendBroadcast(closeIntent)
        } else if (action == "ACTION_START_TEMPORARY_UNLOCK") {
            startTemporaryUnlock()
        } else if (action == "ACTION_UPDATE_STATUS") {
            checkBedtimeStatus()
        } else {
            checkBedtimeStatus()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun checkBedtimeStatus() {
        if (isInBedtime(this)) {
            suspendNonWhitelistedApps(this, true)
            muteMusicStream(this)

            if (!isPhoneCallActive && !isWhatsAppCallActive && !isTemporaryUnlockFlowActive) {
                launchLockdownIfNeeded()
            } else {
                if (isPhoneCallActive || isWhatsAppCallActive) {
                    val suspendIntent = Intent("ACTION_SUSPEND_KIOSK")
                    sendBroadcast(suspendIntent)
                } else if (isTemporaryUnlockFlowActive) {
                    val closeIntent = Intent("ACTION_END_LOCKDOWN_ACTIVITY")
                    sendBroadcast(closeIntent)
                }
            }
        } else {
            suspendNonWhitelistedApps(this, false)
            unmuteMusicStream(this)

            isTemporaryUnlockFlowActive = false
            stopCountdownTimer()

            val closeIntent = Intent("ACTION_END_LOCKDOWN_ACTIVITY")
            sendBroadcast(closeIntent)
        }
    }

    fun launchLockdownIfNeeded() {
        if (!isInBedtime(this)) return

        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (keyguardManager?.isKeyguardLocked == true) {
            return // Do NOT show above system lockscreen to avoid visual overlapping
        }

        val fgPkg = getForegroundPackage()
        val whitelisted = isWhitelistedPackage(this, fgPkg) || isPhoneCallActive || isWhatsAppCallActive
        val isOurAppForeground = (fgPkg == packageName)

        if (!whitelisted && !isOurAppForeground && !isTemporaryUnlockFlowActive) {
            setupLockTaskPackagesIfNeeded()

            val lockdownIntent = Intent(this, LockdownActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            try {
                startActivity(lockdownIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun registerUserPresentReceiver() {
        userPresentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_PRESENT) {
                    if (isInBedtime(context)) {
                        launchLockdownIfNeeded()
                    }
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(this, userPresentReceiver!!, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    private fun startTemporaryUnlock() {
        isTemporaryUnlockFlowActive = true
        remainingUnlockSeconds = 300 // Exactly 5 minutes
        stopCountdownTimer()

        countdownTimerRunnable = object : Runnable {
            override fun run() {
                if (remainingUnlockSeconds > 0) {
                    remainingUnlockSeconds--
                    mainHandler.postDelayed(this, 1000)
                } else {
                    isTemporaryUnlockFlowActive = false
                    checkBedtimeStatus()
                }
            }
        }
        mainHandler.post(countdownTimerRunnable!!)
        
        // Terminate any active LockdownActivity immediately
        val closeIntent = Intent("ACTION_END_LOCKDOWN_ACTIVITY")
        sendBroadcast(closeIntent)
    }

    private fun stopCountdownTimer() {
        countdownTimerRunnable?.let {
            mainHandler.removeCallbacks(it)
        }
        countdownTimerRunnable = null
    }

    private fun setupLockTaskPackagesIfNeeded() {
        setupLockTaskPackages(this)
    }

    private fun suspendNonWhitelistedApps(context: Context, suspend: Boolean) {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager ?: return
        val adminComponent = ComponentName(context, BedtimeDeviceAdminReceiver::class.java)
        
        serviceScope.launch(Dispatchers.IO) {
            try {
                if (!dpm.isAdminActive(adminComponent)) return@launch

                val pm = context.packageManager
                val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
                val packagesToSuspend = mutableListOf<String>()
                val defaultDialer = getDefaultDialerPackageName(context)?.lowercase()
                val clockPkg = getSystemClockPackage(context)?.lowercase()

                for (appInfo in installedApps) {
                    val pkgName = appInfo.packageName
                    val lowerPkg = pkgName.lowercase()

                    if (pkgName == context.packageName) continue
                    if (lowerPkg == "com.whatsapp") continue
                    if (lowerPkg == "com.android.systemui") continue
                    if (defaultDialer != null && lowerPkg == defaultDialer) continue
                    if (clockPkg != null && lowerPkg == clockPkg) continue

                    // Explicit alarm engine exemption & standard telephony
                    if (lowerPkg.contains("clock") ||
                        lowerPkg.contains("alarm") ||
                        lowerPkg.contains("deskclock") ||
                        lowerPkg == "com.android.phone" ||
                        lowerPkg == "com.android.server.telecom" ||
                        lowerPkg.contains("telephony") ||
                        lowerPkg.contains("incallui") ||
                        lowerPkg.contains("phone") ||
                        lowerPkg.contains("dialer") ||
                        lowerPkg == "com.google.android.packageinstaller" ||
                        lowerPkg == "com.android.packageinstaller" ||
                        lowerPkg == "android") {
                        continue
                    }

                    packagesToSuspend.add(pkgName)
                }

                if (packagesToSuspend.isNotEmpty() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dpm.setPackagesSuspended(adminComponent, packagesToSuspend.toTypedArray(), suspend)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun muteMusicStream(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val prefs = context.getSharedPreferences("bedtime_prefs", Context.MODE_PRIVATE)
            if (!prefs.contains("saved_music_volume")) {
                val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                prefs.edit().putInt("saved_music_volume", currentVol).apply()
            }
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun unmuteMusicStream(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            val prefs = context.getSharedPreferences("bedtime_prefs", Context.MODE_PRIVATE)
            val savedVol = prefs.getInt("saved_music_volume", -1)
            if (savedVol != -1) {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVol, 0)
                prefs.edit().remove("saved_music_volume").apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getForegroundPackage(): String? {
        try {
            val usageStatsManager = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val endTime = System.currentTimeMillis()
            val startTime = endTime - 1000 * 15
            val usageStats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
            if (!usageStats.isNullOrEmpty()) {
                val latest = usageStats.maxByOrNull { it.lastTimeUsed }
                if (latest != null && (endTime - latest.lastTimeUsed) < 15000) {
                    return latest.packageName
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(1)
            if (!tasks.isNullOrEmpty()) {
                return tasks[0].topActivity?.packageName
            }
        } catch (e: Exception) {
            // ignore
        }

        try {
            val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processes = am.runningAppProcesses
            if (!processes.isNullOrEmpty()) {
                for (proc in processes) {
                    if (proc.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        return proc.pkgList?.firstOrNull()
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }

        return null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private class BedtimeTelephonyCallback(private val onStateChanged: (Int) -> Unit) : 
        android.telephony.TelephonyCallback(), 
        android.telephony.TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            onStateChanged(state)
        }
    }

    private fun registerPhoneStateListener() {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        
        val onStateChanged: (Int) -> Unit = { state ->
            val ringing = (state == TelephonyManager.CALL_STATE_RINGING)
            val offhook = (state == TelephonyManager.CALL_STATE_OFFHOOK)
            val idle = (state == TelephonyManager.CALL_STATE_IDLE)

            if (ringing || offhook) {
                updatePhoneCallState(this@BedtimeService, true)
            } else if (idle) {
                updatePhoneCallState(this@BedtimeService, false)
            }
            checkBedtimeStatus()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val callback = BedtimeTelephonyCallback(onStateChanged)
                telephonyCallback = callback
                telephonyManager.registerTelephonyCallback(ContextCompat.getMainExecutor(this), callback)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            try {
                @Suppress("DEPRECATION")
                val listener = object : android.telephony.PhoneStateListener() {
                    @Deprecated("Deprecated in Java", ReplaceWith("onCallStateChanged"))
                    override fun onCallStateChanged(state: Int, incomingNumber: String?) {
                        onStateChanged(state)
                    }
                }
                phoneStateListener = listener
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, android.telephony.PhoneStateListener.LISTEN_CALL_STATE)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        phoneStateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE)
                val ringing = (state == TelephonyManager.EXTRA_STATE_RINGING)
                val offhook = (state == TelephonyManager.EXTRA_STATE_OFFHOOK)
                val idle = (state == TelephonyManager.EXTRA_STATE_IDLE)

                if (ringing || offhook) {
                    updatePhoneCallState(context, true)
                } else if (idle) {
                    updatePhoneCallState(context, false)
                }
                checkBedtimeStatus()
            }
        }
        val filter = IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        ContextCompat.registerReceiver(this, phoneStateReceiver!!, filter, ContextCompat.RECEIVER_EXPORTED)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        
        serviceJob.cancel()
        serviceScope.cancel()

        mainHandler.removeCallbacksAndMessages(null)
        stopCountdownTimer()
        
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    telephonyCallback?.let {
                        if (it is android.telephony.TelephonyCallback) {
                            telephonyManager.unregisterTelephonyCallback(it)
                        }
                    }
                } else {
                    phoneStateListener?.let {
                        @Suppress("DEPRECATION")
                        telephonyManager.listen(it, android.telephony.PhoneStateListener.LISTEN_NONE)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        telephonyCallback = null
        phoneStateListener = null

        try {
            phoneStateReceiver?.let {
                unregisterReceiver(it)
            }
        } catch (e: Exception) {
            // ignore
        }
        phoneStateReceiver = null

        try {
            userPresentReceiver?.let {
                unregisterReceiver(it)
            }
        } catch (e: Exception) {
            // ignore
        }
        userPresentReceiver = null

        val prefs = getSharedPreferences("bedtime_prefs", MODE_PRIVATE)
        val isActive = prefs.getBoolean("is_active", false)
        if (isActive && isInBedtime(this)) {
            val broadcastIntent = Intent(this, AlarmReceiver::class.java).apply {
                action = "ACTION_START_BEDTIME"
            }
            sendBroadcast(broadcastIntent)
        }

        System.gc()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "bedtime_wellbeing_channel",
                "Bedtime Well-being Lock",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps your device locked safely to protect your sleep quality."
                setSound(null, null)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val title = "Bedtime Protection Active"
        val content = "Bedtime Guardian is constantly guarding your sleep cycle."

        return NotificationCompat.Builder(this, "bedtime_wellbeing_channel")
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }
}

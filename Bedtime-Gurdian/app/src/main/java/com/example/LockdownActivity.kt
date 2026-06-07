package com.example

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import android.provider.AlarmClock
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

/**
 * Principal-engineered Android Lockout screen UI. Built for absolute OLED pitch-black
 * power efficiency, reactive event-driven context, zero-polling rendering, and proactive GC.
 */
class LockdownActivity : ComponentActivity() {

    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Broadcaster to finish when bedtime ends
    private var finishReceiver: BroadcastReceiver? = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "ACTION_END_LOCKDOWN_ACTIVITY") {
                try {
                    stopLockTask()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                finish()
            }
        }
    }

    // Audio Focus listener to reclaim focus instantly ONLY when stolen, avoiding any background runnables
    private var focusChangeListener: AudioManager.OnAudioFocusChangeListener? = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (focusChange != AudioManager.AUDIOFOCUS_GAIN) {
            // Re-gain focus immediately to pause any background audio attempting to play
            hijackAudioFocus()
            muteMusicStream()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Keyguard Awareness: Lockdown screen must NOT draw over the secure system lock screen.
        // Keep LockdownActivity completely hidden behind the keyguard, waiting until the user unlocks during bedtime.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(false)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        // Listen for end-bedtime signals
        finishReceiver?.let {
            val filter = IntentFilter("ACTION_END_LOCKDOWN_ACTIVITY")
            ContextCompat.registerReceiver(this, it, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Black // OLED/AMOLED pure pitch black
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        LockdownContent()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Aggressive early exit if outside bedtime window
        if (!BedtimeService.isInBedtime(this)) {
            try {
                stopLockTask()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            finish()
            return
        }

        var isDeviceOwner = false
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            if (dpm != null && dpm.isDeviceOwnerApp(packageName)) {
                isDeviceOwner = true
                BedtimeService.setupLockTaskPackages(this)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (isDeviceOwner) {
            try {
                // Enterprise Lockdown: Activate Native Android Kiosk Mode
                startLockTask()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Hijack focus and silence streams reactively with zero-polling
        hijackAudioFocus()
        muteMusicStream()
    }

    override fun onPause() {
        super.onPause()
        // No active runnables to clean up, purely callback driven
    }

    override fun onDestroy() {
        super.onDestroy()
        
        mainHandler.removeCallbacksAndMessages(null)
        
        try {
            finishReceiver?.let {
                unregisterReceiver(it)
            }
        } catch (e: Exception) {
            // ignore
        }
        finishReceiver = null

        try {
            stopLockTask()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        releaseAudioFocus()
        focusChangeListener = null

        // Explicitly clear references and run proactive GC to save RAM
        audioManager = null
        focusRequest = null
        
        System.gc()
    }

    // Disable standard back key behavior during lockdown
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        // Absorbed to enforce strict lockout restrictions
    }

    private fun hijackAudioFocus() {
        val am = audioManager ?: return
        val listener = focusChangeListener ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()

                focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(listener)
                    .build()

                am.requestAudioFocus(focusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                am.requestAudioFocus(
                    listener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseAudioFocus() {
        val am = audioManager ?: return
        val listener = focusChangeListener ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(listener)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun muteMusicStream() {
        val am = audioManager ?: return
        try {
            val prefs = getSharedPreferences("bedtime_prefs", Context.MODE_PRIVATE)
            if (!prefs.contains("saved_music_volume")) {
                val currentVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                prefs.edit().putInt("saved_music_volume", currentVol).apply()
            }
            // Force stream music volume to completely mute
            am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun launchWhatsApp() {
        try {
            val pm = packageManager
            val intent = pm.getLaunchIntentForPackage("com.whatsapp")
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/send")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(webIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun launchDialer() {
        try {
            val defaultDialerPkg = BedtimeService.getDefaultDialerPackageName(this) ?: "com.android.dialer"
            val intent = packageManager.getLaunchIntentForPackage(defaultDialerPkg) ?: Intent(Intent.ACTION_DIAL).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun launchAlarmClock() {
        try {
            val clockPkg = BedtimeService.getSystemClockPackage(this)
            val intent = if (clockPkg != null) {
                packageManager.getLaunchIntentForPackage(clockPkg)
            } else {
                null
            }
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                val fallbackIntent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(fallbackIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun triggerServiceTemporaryUnlock() {
        val updateIntent = Intent(this, BedtimeService::class.java).apply {
            action = "ACTION_START_TEMPORARY_UNLOCK"
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(updateIntent)
            } else {
                startService(updateIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finish()
    }

    @Composable
    private fun LockdownContent() {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null, // Disables ripple so the background absorption feels solid
                    onClick = {}
                )
                .padding(24.dp)
        ) {
            // Center Content: Geometrically perfect AMOLED header layout
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.align(Alignment.Center)
            ) {
                Box(
                    modifier = Modifier
                        .size(88.dp)
                        .background(Color(0xFF0A0F1D), RoundedCornerShape(28.dp))
                        .border(1.5.dp, Color(0xFF82B1FF).copy(alpha = 0.6f), RoundedCornerShape(28.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Device Locked Status Indicator",
                        tint = Color(0xFF82B1FF),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Please go to bed now.",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Light,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontFamily = FontFamily.SansSerif,
                    letterSpacing = (-0.5).sp
                )

                Text(
                    text = "Restoration cycle is active. No digital distractions.",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color(0xFF64748B),
                    textAlign = TextAlign.Center,
                    lineHeight = 18.sp
                )
            }

            // Bottom elements: Pure flat hierarchy, custom Material 3 styled buttons with hardware ripples
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .fillMaxWidth()
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "EMERGENCY COMMUNICATION EXCEPTIONS",
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF82B1FF),
                        letterSpacing = 1.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Dialer Exception Button with automatic hardware Ripple Click
                        Button(
                            onClick = { launchDialer() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0F172A),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Call,
                                contentDescription = "Emergency Phone Dialer App",
                                tint = Color(0xFF82B1FF),
                                modifier = Modifier.size(18.dp)
                              )
                              Spacer(modifier = Modifier.width(6.dp))
                              Text(
                                  "Dialer",
                                  color = Color.White,
                                  fontWeight = FontWeight.SemiBold,
                                  fontSize = 12.sp
                              )
                        }

                        // Alarm Exception Button
                        Button(
                            onClick = { launchAlarmClock() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0F172A),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = "System Alarm Clock App",
                                tint = Color(0xFFFFB74D),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "Alarm",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }

                        // WhatsApp Exception Button
                        Button(
                            onClick = { launchWhatsApp() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF0F172A),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                            modifier = Modifier
                                .weight(1f)
                                .height(56.dp)
                                .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(20.dp))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "WhatsApp Chat App Client",
                                tint = Color(0xFF25D366),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                "WhatsApp",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

package com.example

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import java.util.Calendar
import kotlinx.coroutines.*
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val permissionSharedPrefs by lazy {
        getSharedPreferences("bedtime_prefs", Context.MODE_PRIVATE)
    }

    // Permission request launchers
    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) updateStateTrigger()
    }

    private val requestPhonePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) updateStateTrigger()
    }

    // State trigger to force Compose recomposition after permissions settings activity return
    private var permissionStateChangedTrigger by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enterprise Kiosk Mode: Device Owner log diagnosis
        val dpmService = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
        if (dpmService != null) {
            val isOwner = dpmService.isDeviceOwnerApp(packageName)
            android.util.Log.d("BedtimeLock", "isDeviceOwnerApp: $isOwner")
            if (!isOwner) {
                android.util.Log.w("BedtimeLock", "CRITICAL LOOPHOLE FIX: App is NOT device owner. To prevent YouTube / Recents bypasses, please set device owner via ADB:\n")
                android.util.Log.w("BedtimeLock", "adb shell dpm set-device-owner com.example/.BedtimeDeviceAdminReceiver")
            } else {
                android.util.Log.i("BedtimeLock", "Bedtime Lock configured successfully as Enterprise Device Owner.")
            }
        }

        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Black // OLED/AMOLED pure pitch black to eliminate idle display battery draw
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        // Trick to recompose on system setting returns
                        val trigger = permissionStateChangedTrigger
                        AppScreenContent(trigger)
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateStateTrigger()
        // If background service is active, make sure it is running
        if (permissionSharedPrefs.getBoolean("is_active", false)) {
            startBedtimeService()
        }
    }

    private fun updateStateTrigger() {
        permissionStateChangedTrigger++
    }

    private fun startBedtimeService() {
        val intent = Intent(this, BedtimeService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val expectedId = android.content.ComponentName(context, BedtimeAccessibilityService::class.java).flattenToString()
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val colonSplitter = enabledServices.split(":")
        for (enabledService in colonSplitter) {
            if (enabledService.equals(expectedId, ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: true
        } else {
            true
        }
    }

    @Composable
    fun AppScreenContent(trigger: Int) {
        val prefs = remember(trigger) { getSharedPreferences("bedtime_prefs", Context.MODE_PRIVATE) }
        val isSetupComplete = remember { mutableStateOf(prefs.getBoolean("is_active", false)) }
        val coroutineScope = rememberCoroutineScope()

        var bedtimeHour by remember { mutableStateOf(prefs.getInt("bedtime_hour", 23)) }
        var bedtimeMinute by remember { mutableStateOf(prefs.getInt("bedtime_minute", 30)) }
        var wakeupHour by remember { mutableStateOf(prefs.getInt("wakeup_hour", 6)) }
        var wakeupMinute by remember { mutableStateOf(prefs.getInt("wakeup_minute", 0)) }

        val hasOverlay = remember(trigger) { Settings.canDrawOverlays(this@MainActivity) }
        val hasNotifications = remember(trigger) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        }
        val hasPhoneState = remember(trigger) {
            ContextCompat.checkSelfPermission(
                this@MainActivity,
                Manifest.permission.READ_PHONE_STATE
            ) == PackageManager.PERMISSION_GRANTED
        }
        val hasAccessibility = remember(trigger) { isAccessibilityServiceEnabled(this@MainActivity) }
        val hasIgnoringBattery = remember(trigger) { isIgnoringBatteryOptimizations(this@MainActivity) }

        val dpm = remember(trigger) { getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager }
        val isDeviceOwner = remember(trigger) { dpm?.isDeviceOwnerApp(packageName) == true }
        val isDeviceAdmin = remember(trigger) {
            val adminComponent = android.content.ComponentName(this@MainActivity, BedtimeDeviceAdminReceiver::class.java)
            dpm?.isAdminActive(adminComponent) == true
        }

        var showTimezoneDialog by remember { mutableStateOf(false) }
        val detectedTimezoneName = remember { java.util.TimeZone.getDefault().displayName }
        val detectedTimezoneId = remember { java.util.TimeZone.getDefault().id }

        if (showTimezoneDialog) {
            AlertDialog(
                onDismissRequest = { showTimezoneDialog = false },
                title = {
                    Text(
                        text = "Timezone Setup Lock-In",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                },
                text = {
                    Text(
                        text = "Warning: You cannot change your bedtime timezone later. Are you sure you want to set $detectedTimezoneName for your bedtime?",
                        color = Color(0xFFE6E1E5),
                        fontSize = 14.sp
                    )
                },
                confirmButton = {
                    Button(
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD0BCFF), contentColor = Color(0xFF381E72)),
                        onClick = {
                            showTimezoneDialog = false
                            // Save to SharedPreferences and schedule alarms securely off-thread to avoid any UI block
                            coroutineScope.launch(Dispatchers.IO) {
                                prefs.edit().apply {
                                    putInt("bedtime_hour", bedtimeHour)
                                    putInt("bedtime_minute", bedtimeMinute)
                                    putInt("wakeup_hour", wakeupHour)
                                    putInt("wakeup_minute", wakeupMinute)
                                    putString("bedtime_timezone", detectedTimezoneId)
                                    putBoolean("is_active", true)
                                    commit() // use commit on background thread for synchronous disk operations
                                }
                                
                                // Dynamically set exact alarms via AlarmManager for bedtime onset & wake up
                                AlarmHelper.scheduleBedtimeAlarms(this@MainActivity)

                                withContext(Dispatchers.Main) {
                                    try {
                                        val dpmService = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
                                        if (dpmService != null && dpmService.isDeviceOwnerApp(packageName)) {
                                            BedtimeService.setupLockTaskPackages(this@MainActivity)
                                        }
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                    isSetupComplete.value = true
                                    startBedtimeService()
                                    Toast.makeText(
                                        this@MainActivity,
                                        "Bedtime Secure Lock is now active! Rest well.",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    ) {
                        Text("Yes, Confirm")
                    }
                },
                dismissButton = {
                    TextButton(
                        colors = ButtonDefaults.textButtonColors(contentColor = Color(0xFFD0BCFF)),
                        onClick = { showTimezoneDialog = false }
                    ) {
                        Text("Cancel")
                    }
                },
                containerColor = Color(0xFF1C1B1F),
                textContentColor = Color.White
            )
        }

        if (isSetupComplete.value) {
            BypassStatusScreen(
                bedtimeHour = bedtimeHour,
                bedtimeMinute = bedtimeMinute,
                wakeupHour = wakeupHour,
                wakeupMinute = wakeupMinute
            )
        } else {
            SetupConfigurationScreen(
                bedtimeHour = bedtimeHour,
                bedtimeMinute = bedtimeMinute,
                wakeupHour = wakeupHour,
                wakeupMinute = wakeupMinute,
                onBedtimeHourChange = { bedtimeHour = it },
                onBedtimeMinuteChange = { bedtimeMinute = it },
                onWakeupHourChange = { wakeupHour = it },
                onWakeupMinuteChange = { wakeupMinute = it },
                hasOverlay = hasOverlay,
                hasNotifications = hasNotifications,
                hasPhoneState = hasPhoneState,
                hasAccessibility = hasAccessibility,
                hasIgnoringBattery = hasIgnoringBattery,
                isDeviceOwner = isDeviceOwner,
                isDeviceAdmin = isDeviceAdmin,
                onSave = {
                    if (!hasOverlay) {
                        Toast.makeText(
                            this@MainActivity,
                            "Please grant Display Over Other Apps permission to activate.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@SetupConfigurationScreen
                    }

                    if (!hasAccessibility) {
                        Toast.makeText(
                            this@MainActivity,
                            "Please enable Bedtime Accessibility Service to configure bulletproof lock.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@SetupConfigurationScreen
                    }

                    showTimezoneDialog = true
                }
            )
        }
    }

    private fun Toast.addPermissionToast() {
        this.show()
    }

    // --- LOCKED BYPASS SCREEN (SHOWS STATUS) ---
    @Composable
    fun BypassStatusScreen(
        bedtimeHour: Int,
        bedtimeMinute: Int,
        wakeupHour: Int,
        wakeupMinute: Int
    ) {
        val scrollState = rememberScrollState()
        val prefs = remember { getSharedPreferences("bedtime_prefs", Context.MODE_PRIVATE) }
        val savedTimezoneId = remember { prefs.getString("bedtime_timezone", java.util.TimeZone.getDefault().id) ?: java.util.TimeZone.getDefault().id }
        val savedTimezoneName = remember { java.util.TimeZone.getTimeZone(savedTimezoneId).displayName }

        // Calculate minutes remaining dynamically to make the dashboard stateful and professional in the locked-in timezone
        val now = Calendar.getInstance(java.util.TimeZone.getTimeZone(savedTimezoneId))
        val curMins = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        val bedMins = bedtimeHour * 60 + bedtimeMinute
        var diffMins = bedMins - curMins
        if (diffMins < 0) {
            diffMins += 24 * 60
        }
        val countdownHours = diffMins / 60
        val countdownMinutes = diffMins % 60
        val countdownStr = String.format("%02d:%02d", countdownHours, countdownMinutes)

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Elegant Dark Status Bar Mock Padding & Custom Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFD0BCFF), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Lock Logo",
                            tint = Color(0xFF381E72),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column {
                        Text(
                            text = "BedtimeGuardian",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE6E1E5)
                        )
                        Text(
                            text = "SYSTEM CORE ACTIVE",
                            fontSize = 9.sp,
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD0BCFF)
                        )
                    }
                }
                
                // Top control mock representation matching design
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color(0xFF49454F), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(3.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(modifier = Modifier.size(width = 14.dp, height = 2.dp).background(Color(0xFFE6E1E5), RoundedCornerShape(1.dp)))
                        Box(modifier = Modifier.size(width = 9.dp, height = 2.dp).background(Color(0xFFE6E1E5), RoundedCornerShape(1.dp)))
                    }
                }
            }

            // Main Bento Grid Content Area
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. Sleep Cycle Card (Restoration Pending blur-glow card)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(24.dp))
                        .background(Color(0xFF49454F))
                        .border(1.dp, Color(0xFFD0BCFF).copy(alpha = 0.2f), RoundedCornerShape(24.dp))
                ) {
                    // Subtle glowing corner pattern representing the visual blur-glow
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(120.dp)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFD0BCFF).copy(alpha = 0.15f),
                                        Color.Transparent
                                    )
                                )
                            )
                    )

                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column {
                                Text(
                                    text = "CURRENT WINDOW",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFCAC4D0),
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Row {
                                    Text(
                                        text = "Restoration ",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Light,
                                        color = Color(0xFFE6E1E5)
                                    )
                                    Text(
                                        text = "Pending",
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Light,
                                        color = Color(0xFFD0BCFF)
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(Color(0xFF381E72), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = "Active Status Icon",
                                    tint = Color(0xFFD0BCFF),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(18.dp))

                        Row(
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = countdownStr,
                                fontSize = 42.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                lineHeight = 42.sp
                            )
                            Text(
                                text = "UNTIL LOCK",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD0BCFF),
                                letterSpacing = 1.sp,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "TIMEZONE BOUND: $savedTimezoneName ($savedTimezoneId)",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD0BCFF).copy(alpha = 0.8f),
                            letterSpacing = 0.5.sp
                        )
                    }
                }

                // 2. Twin Bento Block Rows (Bedtime & Wakeup Side-by-Side)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Bedtime Block
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1C1B1F)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp)
                        ) {
                            Text(
                                text = "BEDTIME",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD0BCFF),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val isPm = bedtimeHour >= 12
                            val displayHour = when {
                                bedtimeHour == 0 -> 12
                                bedtimeHour > 12 -> bedtimeHour - 12
                                else -> bedtimeHour
                            }
                            val amPmStr = if (isPm) "PM" else "AM"
                            Text(
                                text = String.format("%02d:%02d", bedtimeHour, bedtimeMinute),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE6E1E5)
                            )
                            Text(
                                text = "$displayHour:$bedtimeMinute $amPmStr",
                                fontSize = 11.sp,
                                color = Color(0xFFCAC4D0)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            // Simple Elegant percentage track
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(Color(0xFF49454F), RoundedCornerShape(2.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.75f)
                                        .fillMaxHeight()
                                        .background(Color(0xFFD0BCFF), RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }

                    // Wake-up Block
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF1C1B1F)
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp)),
                        shape = RoundedCornerShape(24.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(18.dp)
                        ) {
                            Text(
                                text = "WAKE UP",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD0BCFF),
                                letterSpacing = 1.sp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            val isPm = wakeupHour >= 12
                            val displayHour = when {
                                wakeupHour == 0 -> 12
                                wakeupHour > 12 -> wakeupHour - 12
                                else -> wakeupHour
                            }
                            val amPmStr = if (isPm) "PM" else "AM"
                            Text(
                                text = String.format("%02d:%02d", wakeupHour, wakeupMinute),
                                fontSize = 22.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE6E1E5)
                            )
                            Text(
                                text = "$displayHour:$wakeupMinute $amPmStr",
                                fontSize = 11.sp,
                                color = Color(0xFFCAC4D0)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            // Simple Elegant percentage track
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .background(Color(0xFF49454F), RoundedCornerShape(2.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.25f)
                                        .fillMaxHeight()
                                        .background(Color(0xFFD0BCFF), RoundedCornerShape(2.dp))
                                )
                            }
                        }
                    }
                }

                // 3. Exception Whitelist Block
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF49454F).copy(alpha = 0.4f)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Allowed Exceptions",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFFE6E1E5)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "WhatsApp & Phone Dialer",
                                fontSize = 11.sp,
                                color = Color(0xFFD0BCFF).copy(alpha = 0.8f)
                            )
                        }

                        // App visual circles representations
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF1C1B1F), CircleShape)
                                    .border(1.dp, Color(0xFFD0BCFF).copy(alpha = 0.3f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = "Allowed Call",
                                    tint = Color(0xFFD0BCFF),
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(Color(0xFF1C1B1F), CircleShape)
                                    .border(1.dp, Color(0xFFD0BCFF).copy(alpha = 0.3f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Lock,
                                    contentDescription = "Allowed Apps",
                                    tint = Color(0xFFD0BCFF),
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                }

                // 4. Anti-Removal Protection Warning Block
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF2D2930)
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = 1.dp,
                            color = Color(0xFFF2B8B5).copy(alpha = 0.3f),
                            shape = RoundedCornerShape(24.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(Color(0xFFF2B8B5).copy(alpha = 0.1f), RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(Color(0xFFF2B8B5), CircleShape)
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Lockdown Active",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF2B8B5)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Settings are persistent. Manual uninstallation from system settings is required to modify this schedule.",
                                fontSize = 11.sp,
                                color = Color(0xFFCAC4D0),
                                lineHeight = 16.sp
                            )
                        }
                    }
                }

                // 5. Test Tools & Utility controls
                Button(
                    onClick = {
                        Toast.makeText(this@MainActivity, "Launching bedtime lockdown in 3 seconds...", Toast.LENGTH_LONG).show()
                        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                            val testIntent = Intent(this@MainActivity, BedtimeService::class.java)
                            startService(testIntent)
                        }, 3000)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2D2930)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .border(1.dp, Color(0xFF49454F), RoundedCornerShape(16.dp))
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "Pre-Test Overlay",
                        tint = Color(0xFFD0BCFF),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Pre-Test Bedtime Overlay Layout", color = Color(0xFFD0BCFF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Spacer(modifier = Modifier.height(32.dp))

            // Footer Bar matching Website Layout
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF211F26))
                    .padding(horizontal = 24.dp, vertical = 18.dp)
                    .border(width = 1.dp, color = Color(0xFF49454F).copy(alpha = 0.5f)),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Pulse live tracker beacon
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(Color(0xFF00FF00), CircleShape)
                    )
                    Text(
                        text = "SERVICE: 0X82_PROTECT_ENABLED",
                        fontSize = 10.sp,
                        color = Color(0xFFCAC4D0),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = Color(0xFFD0BCFF),
                    shape = RoundedCornerShape(100.dp)
                ) {
                    Text(
                        text = "LOCKED",
                        color = Color(0xFF381E72),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun TimeStatusColumn(title: String, hour: Int, minute: Int, themeColor: Color) {
        val isPm = hour >= 12
        val displayHour = when {
            hour == 0 -> 12
            hour > 12 -> hour - 12
            else -> hour
        }
        val amPmStr = if (isPm) "PM" else "AM"
        val timeStr = String.format("%02d:%02d %s", displayHour, minute, amPmStr)

        Column {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF64748B)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = timeStr,
                fontSize = 20.sp,
                color = themeColor,
                fontWeight = FontWeight.Bold
            )
        }
    }

    // --- SETUP / FIRST TIME LAUNCH SCREEN ---
    @Composable
    fun SetupConfigurationScreen(
        bedtimeHour: Int,
        bedtimeMinute: Int,
        wakeupHour: Int,
        wakeupMinute: Int,
        onBedtimeHourChange: (Int) -> Unit,
        onBedtimeMinuteChange: (Int) -> Unit,
        onWakeupHourChange: (Int) -> Unit,
        onWakeupMinuteChange: (Int) -> Unit,
        hasOverlay: Boolean,
        hasNotifications: Boolean,
        hasPhoneState: Boolean,
        hasAccessibility: Boolean,
        hasIgnoringBattery: Boolean,
        isDeviceOwner: Boolean,
        isDeviceAdmin: Boolean,
        onSave: () -> Unit
    ) {
        val scrollState = rememberScrollState()

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // Brand/Lock Circle
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFFD0BCFF), RoundedCornerShape(16.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Clock Logo",
                    tint = Color(0xFF381E72),
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "Create Bedtime Lock",
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Block digital habits, embrace quiet nights.",
                fontSize = 14.sp,
                color = Color(0xFFCAC4D0)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Time Selectors using Elegant Dark palette
            CustomTimeSelector(
                label = "Bedtime (Lock Screen)",
                hour = bedtimeHour,
                minute = bedtimeMinute,
                onHourChange = onBedtimeHourChange,
                onMinuteChange = onBedtimeMinuteChange,
                primaryColor = Color(0xFFD0BCFF)
            )

            Spacer(modifier = Modifier.height(18.dp))

            CustomTimeSelector(
                label = "Wakeup (Unlock Screen)",
                hour = wakeupHour,
                minute = wakeupMinute,
                onHourChange = onWakeupHourChange,
                onMinuteChange = onWakeupMinuteChange,
                primaryColor = Color(0xFFD0BCFF)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // Permissions Status Checklist
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1C1B1F)
                ),
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp))
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "HARDWARE & SECURITY COMPLIANCE",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFD0BCFF),
                        letterSpacing = 1.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    PermissionItemRow(
                        title = "Draw Over Other Apps",
                        description = "Required to lock your screen with overlay.",
                        isGranted = hasOverlay,
                        icon = Icons.Default.Lock,
                        onGrantClick = {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:$packageName")
                            )
                            startActivity(intent)
                        }
                    )

                    HorizontalDivider(color = Color(0xFF49454F).copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

                    PermissionItemRow(
                        title = "Push Notifications",
                        description = "Required to keep lock service alive.",
                        isGranted = hasNotifications,
                        icon = Icons.Default.Notifications,
                        onGrantClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                        }
                    )

                    HorizontalDivider(color = Color(0xFF49454F).copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

                    PermissionItemRow(
                        title = "Allow Calls (Phone State)",
                        description = "Detect calls to let you speak in emergencies.",
                        isGranted = hasPhoneState,
                        icon = Icons.Default.Phone,
                        onGrantClick = {
                            requestPhonePermissionLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                        }
                    )

                    HorizontalDivider(color = Color(0xFF49454F).copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

                    PermissionItemRow(
                        title = "Accessibility Shield Core",
                        description = "Required to block Recents swiping and background YouTube.",
                        isGranted = hasAccessibility,
                        icon = Icons.Default.Lock,
                        onGrantClick = {
                            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            startActivity(intent)
                        }
                    )

                    HorizontalDivider(color = Color(0xFF49454F).copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

                    PermissionItemRow(
                        title = "Unrestricted Battery",
                        description = "Ensures absolute reboot survival and background tracking.",
                        isGranted = hasIgnoringBattery,
                        icon = Icons.Default.Info,
                        onGrantClick = {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                    data = Uri.parse("package:$packageName")
                                }
                                try {
                                    startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(this@MainActivity, "Please enable Unrestricted Battery under system settings manually.", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    )

                    HorizontalDivider(color = Color(0xFF49454F).copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

                    PermissionItemRow(
                        title = "Device Administrator",
                        description = "Enables enterprise-grade lockdown and security compliance.",
                        isGranted = isDeviceAdmin,
                        icon = Icons.Default.Lock,
                        onGrantClick = {
                            val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                                putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, android.content.ComponentName(this@MainActivity, BedtimeDeviceAdminReceiver::class.java))
                                putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Enables enterprise-grade lockdown and background restriction policies.")
                            }
                            startActivity(intent)
                        }
                    )

                    HorizontalDivider(color = Color(0xFF49454F).copy(alpha = 0.5f), modifier = Modifier.padding(vertical = 12.dp))

                    PermissionItemRow(
                        title = "Enterprise Device Owner",
                        description = if (isDeviceOwner) "App has absolute authority to suspend packages and activate Lock Task Kiosk Mode." else "Crucial for Lock Task Mode (Kiosk Mode) to block Recents swiping. Read setup guide below.",
                        isGranted = isDeviceOwner,
                        icon = Icons.Default.Info,
                        onGrantClick = {
                            Toast.makeText(this@MainActivity, "Run ADB command to configure device owner (see guide below).", Toast.LENGTH_LONG).show()
                        }
                    )

                    if (!isDeviceOwner) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = Color(0xFF381E72).copy(alpha = 0.3f)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, Color(0xFFD0BCFF).copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = "ADB DEVICE OWNER SETUP GUIDE",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFD0BCFF),
                                    letterSpacing = 1.sp
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "To enable native Kiosk Mode to eliminate the Recents loophole:\n\n" +
                                            "1. Ensure USB Debugging is turned on in your developer settings.\n" +
                                            "2. Connect your device via USB to a PC with ADB installed.\n" +
                                            "3. Run the following command exactly:\n\n" +
                                            "adb shell dpm set-device-owner com.example/.BedtimeDeviceAdminReceiver\n\n" +
                                            "This grants administrative kiosk privileges to enforce sleep schedule bounds.",
                                    fontSize = 11.sp,
                                    color = Color.White,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Save and Activate Button
            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFD0BCFF),
                    contentColor = Color(0xFF381E72),
                    disabledContainerColor = Color(0xFF49454F)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = "Save & Activate Lockdown Engine",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }

    private fun showTimePickerDialog(
        context: Context,
        initialHour: Int,
        initialMinute: Int,
        onTimeSet: (Int, Int) -> Unit
    ) {
        val dialog = android.app.TimePickerDialog(
            context,
            { view, hourOfDay, minute ->
                // Force setting 12-hour style configuration to satisfy the requirement
                view.setIs24HourView(false)
                onTimeSet(hourOfDay, minute)
            },
            initialHour,
            initialMinute,
            false // 12-hour format with AM/PM selection
        )
        dialog.show()
    }

    @Composable
    fun CustomTimeSelector(
        label: String,
        hour: Int,
        minute: Int,
        onHourChange: (Int) -> Unit,
        onMinuteChange: (Int) -> Unit,
        primaryColor: Color
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current

        Card(
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1C1B1F)
            ),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color(0xFF49454F), RoundedCornerShape(24.dp))
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = label,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor
                    )

                    // Helper label for selecting via custom native TimePicker dialog
                    Text(
                        text = "Customize",
                        fontSize = 11.sp,
                        color = primaryColor.copy(alpha = 0.8f),
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                showTimePickerDialog(context, hour, minute) { h, m ->
                                    onHourChange(h)
                                    onMinuteChange(m)
                                }
                            }
                            .padding(4.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                val isPm = hour >= 12
                val displayHour = when {
                    hour == 0 -> 12
                    hour > 12 -> hour - 12
                    else -> hour
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showTimePickerDialog(context, hour, minute) { h, m ->
                                onHourChange(h)
                                onMinuteChange(m)
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Hour selector controls (12-hour display and circular cycle)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = {
                            var nextHour = hour + 1
                            if (nextHour > 23) nextHour = 0
                            onHourChange(nextHour)
                        }) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up Hour", tint = Color(0xFFCAC4D0))
                        }
                        Text(
                            text = String.format("%02d", displayHour),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(onClick = {
                            var prevHour = hour - 1
                            if (prevHour < 0) prevHour = 23
                            onHourChange(prevHour)
                        }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down Hour", tint = Color(0xFFCAC4D0))
                        }
                        Text("Hours", fontSize = 11.sp, color = Color(0xFFCAC4D0))
                    }

                    Text(
                        text = ":",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF49454F),
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Minute selector controls
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { if (minute < 59) onMinuteChange(minute + 1) else onMinuteChange(0) }) {
                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Up Minute", tint = Color(0xFFCAC4D0))
                        }
                        Text(
                            text = String.format("%02d", minute),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        IconButton(onClick = { if (minute > 0) onMinuteChange(minute - 1) else onMinuteChange(59) }) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Down Minute", tint = Color(0xFFCAC4D0))
                        }
                        Text("Minutes", fontSize = 11.sp, color = Color(0xFFCAC4D0))
                    }

                    // AM/PM Selection Button
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .border(1.dp, primaryColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                            .clickable {
                                // Toggle PM and AM setting instantly
                                if (isPm) {
                                    onHourChange(hour - 12)
                                } else {
                                    onHourChange(hour + 12)
                                }
                            },
                        color = Color(0xFF211F26)
                    ) {
                        Text(
                            text = if (isPm) "PM" else "AM",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryColor,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }

    @Composable
    fun PermissionItemRow(
        title: String,
        description: String,
        isGranted: Boolean,
        icon: ImageVector,
        onGrantClick: () -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(Color(0xFF49454F), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = "",
                    tint = if (isGranted) Color(0xFF00FF00) else Color(0xFFD0BCFF),
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = description,
                    fontSize = 11.sp,
                    color = Color(0xFFCAC4D0),
                    lineHeight = 15.sp
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            if (isGranted) {
                Surface(
                    color = Color(0xFF00FF00).copy(alpha = 0.12f),
                    shape = RoundedCornerShape(100.dp),
                    modifier = Modifier.border(1.dp, Color(0xFF00FF00).copy(alpha = 0.4f), RoundedCornerShape(100.dp))
                ) {
                    Text(
                        text = "Granted",
                        color = Color(0xFF00FF00),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            } else {
                Button(
                    onClick = onGrantClick,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF49454F)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Text(
                        "Grant",
                        color = Color(0xFFD0BCFF),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

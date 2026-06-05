# 🌙 Bedtime Guardian: The Ultimate Device Lockdown Machine 🛡️

[![Platform](https://img.shields.io/badge/Platform-Android-green.svg?style=flat-for-the-badge&logo=android)](https://developer.android.com/)
[![Language](https://img.shields.io/badge/Language-Kotlin-orange.svg?style=flat-for-the-badge&logo=kotlin)](https://kotlinlang.org/)
[![License](https://img.shields.io/badge/License-MIT-blue.svg?style=flat-for-the-badge)](https://opensource.org/licenses/MIT)

Have you or your friend ever promised to go to sleep at 11:00 PM, only to find yourselves staring at an anime cliffhanger or doom-scrolling social media until 2:00 AM? 🌌 

Standard blocker apps are too weak. You can easily bypass them by swiping them away from the recent tasks, clearing the cache, or simply restarting your phone. **Bedtime Guardian is different.** Built with enterprise-grade security protocols, this app transforms the phone into an unbreakable digital vault when bedtime strikes. No loopholes. No escapes. Just pure, unadulterated sleep. 💤

---

## 🧐 What is Bedtime Guardian?

**Bedtime Guardian** is a highly persistent, ultra-optimized digital wellness application for Android. Utilizing advanced enterprise management APIs, it forcefully enforces healthy sleep schedules by locking down the entire device user interface during designated bedtime hours. 

It strips away all digital distractions while safely keeping essential communication channels alive for emergencies.

---

## 🎯 Why Do You Need This App?

* **Defeats the "One More Episode" Monster:** Automatically cuts off access to non-essential apps the exact minute bedtime arrives.
* **Un-Bypassable Protection:** Traditional apps can be closed or cheated against. Bedtime Guardian runs with root-like Device Owner privileges, making it impossible to close via gestures, shortcuts, or reboots.
* **Guilt-Free Communication:** It doesn't isolate you from the world completely. Important family calls, emergency WhatsApp messages, and your morning alarm will still function perfectly.

---

## ⚡ What App Will Do & Key Benefits

| What the App Does | What the User Gets (Benefits) |
| :--- | :--- |
| **Complete Screen Blackout** | Deep physical rest. The pure dark AMOLED interface reduces eye strain and saves 100% display battery power. |
| **Native Kiosk Mode Integration** | The **Home** and **Recents** buttons are completely disabled. The notification shade is frozen shut. |
| **Audio Focus Hijack & App Suspension** | Background audio from YouTube Premium, Spotify, or video players is instantly silenced. Rogue background processes are frozen. |
| **Smart System Whitelisting** | Full incoming and outgoing access to **Offline Voice Calls**, **WhatsApp**, and the **System Alarm Clock**. |
| **0% Daytime Battery Drain** | Deep architectural optimization ensures the app stays completely asleep during daytime hours, consuming zero CPU cycles. |

---

## 🛠️ How It Works (Under the Hood)

Bedtime Guardian achieves its un-killable enforcement state by weaving together four advanced Android framework mechanics:

1. **Device Owner API (Lock Task Mode):** Locks the phone into a secure sandbox environment, physically stripping the operating system's ability to minimize the app.
2. **System Accessibility Service:** Monitors active system window changes in real-time. If an un-whitelisted app attempts to peek forward, it is instantly minimized.
3. **Hardware AlarmManager:** Uses kernel-level system timers to trigger the lockdown window precisely on time, even if the phone is under heavy CPU load (like playing high-framerate video games or streaming).
4. **Telephony & VoIP Call Listeners:** Actively monitors system communication layers to seamlessly drop the lockdown screen during active calls, bringing it back the exact millisecond the call ends.

---

## 📥 Installation & Mandatory Setup Guide

Because Bedtime Guardian requires advanced enterprise-level access to secure your phone, setting it up requires a few simple developer steps. Follow this guide to activate your digital shield:

### Step 1: Install the APK
Build the project in Android Studio, generate your custom APK, and install it on the target device.

### Step 2: Grant Mandatory Device Permissions
Upon launching the app for the very first time, you must manually grant the following requested system permissions:
1. **Display Over Other Apps (System Alert Window):** Allows the black screen overlay to draw on top of other content.
2. **Accessibility Service Activation:** Enables the background real-time app monitor.
3. **Ignore Battery Optimizations:** Protects the background timing engine from being killed by aggressive system power-saving modes.

### Step 3: Select Your Sleep Window (Lock-In Feature)
Choose your desired Bedtime and Wake-up time using the sleek 12-Hour AM/PM TimePicker interface. 
⚠️ **CRITICAL NOTE:** *Once you hit "Save & Activate", the setup menu is securely locked away. You cannot modify or change these hours ever again from the device interface. Choose wisely!*

---

## 🔒 The Ultimate Anti-Cheat Protocol (Activating Superpowers)

To make this app completely un-killable and disable the Home/Recents buttons, you must grant it **Device Owner Status** using a computer. 

1. Connect the phone to your computer via a USB cable.
2. Ensure **USB Debugging** is enabled in the phone's Developer Options.
3. Open a Terminal / Command Prompt on your computer and execute the following `ADB` command:

```bash
adb shell dpm set-device-owner com.yourusername.bedtimeguardian/.BedtimeDeviceAdminReceiver

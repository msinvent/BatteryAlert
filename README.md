# ⚡ Battery Alert — Android App

Blares a loud siren when your battery hits critical thresholds, bypassing Do Not Disturb. If you disable alerts, they automatically resume after 15 minutes.

## Alert Schedule

| Battery Level | Siren Duration |
|---------------|----------------|
| 20%           | 30 seconds     |
| 15%           | 1 minute       |
| 10%           | 1 minute       |

Each alert fires **once per discharge cycle** and resets automatically when you charge above 22%.

---

## Features

- **Loud alarm** using Android's ALARM audio channel — bypasses Do Not Disturb on most devices
- **Vibration** with an intense siren-like pattern
- **Full-screen alert** appears even on the lock screen
- **Puzzle to disable** — solve a maths puzzle to turn alerts off (prevents accidental taps)
- **Auto resume** — alerts automatically turn back on after 15 minutes if manually disabled
- **Boot persistence** — service restarts automatically after device reboot

---

## How DND Bypass Works

The app uses two layers of Do Not Disturb bypass:

1. **`AudioAttributes.USAGE_ALARM` + `FLAG_AUDIBILITY_ENFORCED`** — Android's alarm audio stream bypasses DND on most devices without any special permission.

2. **Notification Policy Access (optional but recommended)** — If you grant DND access in Settings, the app temporarily switches DND to "Alarms only" mode during the alert, then restores your previous setting when done.

3. **`NotificationChannel.setBypassDnd(true)`** — The alert notification channel is configured to bypass DND at the channel level.

---

## Project Structure

```
BatteryAlert/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/batteryalert/app/
│   │   ├── MainActivity.kt          — UI, puzzle disable/resume flow
│   │   ├── BatteryMonitorService.kt — Foreground service, siren logic, DND bypass
│   │   ├── AutoResumeReceiver.kt    — AlarmManager receiver for auto resume after 15 min
│   │   ├── BootReceiver.kt          — Restarts service after reboot
│   │   └── BatteryReceiver.kt       — Legacy stub for older Android versions
│   └── res/
│       ├── layout/activity_main.xml
│       ├── values/colors.xml
│       ├── values/strings.xml
│       ├── values/styles.xml
│       └── drawable/
├── build.gradle
├── settings.gradle
└── gradle.properties
```

---

## Build Instructions

### Requirements
- Android Studio Meerkat (2024.3.1) or newer
- JDK 17
- Android SDK 37
- Kotlin 2.2.20 (configured automatically via Gradle)

### Steps

1. **Open the project in Android Studio:**
   - File → Open → select the `BatteryAlert/` folder

2. **Sync Gradle:**
   - Android Studio will prompt to sync — click **Sync Now**

3. **Build & Install:**
   ```bash
   ./gradlew installDebug
   ```
   Or use **Run ▶** in Android Studio with a connected device/emulator.

---

## First-Time Setup on Device

After installing:

1. Open **Battery Alert**
2. Tap **"Grant DND Access"** → find "Battery Alert" in the list → enable it
3. Tap **"Grant Exact Alarm Access"** → enable it for Battery Alert (required for the 15-minute auto resume)
4. If prompted, grant **Notification permission** (Android 13+)
5. The app will now run silently in the background and survive reboots

### Recommended: Disable Battery Optimization
To prevent Android from killing the background service:

- Settings → Apps → Battery Alert → Battery → **Unrestricted**

---

## Permissions Explained

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | Keep monitoring service alive in background |
| `RECEIVE_BOOT_COMPLETED` | Restart after device reboot |
| `ACCESS_NOTIFICATION_POLICY` | Temporarily disable DND during alarm |
| `SCHEDULE_EXACT_ALARM` | Fire the 15-minute auto resume at the right time |
| `VIBRATE` | Vibrate during alert |
| `WAKE_LOCK` | Keep CPU alive to detect battery events |
| `POST_NOTIFICATIONS` | Show alert notification (Android 13+) |
| `USE_FULL_SCREEN_INTENT` | Show alarm on lock screen |

---

## Troubleshooting

**Alarm doesn't sound through DND:**
- Grant DND access via the in-app button
- Some manufacturers (Xiaomi MIUI, Samsung One UI) have extra battery optimization — disable it for this app

**Service gets killed:**
- Disable battery optimization for the app
- On MIUI: Security → Battery → find the app → No restrictions

**Auto resume doesn't fire after 15 minutes:**
- Grant Exact Alarm access via the in-app button (Android 12+)
- Without this permission the alarm may fire a few minutes late

**Alarm fires multiple times:**
- This shouldn't happen — each threshold fires once per discharge cycle
- If it does, try force-stopping and reopening the app

---

## Credits

Built with assistance from [Claude Sonnet 4.6](https://claude.ai) and the **Android Studio AI Assistant**.

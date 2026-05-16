# ⚡ Battery Siren Alert — Android App

Blares a loud siren when your battery hits critical thresholds, bypassing Do Not Disturb.

## Alert Schedule

| Battery Level | Siren Duration |
|---------------|----------------|
| 20%           | 30 seconds     |
| 15%           | 1 minute       |
| 10%           | 1 minute       |

Each alert fires **once per discharge cycle** and resets automatically when you charge above 22%.

---

## How DND Bypass Works

The app uses **two layers** of Do Not Disturb bypass:

1. **`AudioAttributes.USAGE_ALARM` + `FLAG_AUDIBILITY_ENFORCED`** — Android's alarm audio
   stream bypasses DND silently mode on most devices without any special permission.

2. **Notification Policy Access (optional but recommended)** — If you grant DND access
   in Settings, the app temporarily switches DND to "Alarms only" mode during the alert,
   then restores your previous setting when done.

3. **`NotificationChannel.setBypassDnd(true)`** — The alert notification channel is
   configured to bypass DND at the channel level.

---

## Project Structure

```
BatteryAlert/
├── app/src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/batteryalert/app/
│   │   ├── MainActivity.java          — UI, enable/disable toggle
│   │   ├── BatteryMonitorService.java — Foreground service, siren logic, DND bypass
│   │   ├── BootReceiver.java          — Restarts service after reboot
│   │   └── BatteryReceiver.java       — Legacy stub
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
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android SDK 34

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

1. Open **Battery Siren Alert**
2. Make sure the master switch is **ON**
3. Tap **"Grant DND Access"** → find "Battery Siren Alert" in the list → enable it
4. If prompted, grant **Notification permission** (Android 13+)
5. The app will now run silently in the background and survive reboots

### Recommended: Disable Battery Optimization
To prevent Android from killing the background service:

- Settings → Apps → Battery Siren Alert → Battery → **Unrestricted**

---

## Permissions Explained

| Permission | Why |
|---|---|
| `FOREGROUND_SERVICE` | Keep monitoring service alive in background |
| `RECEIVE_BOOT_COMPLETED` | Restart after device reboot |
| `ACCESS_NOTIFICATION_POLICY` | Temporarily disable DND during alarm |
| `VIBRATE` | Vibrate during alert |
| `WAKE_LOCK` | Keep CPU alive to detect battery events |
| `POST_NOTIFICATIONS` | Show alert notification (Android 13+) |
| `USE_FULL_SCREEN_INTENT` | Show alarm on lock screen |

---

## Troubleshooting

**Alarm doesn't sound through DND:**
- Grant DND access via the in-app button
- Some phone manufacturers (Xiaomi MIUI, Samsung One UI) have extra battery optimization — disable it for this app

**Service gets killed:**
- Disable battery optimization for the app
- On MIUI: Security → Battery → find the app → No restrictions

**Alarm fires multiple times:**
- This shouldn't happen — each threshold fires once per discharge cycle
- If it does, try force-stopping and reopening the app

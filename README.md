# ⚡ Battery Siren — Android App

> Formerly "Battery Alert" — renamed for Play Store distinctiveness (repo and package id unchanged).

Blares a loud siren when your battery hits critical thresholds, bypassing Do Not Disturb. Alerts can be paused for 30 minutes, 1 hour, or 2 hours and resume automatically.

## Alert Schedule

Three thresholds, **configurable in the app** (defaults below). Each must sit at least
**5% below** the previous one, within 5–95%. Siren length per alert: 15s / 30s / 45s / 60s.

| Alert  | Default Level | Default Siren |
|--------|---------------|---------------|
| First  | 20%           | 30 seconds    |
| Second | 15%           | 1 minute      |
| Final  | 10%           | 1 minute      |

Each alert fires **once per discharge cycle** and resets automatically when you charge
above the first threshold + 2% (22% with defaults). Saving new thresholds re-arms all alerts.

---

## Features

- **Loud alarm** using Android's ALARM audio channel — bypasses Do Not Disturb on most devices
- **Vibration** with an intense siren-like pattern
- **Full-screen alert** appears even on the lock screen
- **Pause alerts** — one tap pauses alerts for 30 minutes, 1 hour, or 2 hours; they resume automatically. While paused the whole app turns red with a single circular ENABLE button (header + battery level stay visible)
- **Deep sleep window** — opt-in mute during scheduled hours (23:00–07:00 preset; midnight-crossing supported); a threshold crossed while asleep fires on the first check after the window ends
- **Themes** — four looks (Mint Light, Cream Comfort, Deep Ocean · default, Lavender) via the 🎨 button; semantic green/red state colours stay constant across themes
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
│   │   ├── MainActivity.kt          — UI: pause/resume, threshold + deep sleep editors
│   │   ├── BatteryCheck.kt          — Scheduled battery checks (exact alarms, adaptive interval)
│   │   ├── BatteryCheckReceiver.kt  — Alarm receiver that runs each check
│   │   ├── BatteryAlarmService.kt   — Short-lived siren FGS (shortService), DND bypass while ringing
│   │   ├── BatteryAlarmDecider.kt   — Pure threshold state machine (no Android deps, unit-tested)
│   │   ├── ThresholdConfig.kt       — User thresholds + siren lengths, validation (pure, unit-tested)
│   │   ├── DeepSleepWindow.kt       — Daily mute window incl. midnight wrap (pure, unit-tested)
│   │   ├── AutoResumeReceiver.kt    — AlarmManager receiver for auto resume when a pause ends
│   │   └── BootReceiver.kt          — Restarts service after reboot
│   └── res/
│       ├── layout/activity_main.xml
│       ├── values/colors.xml
│       ├── values/strings.xml
│       ├── values/styles.xml
│       └── drawable/
├── app/src/test/
│   └── java/com/batteryalert/app/
│       └── BatteryAlarmDeciderTest.kt — JVM tests: charge/discharge alarm behaviour
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

## Unit Tests

The alarm's decision logic lives in `BatteryAlarmDecider.kt` — a pure Kotlin class with no
Android dependencies. It owns *when* an alarm should fire; `BatteryAlarmService` owns the
side effects (siren, vibration, notification, DND, ring duration). That split means the
alarm behaviour is testable on the JVM, with **no emulator or device required**.

```bash
./gradlew testDebugUnitTest
```

HTML report: `app/build/reports/tests/testDebugUnitTest/index.html`

The tests emulate a phone charging and discharging by feeding sequences of battery levels
into the decider and asserting which alarms fire:

| Scenario | Expected behaviour |
|---|---|
| Discharge 100% → 21% | No alarm |
| Slow discharge 100% → 1% | 20%, 15%, 10% each fire **once**, in order |
| Crossing 20%, then ticking down | Only the 20% alarm — silent on every tick below |
| Sudden drop 25% → 16% (no tick at 20%) | 20% alarm fires, reported at the level actually seen |
| Drop straight to 8% | Only the 10% alarm — no back-fill for 20%/15% |
| Any level while charging | No alarm |
| Charger plugged in mid-alarm | Alarm silenced immediately |
| Charger plugged in, nothing playing | No-op |
| Charge back above 22% | Thresholds re-arm for the next cycle |
| Brief charge to ≤ 22% | Does **not** re-arm (avoids chirping at the boundary) |
| Monitoring disabled | All alarms suppressed |
| Full charge → discharge cycle | Alarms fire again, once per cycle |

### Why an alarm at 16% is not a false alarm

Each threshold fires at-or-below its level, once per discharge cycle. If the battery drops
past 20% before Android broadcasts an update, the **20% alarm fires the first time it sees
you under 20** — which can be at 16%. The alert message names both the threshold and the
live level to make this obvious:

> 🔋 Battery below 20% (now 16%)

---

## First-Time Setup on Device

After installing:

1. Open **Battery Alert**
2. Tap **"Grant DND Access"** → find "Battery Alert" in the list → enable it
3. Tap **"Grant Exact Alarm Access"** → enable it for Battery Alert (required for battery checks and pause auto-resume)
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
| `SCHEDULE_EXACT_ALARM` | Schedule battery checks and pause auto-resume exactly |
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

**Auto resume doesn't fire after the pause ends:**
- Grant Exact Alarm access via the in-app button (Android 12+)
- Without this permission the alarm may fire a few minutes late

**Alarm fires multiple times:**
- This shouldn't happen — each threshold fires once per discharge cycle
- If it does, try force-stopping and reopening the app

---

## History Notes

- **`96baae5` (2026-07-25)** — removed the maths-puzzle "solve to disable" gate, replaced by
  the 1h/2h PAUSE buttons. If the puzzle friction is ever wanted back, recover it from this
  commit's parent: `git show 96baae5^ -- app/src/main/java/com/batteryalert/app/MainActivity.kt`.

---

## Credits

Built with assistance from [Claude](https://claude.ai) and the **Android Studio AI Assistant**.

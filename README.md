# ClassPulse — Classroom Presence via BLE + Accelerometer

> **Physics note (important):** An accelerometer alone **cannot** measure distance to another person.
> It only measures *your own* motion. This app does what you actually asked for by fusing two sensors:
> - **BLE RSSI → distance estimate** (who is within ~1 m / in-room / gone) — this is the ranging sensor.
> - **Accelerometer → step / moving detection** (stabilises distance + detects "walked out").
>
> All phones advertise + scan over Bluetooth LE, build a shared roster for a Room Code,
> show live headcount, and flag `OUT OF BOUNDS` when a device disappears or walks away.

## How it works

```
Each phone (Room "A3F9K", user "Mike"):
  BLE ADVERTISE:  Service UUID 0000C1A5-… + ServiceData(roomHash + userHash + steps)
  BLE SCAN:       listens for same Service UUID
  RSSI ──► DistanceEstimator (log-distance path loss) ──► NEAR (≤1m) / IN_ROOM (≤8m) / EDGE / OUT (>15s unseen)
  ACCEL ──► StepDetector (peak detection) ──► moving? ──► gates distance + "walked out" flag
  ROSTER:   { self + all peers seen } ──► headcount + out-of-bounds list
```

Distance formula (standard indoor log-distance model):

```
d = 10 ^ ((TxPower - RSSI) / (10 * n)),  TxPower = -59 dBm @1m, n = 2.0
```

Smoothed with exponential moving average (α = 0.35) + frozen when both devices still
(accelerometer variance low) to stop jitter.

## Get the APK (3 options)

### Option A — Cloud build, no installs (recommended)
1. Create a GitHub repo, upload this whole `ClassPulse` folder.
2. Go to **Actions → build-apk → Run** (runs automatically on push too).
3. Download the `ClassPulse-debug-apk` artifact → `app-debug.apk` → install on phones.

### Option B — Android Studio (on your PC)
1. Install Android Studio (includes JDK + SDK).
2. Open the `ClassPulse` folder → let Gradle sync.
3. `Run ▶` on a physical phone (BLE doesn't work on emulators). Or `Build → Build APK(s)`.

### Option C — Command line (auto-installs SDK)
```bat
build-apk.bat
```
Requires: JDK 17 installed (`winget install Microsoft.OpenJDK.17`). The script downloads
Android cmdline-tools, platform-34 + build-tools, then runs `gradlew assembleDebug`.
APK lands in `app\build\outputs\apk\debug\app-debug.apk`.

## Using the app
1. Install the same APK on every student's phone.
2. Grant **Bluetooth + Location** permissions (Android requires Location for BLE scan).
   Turn Bluetooth + Location ON.
3. One phone taps **Create** (generates Room Code e.g. `A3F9K`). Others type it + tap **Join**.
4. Tap **Start tracking**. You see:
   - **IN ROOM: N** (self + peers recently seen)
   - **NEAR (≤1m): M** — people right next to you
   - List: name, distance (m), status chip, steps, last-seen
   - **OUT OF BOUNDS** red flag: not seen for >15 s, or estimated >8 m while moving.
5. **Simulate** toggle: demos a fake 5-person classroom when you only have 1 phone.

## Files
```
ClassPulse/
  settings.gradle  root build.gradle  gradle.properties
  gradlew.bat  gradle/wrapper/gradle-wrapper.properties
  build-apk.bat
  app/build.gradle
  app/src/main/AndroidManifest.xml
  app/src/main/java/com/classpulse/app/MainActivity.kt
  app/src/main/java/com/classpulse/app/DistanceEstimator.kt
  app/src/main/java/com/classpulse/app/StepDetector.kt
  app/src/main/java/com/classpulse/app/ClassRoomBleManager.kt
  app/src/main/res/…
  .github/workflows/build-apk.yml
web-demo/index.html   ← try the same algorithm in your browser right now
tests/distance_test.js ← algorithm sanity checks (run: node tests/distance_test.js)
```

## Tuning
- `DistanceEstimator.TX_POWER` / `PATH_LOSS_N`: calibrate per classroom (concrete walls → n≈2.5–3).
- `NEAR_RADIUS_M = 1.0`, `ROOM_RADIUS_M = 8.0`, `OUT_TIMEOUT_MS = 15_000`.
- `StepDetector`: peak threshold / min interval in `StepDetector.kt`.

## Limitations (honest)
- BLE RSSI ranging is ±1–2 m indoors (walls, bodies, pockets). Good for headcount + near/far,
  not tape-measure accuracy. For cm accuracy you'd need UWB (Pixel/Samsung Nearby / DW1000).
- All phones must run the app in foreground (background BLE scan is throttled on Android 8+).
- iPhones can't join this APK build (need a separate iOS target — same algorithm ports directly).

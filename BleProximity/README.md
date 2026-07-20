# BLE Proximity

An Android app for the Pixel 8 Pro (and any Android 12+ phone) that uses the
phone's Bluetooth radio to detect BLE advertisements and estimate how close you
are to each device from its RSSI (signal strength).

## What it does

- **Live scan** of all nearby BLE broadcasters, sorted strongest-signal-first.
- **Search / filter** by device name or address — built for environments with
  100+ devices in range at once.
- **Distance estimate** in meters from RSSI, with a calibration panel
  (RSSI-at-1m and path-loss exponent) so you can tune it to your environment.
- **Track one device**: tap the target icon on any row for a big proximity meter
  that grows as you get closer — walk around and home in on it.
- **Raw advertising data**: tap a row to expand device name, address, TX power,
  service UUIDs, and manufacturer-specific data.

RSSI smoothing (an exponential moving average) is applied so the numbers don't
jump around wildly.

## How distance is estimated

```
distance(m) = 10 ^ ((RSSI_at_1m - RSSI) / (10 * pathLoss))
```

RSSI-to-distance is inherently noisy — walls, bodies, orientation, and radio
chip differences all affect it. Treat the meters value as a *relative* guide
(closer vs. farther), not a precise measurement. Calibrate for best results:
place the phone ~1 m from a device and adjust **RSSI at 1 m** until the estimate
reads about 1 m.

---

## Getting the APK

You have two options. **Option A (cloud build)** needs no Android Studio.
**Option B** builds locally in Android Studio.

### Option A — Build in the cloud with GitHub Actions (no Mac setup)

1. Create a free account at https://github.com and a new **private** repository
   (e.g. `ble-proximity`).
2. Upload this whole `BleProximity` folder to the repo. Easiest way on a Mac:
   - Install GitHub Desktop (https://desktop.github.com), or
   - Use the web UI: on the repo page, "Add file" → "Upload files" and drag the
     contents in. Make sure the `.github/workflows/build.yml` file is included
     (it may be hidden — enable "show hidden files" with `Cmd+Shift+.` in Finder).
3. The push automatically triggers the **Build APK** workflow. Open the
   **Actions** tab in your repo and watch it run (~3-5 min).
4. When it finishes (green check), click the run, scroll to **Artifacts**, and
   download **`ble-proximity-debug-apk`**. Unzip it to get `app-debug.apk`.
5. Transfer `app-debug.apk` to your phone (email it to yourself, Google Drive,
   or USB) and open it to install — see **Installing** below.

You can re-run the build anytime from the Actions tab ("Run workflow").

### Option B — Build locally in Android Studio (works great on Mac, incl. M-series)

1. Download Android Studio (free): https://developer.android.com/studio and
   install it. On first launch it downloads the Android SDK automatically.
2. **File → Open** and select this `BleProximity` folder. Let Gradle sync
   finish (it will download Gradle 8.9 and dependencies on first run).
3. Build the APK: **Build → Build Bundle(s) / APK(s) → Build APK(s)**. When it's
   done, click **locate** in the notification to find `app-debug.apk` under
   `app/build/outputs/apk/debug/`.
   - Or, with your phone plugged in and USB debugging on, just press **Run** (▶)
     to install and launch it directly.

---

## Installing the APK on your Pixel 8 Pro

Since this is a debug APK (not from the Play Store), allow installs from your
transfer app:

1. Copy `app-debug.apk` to the phone.
2. Open it with the Files app. Android will prompt about "unknown apps" — tap
   **Settings** and enable **Allow from this source** for whichever app you're
   installing from, then go back and tap **Install**.
3. Launch **BLE Proximity**. On first run it asks for the **Nearby devices**
   permission — tap **Allow**. (No location permission is requested.)
4. Make sure Bluetooth is on, then press **Scan**.

## Notes & customization

- **Package / app id**: `com.example.bleproximity`. Change it in
  `app/build.gradle.kts` (`applicationId` + `namespace`) and the folder/package
  in `app/src/main/java/...` if you want your own id.
- **Min Android version**: 12 (API 31). The Pixel 8 Pro ships with 14+, so
  you're well covered. This lets the app use the modern `BLUETOOTH_SCAN`
  permission and skip location access entirely.
- **Stale timeout**: devices not heard from for 12 s drop off the list. Tune
  `staleMillis` in `BleScanViewModel.kt`.
- **Scan aggressiveness**: uses `SCAN_MODE_LOW_LATENCY` for the fastest updates
  (higher battery use). Change it in `BleScanViewModel.start()`.

## Project layout

```
BleProximity/
├─ settings.gradle.kts
├─ build.gradle.kts            (plugin versions)
├─ gradle.properties
├─ gradle/wrapper/gradle-wrapper.properties
├─ .github/workflows/build.yml (cloud APK build)
└─ app/
   ├─ build.gradle.kts
   └─ src/main/
      ├─ AndroidManifest.xml   (BLE permissions)
      ├─ java/com/example/bleproximity/
      │  ├─ MainActivity.kt         (Compose UI: list, search, tracker, calibration)
      │  ├─ BleScanViewModel.kt     (BluetoothLeScanner + RSSI smoothing + filtering)
      │  └─ BleModels.kt            (data classes + distance math)
      └─ res/                       (theme, strings, launcher icon)
```

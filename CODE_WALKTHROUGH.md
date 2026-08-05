# Pixel Proximity — Code Walkthrough

A function-by-function explanation of every file, plus the end-to-end data flow and
a focused look at the cloud auth/register/resolve sequence (the part still being
worked out).

## The big picture

The app does four things:

1. **Scan BLE directly** for Wiliot Pixel advertisements (`RawBleProbe`).
2. For each pixel packet, **resolve its rotating/encrypted payload into a stable
   Pixel ID** by calling the Wiliot cloud REST API (`WiliotRestClient`).
3. **Group results by Pixel ID**, track a smoothed RSSI per pixel, convert RSSI to
   an approximate distance — all **on-device** (`PixelScanViewModel`, `Proximity`).
4. **Show** a searchable, signal-sorted list and a single-pixel proximity meter
   (`MainActivity` Compose UI).

The Wiliot **SDK is no longer used** for data. `WiliotController.kt` and
`LocationManagerImpl.kt` are leftover SDK stubs (see notes at the end).

Data flow, one line:

```
RawBleProbe (radio) → onPixel(hex,mac,rssi) → queue → 3 worker coroutines
   → WiliotRestClient.resolve(hex) → externalId → stats[externalId] (EMA RSSI)
   → rebuild() → rows (sorted/filtered) → Compose UI
```

---

## WiliotRestClient.kt  — the cloud calls (most important file to review)

Handles all HTTP to `https://api.us-central1.wmt-prod.gcp.wiliot.cloud`.

**Fields**
- `token` — the API-key access token (from `/v1/auth/token/api`). Used to *register* the gateway.
- `gatewayToken` — the token returned *by* gateway registration. Intended to authorize `/resolve`.
- `registered` — set true once registration succeeds.
- `loggedError` — so a failing resolve is logged once, not per packet.

**`authenticate()`** — `POST /v1/auth/token/api` with header `Authorization: <apiKey>`
(the raw key, **no** "Bearer"), empty body. Parses `{"access_token": ...}`, stores it
in `token`, returns it. Throws on non-2xx. This step has always worked ("auth ok").

**`currentToken()`** — returns the cached `token`, or calls `authenticate()` if null.

**`ensureRegistered(tk)`** — runs once (guarded by `registered`). `POST /v1/owner/{ownerId}/gateway/{gatewayId}/mobile`
with `Authorization: Bearer <tk>` and body `{"gatewayType":"android"}`. On success it
reads the **gateway token** from `data.access_token` in the response and stores it in
`gatewayToken`, then sets `registered = true`. On failure it logs the code/body and
leaves `registered = false` (so a later call can retry). *This is the step that started
returning HTTP 200 once the correct numeric owner ID (`339798146063`) was used.*

**`resolve(rawServiceDataHex)`** — the core call. Steps:
1. Build `payload = "affd" + hex.lowercase()` (the SDK prepends the `affd` type prefix to the raw 0xFDAF service data).
2. Derive `tagId = payload.substring(18,30)` (the pixel's on-air endpoint id slice).
3. Build the request body:
   ```json
   {"gatewayId": "...", "gatewayType": "android", "timestamp": <ms>,
    "packets": [ {"tagId": "...", "payload": "affd...", "sequenceId": 0, "timestamp": <ms>} ]}
   ```
4. `ensureRegistered(currentToken())` — make sure we've registered and have `gatewayToken`.
5. `doResolve(body, gatewayToken ?: currentToken())` — **currently sends the gateway token**
   (falls back to the API token only if registration returned none).
6. On `401`, wipe tokens + `registered` and retry once. On `400`, treat as "unresolved" (return null).
   On other non-2xx (e.g. 403), log once and return null.
7. On success, read `data[0].externalId` — the resolved **Pixel ID** — and return it.

**`doResolve(body, tk)`** — helper that actually fires `POST /v1/owner/{ownerId}/resolve`
with `Authorization: Bearer <tk>`.

**Companion** — `GCP_WMT_PROD_API` base URL.

> **Where to look for the missing piece:** it's almost certainly in this file's
> auth/token choice for `/resolve`. Right now `resolve()` uses the **gateway token**
> from registration. If Wiliot's `/resolve` actually wants the original **API-key
> token** (or a *third* token, or the gateway token used a specific way), that's the
> mismatch. The two knobs to try are: (a) which token goes in `doResolve` —
> `gatewayToken` vs `currentToken()`; and (b) whether `/resolve` needs the same
> `gatewayId` that was registered (it does — both use the `gatewayId` field). The
> body shape and headers match the SDK; the open question is purely which bearer
> token `/resolve` accepts.

---

## RawBleProbe.kt  — the radio (this part fully works)

A thin wrapper over Android's `BluetoothLeScanner`. Independent of Wiliot code.

**Fields** — `adapter`/`scanner` (BLE), `seen`/`wiliotSeen`/`bridgeSeen` (dedupe sets),
`total`/`wiliot` (StateFlow counts shown in the header), `onPixel` (callback).

**`callback.onScanResult(...)`** — for each advertisement: record the MAC; check the
advertised service data/UUIDs for the Wiliot Pixel UUID `0xFDAF` and Bridge UUID
`0xFCC6`. If it's a pixel, bump the count and invoke `onPixel(hex, mac, rssi)` where
`hex` is the raw 0xFDAF service-data bytes as a hex string.

**`start()`** — clears counters and starts a `SCAN_MODE_LOW_LATENCY` scan with no
filters. `stop()` — stops the scan and logs totals. **`ByteArray.toHex()`** — hex helper.

**Companion** — the two 16-bit UUIDs expanded to full 128-bit form.

> Confirmed working: this reliably reports ~60–78 pixels and streams every payload.

---

## PixelScanViewModel.kt  — orchestration + on-device RSSI/distance

`AndroidViewModel` that ties the probe to the REST client and exposes UI state.

**Exposed state (StateFlows)** — `rows` (the displayed list), `scanning`, `query`
(search text), `trackedId` (pixel being tracked), `calibration`, `resolvedCount`,
plus `rawTotal`/`rawWiliot` passed through from the probe.

**Internal state** — `stats: HashMap<externalId, Stat>` where `Stat(rssiRaw, ema, lastSeen)`;
`recentPayloads` (dedupe cache); a `queue: Channel<Obs>` (bounded, drops oldest);
`workers` (the coroutine jobs). `Obs(payload, mac, rssi)` is one captured packet.

**`setQuery` / `setTracked` / `setCalibration`** — update the corresponding state and
call `rebuild()` where the displayed list depends on it.

**`start()`** — guard against double-start; build a `WiliotRestClient` from saved
credentials; wire `rawProbe.onPixel` to push `Obs` into the queue; start the probe;
launch **3 worker coroutines** running `resolverLoop()` on `Dispatchers.IO`.

**`stop()`** — detach the callback, stop the probe, cancel workers.

**`resolverLoop()`** — each worker loops over the channel:
1. **Dedupe**: skip payloads resolved within `dedupeTtlMs` (4 s) — pixels repeat the
   same packet several times, so this cuts redundant cloud calls.
2. Call `client.resolve(payload)`.
3. If it returns an `externalId`, update `stats[externalId]`: set `rssiRaw`, update the
   **exponential moving average** `ema` toward the new RSSI (`smoothing = 0.4`), set
   `lastSeen`. Then `rebuild()`.
4. `pruneRecent()` trims the dedupe cache if it grows past 3000 entries.

**`rebuild()`** — under a lock: drop pixels not heard from in `staleMs` (30 s); map
`stats` into `PixelRow`s (computing `meters` via the calibration); set `resolvedCount`;
then filter by the search query and sort by smoothed RSSI (closest first) into `rows`.

**`onCleared()`** — stops everything when the ViewModel is destroyed.

> If resolves ever start succeeding, this is where the list magically fills in — no
> other change needed. Today `stats` stays empty only because `resolve()` returns null.

---

## Proximity.kt  — the math + row model

**`Calibration(rssiAt1m = -59, pathLoss = 2.5)`** — `estimateMeters(rssi)` implements
`distance = 10^((rssiAt1m - rssi) / (10 * pathLoss))`. Returns -1 for rssi ≥ 0 (invalid).

**`Proximity`** enum (`IMMEDIATE/NEAR/MID/FAR/UNKNOWN`) with `fromMeters(m)` bucketing.

**`PixelRow(key, pixelId, resolved, rssiRaw, rssiSmoothed, meters, lastSeen)`** — the
immutable row the UI renders. `key`/`pixelId` are the resolved external Pixel ID.

---

## CredentialStore.kt  — persisted settings

Wraps app-private `SharedPreferences`.

- `ownerId`, `apiKey`, `gatewayId` — read/write properties.
- `defaultGw` — computed once: the device's `Settings.Secure.ANDROID_ID` uppercased
  (this is exactly the gateway id the SDK uses). Used as the default gateway id.
- `hasCredentials` — true when owner + key are both non-blank.
- `save(owner, key, gateway)` — trims and stores all three (blank gateway → `defaultGw`).
- `clear()` — wipes everything (used by the logout icon).

---

## MainActivity.kt  — the Compose UI

**`MainActivity.onCreate`** — sets a dark Material theme and renders `AppRoot()`.

**`REQUIRED_PERMISSIONS`** — `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`,
`ACCESS_FINE_LOCATION`, and (API 33+) `POST_NOTIFICATIONS`.

**`AppRoot()`** — top-level router:
- If no credentials → `CredentialsScreen`.
- Else if permissions not granted → request them / `PermissionGate`.
- Else → `TrackerScreen` if a pixel is being tracked, otherwise `ScanScreen`.

**`ScanScreen(vm, onSignOut)`** — the main screen:
- Collects `rows`, `scanning`, `query`, `calibration`, `resolvedCount`, `rawTotal`,
  `rawWiliot`.
- Top bar: bug-report icon (shows the last saved crash), calibration toggle, logout.
- FAB: toggles `vm.start()` / `vm.stop()`.
- Body: search field; a header line `Pixels seen N • resolved M` plus `Raw radio • all
  BLE N`; optional calibration card; then the `LazyColumn` of `PixelRowItem`s (or an
  empty-state message).

**`PixelRowItem(row, calib, onTrack)`** — one list row: a colored proximity dot, the
Pixel ID + resolved/resolving label, the estimated distance, raw RSSI, and a track icon.

**`TrackerScreen(vm)`** — finds the tracked pixel in `rows`, shows a big colored
proximity meter with the distance and bucket label, plus raw/smoothed RSSI.

**`CredentialsScreen(...)`** — three fields (Owner ID, API key, Gateway ID) + save.

**`CalibrationCard(...)`** — two sliders (RSSI-at-1m, path-loss) feeding `setCalibration`.

**`PermissionGate(...)`** — explanation + "Grant permissions" button.

**`formatMeters` / `proximityColor`** — small formatting/color helpers.

---

## App.kt / CrashLog.kt

**`App`** — the `Application`. Just stores a static `instance` and installs `CrashLog`.
(No SDK init anymore.)

**`CrashLog`** — `install()` sets a default uncaught-exception handler that writes the
stack trace to `filesDir/last_crash.txt` (then delegates to the previous handler).
`read()`/`clear()` back the in-app crash viewer.

---

## Leftover / unused

- **`WiliotController.kt`** — emptied out (was the SDK gateway wiring). Not used.
- **`LocationManagerImpl.kt`** — an SDK `LocationManagerContract` stub. Not used by the
  REST path; it only still compiles because the SDK dependency is still on the
  classpath. Both can be deleted along with the Wiliot SDK dependencies in
  `app/build.gradle.kts` if you want to fully drop the SDK.

---

## The cloud sequence, exactly (for your review)

All three calls go to `apiBase = https://api.us-central1.wmt-prod.gcp.wiliot.cloud`.

```
1) AUTH        POST /v1/auth/token/api
   headers:    Authorization: <API_KEY>          (raw key, NO "Bearer")
               Content-Type: application/json
   body:       (empty)
   response:   {"access_token": "<JWT-A>", ...}   ✅ works

2) REGISTER    POST /v1/owner/{ownerId}/gateway/{gatewayId}/mobile
   headers:    Authorization: Bearer <JWT-A>
               Content-Type: application/json
   body:       {"gatewayType":"android"}
   response:   {"data":{"access_token":"<JWT-B>", ...}}   ✅ 200 with owner 339798146063
               (JWT-B is the "gateway token")

3) RESOLVE     POST /v1/owner/{ownerId}/resolve
   headers:    Authorization: Bearer <JWT-B>      ← app currently uses the gateway token
               Content-Type: application/json
   body:       {"gatewayId":"{gatewayId}","gatewayType":"android","timestamp":<ms>,
                "packets":[{"tagId":"...","payload":"affd<hex>","sequenceId":0,"timestamp":<ms>}]}
   response:   {"data":[{"externalId":"<PIXEL_ID>", "asset":{...}}], "message":"..."}
```

**Known-good so far:** (1) auth and (2) registration both succeed with owner
`339798146063`.

**The open question is only step (3):** which bearer token `/resolve` accepts. The
code is set to use **JWT-B (the gateway token)**. If Wiliot expects **JWT-A (the API
token)** for resolve, change `doResolve(bodyJson, gatewayToken ?: currentToken())`
back to `doResolve(bodyJson, currentToken())` in `WiliotRestClient.resolve()`. Those
are the only two realistic options, and both are one-line changes.

Everything upstream of that (scanning, payload format `affd…`, gatewayId, owner,
registration) is verified against the Wiliot SDK's own behavior.

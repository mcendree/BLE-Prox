package com.example.pixelproximity

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.wiliot.wiliotcore.model.PacketData
import com.wiliot.wiliotresolvedata.WiliotDataResolver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Bridges the Wiliot SDK data Flows into a simple, searchable, RSSI-sorted list.
 *
 * Two SDK Flows are merged:
 *   - WiliotDataResolver.beaconsFlow()     -> List<PacketData>  (has local RSSI)
 *   - WiliotDataResolver.resolveInfoFlow() -> List<IResolveInfo> (resolved Pixel IDs)
 *
 * Both underlying objects are PacketData, so we join them on the local BLE MAC
 * (PacketData.deviceMAC). RSSI + distance are computed purely on-device.
 */
class PixelScanViewModel(app: Application) : AndroidViewModel(app) {

    private val creds = CredentialStore(app)

    private val _rows = MutableStateFlow<List<PixelRow>>(emptyList())
    val rows: StateFlow<List<PixelRow>> = _rows.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _trackedId = MutableStateFlow<String?>(null)
    val trackedId: StateFlow<String?> = _trackedId.asStateFlow()

    private val _calibration = MutableStateFlow(Calibration())
    val calibration: StateFlow<Calibration> = _calibration.asStateFlow()

    private val ema = HashMap<String, Double>()   // key(MAC) -> smoothed RSSI
    private val smoothing = 0.35
    private var collectJob: Job? = null

    // Keep the last merged snapshot so re-filtering (search) is instant.
    private var lastSnapshot: List<PixelRow> = emptyList()

    fun setQuery(q: String) { _query.value = q; publish() }
    fun setTracked(id: String?) { _trackedId.value = id }
    fun setCalibration(c: Calibration) { _calibration.value = c; publish() }

    fun start() {
        if (_scanning.value) return
        WiliotController.ensureStarted(getApplication(), creds.ownerId, creds.apiKey)
        _scanning.value = true

        collectJob = viewModelScope.launch {
            combine(
                WiliotDataResolver.beaconsFlow(),
                WiliotDataResolver.resolveInfoFlow()
            ) { beacons, resolved ->
                mergeSnapshot(beacons, resolved)
            }.collect { snapshot ->
                lastSnapshot = snapshot
                publish()
            }
        }
    }

    fun stop() {
        if (!_scanning.value) return
        collectJob?.cancel(); collectJob = null
        WiliotController.stop()
        _scanning.value = false
    }

    private fun mergeSnapshot(
        beacons: List<PacketData>,
        resolved: List<Any?>
    ): List<PixelRow> {
        // Resolved external IDs, keyed by the local MAC.
        val resolvedIdByMac = HashMap<String, String>()
        for (item in resolved) {
            val pd = item as? PacketData ?: continue
            resolvedIdByMac[pd.deviceMAC] = pd.name
        }

        val now = System.currentTimeMillis()
        return beacons.mapNotNull { pd ->
            val mac = pd.deviceMAC
            val rssi = pd.rssi ?: return@mapNotNull null

            val prev = ema[mac]
            val smoothed = if (prev == null) rssi.toDouble()
            else prev + smoothing * (rssi - prev)
            ema[mac] = smoothed

            val resolvedId = resolvedIdByMac[mac]
            val id = resolvedId ?: pd.name   // pd.name = raw endpoint id until resolved

            PixelRow(
                key = mac,
                pixelId = id,
                resolved = resolvedId != null,
                rssiRaw = rssi,
                rssiSmoothed = smoothed,
                meters = _calibration.value.estimateMeters(smoothed),
                lastSeen = now
            )
        }
    }

    private fun publish() {
        val q = _query.value.trim().lowercase()
        _rows.value = lastSnapshot
            .asSequence()
            .filter { if (q.isEmpty()) true else it.pixelId.lowercase().contains(q) }
            .sortedByDescending { it.rssiSmoothed }
            .toList()
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}

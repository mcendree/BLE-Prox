package com.example.pixelproximity

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * New data path (no SDK gateway):
 *   RawBleProbe  --(payload,mac,rssi)-->  resolve queue  --workers-->  Wiliot REST /resolve
 *   resolved externalId + local RSSI  -->  grouped rows (searchable, sortable, trackable)
 */
class PixelScanViewModel(app: Application) : AndroidViewModel(app) {

    private val TAG = "PixelVM"
    private val creds = CredentialStore(app)
    private val rawProbe = RawBleProbe(app)
    private var client: WiliotRestClient? = null

    // Raw radio diagnostics (still useful in the header).
    val rawTotal: StateFlow<Int> = rawProbe.total
    val rawWiliot: StateFlow<Int> = rawProbe.wiliot

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

    private val _resolvedCount = MutableStateFlow(0)
    val resolvedCount: StateFlow<Int> = _resolvedCount.asStateFlow()

    // externalId -> live stats (guarded by `lock`)
    private data class Stat(var rssiRaw: Int, var ema: Double, var lastSeen: Long)
    private val lock = Any()
    private val stats = HashMap<String, Stat>()

    // payload -> last-resolved timestamp, to avoid re-resolving identical repeats
    private val recentPayloads = HashMap<String, Long>()

    private data class Obs(val payload: String, val mac: String, val rssi: Int)
    private val queue = Channel<Obs>(capacity = 128, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val workers = mutableListOf<Job>()
    private val smoothing = 0.4
    private val staleMs = 30_000L
    private val dedupeTtlMs = 4_000L

    fun setQuery(q: String) { _query.value = q; rebuild() }
    fun setTracked(id: String?) { _trackedId.value = id }
    fun setCalibration(c: Calibration) { _calibration.value = c; rebuild() }

    fun start() {
        if (_scanning.value) return
        if (!creds.hasCredentials) { Log.e(TAG, "no credentials"); return }
        client = WiliotRestClient(
            apiBase = WiliotRestClient.GCP_WMT_PROD_API,
            ownerId = creds.ownerId,
            apiKey = creds.apiKey,
            gatewayId = creds.gatewayId
        )
        _scanning.value = true
        Log.i(TAG, "start(): scanning + resolving")

        rawProbe.onPixel = { hex, mac, rssi -> queue.trySend(Obs(hex, mac, rssi)) }
        rawProbe.start()

        // A few workers pull from the queue and resolve payloads via REST.
        repeat(3) { workers += viewModelScope.launch(Dispatchers.IO) { resolverLoop() } }
    }

    fun stop() {
        if (!_scanning.value) return
        rawProbe.onPixel = null
        rawProbe.stop()
        workers.forEach { it.cancel() }
        workers.clear()
        _scanning.value = false
    }

    private suspend fun resolverLoop() {
        for (obs in queue) {
            val now = System.currentTimeMillis()
            // Dedupe identical payloads seen within the TTL.
            val skip = synchronized(recentPayloads) {
                val last = recentPayloads[obs.payload]
                if (last != null && now - last < dedupeTtlMs) true
                else { recentPayloads[obs.payload] = now; false }
            }
            if (skip) continue

            val ext = try { client?.resolve(obs.payload) } catch (t: Throwable) {
                Log.w(TAG, "resolve error: ${t.message}"); null
            }
            if (ext != null) {
                synchronized(lock) {
                    val s = stats[ext]
                    if (s == null) stats[ext] = Stat(obs.rssi, obs.rssi.toDouble(), now)
                    else {
                        s.rssiRaw = obs.rssi
                        s.ema += smoothing * (obs.rssi - s.ema)
                        s.lastSeen = now
                    }
                }
                rebuild()
            }
            pruneRecent(now)
        }
    }

    private fun pruneRecent(now: Long) {
        if (recentPayloads.size < 3000) return
        synchronized(recentPayloads) {
            val it = recentPayloads.entries.iterator()
            while (it.hasNext()) if (now - it.next().value > dedupeTtlMs) it.remove()
        }
    }

    private fun rebuild() {
        val now = System.currentTimeMillis()
        val calib = _calibration.value
        val q = _query.value.trim().lowercase()

        val list: List<PixelRow>
        synchronized(lock) {
            // drop pixels not heard from recently
            val gone = stats.filterValues { now - it.lastSeen > staleMs }.keys
            gone.forEach { stats.remove(it) }

            list = stats.entries.map { (id, s) ->
                PixelRow(
                    key = id,
                    pixelId = id,
                    resolved = true,
                    rssiRaw = s.rssiRaw,
                    rssiSmoothed = s.ema,
                    meters = calib.estimateMeters(s.ema),
                    lastSeen = s.lastSeen
                )
            }
        }
        _resolvedCount.value = list.size
        _rows.value = list
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

package com.example.bleproximity

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Owns the BLE scan and exposes an observable, de-duplicated, sorted list of
 * nearby devices. Designed to stay responsive with 100+ devices in range.
 */
class BleScanViewModel(app: Application) : AndroidViewModel(app) {

    private val adapter: BluetoothAdapter? =
        (app.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null

    // Raw store keyed by address; concurrent because scan callbacks fire off-thread.
    private val store = ConcurrentHashMap<String, BleDevice>()

    private val _devices = MutableStateFlow<List<BleDevice>>(emptyList())
    val devices: StateFlow<List<BleDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _trackedAddress = MutableStateFlow<String?>(null)
    val trackedAddress: StateFlow<String?> = _trackedAddress.asStateFlow()

    private val _calibration = MutableStateFlow(Calibration())
    val calibration: StateFlow<Calibration> = _calibration.asStateFlow()

    // Bluetooth may be off even if hardware exists.
    val bluetoothEnabled: Boolean get() = adapter?.isEnabled == true

    private var refreshJob: Job? = null

    private val smoothing = 0.35 // EMA weight for the newest reading (0..1)
    private val staleMillis = 12_000L

    fun setQuery(q: String) { _query.value = q; rebuild() }
    fun setTracked(address: String?) { _trackedAddress.value = address }
    fun setCalibration(c: Calibration) { _calibration.value = c }

    @SuppressLint("MissingPermission") // Permissions are checked in the UI before start is called.
    fun start() {
        if (_scanning.value) return
        scanner = adapter?.bluetoothLeScanner ?: return

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setReportDelay(0L)
            .build()

        scanner?.startScan(null, settings, scanCallback)
        _scanning.value = true

        // Periodically rebuild the sorted list and drop devices we haven't heard
        // from recently, so the UI stays current.
        refreshJob = viewModelScope.launch {
            while (true) {
                pruneStale()
                rebuild()
                delay(1_000)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!_scanning.value) return
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) { /* adapter may have been turned off */ }
        _scanning.value = false
        refreshJob?.cancel()
        refreshJob = null
    }

    fun clear() {
        store.clear()
        rebuild()
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            ingest(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { ingest(it) }
        }

        override fun onScanFailed(errorCode: Int) {
            _scanning.value = false
        }
    }

    @SuppressLint("MissingPermission")
    private fun ingest(result: ScanResult) {
        val address = result.device.address ?: return
        val record = result.scanRecord
        val now = System.currentTimeMillis()
        val rssi = result.rssi

        val existing = store[address]
        val smoothed = if (existing == null) rssi.toDouble()
        else existing.rssiSmoothed + smoothing * (rssi - existing.rssiSmoothed)

        val name = record?.deviceName ?: existing?.name
        val serviceUuids = record?.serviceUuids?.map { it.uuid.toString() } ?: emptyList()

        val mfg = record?.manufacturerSpecificData
        val mfgHex = if (mfg != null && mfg.size() > 0) {
            val id = mfg.keyAt(0)
            val bytes = mfg.valueAt(0)
            "0x%04X: %s".format(id, bytes.toHex())
        } else existing?.manufacturerData

        val tx = if (result.txPower != ScanResult.TX_POWER_NOT_PRESENT) result.txPower
        else existing?.txPower

        store[address] = BleDevice(
            address = address,
            name = name,
            rssiRaw = rssi,
            rssiSmoothed = smoothed,
            txPower = tx,
            serviceUuids = if (serviceUuids.isNotEmpty()) serviceUuids
            else existing?.serviceUuids ?: emptyList(),
            manufacturerData = mfgHex,
            lastSeen = now
        )
    }

    private fun pruneStale() {
        val cutoff = System.currentTimeMillis() - staleMillis
        val gone = store.filterValues { it.lastSeen < cutoff }.keys
        gone.forEach { store.remove(it) }
    }

    private fun rebuild() {
        val q = _query.value.trim().lowercase()
        val list = store.values
            .asSequence()
            .filter { d ->
                if (q.isEmpty()) true
                else (d.name?.lowercase()?.contains(q) == true) ||
                        d.address.lowercase().contains(q)
            }
            .sortedByDescending { it.rssiSmoothed } // strongest signal (closest) first
            .toList()
        _devices.value = list
    }

    override fun onCleared() {
        super.onCleared()
        stop()
    }
}

private fun ByteArray.toHex(): String =
    joinToString(" ") { "%02X".format(it) }

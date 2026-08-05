package com.example.pixelproximity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Independent, raw BLE scanner used purely as a diagnostic. It does NOT go
 * through the Wiliot SDK — it uses Android's BluetoothLeScanner directly (the
 * same approach as the first app) and counts:
 *   - total distinct BLE advertisers seen
 *   - how many carry the Wiliot Pixel service UUID 0xFDAF
 *   - how many carry the Wiliot Bridge service UUID 0xFCC6
 *
 * This tells us whether the phone's radio is physically receiving Wiliot
 * packets, independent of the SDK's data pipeline.
 */
class RawBleProbe(context: Context) {

    private val adapter =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
    private var scanner: BluetoothLeScanner? = null
    private var running = false

    private val seen = HashSet<String>()
    private val wiliotSeen = HashSet<String>()
    private val bridgeSeen = HashSet<String>()

    private val _total = MutableStateFlow(0)
    val total: StateFlow<Int> = _total.asStateFlow()
    private val _wiliot = MutableStateFlow(0)
    val wiliot: StateFlow<Int> = _wiliot.asStateFlow()

    /** Called for every Wiliot Pixel advertisement: (raw 0xFDAF service-data hex, mac, rssi). */
    var onPixel: ((hex: String, mac: String, rssi: Int) -> Unit)? = null

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val addr = result.device.address ?: return
            if (seen.add(addr)) _total.value = seen.size

            val record = result.scanRecord
            val serviceData = record?.serviceData
            val uuids = record?.serviceUuids

            val hasPixel = serviceData?.containsKey(WILIOT_PIXEL) == true ||
                    uuids?.contains(WILIOT_PIXEL) == true
            val hasBridge = serviceData?.containsKey(WILIOT_BRIDGE) == true ||
                    uuids?.contains(WILIOT_BRIDGE) == true

            if (hasPixel) {
                if (wiliotSeen.add(addr)) _wiliot.value = wiliotSeen.size
                val hex = serviceData?.get(WILIOT_PIXEL)?.toHex()
                if (hex != null) onPixel?.invoke(hex, addr, result.rssi)
            }
            if (hasBridge) bridgeSeen.add(addr)
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "raw scan failed: $errorCode")
        }
    }

    @SuppressLint("MissingPermission") // caller ensures BLUETOOTH_SCAN granted
    fun start() {
        if (running) return
        scanner = adapter?.bluetoothLeScanner ?: run {
            Log.e(TAG, "no BLE scanner (adapter=${adapter != null})")
            return
        }
        seen.clear(); wiliotSeen.clear(); bridgeSeen.clear()
        _total.value = 0; _wiliot.value = 0
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner?.startScan(null, settings, callback)
        running = true
        Log.i(TAG, "raw BLE scan started")
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        if (!running) return
        try { scanner?.stopScan(callback) } catch (_: Exception) {}
        running = false
        Log.i(TAG, "raw BLE scan stopped. totals: all=${seen.size} pixels=${wiliotSeen.size} bridges=${bridgeSeen.size}")
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it) }

    companion object {
        private const val TAG = "RawBLE"
        // 16-bit UUIDs expand into the Bluetooth base UUID.
        val WILIOT_PIXEL: ParcelUuid = ParcelUuid.fromString("0000fdaf-0000-1000-8000-00805f9b34fb")
        val WILIOT_BRIDGE: ParcelUuid = ParcelUuid.fromString("0000fcc6-0000-1000-8000-00805f9b34fb")
    }
}

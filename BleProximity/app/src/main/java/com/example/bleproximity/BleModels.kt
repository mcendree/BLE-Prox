package com.example.bleproximity

import kotlin.math.pow

/**
 * A single BLE device we've heard advertising, with the data we display.
 */
data class BleDevice(
    val address: String,               // MAC-style address, our stable key
    val name: String?,                 // Advertised name (may be null / hidden)
    val rssiRaw: Int,                   // Last raw RSSI reading (dBm)
    val rssiSmoothed: Double,          // Exponentially smoothed RSSI (dBm)
    val txPower: Int?,                 // TX power level from the advertisement, if any
    val serviceUuids: List<String>,   // Advertised service UUIDs
    val manufacturerData: String?,    // Hex of manufacturer-specific data, if any
    val lastSeen: Long                 // millis timestamp of the last packet
)

/**
 * Calibration knobs for turning RSSI into an estimated distance.
 *
 * distance = 10 ^ ((txAt1m - rssi) / (10 * pathLoss))
 *
 * - txAt1m: the RSSI you'd measure at exactly 1 meter (a per-device constant,
 *   usually between -55 and -65 dBm). Calibrate by placing the phone 1 m away.
 * - pathLoss: environmental attenuation factor. 2.0 = free space,
 *   2.7-4.0 = typical indoor with walls/people.
 */
data class Calibration(
    val txAt1m: Int = -59,
    val pathLoss: Double = 2.5
) {
    fun estimateMeters(rssi: Double): Double {
        if (rssi == 0.0) return -1.0
        return 10.0.pow((txAt1m - rssi) / (10.0 * pathLoss))
    }
}

/** Coarse proximity bucket for quick visual feedback. */
enum class Proximity(val label: String) {
    IMMEDIATE("Immediate (< 0.5 m)"),
    NEAR("Near (0.5 - 2 m)"),
    MID("Mid (2 - 6 m)"),
    FAR("Far (> 6 m)"),
    UNKNOWN("Unknown");

    companion object {
        fun fromMeters(m: Double): Proximity = when {
            m < 0 -> UNKNOWN
            m < 0.5 -> IMMEDIATE
            m < 2.0 -> NEAR
            m < 6.0 -> MID
            else -> FAR
        }
    }
}

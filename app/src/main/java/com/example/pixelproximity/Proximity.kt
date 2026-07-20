package com.example.pixelproximity

import kotlin.math.pow

/**
 * RSSI -> distance calibration. All of this runs ON-DEVICE; RSSI is never sent
 * to the Wiliot cloud (only the encrypted payload is, for ID resolution).
 *
 * distance = 10 ^ ((rssiAt1m - rssi) / (10 * pathLoss))
 */
data class Calibration(
    val rssiAt1m: Int = -59,
    val pathLoss: Double = 2.5
) {
    fun estimateMeters(rssi: Double): Double {
        if (rssi >= 0.0) return -1.0
        return 10.0.pow((rssiAt1m - rssi) / (10.0 * pathLoss))
    }
}

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

/** One Pixel as shown in the UI. Identity comes from the cloud; RSSI is local. */
data class PixelRow(
    val key: String,          // join key (local BLE MAC) used to merge the two flows
    val pixelId: String,      // resolved Wiliot Pixel ID, or the raw endpoint id if not yet resolved
    val resolved: Boolean,    // true once the cloud has returned a real Pixel ID
    val rssiRaw: Int,
    val rssiSmoothed: Double,
    val meters: Double,
    val lastSeen: Long
)

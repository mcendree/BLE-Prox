package com.example.pixelproximity

import android.location.Location
import com.wiliot.wiliotcore.location.LocationManagerContract

/**
 * Minimal LocationManager the Wiliot SDK requires for wiring.
 *
 * We do NOT use location for proximity — distance comes purely from RSSI on the
 * device. This returns no location, which is fine for pixel ID resolution.
 *
 * ⚠️ If your SDK version's `LocationManagerContract` has different method
 * signatures, replace this file with the canonical one from the SDK sample:
 * https://github.com/OpenAmbientIoT/wiliot-android-sdk/blob/master/app/src/main/java/com/wiliot/wiliotandroidsdk/utils/LocationManagerImpl.kt
 */
object LocationManagerImpl : LocationManagerContract {

    override fun startObserveLocation() { /* no-op: we don't track GPS */ }

    override fun stopLocationUpdates() { /* no-op */ }

    override fun getLastLocation(): Location? = null
}

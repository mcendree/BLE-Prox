package com.example.pixelproximity

import android.content.Context
import android.location.Location
import com.wiliot.wiliotcore.location.LocationManagerContract

/**
 * Minimal LocationManager required by the Wiliot SDK wiring.
 *
 * We don't use location for proximity — distance comes purely from RSSI on the
 * device — so this is a no-op that reports no location. That's fine for Pixel ID
 * resolution. Method signatures match com.wiliot.wiliotcore.location.LocationManagerContract.
 */
object LocationManagerImpl : LocationManagerContract {

    override fun startObserveLocation(context: Context) { /* no-op */ }

    override fun stopLocationUpdates(context: Context?) { /* no-op */ }

    override fun getLastLocation(): Location? = null
}

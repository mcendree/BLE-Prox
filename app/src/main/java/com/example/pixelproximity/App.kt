package com.example.pixelproximity

import android.app.Application

/**
 * Custom Application. We no longer use the Wiliot SDK gateway pipeline — the app
 * scans BLE itself (RawBleProbe) and resolves Pixel IDs via the Wiliot REST API
 * (WiliotRestClient). Nothing to initialize here beyond crash logging.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}

package com.example.pixelproximity

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.wiliot.wiliotcore.Wiliot

/**
 * Custom Application. Implements the Wiliot context provider so the SDK can get
 * an Application context during init.
 */
class App : Application(), Wiliot.ContextInitializationProvider {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)

        // Re-initialize and start Wiliot on EVERY process start when we're
        // already set up. The SDK's scanner runs in a sticky foreground service;
        // if the OS re-delivers that service (null intent) in a fresh process
        // before Wiliot.start() has wired the QueueManager, it crashes. Doing
        // init+start here guarantees the wiring is in place first.
        val creds = CredentialStore(this)
        if (creds.hasCredentials && hasScanPermissions()) {
            WiliotController.ensureStarted(this, creds.ownerId, creds.apiKey)
        }
    }

    private fun hasScanPermissions(): Boolean {
        val scan = ContextCompat.checkSelfPermission(
            this, Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
        val loc = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return scan && loc
    }

    override fun provideContext(): Application = this

    companion object {
        lateinit var instance: App
            private set
    }
}

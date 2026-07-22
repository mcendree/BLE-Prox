package com.example.pixelproximity

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.wiliot.wiliotcore.Wiliot

/**
 * Custom Application. Implements the Wiliot context provider so the SDK can get
 * an Application context during init.
 *
 * We initialize the SDK and wire the QueueManager provider here on every process
 * start (when credentials + permissions are present). This is required to survive
 * the SDK's sticky-service re-delivery — see WiliotController for the full
 * explanation. Wiliot.start() wires the provider before it tries to launch the
 * foreground service, and WiliotController.start() swallows any background
 * foreground-start exception, so this is safe even in an OS-restarted process.
 */
class App : Application(), Wiliot.ContextInitializationProvider {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)

        val creds = CredentialStore(this)
        if (creds.hasCredentials) {
            WiliotController.ensureInit(this, creds.ownerId, creds.apiKey)
            if (hasScanPermissions()) {
                WiliotController.start() // wires provider; defeats null-intent sticky crash
            }
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

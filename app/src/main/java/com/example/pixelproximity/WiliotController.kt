package com.example.pixelproximity

import android.app.Application
import android.util.Log
import com.wiliot.wiliotcore.FrameworkDelegate
import com.wiliot.wiliotcore.Wiliot
import com.wiliot.wiliotcore.contextProviderBy
import com.wiliot.wiliotcore.frameworkDelegateBy
import com.wiliot.wiliotcore.locationManagerBy
import com.wiliot.wiliotcore.setApiKey
import com.wiliot.wiliotcore.utils.helper.WiliotAppConfigurationSource
import com.wiliot.wiliotcore.utils.helper.start
import com.wiliot.wiliotcore.utils.helper.stop
// Module init extensions (each lives in its own module package):
import com.wiliot.wiliotqueue.initQueue
import com.wiliot.wiliotupstream.feature.initUpstream
import com.wiliot.wiliotnetworkmeta.initMetaNetwork
import com.wiliot.wiliotresolvedata.initDataResolver

/**
 * Centralizes all Wiliot SDK wiring so the rest of the app never touches SDK
 * internals directly. If SDK symbols differ in your version, this is the ONE
 * file (plus LocationManagerImpl) you'll need to reconcile.
 *
 * Privacy configuration (the whole point of this setup):
 *   - resolveEnabled = true      -> resolve encrypted payloads into Pixel IDs (REST, no RSSI sent)
 *   - pixelsTrafficEnabled = false -> do NOT publish pixel telemetry (RSSI/counts) to MQTT
 *   - edgeTrafficEnabled  = false -> do NOT publish edge/bridge telemetry
 * Result: the only thing leaving the device is the encrypted payload needed to
 * resolve a Pixel ID. RSSI stays local and drives distance on-device.
 */
object WiliotController {

    private const val TAG = "WiliotController"
    private var initialized = false
    @Volatile var started = false
        private set

    fun ensureStarted(app: Application, ownerId: String, apiKey: String) {
        if (!initialized) {
            initSdk(app, ownerId, apiKey)
            initialized = true
        }
        try {
            Wiliot.start()
            started = true
        } catch (t: Throwable) {
            Log.e(TAG, "Wiliot.start() failed", t)
        }
    }

    fun stop() {
        try {
            Wiliot.stop()
        } catch (t: Throwable) {
            Log.e(TAG, "Wiliot.stop() failed", t)
        }
        started = false
    }

    private fun initSdk(app: Application, owner: String, apiKey: String) {
        // Global SDK preferences. Method names below come from
        // WiliotAppConfigurationSource.DefaultSdkPreferenceSource.
        WiliotAppConfigurationSource.initialize(
            object : WiliotAppConfigurationSource.DefaultSdkPreferenceSource() {
                override fun ownerId(): String = owner

                // Turn ON id resolution.
                override fun resolveEnabled(): Boolean = true

                // Turn OFF telemetry uploads so RSSI / packet counts never leave the phone.
                override fun pixelsTrafficEnabled(): Boolean = false
                override fun edgeTrafficEnabled(): Boolean = false

                // Keep the SDK from auto-relaunching its own service; we manage
                // start/stop ourselves (and re-start from Application.onCreate).
                override fun isServicePhoenixEnabled(): Boolean = false
            }
        )

        Wiliot.applicationPackage = app.packageName
        Wiliot.launcherActivity = "com.example.pixelproximity.MainActivity"

        Wiliot.init {
            this contextProviderBy App.instance
            setApiKey(apiKey)

            this frameworkDelegateBy object : FrameworkDelegate() {
                override fun applicationName(): String = "Wiliot Pixel Proximity"
                override fun applicationVersion(): Int = 1
                override fun applicationVersionName(): String = "1.0"
            }

            this locationManagerBy LocationManagerImpl

            // Modules: queue + upstream give us the BLE scanner; network-meta +
            // data-resolver give us cloud ID resolution.
            initQueue()
            initUpstream()
            initMetaNetwork()
            initDataResolver()
        }
    }
}

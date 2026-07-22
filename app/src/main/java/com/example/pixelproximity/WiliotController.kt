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
 * All Wiliot SDK wiring lives here.
 *
 * IMPORTANT — sticky-service crash workaround:
 * The SDK's scanner runs in a START_STICKY foreground service. Its
 * onStartCommand throws if the QueueManager provider isn't wired, and the
 * provider is ONLY wired inside Wiliot.start(). When Android kills and
 * re-delivers the sticky service (null intent) in a fresh process, the provider
 * would be unwired -> crash. So we call [start] from Application.onCreate() on
 * every process start (when set up + permitted). Inside Wiliot.start(), the
 * provider is wired BEFORE the foreground-service launch, so even if the launch
 * throws (e.g. background start not allowed), the provider is already set and
 * the re-delivered service can stop itself cleanly instead of crashing.
 *
 * Privacy config: resolve IDs (REST, no RSSI) but do NOT upload telemetry.
 */
object WiliotController {

    private const val TAG = "WiliotController"
    private var initialized = false

    /** Register modules + config. Safe to call on every process start. Requires credentials. */
    fun ensureInit(app: Application, ownerId: String, apiKey: String) {
        if (initialized) return

        WiliotAppConfigurationSource.initialize(
            object : WiliotAppConfigurationSource.DefaultSdkPreferenceSource() {
                override fun ownerId(): String = ownerId
                override fun resolveEnabled(): Boolean = true      // resolve Pixel IDs
                override fun pixelsTrafficEnabled(): Boolean = false // don't upload RSSI/telemetry
                override fun edgeTrafficEnabled(): Boolean = false
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

            initQueue()
            initUpstream()
            initMetaNetwork()
            initDataResolver()
        }
        initialized = true
    }

    /**
     * Starts the gateway. Also (critically) wires the QueueManager provider.
     * Wrapped in try/catch: if the foreground-service launch is disallowed
     * (background process), the provider is already wired by this point, so we
     * simply swallow the exception.
     */
    fun start() {
        try {
            Wiliot.start()
        } catch (t: Throwable) {
            Log.w(TAG, "Wiliot.start() threw (provider is already wired): ${t.message}")
        }
    }

    fun stop() {
        try {
            Wiliot.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "Wiliot.stop() failed: ${t.message}")
        }
    }
}

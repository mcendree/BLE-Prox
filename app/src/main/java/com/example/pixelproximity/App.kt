package com.example.pixelproximity

import android.app.Application
import com.wiliot.wiliotcore.Wiliot

/**
 * Custom Application. Implements the Wiliot context provider so the SDK can get
 * an Application context during init.
 *
 * NOTE: we deliberately do NOT start Wiliot here. Starting the SDK's foreground
 * service must be user-initiated (from the Scan button) so Android allows the
 * foreground-service start, and so the QueueManager is wired via Wiliot.start()
 * before the scanner service runs.
 */
class App : Application(), Wiliot.ContextInitializationProvider {

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashLog.install(this)
    }

    override fun provideContext(): Application = this

    companion object {
        lateinit var instance: App
            private set
    }
}

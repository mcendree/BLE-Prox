package com.example.pixelproximity

import android.app.Application
import com.wiliot.wiliotcore.Wiliot

/**
 * Custom Application. Implements the Wiliot context provider so the SDK can get
 * an Application context during init.
 */
class App : Application(), Wiliot.ContextInitializationProvider {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun provideContext(): Application = this

    companion object {
        lateinit var instance: App
            private set
    }
}

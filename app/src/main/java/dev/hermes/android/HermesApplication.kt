package dev.hermes.android

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class HermesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Chaquopy starts the embedded interpreter once per process. Starting it here keeps the
        // (slow) first import off the UI thread.
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }
    }
}

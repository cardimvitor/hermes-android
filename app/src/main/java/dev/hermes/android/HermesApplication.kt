package dev.hermes.android

import android.app.Application
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform

class HermesApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        appContext = this
        // Chaquopy starts the embedded interpreter once per process. Starting it here keeps the
        // (slow) first import off the UI thread. A failure is recorded, not thrown, so the UI can show it.
        if (!Python.isStarted()) {
            try {
                Python.start(AndroidPlatform(this))
            } catch (e: Throwable) {
                startError = e.toString()
            }
        }
    }

    companion object {
        /** Set when the embedded Python interpreter fails to start. Read by the chat screen. */
        @Volatile
        var startError: String? = null

        /** Application context for the device bridge (apps list, launching). */
        @Volatile
        var appContext: android.content.Context? = null
    }
}

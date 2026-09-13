package app.brix

import android.app.Application
import app.brix.core.diagnostics.CrashReporter

class BrixApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

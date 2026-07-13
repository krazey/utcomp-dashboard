package de.krazey.utcomp.dashboard

import android.app.Application
import de.krazey.utcomp.dashboard.logging.AppDiagnostics

class UtcompApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppDiagnostics.initialize(this)
    }

    override fun onTrimMemory(level: Int) {
        AppDiagnostics.warning("MEMORY", "Application.onTrimMemory level=$level")
        super.onTrimMemory(level)
    }

    override fun onLowMemory() {
        AppDiagnostics.warning("MEMORY", "Application.onLowMemory")
        super.onLowMemory()
    }
}

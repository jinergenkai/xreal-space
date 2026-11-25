package vn.jiner.xrealspace

import android.app.Application

/** Earliest place our code runs: install the crash logger before any Activity/Service starts. */
class XrealApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

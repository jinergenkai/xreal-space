package vn.jiner.xrealspace

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import vn.jiner.xrealspace.core.SpeedGuard

/**
 * Head-tracking sources owned by the PROCESS, not by an Activity: if Android destroys the activity while
 * another app is in front, the USB reader keeps running. When the glasses are connected we also start
 * GlassesService (foreground, type connectedDevice) so the process is not killed in the background.
 */
object Sensors {
    lateinit var usb: GlassesUsb
        private set
    lateinit var phone: PhoneImu
        private set
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())

    /** UI listener (MainActivity) for status refresh / auto-show on the glasses */
    var listener: (() -> Unit)? = null

    fun appContext(): Context = app

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        usb = GlassesUsb(app) { main.post { onUsbState() } }
        phone = PhoneImu(app)
        usb.register()
    }

    private fun onUsbState() {
        if (usb.connected) { keepAlive(); startSpeed() } else if (!Navigation.active) stopSpeed()
        listener?.invoke()
    }

    // ------------------------------------------------------------------ riding safety (GPS speed)
    private val speed = SpeedGuard()
    private var lm: LocationManager? = null
    private var lastFixMs = 0L
    val speedKmh get() = speed.kmh
    val speedRunning get() = lm != null
    /** last GPS fix (null = none yet / GPS off). Read by the map and the navigator. */
    @Volatile var location: Location? = null; private set
    /** last direction of travel (deg, 0 = north); kept when standing still, where GPS bearing is noise */
    @Volatile var headingDeg = 0.0; private set

    private val locListener = object : LocationListener {
        override fun onLocationChanged(loc: Location) {
            lastFixMs = System.currentTimeMillis()
            location = loc
            if (loc.hasBearing() && loc.hasSpeed() && loc.speed > 1.5f) headingDeg = loc.bearing.toDouble()
            if (loc.hasSpeed() && speed.update(loc.speed.toDouble(), lastFixMs) && SpaceState.rideSafety) applySpeedLock(speed.fast)
            Navigation.onLocation(loc)
        }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) { if (speed.lost()) applySpeedLock(false) }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            if (lm == null) return
            if (System.currentTimeMillis() - lastFixMs > 15_000 && speed.lost()) applySpeedLock(false)   // tunnel / indoors
            main.postDelayed(this, 5000)
        }
    }

    /** GPS runs while the glasses are plugged in (riding lock + map) or while navigating. */
    fun startSpeed() {
        if (lm != null) return
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val m = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locListener, Looper.getMainLooper())
            lm = m; lastFixMs = System.currentTimeMillis()
            main.postDelayed(watchdog, 5000)
            SpaceState.log("GPS speed monitor on")
        } catch (e: Exception) { SpaceState.log("GPS: $e") }
    }

    fun stopSpeed() {
        lm?.let { try { it.removeUpdates(locListener) } catch (_: Exception) {} }
        lm = null; main.removeCallbacks(watchdog)
        if (speed.lost() && SpaceState.speedLocked) applySpeedLock(false)
        location = null
    }

    private fun applySpeedLock(on: Boolean) {
        SpaceState.speedLocked = on
        if (on) SpaceState.notify("Đang di chuyển nhanh", "Ẩn màn hình cho an toàn · %.0f km/h".format(speed.kmh), Colors.AMBER, 4.0)
        else SpaceState.notify("Đã chậm lại", "Màn hình hiện lại", Colors.GREEN, 2.0)
        SpaceState.log("speed lock $on (%.0f km/h)".format(speed.kmh))
    }

    fun connectGlasses(): Boolean {
        val ok = usb.connect()
        if (ok) { phone.stop(); keepAlive() }
        return ok
    }

    fun usePhone(): Boolean { usb.close(); return phone.start() }

    /** Start the foreground service so tracking survives the activity going away. */
    fun keepAlive() {
        try {
            val i = Intent(app, GlassesService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        } catch (e: Exception) { SpaceState.log("keepAlive failed: $e") }
    }
}

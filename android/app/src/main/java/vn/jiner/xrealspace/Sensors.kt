package vn.jiner.xrealspace

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper

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

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        usb = GlassesUsb(app) { main.post { onUsbState() } }
        phone = PhoneImu(app)
        usb.register()
    }

    private fun onUsbState() {
        if (usb.connected) keepAlive()
        listener?.invoke()
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

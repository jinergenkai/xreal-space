package vn.jiner.xrealspace

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Path
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Display
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import vn.jiner.xrealspace.core.MirrorMap

/**
 * Phone screen -> a slot on the glasses. MediaProjection mirrors the default display into a VirtualDisplay whose
 * surface is the mirror panel's TextureView. One projection may create only ONE VirtualDisplay (Android 14), so a
 * rebuilt panel re-targets the same display with setSurface() instead of creating a new one.
 */
object MirrorController {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    val active get() = projection != null
    /** the granted projection (also used to capture playback audio for film subtitles) */
    val current: MediaProjection? get() = projection
    var phoneW = 1080; private set
    var phoneH = 2400; private set

    fun start(p: MediaProjection, ctx: Context) {
        stop()
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                Handler(Looper.getMainLooper()).post {
                    display?.release(); display = null; projection = null
                    SpaceState.notify("Màn hình điện thoại", "đã dừng chiếu", Colors.AMBER, 2.0)
                }
            }
        }, Handler(Looper.getMainLooper()))
        @Suppress("DEPRECATION")
        val m = DisplayMetrics().also {
            (ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager).getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(it)
        }
        phoneW = m.widthPixels; phoneH = m.heightPixels
        SpaceState.log("mirror started ${phoneW}x$phoneH")
    }

    fun attach(surface: Surface, w: Int, h: Int, dpi: Int) {
        val p = projection ?: return
        val d = display
        try {
            if (d == null) display = p.createVirtualDisplay("xspace-mirror", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null)
            else { d.resize(w, h, dpi); d.surface = surface }
        } catch (e: Exception) { SpaceState.log("mirror attach failed: $e") }
    }

    fun detach(surface: Surface) { display?.let { if (it.surface === surface) it.surface = null } }

    fun stop() {
        display?.release(); display = null
        projection?.stop(); projection = null
    }
}

/**
 * Keeps the glasses alive while another app (Zalo, Netflix…) is in front of the phone:
 *  - hosts the space as an overlay Presentation on the glasses display (needs "display over other apps"),
 *  - holds the MediaProjection for the phone mirror (foreground service of type mediaProjection),
 *  - keeps the Assistant (voice, captions, teleprompter) running.
 */
class GlassesService : Service() {
    companion object {
        const val ACTION_HOST = "vn.jiner.xrealspace.HOST"
        const val ACTION_MIRROR = "vn.jiner.xrealspace.MIRROR"
        const val ACTION_STOP = "vn.jiner.xrealspace.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "glasses"
        private const val NOTIF_ID = 7
        @Volatile var instance: GlassesService? = null; private set
        /** run once the projection is ready (e.g. launch the app the user asked for) */
        var afterMirror: (() -> Unit)? = null

        fun canHost(ctx: Context) = Settings.canDrawOverlays(ctx)
    }

    var presentation: SpacePresentation? = null; private set

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        SpaceState.init(this); Assistant.init(this); Sensors.init(this)
        if (Build.VERSION.SDK_INT >= 26) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Kính đang chạy", NotificationManager.IMPORTANCE_LOW))
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_MIRROR -> {
                startFg(mirror = true)   // must be in the foreground with type mediaProjection BEFORE getMediaProjection
                val code = intent.getIntExtra(EXTRA_CODE, 0)
                @Suppress("DEPRECATION") val data: Intent? = intent.getParcelableExtra(EXTRA_DATA)
                val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val p = if (data != null) mpm.getMediaProjection(code, data) else null
                if (p == null) { SpaceState.notify("Màn hình điện thoại", "Không lấy được quyền chiếu màn hình", Colors.RED, 3.0); startFg(false) }
                else { MirrorController.start(p, this); afterMirror?.invoke(); afterMirror = null }
                show()
            }
            else -> { startFg(MirrorController.active); show() }
        }
        return START_NOT_STICKY
    }

    fun refreshForeground() = startFg(MirrorController.active)

    private fun startFg(mirror: Boolean) {
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("XREAL Space đang chạy trên kính")
            .setContentText(if (mirror) "Đang chiếu màn hình điện thoại lên kính" else "Giữ kính hoạt động khi mở app khác")
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (mirror) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            // GPS speed keeps working while another app is in front (Android 10+ needs the location type for that)
            if (checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            // Android 14 refuses some types when the app is not in front (e.g. location "while in use"):
            // fall back to fewer types instead of crashing the service
            try { startForeground(NOTIF_ID, n, types) }
            catch (e: Exception) {
                SpaceState.log("startForeground($types) refused: $e")
                val basic = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (mirror) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0)
                try { startForeground(NOTIF_ID, n, basic) } catch (e2: Exception) { SpaceState.log("startForeground basic refused: $e2") }
            }
        } else startForeground(NOTIF_ID, n)
    }

    /** Show the space on the glasses as an overlay window (survives other apps coming to the front). */
    fun show() {
        if (presentation != null || !canHost(this)) return
        val dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val d = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != Display.DEFAULT_DISPLAY } ?: return
        try {
            val p = SpacePresentation(this, d)
            p.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            p.show()
            presentation = p
            SpaceState.log("service presentation on display ${d.displayId}")
        } catch (e: Exception) { SpaceState.log("service presentation failed: $e") }
    }

    fun hide() { presentation?.dismiss(); presentation = null }

    override fun onDestroy() {
        hide(); MirrorController.stop()
        instance = null
        super.onDestroy()
    }
}

/**
 * Accessibility = the hands for the mirrored phone apps (user must enable it in Settings > Accessibility):
 *  - taps / swipes at the gaze dot on the mirror slot,
 *  - Back / Home by voice,
 *  - volume keys keep working as the glasses remote while another app is in front
 *    (+ click, hold + = talk, − = recenter).
 */
class ControlService : AccessibilityService() {
    companion object { @Volatile var instance: ControlService? = null; private set }

    private val main = Handler(Looper.getMainLooper())
    private var holdFired = false
    private val hold = Runnable { holdFired = true; Assistant.pushToTalk() }

    override fun onServiceConnected() { instance = this; SpaceState.log("Accessibility connected") }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }

    override fun onKeyEvent(e: KeyEvent): Boolean {
        if (!SpaceState.glassesActive || MainActivity.inForeground) return false
        when (e.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) { holdFired = false; main.postDelayed(hold, 600) }
                if (e.action == KeyEvent.ACTION_UP) {
                    main.removeCallbacks(hold)
                    if (!holdFired) SpaceHost.space?.clickAtGaze()
                }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { if (e.action == KeyEvent.ACTION_UP) SpaceState.recenter(); return true }
        }
        return false
    }

    fun tap(x: Float, y: Float) = swipe(x, y, x, y, 60)

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long = 250): Boolean {
        val path = Path().apply { moveTo(x1, y1); if (x1 != x2 || y1 != y2) lineTo(x2, y2) }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, ms)).build()
        return dispatchGesture(g, null, null)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    /** a point inside the mirror view -> real phone pixels -> gesture */
    fun tapMirror(lx: Float, ly: Float, vw: Float, vh: Float): Boolean {
        val (x, y) = MirrorMap.toPhone(lx, ly, vw, vh, MirrorController.phoneW, MirrorController.phoneH) ?: return false
        return tap(x, y)
    }

    fun swipeMirror(lx1: Float, ly1: Float, lx2: Float, ly2: Float, vw: Float, vh: Float): Boolean {
        val a = MirrorMap.toPhone(lx1, ly1, vw, vh, MirrorController.phoneW, MirrorController.phoneH) ?: return false
        val b = MirrorMap.toPhone(lx2.coerceIn(0f, vw), ly2.coerceIn(0f, vh), vw, vh, MirrorController.phoneW, MirrorController.phoneH) ?: return false
        return swipe(a.first, a.second, b.first, b.second, 300)
    }
}

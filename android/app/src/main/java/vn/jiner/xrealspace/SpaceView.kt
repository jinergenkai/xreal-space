package vn.jiner.xrealspace

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import vn.jiner.xrealspace.core.HeadGestures
import vn.jiner.xrealspace.core.LazyFollow
import vn.jiner.xrealspace.core.M3
import vn.jiner.xrealspace.core.Mat3
import vn.jiner.xrealspace.core.Placement
import vn.jiner.xrealspace.core.Projection
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The spatial scene. Every world-locked screen is a real, interactive WebView laid out at a fixed
 * 1280x720 "virtual resolution"; each vsync we move/scale/rotate it from the head pose
 * (billboard projection: correct position, size and roll; no perspective tilt).
 * Works both inside a Presentation on the glasses and full-screen on the phone.
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
class SpaceView(ctx: Context, private val onPhoneScreen: Boolean) : FrameLayout(ctx), Choreographer.FrameCallback {
    companion object {
        const val BASE_W = 1280
        const val BORDER = 6
    }

    inner class Panel(val index: Int) {
        val frame = FrameLayout(context)
        val web = WebView(context)
        val border = GradientDrawable().apply { setColor(Color.BLACK); setStroke(BORDER, Colors.BORDER); cornerRadius = 10f }
        var heightPx = BASE_W * 9 / 16
        var focused = false
            set(v) { if (v != field) { field = v; border.setStroke(BORDER, if (v) Colors.BORDER_FOCUS else Colors.BORDER) } }

        init {
            frame.background = border
            frame.setPadding(BORDER, BORDER, BORDER, BORDER)
            web.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                useWideViewPort = true
                loadWithOverviewMode = true
                setSupportZoom(true); builtInZoomControls = true; displayZoomControls = false
            }
            web.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val s = r.url.scheme ?: return true
                    return s != "http" && s != "https" // keep web navigation inside the panel, block intent:// etc.
                }
                override fun onPageFinished(v: WebView, url: String) { SpaceState.config.screens.getOrNull(index)?.let { if (it.url != url) { it.url = url } } }
            }
            web.webChromeClient = WebChromeClient()
            web.setBackgroundColor(Color.BLACK)
            frame.addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            frame.pivotX = 0f; frame.pivotY = 0f
            SpaceState.config.screens.getOrNull(index)?.let { web.loadUrl(it.url) }
        }
    }

    private val panels = ArrayList<Panel>()
    private var builtVersion = -1
    private val mapView = MapPanelView(ctx)
    private val info = InfoView(ctx)
    private val toastView = ToastView(ctx)
    private val crosshair = CrosshairView(ctx)
    private val status = TextView(ctx).apply {
        setTextColor(0xFFFFFF78.toInt()); textSize = 13f; setBackgroundColor(0xFF000000.toInt()); setPadding(12, 6, 12, 6)
    }
    private val wizardText = TextView(ctx).apply {
        setTextColor(Colors.AMBER); textSize = 26f; gravity = Gravity.CENTER; setBackgroundColor(0xFF000000.toInt()); setPadding(30, 20, 30, 20)
    }
    private val follow = LazyFollow(SpaceState.config.map)
    private val gestures = HeadGestures()
    private var lastFrameNs = 0L
    private var fps = 0.0
    private var fpsCount = 0
    private var fpsT0 = 0L
    private var lastInfo = 0L
    private var running = false
    private var C: M3 = Mat3.eye()

    init {
        setBackgroundColor(Color.BLACK) // black = see-through on the glasses
        clipChildren = false
        addView(mapView, LayoutParams(MapPanelView.W, MapPanelView.H))
        mapView.pivotX = 0f; mapView.pivotY = 0f
        addView(info, LayoutParams(InfoView.W, InfoView.H))
        addView(toastView, LayoutParams(ToastView.W, ToastView.H)); toastView.visibility = GONE
        addView(status, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(wizardText, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(crosshair, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        crosshair.isClickable = false
        rebuildPanels()
    }

    private fun rebuildPanels() {
        panels.forEach { removeView(it.frame); it.web.destroy() }
        panels.clear()
        SpaceState.config.screens.indices.forEach { i ->
            val p = Panel(i)
            addView(p.frame, i, LayoutParams(BASE_W + 2 * BORDER, p.heightPx + 2 * BORDER))
            panels.add(p)
        }
        builtVersion = SpaceState.panelsVersion
        SpaceState.pendingLoads.clear()
    }

    fun start() { if (!running) { running = true; lastFrameNs = 0; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; Choreographer.getInstance().removeFrameCallback(this) }

    fun destroy() { stop(); panels.forEach { it.web.destroy() }; panels.clear() }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNs == 0L) 0.016 else ((frameTimeNanos - lastFrameNs) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNs = frameTimeNanos
        fpsCount++
        if (frameTimeNanos - fpsT0 > 1_000_000_000L) { fps = fpsCount * 1e9 / (frameTimeNanos - fpsT0); fpsCount = 0; fpsT0 = frameTimeNanos }
        try { frame(dt) } catch (e: Exception) { SpaceState.log("frame error: $e") }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun frame(dt: Double) {
        val w = width; val h = height
        if (w == 0 || h == 0) return
        val st = SpaceState
        if (builtVersion != st.panelsVersion) rebuildPanels()
        if (st.pendingLoads.isNotEmpty()) {
            for ((i, url) in st.pendingLoads) panels.getOrNull(i)?.web?.loadUrl(url)
            st.pendingLoads.clear()
        }
        val tracker = st.tracker
        val M = st.axisMapping()
        val now = st.nowS()

        // calibration wizard
        st.wizard?.let { wz ->
            val res = wz.update(now)
            if (res != null) { st.saveCalibration(res); st.wizard = null; tracker.reset(); st.notify("Hiệu chỉnh xong", "Đã lưu trục kính", Colors.GREEN, 3.0) }
        }
        val wz = st.wizard
        wizardText.visibility = if (wz != null) VISIBLE else GONE
        if (wz != null) wizardText.text = wz.text

        if (st.recenterRequested) { st.recenterRequested = false; follow.reset() }

        // camera orientation (cam -> world)
        C = if (M == null || st.headLocked || wz != null) Mat3.eye()
            else Projection.camera(tracker.predicted(st.config.predictMs), M)

        val cfg = st.config
        // world-locked screens
        var best = -1; var bestD = Double.MAX_VALUE
        panels.forEachIndexed { i, p ->
            val spec = cfg.screens.getOrNull(i) ?: return@forEachIndexed
            if (st.walkMode || wz != null) { hide(p.frame); return@forEachIndexed }
            val P = Mat3.mul(Mat3.rotY(spec.yaw), Mat3.rotX(spec.pitch))
            val pl = Projection.place(C, P, cfg.distanceM, spec.widthM, w, h, cfg.fovVDeg)
            layoutOn(p.frame, pl, BASE_W + 2.0 * BORDER, p.heightPx + 2.0 * BORDER)
            if (pl.visible) {
                val d = hypot(pl.cx - w / 2.0, pl.cy - h / 2.0)
                val halfW = pl.widthPx / 2
                if (d < bestD && d < halfW) { bestD = d; best = i }
            }
        }
        st.focusedPanel = best
        panels.forEachIndexed { i, p -> p.focused = i == best }

        // lazy-follow map
        if (st.showMap && wz == null) {
            val F = follow.update(C, dt)
            val P = Mat3.mul(F, Mat3.mul(Mat3.rotY(cfg.map.yaw), Mat3.rotX(cfg.map.pitch)))
            val pl = Projection.place(C, P, cfg.map.distanceM, cfg.map.widthM, w, h, cfg.fovVDeg)
            layoutOn(mapView, pl, MapPanelView.W.toDouble(), MapPanelView.H.toDouble())
            mapView.step(dt)
        } else hide(mapView)

        // head gestures (camera-frame gyro)
        if (M != null && wz == null) {
            val g = gestures.update(Mat3.mulV(M, tracker.lastGyro), now)
            when (g) {
                HeadGestures.Gesture.NOD -> {
                    val t = st.toast
                    if (t != null && t.actionable) st.notify("Đã trả lời", "\"OK, 7h nhé!\"", Colors.GREEN, 2.5)
                    else st.setMap(null)
                }
                HeadGestures.Gesture.SHAKE -> {
                    val t = st.toast
                    st.notify("Lắc đầu", if (t != null && t.actionable) "Đã bỏ qua" else "Đóng", Colors.RED, 1.5)
                }
                null -> {}
            }
        }

        // head-locked HUD
        info.x = (w - InfoView.W - 20).toFloat(); info.y = 20f
        if (SystemClock.uptimeMillis() - lastInfo > 1000) {
            lastInfo = SystemClock.uptimeMillis()
            info.mode = when { st.walkMode -> "ĐI BỘ"; st.headLocked -> "HEAD-LOCKED"; else -> "WORLD-LOCKED" } +
                if (onPhoneScreen) " · màn hình điện thoại" else ""
            info.refresh()
        }
        val t = st.toast
        if (t != null && System.currentTimeMillis() > t.until) st.toast = null
        toastView.show(st.toast)
        toastView.x = (w / 2f - ToastView.W / 2f); toastView.y = h * 0.30f

        status.visibility = if (st.showStatus && wz == null) VISIBLE else GONE
        if (status.visibility == VISIBLE) {
            status.x = 20f; status.y = 20f
            status.text = "%s  imu %.0fHz  bias %s  render %.0ffps  focus %s".format(
                st.imuSource, tracker.rate, tracker.biasState, fps, if (best >= 0) cfg.screens[best].title else "-")
        }
    }

    private fun hide(v: View) { if (v.visibility != INVISIBLE) v.visibility = INVISIBLE }

    /** Place a view whose natural size is (vw x vh) so its centre lands on the projected point. */
    private fun layoutOn(v: View, pl: Placement, vw: Double, vh: Double) {
        if (!pl.visible) { hide(v); return }
        if (v.visibility != VISIBLE) v.visibility = VISIBLE
        val s = (pl.widthPx / vw).toFloat()
        v.scaleX = s; v.scaleY = s
        v.rotation = pl.rollDeg.toFloat()
        // pivot is (0,0): rotate/scale about the top-left, then shift so the centre is at (cx, cy)
        val rad = Math.toRadians(pl.rollDeg)
        val hx = vw / 2 * s; val hy = vh / 2 * s
        val ox = hx * Math.cos(rad) - hy * Math.sin(rad)
        val oy = hx * Math.sin(rad) + hy * Math.cos(rad)
        v.translationX = (pl.cx - ox).toFloat()
        v.translationY = (pl.cy - oy).toFloat()
    }

    // ------------------------------------------------------------------ input proxied from the phone

    private var touchTarget: Panel? = null
    private var touchDownTime = 0L
    private var touchX = 0f
    private var touchY = 0f

    /** Point under the gaze dot in the focused WebView's own coordinates, or null. */
    private fun gazeLocal(p: Panel): FloatArray? {
        val f = p.frame
        if (f.visibility != VISIBLE) return null
        val inv = Matrix()
        if (!f.matrix.invert(inv)) return null
        val pt = floatArrayOf(width / 2f - f.left, height / 2f - f.top)
        inv.mapPoints(pt)
        pt[0] -= BORDER; pt[1] -= BORDER
        if (pt[0] < 0 || pt[1] < 0 || pt[0] > p.web.width || pt[1] > p.web.height) return null
        return pt
    }

    /**
     * Phone touchpad: finger down = touch down at the gaze dot, finger moves = drag (scroll),
     * finger up = release. A short tap therefore becomes a click. [gain] converts phone px to web px.
     */
    fun touchpad(action: Int, dxTotal: Float, dyTotal: Float, gain: Float = 1.6f) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                val p = panels.getOrNull(SpaceState.focusedPanel) ?: return
                val pt = gazeLocal(p) ?: return
                touchTarget = p; touchX = pt[0]; touchY = pt[1]
                touchDownTime = SystemClock.uptimeMillis()
                send(p, MotionEvent.ACTION_DOWN, touchX, touchY)
            }
            MotionEvent.ACTION_MOVE -> touchTarget?.let { send(it, MotionEvent.ACTION_MOVE, touchX + dxTotal * gain, touchY + dyTotal * gain) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touchTarget?.let {
                send(it, action, touchX + dxTotal * gain, touchY + dyTotal * gain); touchTarget = null
            }
        }
    }

    private fun send(p: Panel, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(touchDownTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        p.web.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Volume-key / remote click at the gaze dot. */
    fun clickAtGaze(): Boolean {
        val p = panels.getOrNull(SpaceState.focusedPanel) ?: return false
        val pt = gazeLocal(p) ?: return false
        touchDownTime = SystemClock.uptimeMillis()
        send(p, MotionEvent.ACTION_DOWN, pt[0], pt[1])
        send(p, MotionEvent.ACTION_UP, pt[0], pt[1])
        return true
    }

    fun focusedWeb(): WebView? = panels.getOrNull(SpaceState.focusedPanel)?.web ?: panels.firstOrNull()?.web

    fun webAt(i: Int): WebView? = panels.getOrNull(i)?.web

    /** Type text into whatever is focused inside the page (input, textarea, contenteditable). */
    fun typeText(text: String, enter: Boolean) {
        val web = focusedWeb() ?: return
        val js = org.json.JSONObject.quote(text)
        web.evaluateJavascript(
            """(function(t,enter){var e=document.activeElement;if(!e)return 'no-focus';
               if(t){ if(e.isContentEditable){document.execCommand('insertText',false,t);}
               else if('value' in e){var s=e.selectionStart==null?e.value.length:e.selectionStart;
                 var en=e.selectionEnd==null?s:e.selectionEnd; e.value=e.value.slice(0,s)+t+e.value.slice(en);
                 e.dispatchEvent(new Event('input',{bubbles:true}));} }
               if(enter){ ['keydown','keypress','keyup'].forEach(function(k){e.dispatchEvent(new KeyboardEvent(k,{key:'Enter',code:'Enter',keyCode:13,which:13,bubbles:true}));});
                 if(e.form){ if(e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); } }
               return 'ok';})($js,$enter)""".trimIndent(), null
        )
    }

    /** Current gaze direction (deg), used to pin a new screen where you look. */
    fun gazeYawPitch(): Pair<Double, Double> = Projection.yawPitch(C).let { (y, p) -> (y * 10).roundToInt() / 10.0 to (p * 10).roundToInt() / 10.0 }
}

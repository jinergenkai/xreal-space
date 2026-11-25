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
import android.graphics.SurfaceTexture
import android.net.Uri
import android.webkit.WebResourceResponse
import vn.jiner.xrealspace.core.AdFilter
import vn.jiner.xrealspace.core.ResumeStore
import vn.jiner.xrealspace.core.Stereo
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Surface
import android.view.TextureView
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import vn.jiner.xrealspace.core.BodyFollow
import vn.jiner.xrealspace.core.FollowMode
import vn.jiner.xrealspace.core.HeadGestures
import vn.jiner.xrealspace.core.ImuTracker
import vn.jiner.xrealspace.core.LazyFollow
import vn.jiner.xrealspace.core.M3
import vn.jiner.xrealspace.core.Mat3
import vn.jiner.xrealspace.core.Placement
import vn.jiner.xrealspace.core.Projection
import vn.jiner.xrealspace.core.SlotGrid
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The spatial scene. Every world-locked screen is a real, interactive WebView laid out at a fixed
 * 1280x720 "virtual resolution"; each vsync we move/scale/rotate it from the head pose
 * (billboard projection: correct position, size and roll; no perspective tilt).
 * Works both inside a Presentation on the glasses and full-screen on the phone.
 */
@SuppressLint("ViewConstructor", "SetJavaScriptEnabled")
private var googleHintShown = false

class SpaceView(ctx: Context, private val onPhoneScreen: Boolean) : FrameLayout(ctx), Choreographer.FrameCallback {
    companion object {
        const val BASE_W = 1280
        const val BORDER = 6

        /** tells the app when a text field in the page gets focus, so the phone keyboard can open */
        const val INPUT_HOOK_JS = "(function(){if(window.__xs)return;window.__xs=1;" +
            "function t(e){if(!e)return false;if(e.isContentEditable)return true;var y=(e.type||'').toLowerCase();" +
            "return e.tagName=='TEXTAREA'||(e.tagName=='INPUT'&&['text','search','email','url','tel','password','number',''].indexOf(y)>=0);}" +
            "document.addEventListener('focusin',function(v){var e=v.target;if(t(e))XSpace.focus(e.type||'text',e.isContentEditable?(e.innerText||''):(e.value||''));},true);" +
            "document.addEventListener('focusout',function(v){if(t(v.target))XSpace.blur();},true);})();"

        /**
         * "continue watching": reports the biggest video's position every 5 s and, the first time a video
         * starts, jumps to where you stopped last time. Main frame only (videos inside a cross-site iframe
         * are out of reach of the app).
         */
        const val RESUME_JS = "(function(){if(window.__xsr)return;window.__xsr=1;" +
            "function big(){var b=null,a=0;document.querySelectorAll('video').forEach(function(v){var s=v.clientWidth*v.clientHeight;if(s>a){a=s;b=v;}});return b;}" +
            "var done=new WeakSet();" +
            "document.addEventListener('playing',function(e){var v=e.target;if(!(v instanceof HTMLVideoElement)||done.has(v))return;done.add(v);" +
            "var t=XSpace.resumeAt(location.href);if(t>0&&Math.abs(v.currentTime-t)>20&&(!v.duration||t<v.duration-30)){v.currentTime=t;XSpace.resumed(t);}},true);" +
            "setInterval(function(){var v=big();if(v&&!v.paused&&v.duration>120)XSpace.videoPos(location.href,v.currentTime,v.duration);},5000);})();"

        const val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        fun EMPTY_RESPONSE() = WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))

        /** dark mode for old Android (< 10): invert the page, re-invert media */
        const val CSS_DARK_JS = "(function(){var s=document.createElement('style');" +
            "s.textContent='html{filter:invert(1) hue-rotate(180deg);background:#fff}img,video,picture,canvas,iframe{filter:invert(1) hue-rotate(180deg)}';" +
            "document.head.appendChild(s);})();"
    }

    private var lastResumeSave = 0L

    // ---- side-by-side 3D: glasses switched to 3840×1080 (hold Brightness+ ~2 s). Each eye = one half.
    private var sbs = false
    private var eyeW = 0
    private var focalPx = 0.0
    /** per-view horizontal shift for one eye, from that view's distance (near = pops out) */
    private val eyeShift = HashMap<View, Float>()

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        if (!sbs) { super.dispatchDraw(canvas); return }
        val t = drawingTime
        for (eye in 0..1) {
            canvas.save()
            canvas.clipRect(eye * eyeW, 0, (eye + 1) * eyeW, height)
            canvas.translate((eye * eyeW).toFloat(), 0f)
            for (i in 0 until childCount) {
                val c = getChildAt(i)
                if (c === cinema || c.visibility != VISIBLE) continue
                val sh = (eyeShift[c] ?: 0f) * (if (eye == 0) 1 else -1)
                canvas.save(); canvas.translate(sh, 0f); drawChild(canvas, c, t); canvas.restore()
            }
            canvas.restore()
        }
        // a 3D (SBS) film is already two images side by side: draw it once across both halves
        if (cinema.visibility == VISIBLE) drawChild(canvas, cinema, t)
    }

    inner class Panel(val index: Int) {
        val frame = FrameLayout(context)
        val web = WebView(context)
        var mobileUa = ""
        var desktop = false

        /** desktop layout (many film / office sites are cramped in their mobile version) */
        fun setDesktop(on: Boolean) {
            if (on == desktop || isMirror) return
            desktop = on
            web.settings.userAgentString = if (on) DESKTOP_UA else mobileUa
            web.reload()
        }
        /** a panel showing the phone screen (MediaProjection) instead of a web page */
        val isMirror = SpaceState.config.screens.getOrNull(index)?.url == Assistant.MIRROR_URL
        var mirrorView: TextureView? = null
        val border = GradientDrawable().apply { setColor(Color.BLACK); setStroke(BORDER, Colors.BORDER); cornerRadius = 10f }
        /** content size in px (web: 1280x720; mirror: phone aspect, portrait) */
        val widthPx = if (isMirror) 720 else BASE_W
        var heightPx = if (isMirror) (720f * MirrorController.phoneH / MirrorController.phoneW).toInt() else BASE_W * 9 / 16
        /** the view that receives input: the WebView or the mirror */
        val content: View get() = mirrorView ?: web
        var focused = false
            set(v) { if (v != field) { field = v; border.setStroke(BORDER, if (v) Colors.BORDER_FOCUS else Colors.BORDER) } }
        /** last time (ms) the panel was on screen, for pausing panels you are not looking at */
        var lastSeen = SystemClock.uptimeMillis()
        var paused = false

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
                // Google refuses sign-in when the UA says "; wv" (embedded WebView). Look like plain Chrome.
                userAgentString = userAgentString.replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
                mobileUa = userAgentString
                // pop-ups go through onCreateWindow, where ads (no tap) are dropped and real links open in this panel
                setSupportMultipleWindows(true)
                javaScriptCanOpenWindowsAutomatically = false
            }
            applyDark(web, false)
            web.addJavascriptInterface(InputBridge(), "XSpace")
            web.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(v: WebView, r: WebResourceRequest): WebResourceResponse? =
                    if (SpaceState.adBlock && AdFilter.isAd(r.url.host)) EMPTY_RESPONSE() else null

                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val s = r.url.scheme ?: return true
                    if (SpaceState.adBlock && r.isForMainFrame &&
                        AdFilter.isHijack(Uri.parse(v.url ?: "").host, r.url.host, r.hasGesture(), Build.VERSION.SDK_INT >= 24 && r.isRedirect)) {
                        SpaceState.notify("Đã chặn chuyển trang", r.url.host ?: "", Colors.GREY, 1.5)
                        return true
                    }
                    if (r.url.host == "accounts.google.com" && !googleHintShown) {
                        googleHintShown = true
                        SpaceState.notify("Đăng nhập Google", "Nếu Google chặn: bấm \"Mở bằng Chrome\" rồi xem qua màn hình điện thoại", Colors.AMBER, 6.0)
                    }
                    return s != "http" && s != "https" // keep web navigation inside the panel, block intent:// etc.
                }
                override fun onPageFinished(v: WebView, url: String) {
                    SpaceState.config.screens.getOrNull(index)?.let { if (it.url != url) { it.url = url } }
                    v.evaluateJavascript(INPUT_HOOK_JS, null)
                    v.evaluateJavascript(RESUME_JS, null)
                    if (SpaceState.darkWeb && Build.VERSION.SDK_INT < 29) v.evaluateJavascript(CSS_DARK_JS, null)
                }
            }
            web.webChromeClient = object : WebChromeClient() {
                override fun onShowCustomView(view: View, callback: CustomViewCallback) { enterFullscreen(this@Panel, view, callback) }
                override fun onHideCustomView() { exitFullscreen() }
                /** window.open / target=_blank: ads (no tap) are dropped, real links open in this same panel */
                override fun onCreateWindow(v: WebView, isDialog: Boolean, isUserGesture: Boolean, msg: android.os.Message): Boolean {
                    if (SpaceState.adBlock && !isUserGesture) { SpaceState.notify("Đã chặn cửa sổ bật lên", "", Colors.GREY, 1.2); return false }
                    val catcher = WebView(context)
                    catcher.webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(cv: WebView, r: WebResourceRequest): Boolean {
                            val host = r.url.host
                            if (!(SpaceState.adBlock && AdFilter.isAd(host))) web.loadUrl(r.url.toString())
                            cv.destroy(); return true
                        }
                    }
                    (msg.obj as WebView.WebViewTransport).webView = catcher
                    msg.sendToTarget()
                    return true
                }
            }
            web.setBackgroundColor(Color.BLACK)
            if (isMirror) {
                val tv = TextureView(context)
                var surface: Surface? = null
                tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                        surface = Surface(st).also { MirrorController.attach(it, w, h, 320) }
                    }
                    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { surface?.let { MirrorController.attach(it, w, h, 320) } }
                    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { surface?.let { MirrorController.detach(it); it.release() }; surface = null; return true }
                    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                }
                mirrorView = tv
                frame.addView(tv, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            } else {
                frame.addView(web, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
                SpaceState.config.screens.getOrNull(index)?.let { web.loadUrl(it.url) }
            }
            frame.pivotX = 0f; frame.pivotY = 0f
        }
    }

    /** Called by page JS when a text field gets / loses focus (binder thread -> main). */
    inner class InputBridge {
        /** video position every few seconds (resume later) */
        @JavascriptInterface fun videoPos(url: String, t: Double, dur: Double) {
            synchronized(SpaceState.resume) { SpaceState.resume.save(url, t, dur) }
            val now = SystemClock.uptimeMillis()
            if (now - lastResumeSave > 30_000) { lastResumeSave = now; post { SpaceState.saveSettings() } }
        }
        @JavascriptInterface fun resumeAt(url: String): Double = synchronized(SpaceState.resume) { SpaceState.resume.get(url) }
        @JavascriptInterface fun resumed(t: Double) { post { SpaceState.notify("Xem tiếp", "Từ ${ResumeStore.clock(t)}", Colors.CYAN, 3.0) } }
        @JavascriptInterface fun focus(kind: String, value: String) {
            post { if (SpaceState.remoteFocusSink?.invoke(value) != true) SpaceState.onInputFocus?.invoke(value) }
        }
        @JavascriptInterface fun blur() { post { if (SpaceState.remoteBlurSink?.invoke() != true) SpaceState.onInputBlur?.invoke() } }
    }

    @Suppress("DEPRECATION")
    private fun applyDark(w: WebView, reload: Boolean) {
        val on = SpaceState.darkWeb
        when {
            Build.VERSION.SDK_INT >= 33 -> w.settings.isAlgorithmicDarkeningAllowed = on   // app theme is dark, so pages get darkened
            Build.VERSION.SDK_INT >= 29 -> w.settings.forceDark = if (on) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
        }
        if (reload) w.reload()
    }

    // ------------------------------------------------------------------ fullscreen video (YouTube "⛶" button)
    private val cinema = FrameLayout(ctx).apply { setBackgroundColor(Color.BLACK); visibility = GONE }
    private var fullView: View? = null
    private var fullCallback: WebChromeClient.CustomViewCallback? = null
    private var fullOwner: Panel? = null
    private var modeBeforeFull: FollowMode? = null
    val inFullscreen get() = fullView != null

    private fun enterFullscreen(owner: Panel, view: View, cb: WebChromeClient.CustomViewCallback) {
        if (fullView != null) { cb.onCustomViewHidden(); return }
        fullView = view; fullCallback = cb; fullOwner = owner
        cinema.addView(view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        cinema.visibility = VISIBLE
        if (SpaceState.followMode == FollowMode.WORLD) { modeBeforeFull = FollowMode.WORLD; SpaceState.setFollow(FollowMode.CINEMA, quiet = true) }
        SpaceState.notify("Rạp phim", "Toàn màn hình · vuốt 2 ngón sang trái để thoát", Colors.CYAN, 2.5)
        SpaceState.log("fullscreen video on")
    }

    fun exitFullscreen() {
        val v = fullView ?: return
        cinema.removeView(v); cinema.visibility = GONE
        fullCallback?.onCustomViewHidden()
        fullView = null; fullCallback = null; fullOwner = null
        modeBeforeFull?.let { SpaceState.setFollow(it, quiet = true) }; modeBeforeFull = null
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
    /** numbered markers for the 9 grid slots (shown while giving voice commands) */
    private val slotLabels = Array(9) { i ->
        TextView(ctx).apply {
            text = "${i + 1}"; textSize = 40f; gravity = Gravity.CENTER; setTextColor(0xFF7FA8C8.toInt())
            background = GradientDrawable().apply { setColor(0xFF000000.toInt()); setStroke(3, 0xFF2F5A7A.toInt()); cornerRadius = 16f }
            visibility = INVISIBLE
        }
    }
    private val card = TextView(ctx).apply {
        setTextColor(Colors.WHITE); textSize = 18f; setPadding(28, 22, 28, 22); setLineSpacing(4f, 1.0f)
        background = GradientDrawable().apply { setColor(0xF2000000.toInt()); setStroke(2, Colors.CYAN); cornerRadius = 18f }
        visibility = GONE
    }
    private val captionView = TextView(ctx).apply {
        setTextColor(Colors.WHITE); textSize = 22f; setPadding(26, 14, 26, 14); gravity = Gravity.CENTER; setLineSpacing(4f, 1.0f)
        background = GradientDrawable().apply { setColor(0xF2000000.toInt()); cornerRadius = 16f }
        visibility = GONE
    }
    private val prompterView = TextView(ctx).apply {
        textSize = 30f; setPadding(36, 24, 36, 24); setLineSpacing(10f, 1.0f)
        background = GradientDrawable().apply { setColor(0xF2000000.toInt()); setStroke(2, 0xFF2F5A7A.toInt()); cornerRadius = 20f }
        visibility = GONE
    }
    private var captionsSeen = -1
    private var prompterSeen = -1
    private val voice = TextView(ctx).apply {
        setTextColor(Colors.CYAN); textSize = 18f; setPadding(22, 10, 22, 10); gravity = Gravity.CENTER
        background = GradientDrawable().apply { setColor(0xF2000000.toInt()); setStroke(2, Colors.CYAN); cornerRadius = 30f }
        visibility = GONE
    }
    private val biasHint = TextView(ctx).apply {
        setTextColor(Colors.AMBER); textSize = 24f; gravity = Gravity.CENTER; setPadding(30, 18, 30, 18)
        background = GradientDrawable().apply { setColor(0xF2000000.toInt()); setStroke(2, Colors.AMBER); cornerRadius = 18f }
        text = "Giữ yên đầu 1 giây…"; visibility = GONE
    }
    private val body = BodyFollow()
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
        slotLabels.forEach { addView(it, LayoutParams(220, 124)); it.pivotX = 0f; it.pivotY = 0f }
        addView(card, LayoutParams(820, LayoutParams.WRAP_CONTENT))
        addView(captionView, LayoutParams(1100, LayoutParams.WRAP_CONTENT))
        addView(prompterView, LayoutParams(1200, LayoutParams.WRAP_CONTENT))
        addView(voice, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        addView(cinema, LayoutParams(100, 100))
        addView(biasHint, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        addView(crosshair, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        crosshair.isClickable = false
        rebuildPanels()
    }

    private fun rebuildPanels() {
        panels.forEach { removeView(it.frame); it.web.destroy() }
        panels.clear()
        SpaceState.config.screens.indices.forEach { i ->
            val p = Panel(i)
            addView(p.frame, i, LayoutParams(p.widthPx + 2 * BORDER, p.heightPx + 2 * BORDER))
            panels.add(p)
        }
        builtVersion = SpaceState.panelsVersion
        SpaceState.pendingLoads.clear()
    }

    fun start() { if (!running) { running = true; lastFrameNs = 0; SpaceHost.space = this; Choreographer.getInstance().postFrameCallback(this) } }
    fun stop() { running = false; if (SpaceHost.space === this) SpaceHost.space = null; Choreographer.getInstance().removeFrameCallback(this) }

    fun destroy() { stop(); exitFullscreen(); panels.forEach { it.web.destroy() }; panels.clear() }

    private var styleSeen = SpaceState.styleVersion

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
        val st = SpaceState
        val nowSbs = !onPhoneScreen && st.stereo3d && Stereo.isSbs(width, height)
        if (nowSbs != sbs) {
            sbs = nowSbs; invalidate()
            st.notify(if (sbs) "Chế độ 3D" else "Chế độ 2D", if (sbs) "Màn hình có chiều sâu · phim 3D SBS xem toàn màn" else "Kính về hình thường", Colors.CYAN, 3.0)
        }
        val w = if (sbs) width / 2 else width; val h = height
        if (w == 0 || h == 0) return
        eyeW = w
        focalPx = Projection.focalPx(h, st.config.fovVDeg)
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

        if (st.recenterRequested) { st.recenterRequested = false; follow.reset(); body.reset() }
        if (styleSeen != st.styleVersion) { styleSeen = st.styleVersion; panels.forEach { applyDark(it.web, true) } }

        // camera orientation (cam -> world)
        C = if (M == null || st.headLocked || wz != null) Mat3.eye()
            else Projection.camera(tracker.predicted(st.config.predictMs), M)

        val cfg = st.config
        // the whole space's anchor: identity when fixed in the room, trailing the head in cinema / travel mode
        val F = body.update(C, if (st.headLocked) FollowMode.WORLD else st.followMode, dt)
        // carrying a screen with the head: it follows the gaze direction (expressed in the anchor frame)
        cfg.screens.getOrNull(st.grabbedPanel)?.let { g ->
            val (gy, gp) = Projection.yawPitch(Mat3.mul(Mat3.t(F), C))
            g.yaw = gy; g.pitch = gp
        }
        // fullscreen video: fills the glasses screen (scaled), drifts with the cinema anchor
        if (st.speedLocked && fullView != null) exitFullscreen()   // no film while riding
        if (fullView != null) {
            val k = if (sbs) 2 else 1                                     // SBS film spans both eyes
            val cw = (w * k * st.cinemaScale).toInt(); val ch = (h * st.cinemaScale).toInt()
            val lp = cinema.layoutParams
            if (lp.width != cw || lp.height != ch) { lp.width = cw; lp.height = ch; cinema.layoutParams = lp }
            val pl = Projection.place(C, F, cfg.distanceM, 1.0, w, h, cfg.fovVDeg)
            cinema.translationX = ((if (pl.visible) pl.cx * k else w * k / 2.0) - cw / 2.0).toFloat()
            cinema.translationY = ((if (pl.visible) pl.cy else h / 2.0) - ch / 2.0).toFloat()
        }
        // world-locked screens
        var best = -1; var bestD = Double.MAX_VALUE
        val nowMs = SystemClock.uptimeMillis()
        panels.forEachIndexed { i, p ->
            val spec = cfg.screens.getOrNull(i) ?: return@forEachIndexed
            if (st.walkMode || st.speedLocked || wz != null || fullView != null) { hide(p.frame); pauseIfStale(p, nowMs); return@forEachIndexed }
            val P = Mat3.mul(F, Mat3.mul(Mat3.rotY(spec.yaw), Mat3.rotX(spec.pitch)))
            val pl = Projection.place(C, P, cfg.distanceM, spec.widthM, w, h, cfg.fovVDeg)
            layoutOn(p.frame, pl, p.widthPx + 2.0 * BORDER, p.heightPx + 2.0 * BORDER)
            if (pl.visible) { p.lastSeen = nowMs; if (p.paused) { p.web.onResume(); p.paused = false } } else pauseIfStale(p, nowMs)
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
            val Fm = follow.update(C, dt)
            val P = Mat3.mul(Fm, Mat3.mul(Mat3.rotY(cfg.map.yaw), Mat3.rotX(cfg.map.pitch)))
            val pl = Projection.place(C, P, cfg.map.distanceM, cfg.map.widthM, w, h, cfg.fovVDeg)
            layoutOn(mapView, pl, MapPanelView.W.toDouble(), MapPanelView.H.toDouble())
            mapView.step(dt)
        } else hide(mapView)

        // head gestures (camera-frame gyro)
        if (M != null && wz == null) {
            val g = gestures.update(Mat3.mulV(M, tracker.lastGyro), now)
            when (g) {
                HeadGestures.Gesture.NOD -> if (st.grabbedPanel >= 0) st.toggleGrab() else {
                    val t = st.toast
                    val key = st.toastNotifKey
                    if (t != null && t.actionable && key != null) Assistant.replyByVoice(key)
                    else if (t != null && t.actionable) st.notify("Đã trả lời", "\"OK, 7h nhé!\"", Colors.GREEN, 2.5)
                    else st.setMap(null)
                }
                HeadGestures.Gesture.SHAKE -> {
                    val t = st.toast
                    st.notify("Lắc đầu", if (t != null && t.actionable) "Đã bỏ qua" else "Đóng", Colors.RED, 1.5)   // also clears toastNotifKey
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

        // slot numbers
        val showGrid = System.currentTimeMillis() < st.gridUntil && wz == null && !st.walkMode
        val used = cfg.screens.mapNotNull { SlotGrid.slotOf(it) }.toSet()
        for (k in 1..9) {
            val v = slotLabels[k - 1]
            if (!showGrid) { hide(v); continue }
            val P = Mat3.mul(F, Mat3.mul(Mat3.rotY(SlotGrid.yaw(k)), Mat3.rotX(SlotGrid.pitch(k))))
            if (k in used) {
                // small tag at the top-left corner of an occupied slot
                val pl = Projection.place(C, P, cfg.distanceM, 0.18, w, h, cfg.fovVDeg)
                val pw = Projection.place(C, P, cfg.distanceM, SlotGrid.width(k), w, h, cfg.fovVDeg)
                layoutOn(v, pl.copy(cx = pw.cx - pw.widthPx / 2 + pl.widthPx / 2, cy = pw.cy - pw.widthPx * 9 / 32), 220.0, 124.0)
                v.setTextColor(Colors.BORDER_FOCUS)
            } else {
                layoutOn(v, Projection.place(C, P, cfg.distanceM, 0.35, w, h, cfg.fovVDeg), 220.0, 124.0)
                v.setTextColor(0xFF7FA8C8.toInt())
            }
        }
        // assistant card (head-locked, lower centre)
        if (System.currentTimeMillis() < st.cardUntil && st.cardText.isNotEmpty()) {
            if (card.visibility != VISIBLE) card.visibility = VISIBLE
            val txt = "${st.cardTitle}\n\n${st.cardText}"
            if (card.text.toString() != txt) card.text = txt
            card.x = w / 2f - 410f; card.y = h * 0.42f
        } else if (card.visibility != GONE) card.visibility = GONE
        // live captions (head-locked, bottom)
        if (st.captionMode != vn.jiner.xrealspace.core.CaptionMode.OFF) {
            if (captionView.visibility != VISIBLE) captionView.visibility = VISIBLE
            if (captionsSeen != Assistant.captionsVersion) {
                captionsSeen = Assistant.captionsVersion
                val sb = SpannableStringBuilder()
                val lines = Assistant.captions.visible()
                if (lines.isEmpty()) sb.append("… đang nghe")
                lines.forEachIndexed { k, l ->
                    if (k > 0) sb.append('\n')
                    val tr = l.translation
                    if (tr != null) { val a = sb.length; sb.append(tr); sb.setSpan(ForegroundColorSpan(Colors.WHITE), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        sb.append('\n'); val b = sb.length; sb.append(l.text); sb.setSpan(ForegroundColorSpan(Colors.GREY), b, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                    else sb.append(l.text)
                }
                captionView.text = sb
            }
            captionView.x = w / 2f - 550f; captionView.y = h - captionView.height - 110f
        } else if (captionView.visibility != GONE) captionView.visibility = GONE
        // teleprompter (head-locked, upper middle): read words dim, next word highlighted
        val pr = Assistant.prompter
        if (pr != null) {
            if (prompterView.visibility != VISIBLE) prompterView.visibility = VISIBLE
            if (prompterSeen != Assistant.prompterVersion) {
                prompterSeen = Assistant.prompterVersion
                val sb = SpannableStringBuilder()
                val past = pr.words.subList(maxOf(0, pr.pos - 8), pr.pos).joinToString(" ")
                if (past.isNotEmpty()) { sb.append(past).append(' '); sb.setSpan(ForegroundColorSpan(0xFF5C6670.toInt()), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                val next = pr.words.subList(pr.pos, minOf(pr.words.size, pr.pos + 45))
                if (next.isEmpty()) { val a = sb.length; sb.append("— hết kịch bản —"); sb.setSpan(ForegroundColorSpan(Colors.GREEN), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
                next.forEachIndexed { k, word ->
                    val a = sb.length; sb.append(word).append(' ')
                    sb.setSpan(ForegroundColorSpan(if (k == 0) Colors.BORDER_FOCUS else Colors.WHITE), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    if (k == 0) sb.setSpan(StyleSpan(android.graphics.Typeface.BOLD), a, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                prompterView.text = sb
            }
            prompterView.x = w / 2f - 600f; prompterView.y = h * 0.16f
        } else if (prompterView.visibility != GONE) prompterView.visibility = GONE

        // voice indicator
        val vtext = when (st.voiceState) {
            "listening" -> "🎤 Đang nghe… " + st.voiceHeard
            "thinking" -> "✨ Đang hiểu: " + st.voiceHeard
            else -> ""
        }
        if (vtext.isNotEmpty()) {
            if (voice.visibility != VISIBLE) voice.visibility = VISIBLE
            if (voice.text.toString() != vtext) voice.text = vtext
            voice.x = (w - voice.width) / 2f; voice.y = h - voice.height - 30f
        } else if (voice.visibility != GONE) voice.visibility = GONE

        val needStill = wz == null && st.imuSource != SpaceState.ImuSource.NONE && tracker.biasState != ImuTracker.BiasState.READY
        if (needStill != (biasHint.visibility == VISIBLE)) biasHint.visibility = if (needStill) VISIBLE else GONE

        status.visibility = if (st.showStatus && wz == null) VISIBLE else GONE
        if (status.visibility == VISIBLE) {
            status.x = 20f; status.y = 20f
            status.text = "%s  imu %.0fHz  bias %s  render %.0ffps  focus %s".format(
                st.imuSource, tracker.rate, tracker.biasState, fps, if (best >= 0) cfg.screens[best].title else "-")
        }
    }

    /** Pause a WebView you have not looked at for a while (saves battery/heat); never the fullscreen one. */
    private fun pauseIfStale(p: Panel, nowMs: Long) {
        if (!SpaceState.pauseOffscreen || p.paused || p === fullOwner) return
        if (p.isMirror) return
        if (nowMs - p.lastSeen > 8000) { p.web.onPause(); p.paused = true }
    }

    private fun hide(v: View) { if (v.visibility != INVISIBLE) v.visibility = INVISIBLE }

    /** Place a view whose natural size is (vw x vh) so its centre lands on the projected point. */
    private fun layoutOn(v: View, pl: Placement, vw: Double, vh: Double) {
        if (!pl.visible) { hide(v); return }
        eyeShift[v] = if (sbs) Stereo.shiftPx(pl.z, focalPx, convergeM = SpaceState.stereoConvergeM) else 0f
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
        val pt = floatArrayOf((if (eyeW > 0) eyeW else width) / 2f - f.left, height / 2f - f.top)
        inv.mapPoints(pt)
        pt[0] -= BORDER; pt[1] -= BORDER
        if (pt[0] < 0 || pt[1] < 0 || pt[0] > p.content.width || pt[1] > p.content.height) return null
        return pt
    }

    /**
     * Phone touchpad: finger down = touch down at the gaze dot, finger moves = drag (scroll),
     * finger up = release. A short tap therefore becomes a click. [gain] converts phone px to web px.
     */
    fun touchpad(action: Int, dxTotal: Float, dyTotal: Float, gain: Float = 1.6f) {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                fullView?.let { fv -> fullTouch = fv; touchX = fv.width / 2f; touchY = fv.height / 2f; touchDownTime = SystemClock.uptimeMillis(); sendTo(fv, MotionEvent.ACTION_DOWN, touchX, touchY); return }
                val p = panels.getOrNull(SpaceState.focusedPanel) ?: return
                val pt = gazeLocal(p) ?: return
                touchTarget = p; touchX = pt[0]; touchY = pt[1]
                touchDownTime = SystemClock.uptimeMillis()
                if (!p.isMirror) send(p, MotionEvent.ACTION_DOWN, touchX, touchY)
            }
            MotionEvent.ACTION_MOVE -> fullTouch?.let { sendTo(it, MotionEvent.ACTION_MOVE, touchX + dxTotal * gain, touchY + dyTotal * gain) } ?: touchTarget?.let { if (!it.isMirror) send(it, MotionEvent.ACTION_MOVE, touchX + dxTotal * gain, touchY + dyTotal * gain) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> fullTouch?.let { sendTo(it, action, touchX + dxTotal * gain, touchY + dyTotal * gain); fullTouch = null } ?: touchTarget?.let {
                if (it.isMirror) {
                    // mirrored phone app: replay the finger as one Accessibility gesture
                    val c = it.content; val cs = ControlService.instance
                    if (cs == null) SpaceState.notify("Điều khiển app", "Bật Trợ năng cho XREAL Space để bấm vào màn hình điện thoại", Colors.AMBER, 3.0)
                    else if (action == MotionEvent.ACTION_UP) {
                        if (kotlin.math.hypot(dxTotal, dyTotal) < 20) cs.tapMirror(touchX, touchY, c.width.toFloat(), c.height.toFloat())
                        else cs.swipeMirror(touchX, touchY, touchX + dxTotal * gain * 0.6f, touchY + dyTotal * gain * 0.6f, c.width.toFloat(), c.height.toFloat())
                    }
                } else send(it, action, touchX + dxTotal * gain, touchY + dyTotal * gain)
                touchTarget = null
            }
        }
    }

    private var fullTouch: View? = null

    private fun send(p: Panel, action: Int, x: Float, y: Float) = sendTo(p.web, action, x, y)

    private fun sendTo(v: View, action: Int, x: Float, y: Float) {
        val ev = MotionEvent.obtain(touchDownTime, SystemClock.uptimeMillis(), action, x, y, 0)
        ev.source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        v.dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** Volume-key / remote click at the gaze dot (fullscreen: tap the middle = play/pause). */
    fun clickAtGaze(): Boolean {
        fullView?.let { fv ->
            touchDownTime = SystemClock.uptimeMillis()
            sendTo(fv, MotionEvent.ACTION_DOWN, fv.width / 2f, fv.height / 2f); sendTo(fv, MotionEvent.ACTION_UP, fv.width / 2f, fv.height / 2f)
            return true
        }
        val p = panels.getOrNull(SpaceState.focusedPanel) ?: return false
        val pt = gazeLocal(p) ?: return false
        if (p.isMirror) {
            val cs = ControlService.instance ?: run { SpaceState.notify("Điều khiển app", "Bật Trợ năng cho XREAL Space", Colors.AMBER, 3.0); return false }
            return cs.tapMirror(pt[0], pt[1], p.content.width.toFloat(), p.content.height.toFloat())
        }
        touchDownTime = SystemClock.uptimeMillis()
        send(p, MotionEvent.ACTION_DOWN, pt[0], pt[1])
        send(p, MotionEvent.ACTION_UP, pt[0], pt[1])
        return true
    }

    /** Two-finger swipe left: leave fullscreen, else go back in the page you look at. */
    fun back() { if (fullView != null) exitFullscreen() else focusedWeb()?.let { if (it.canGoBack()) it.goBack() } }

    fun forward() { focusedWeb()?.let { if (it.canGoForward()) it.goForward() } }

    /** Phone keyboard -> the focused text field of the page (works with React-style inputs too). */
    fun setFocusedInputValue(value: String) {
        val js = org.json.JSONObject.quote(value)
        focusedWeb()?.evaluateJavascript("""(function(v){var e=document.activeElement;if(!e)return;
            if(e.isContentEditable){document.execCommand('selectAll',false,null);document.execCommand('insertText',false,v);return;}
            var d=Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e),'value');if(d&&d.set)d.set.call(e,v);else e.value=v;
            e.dispatchEvent(new Event('input',{bubbles:true}));})($js)""".trimIndent(), null)
    }

    fun setDesktopFocused(on: Boolean) {
        val p = panels.getOrNull(SpaceState.focusedPanel)
        if (p == null || p.isMirror) { SpaceState.notify("Bản máy tính", "Nhìn vào một màn web trước", Colors.AMBER, 2.5); return }
        p.setDesktop(on)
        SpaceState.notify(if (on) "Bản máy tính" else "Bản điện thoại", "Đang tải lại trang", Colors.CYAN, 2.0)
    }

    fun focusedWeb(): WebView? = fullOwner?.web ?: panels.getOrNull(SpaceState.focusedPanel)?.takeIf { !it.isMirror }?.web
        ?: panels.firstOrNull { !it.isMirror }?.web

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

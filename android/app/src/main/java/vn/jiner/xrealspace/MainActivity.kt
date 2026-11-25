package vn.jiner.xrealspace

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.text.Editable
import android.text.TextWatcher
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import vn.jiner.xrealspace.core.FastPath
import vn.jiner.xrealspace.core.RemoteProtocol
import vn.jiner.xrealspace.core.MediaAction
import vn.jiner.xrealspace.core.FollowMode
import vn.jiner.xrealspace.core.VoiceCommands
import vn.jiner.xrealspace.core.WakeWord

/** Shows the space on the glasses (an external display) while the phone stays the controller. */
class SpacePresentation(ctx: Context, display: Display) : Presentation(ctx, display) {
    lateinit var space: SpaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        space = SpaceView(context, onPhoneScreen = false)
        setContentView(space)
        GlassesDisplay.attach(window, display)
    }

    override fun onStart() { super.onStart(); space.start(); SpaceState.glassesActive = true }
    override fun onStop() { space.stop(); SpaceState.glassesActive = false; GlassesDisplay.detach(window); super.onStop() }
}

class MainActivity : Activity(), TouchpadView.Host {
    companion object {
        const val ACTION_REQUEST_MIRROR = "vn.jiner.xrealspace.REQUEST_MIRROR"
        private const val REQ_MIRROR = 42
        const val ACTION_OPEN_DOC = "vn.jiner.xrealspace.OPEN_DOC"
        private const val REQ_DOC = 43
        /** true while this activity is in front (the Accessibility key filter only acts when it is NOT) */
        @Volatile var inForeground = false; private set
    }
    private val usb: GlassesUsb get() = Sensors.usb
    private val phoneImu: PhoneImu get() = Sensors.phone
    private lateinit var dm: DisplayManager
    private val ui = Handler(Looper.getMainLooper())

    private var presentation: SpacePresentation? = null
    private var phoneSpace: SpaceView? = null
    private var wantGlassesOutput = true
    private var started = false

    private lateinit var root: FrameLayout
    private lateinit var controls: ScrollView
    private lateinit var statusText: TextView
    private lateinit var diagText: TextView
    private lateinit var urlField: EditText
    private lateinit var typeField: EditText
    private lateinit var backBtn: Button
    private val voice: VoiceInput get() = Assistant.voice
    private var afterMirrorGrant: (() -> Unit)? = null
    private lateinit var padOverlay: FrameLayout
    private lateinit var kbField: EditText
    private var kbActive = false
    private var kbProgrammatic = false
    private lateinit var aiStatus: TextView

    private val space: SpaceView? get() = SpaceHost.space ?: phoneSpace ?: presentation?.space
    /** the glasses are hosted by GlassesService (overlay) so they survive other apps in front */
    private val serviceHosted get() = GlassesService.instance?.presentation != null

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { SpaceState.log("display added $id"); if (started && wantGlassesOutput) showOnGlasses(quiet = true) }
        override fun onDisplayRemoved(id: Int) {
            SpaceState.log("display removed $id")
            if (presentation?.display?.displayId == id) { presentation?.dismiss(); presentation = null; showTouchpad(false) }
            GlassesService.instance?.presentation?.let { if (it.display.displayId == id) { GlassesService.instance?.hide(); showTouchpad(false) } }
        }
        override fun onDisplayChanged(id: Int) {}
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.install(this)
        // the last launch died while starting: show why, offer safe mode, before touching anything else
        if (CrashLog.lastStartCrashed(this)) { showCrashScreen(); return }
        normalStart()
    }

    /** Error from the last start + choices. Buttons are reachable with the Beam's D-pad. */
    /** true while the crash screen is shown: the normal UI was never built, so skip its lifecycle */
    private var crashScreen = false

    private fun showCrashScreen() {
        crashScreen = true
        val text = CrashLog.lastStartReport()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        col.addView(TextView(this).apply { this.text = "XREAL Space bị lỗi ở lần mở trước"; textSize = 22f; setTextColor(Colors.AMBER) })
        col.addView(TextView(this).apply {
            this.text = "Gửi file lỗi cho người phát triển. Cắm Beam vào PC, mở: Android/data/vn.jiner.xrealspace/files/ (hoặc Download/) → xreal-space-crash.txt và xreal-space-logcat.txt."
            textSize = 14f; setTextColor(Color.WHITE)
        })
        val go = { safe: Boolean ->
            CrashLog.safeMode = safe; CrashLog.markStarted(this); CrashLog.clear()
            recreate()
        }
        col.addView(row(btn("▶ Chế độ an toàn") { go(true) }, btn("Thử lại bình thường") { go(false) }))
        // Beam's launcher hides Android Settings: open them from here to switch on USB debugging (ADB)
        col.addView(row(btn("Mở Developer options (bật USB debugging)") { openAndroidSettings(true) }, btn("Mở Cài đặt Android") { openAndroidSettings(false) }))
        col.addView(TextView(this).apply { this.text = text.take(6000); textSize = 12f; setTextColor(0xFFCFD8DC.toInt()); setTextIsSelectable(true) })
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0E1116.toInt()); addView(col) })
        col.getChildAt(2)?.let { (it as? LinearLayout)?.getChildAt(0)?.requestFocus() }
    }

    private fun openAndroidSettings(dev: Boolean) {
        val tries = buildList {
            if (dev) add(Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
            if (dev) add(Intent(android.provider.Settings.ACTION_DEVICE_INFO_SETTINGS))   // tap "Build number" 7x
            add(Intent(android.provider.Settings.ACTION_SETTINGS))
            add(Intent().setClassName("com.android.settings", "com.android.settings.Settings"))
        }
        for (i in tries) try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Throwable) {}
        android.widget.Toast.makeText(this, "Máy này chặn mở Cài đặt", android.widget.Toast.LENGTH_LONG).show()
    }

    private fun normalStart() {
        CrashLog.markStarting(this)
        ui.postDelayed({ CrashLog.markStarted(this) }, 8000)     // alive 8 s = this start is fine
        SpaceState.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // plugging the glasses in = connect IMU + show the space on them + phone becomes the touchpad, no buttons
        CrashLog.guard("Cảm biến kính (USB/IMU)") { Sensors.init(this) }
        Sensors.listener = { refreshStatus(); refreshSetup(); if (usb.connected && started && wantGlassesOutput) showOnGlasses(quiet = true) }
        dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        CrashLog.guard("Giọng nói (Vosk)") { Assistant.init(this) }
        Assistant.mirrorRequester = { after -> requestMirror(after) }
        Assistant.docPicker = { pickDoc() }
        buildUi()
        if (CrashLog.safeMode) {
            SpaceState.notify("Chế độ an toàn", "Đã tắt các phần phụ. Tắt ở mục Kiểm tra khi đã gửi lỗi.", Colors.AMBER, 8.0)
        } else {
            CrashLog.guard("Điều khiển từ xa (Wi-Fi)") { setupRemote() }
            CrashLog.guard("Cập nhật") { Updater.check(this) { refreshSetup() } }
        }
        dm.registerDisplayListener(displayListener, ui)
        SpaceState.log("App start. Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        CrashLog.guard("Kết nối kính (USB)") { connectGlasses() }
        // Beam: no external display to present on -> the space goes full-screen here and the Beam puts it on the glasses
        if (SpaceState.onBeam && glassesDisplay() == null) {
            SpaceState.log("XREAL Beam detected -> space full-screen, D-pad controls")
            showOnPhone()
            SpaceState.notify("Điều khiển trên Beam", "◀▶▲▼ xoay nhìn · nút giữa = bấm vào ô · Back = menu", Colors.CYAN, 8.0)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); if (!crashScreen) handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        if (i?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) connectGlasses()
        if (i?.action == ACTION_REQUEST_MIRROR) { val slot = i.getIntExtra("slot", 4); requestMirror { Assistant.ensureMirrorPanel(slot) } }
        if (i?.action == ACTION_OPEN_DOC) pickDoc()
    }

    override fun onStart() {
        if (crashScreen) { super.onStart(); return }
        super.onStart()
        started = true
        if (wantGlassesOutput) showOnGlasses(quiet = true)
        phoneSpace?.start()
        ui.post(ticker)
        if (SpaceState.wakeEnabled && hasMic()) voice.startWake()
    }

    override fun onResume() { super.onResume(); if (crashScreen) return; inForeground = true; refreshSetup() }
    override fun onPause() { inForeground = false; super.onPause() }

    override fun onStop() {
        if (crashScreen) { super.onStop(); return }
        CrashLog.markStarted(this)   // left the app normally before 8 s: not a crash
        started = false
        ui.removeCallbacks(ticker)
        if (!serviceHosted) voice.stopWake()   // service-hosted glasses keep listening while other apps are in front
        phoneSpace?.stop()
        presentation?.dismiss(); presentation = null
        showTouchpad(false)
        SpaceState.save()
        super.onStop()
    }

    override fun onDestroy() {
        if (crashScreen) { super.onDestroy(); return }
        dm.unregisterDisplayListener(displayListener)
        Sensors.listener = null   // USB/IMU live in Sensors (process-wide) and keep running for GlassesService
        Assistant.mirrorRequester = null
        Assistant.docPicker = null
        phoneSpace?.destroy()
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() { refreshStatus(); if (voice.modelStatus.startsWith("đang")) refreshAi(); ui.postDelayed(this, 300) }
    }

    // ------------------------------------------------------------------ actions
    private fun connectGlasses() {
        Sensors.connectGlasses()
        refreshStatus()
    }

    private fun usePhoneSensors() {
        if (Sensors.usePhone()) SpaceState.notify("Cảm biến điện thoại", "Đặt máy yên 1 giây để đo bias", Colors.CYAN, 3.0)
    }

    /** External displays that can host a Presentation (the glasses when plugged into a DP-alt-mode port). */
    private fun glassesDisplay(): Display? =
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    private fun showOnGlasses(quiet: Boolean = false) {
        if (SpaceState.onBeam && glassesDisplay() == null) { showOnPhone(); return }   // Beam: this screen IS the glasses
        wantGlassesOutput = true
        if (presentation != null || serviceHosted) { showTouchpad(true); return }
        // preferred: host the glasses in the foreground service so they stay on when you open another app
        if (GlassesService.canHost(this) && glassesDisplay() != null) {
            leavePhoneScreen()
            val it = Intent(this, GlassesService::class.java).setAction(GlassesService.ACTION_HOST)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(it) else startService(it)
            showTouchpad(true)
            return
        }
        val d = glassesDisplay()
        if (d == null) {
            if (!quiet) SpaceState.notify("Không thấy màn hình kính", "Thử chế độ 'Xem trên màn hình này'", Colors.AMBER)
            SpaceState.log("no presentation display")
            return
        }
        leavePhoneScreen()
        try {
            presentation = SpacePresentation(this, d).also { it.show() }
            SpaceState.log("Presentation on display ${d.displayId} '${d.name}'")
            showTouchpad(true)
        } catch (e: Exception) {
            SpaceState.log("Presentation failed: $e"); presentation = null
        }
    }

    private fun showOnPhone() {
        wantGlassesOutput = false
        presentation?.dismiss(); presentation = null; GlassesService.instance?.hide(); showTouchpad(false)
        if (phoneSpace != null) return
        val s = SpaceView(this, onPhoneScreen = true)
        phoneSpace = s
        root.addView(s, 0, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        controls.visibility = View.GONE
        backBtn.visibility = View.VISIBLE
        s.start()
    }

    private fun leavePhoneScreen() {
        phoneSpace?.let { it.destroy(); root.removeView(it) }
        phoneSpace = null
        controls.visibility = View.VISIBLE
        backBtn.visibility = View.GONE
    }

    // ------------------------------------------------------------------ voice + AI
    private fun hasMic() = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private var afterMicGrant: (() -> Unit)? = null

    private fun withMic(action: () -> Unit) {
        if (hasMic()) { action(); return }
        afterMicGrant = action
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    /** Push-to-talk (button, volume-key long press, keyboard V). */
    private fun pushToTalk() = withMic { voice.captureCommand() }

    private fun setWake(on: Boolean) {
        SpaceState.wakeEnabled = on; SpaceState.saveSettings()
        if (on) withMic {
            voice.ensureModel { ok ->
                if (ok) { voice.startWake(); SpaceState.notify("Từ đánh thức", "Nói \"${SpaceState.wakePhrases.first()}\" rồi ra lệnh", Colors.GREEN, 3.0) }
                else SpaceState.notify("Từ đánh thức", "Không tải được model Vosk: ${voice.modelStatus}", Colors.RED, 4.0)
                refreshAi()
            }
        } else voice.stopWake()
        refreshAi()
    }

    private fun handleUtterance(text: String) = Assistant.handleUtterance(text)
    private fun summarizeFocused() = Assistant.summarizeFocused()

    // ------------------------------------------------------------------ phone mirror (MediaProjection consent needs an Activity)
    private fun pickDoc() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/pdf", "application/epub+zip"))
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_DOC)
    }

    /** put a picked document on the glasses (slot 5 = straight ahead) and keep access after reboot */
    private fun openPickedDoc(uri: android.net.Uri) {
        try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
        val name = DocView.displayName(this, uri)
        SpaceState.place(5, DocView.PREFIX + uri, name.substringBeforeLast('.'))
        SpaceState.notify("📖 $name", "Vuốt 2 ngón trái/phải hoặc nói \"trang sau\" để lật", Colors.CYAN, 4.0)
    }

    private fun requestMirror(after: (() -> Unit)?) {
        if (!GlassesService.canHost(this)) {
            SpaceState.notify("Màn hình điện thoại", "Cho phép \"Hiển thị trên ứng dụng khác\" để kính vẫn chạy khi mở app khác", Colors.AMBER, 5.0)
            openOverlaySettings(); return
        }
        afterMirrorGrant = after
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
        @Suppress("DEPRECATION") startActivityForResult(mpm.createScreenCaptureIntent(), REQ_MIRROR)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DOC) { if (resultCode == RESULT_OK) data?.data?.let { openPickedDoc(it) }; return }
        if (requestCode != REQ_MIRROR) return
        if (resultCode != RESULT_OK || data == null) { SpaceState.notify("Màn hình điện thoại", "Đã huỷ", Colors.AMBER, 2.0); return }
        GlassesService.afterMirror = afterMirrorGrant; afterMirrorGrant = null
        val it = Intent(this, GlassesService::class.java).setAction(GlassesService.ACTION_MIRROR)
            .putExtra(GlassesService.EXTRA_CODE, resultCode).putExtra(GlassesService.EXTRA_DATA, data)
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(it) else startService(it)
        presentation?.dismiss(); presentation = null   // the service takes over the glasses
        showTouchpad(true)
    }

    /** Sites that block WebView (Google sign-in, some banks): open in the real browser, view it via the phone mirror. */
    private fun openInBrowser() {
        val url = space?.focusedWeb()?.url ?: urlField.text.toString().ifBlank { return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            SpaceState.notify("Đã mở trình duyệt", "Nói \"màn hình điện thoại\" để xem nó trên kính", Colors.CYAN, 4.0)
        } catch (e: Exception) { SpaceState.notify("Không mở được", e.message ?: "", Colors.AMBER, 3.0) }
    }

    private var setupText: TextView? = null

    /** What the user still has to allow, in one line each (the app works partially without each). */
    /** status panel only: a failing check must never take the whole app down (Beam: 0.10.1 died here) */
    private fun refreshSetup() {
        try { refreshSetupInner() } catch (e: Throwable) { SpaceState.log("refreshSetup: $e") }
    }

    private fun refreshSetupInner() {
        val t = setupText ?: return
        val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        fun mark(ok: Boolean) = if (ok) "✓" else "✗"
        val notif = android.os.Build.VERSION.SDK_INT < 33 || checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED
        val battery = pm.isIgnoringBatteryOptimizations(packageName)
        val items = HealthCheck.collect(this)
        t.text = buildString {
            append(vn.jiner.xrealspace.core.Health.summary(items)).append("\n")
            vn.jiner.xrealspace.core.Health.blocked(items).entries.take(3).forEach { (f, r) -> append("  ✗ $f — thiếu ${r.title}\n") }
            if (Updater.updateAvailable) append("  ⬆ ${Updater.status}\n")
            append("\n")
            append("${mark(usb.connected)} Kính đã nối (cắm kính, bấm Cho phép khi Android hỏi USB)\n")
            append("${mark(hasMic())} Micro — lệnh giọng nói, phụ đề, nhắc chữ\n")
            append("${mark(GlassesService.canHost(this@MainActivity))} Hiện trên app khác — kính vẫn chạy khi mở Zalo/Netflix\n")
            append("${mark(ControlService.instance != null)} Trợ năng — bấm vào app điện thoại từ kính, nút âm lượng ở mọi app\n")
            append("${mark(notif)} Thông báo — biểu tượng chạy nền\n")
            append("${mark(NotifListener.isEnabled(this@MainActivity))} Đọc thông báo ĐT — tin Zalo/Messenger hiện lên kính, gật đầu để trả lời\n")
            append("${mark(Utilities.calendarAllowed)} Lịch — lịch hôm nay trên kính, nhắc trước 10 phút\n")
            append("${mark(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)} Vị trí — chỉ đo tốc độ: ẩn màn khi đi xe (không lưu, không gửi)\n")
            append("${mark(battery)} Không tối ưu pin — Xiaomi/Oppo/Samsung hay tự tắt app chạy nền")
            val maker = android.os.Build.MANUFACTURER.lowercase()
            if (maker in listOf("xiaomi", "redmi", "poco", "oppo", "realme", "vivo")) append("\n  ↳ máy $maker: bật thêm \"Tự khởi chạy\" cho app trong Cài đặt → Ứng dụng")
        }
    }

    private fun openBatterySettings() {
        try { startActivity(Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
        catch (_: Exception) { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        SpaceState.notify("Pin", "Tìm XREAL Space → Không tối ưu hoá", Colors.CYAN, 4.0)
    }

    private fun openOverlaySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:$packageName")))
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS))
        SpaceState.notify("Trợ năng", "Chọn XREAL Space → Bật. Dùng để bấm vào app trên màn hình điện thoại và nút âm lượng khi mở app khác", Colors.CYAN, 6.0)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) { afterMicGrant?.invoke() }
        if (code == 4 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) Utilities.refreshCalendar()
        if (code == 3 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) { Sensors.keepAlive(); if (usb.connected) Sensors.startSpeed() }
        refreshSetup()
        afterMicGrant = null
    }

    // ------------------------------------------------------------------ keys: volume = remote, BT keyboard = prototype hotkeys
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (crashScreen) return super.onKeyDown(keyCode, event)
        val st = SpaceState
        // Beam / any D-pad remote while the space is on this screen: arrows turn the view, centre clicks at the dot
        if (phoneSpace != null && st.onBeam && Assistant.prompter == null) {
            val sp = space
            val web = sp?.pageWeb()
            // fullscreen video: ◀▶ seek 10 s, centre play/pause
            if (sp?.inFullscreen == true && web != null) when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { PageNav.seek(web, -10); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { PageNav.seek(web, 10); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> { if (event.repeatCount == 0) PageNav.playPause(web); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> return true
            }
            // inside a page: arrows jump between links/buttons (or scroll), centre clicks, Back = page back, hold Back = leave
            if (st.pageMode && web != null) when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { PageNav.move(web, -1, 0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { PageNav.move(web, 1, 0); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { PageNav.move(web, 0, -1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { PageNav.move(web, 0, 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.repeatCount == 0) PageNav.click(web) else if (event.repeatCount == 4) pushToTalk()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.repeatCount == 0) event.startTracking()
                    else if (event.repeatCount == 3) { backLong = true; leavePageMode() }
                    return true
                }
            }
            if (st.pageMode && web == null) st.pageMode = false
            // centre on a web screen = start browsing it
            if (!st.pageMode && web != null && event.repeatCount == 0 &&
                (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_BUTTON_A)) {
                enterPageMode(); return true
            }
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { st.look(25.0, 0.0); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { st.look(-25.0, 0.0); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { st.look(0.0, 15.0); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { st.look(0.0, -15.0); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                    if (event.repeatCount == 0) { if (space?.clickAtGaze() != true) st.showGrid(4.0) }
                    else if (event.repeatCount == 4) pushToTalk()          // hold centre = talk
                    return true
                }
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.repeatCount == 0) event.startTracking()
                else if (event.repeatCount == 3) { volLongPressed = true; pushToTalk() }   // hold = push-to-talk
            }
            KeyEvent.KEYCODE_V -> pushToTalk()
            KeyEvent.KEYCODE_G -> st.toggleGrab()
            KeyEvent.KEYCODE_T -> st.setFollow(st.followMode.next())
            KeyEvent.KEYCODE_D -> st.setDarkWeb(null)
            KeyEvent.KEYCODE_ESCAPE -> space?.back()
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> if (Assistant.prompter != null) Assistant.movePrompter(5) else return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_DPAD_LEFT -> if (Assistant.prompter != null) Assistant.movePrompter(-5) else return super.onKeyDown(keyCode, event)
            KeyEvent.KEYCODE_P -> Assistant.setPrompter(null)
            KeyEvent.KEYCODE_VOLUME_DOWN -> if (RemoteLink.isRemote) RemoteLink.send(RemoteProtocol.Msg.Key("recenter")) else st.recenter()
            KeyEvent.KEYCODE_R -> st.recenter()
            KeyEvent.KEYCODE_W -> st.toggleWalk()
            KeyEvent.KEYCODE_M -> st.setMap(null)
            KeyEvent.KEYCODE_N -> st.demoNotification()
            KeyEvent.KEYCODE_F -> st.headLocked = !st.headLocked
            KeyEvent.KEYCODE_H -> st.showStatus = !st.showStatus
            KeyEvent.KEYCODE_C -> st.startWizard()
            KeyEvent.KEYCODE_B -> st.tracker.recalibrateBias()
            KeyEvent.KEYCODE_DPAD_UP -> st.changeDistance(1.05)
            KeyEvent.KEYCODE_DPAD_DOWN -> st.changeDistance(1 / 1.05)
            KeyEvent.KEYCODE_LEFT_BRACKET -> st.changeFov(-0.5)
            KeyEvent.KEYCODE_RIGHT_BRACKET -> st.changeFov(0.5)
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    private var volLongPressed = false
    private var remoteText: TextView? = null

    /** A box with no touchscreen (original XREAL Beam) can only be driven from a phone: start the host server. */
    private fun setupRemote() {
        RemoteLink.onChange = {
            remoteText?.text = "Trạng thái: ${RemoteLink.status}" +
                if (RemoteLink.role == RemoteLink.Role.HOST) "\nIP máy này: ${localIp()} · MÃ GHÉP ĐÔI: ${RemoteLink.pin}" else ""
        }
        RemoteLink.onFocus = { v -> onPageInputFocus(v) }
        RemoteLink.onBlur = { hideKeyboard() }
        val noTouch = !packageManager.hasSystemFeature("android.hardware.touchscreen")
        if (noTouch || getSharedPreferences("remote", MODE_PRIVATE).getBoolean("host", false)) {
            RemoteLink.startHost(this)
            if (noTouch) SpaceState.log("no touchscreen -> remote host mode")
        }
        RemoteLink.onChange?.invoke()
    }

    private fun localIp(): String = try {
        java.net.NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it is java.net.Inet4Address }?.hostAddress ?: "?"
    } catch (_: Exception) { "?" }

    private var backLong = false

    private fun enterPageMode() {
        val st = SpaceState
        st.pageMode = true
        // turn the view straight onto that screen so it sits in front of you
        st.config.screens.getOrNull(st.focusedPanel)?.let { st.lookYaw = it.yaw; st.lookPitch = it.pitch }
        space?.pageWeb()?.let { PageNav.move(it, 0, 0) }
        st.notify("Duyệt trang", "◀▶▲▼ chọn link/nút · giữa = bấm · Back = trang trước · giữ Back = thoát", Colors.CYAN, 5.0)
    }

    private fun leavePageMode() {
        SpaceState.pageMode = false
        space?.pageWeb()?.let { PageNav.clear(it) }
        SpaceState.notify("Thoát duyệt trang", "◀▶▲▼ lại xoay nhìn", Colors.GREY, 2.0)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (crashScreen) return super.onKeyUp(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_BACK && SpaceState.pageMode && phoneSpace != null) {
            if (backLong) { backLong = false; return true }
            if (space?.inFullscreen == true) { space?.exitFullscreen(); return true }
            val web = space?.pageWeb()
            if (web != null && web.canGoBack()) web.goBack() else leavePageMode()
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (!volLongPressed && RemoteLink.isRemote) RemoteLink.send(RemoteProtocol.Msg.Key("click"))
            else if (!volLongPressed && space?.clickAtGaze() != true) SpaceState.notify("Click", "Không có màn nào dưới chấm ngắm", Colors.AMBER, 1.0)
            volLongPressed = false
            return true
        }
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true else super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (crashScreen) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        if (space?.inFullscreen == true) { space?.exitFullscreen(); return }
        if (::padOverlay.isInitialized && padOverlay.visibility == View.VISIBLE) { showTouchpad(false); return }
        if (phoneSpace != null) { leavePhoneScreen(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------------ eyes-free touchpad + phone keyboard
    override fun pad(action: Int, dx: Float, dy: Float) {
        if (RemoteLink.isRemote) RemoteLink.pad(action, dx, dy) else space?.touchpad(action, dx, dy)
    }
    override fun onHold() {
        if (kbActive) {
            Assistant.dictationSink = { t -> val cur = kbField.text.toString(); kbField.setText(if (cur.isBlank()) t else "$cur $t"); kbField.setSelection(kbField.text.length) }
            withMic { voice.captureCommand() }
        } else pushToTalk()
    }
    override fun onTwoTap() { if (RemoteLink.isRemote) RemoteLink.send(RemoteProtocol.Msg.TwoTap) else SpaceState.recenter() }
    override fun onThreeTap() { if (RemoteLink.isRemote) RemoteLink.send(RemoteProtocol.Msg.ThreeTap) else SpaceState.setFollow(SpaceState.followMode.next()) }
    override fun onSwipe2(dx: Float, dy: Float) {
        if (RemoteLink.isRemote) { RemoteLink.send(RemoteProtocol.Msg.Swipe2(dx, dy)); return }
        if (Assistant.prompter != null && kotlin.math.abs(dx) >= kotlin.math.abs(dy)) { Assistant.movePrompter(if (dx < 0) 8 else -8); return }
        if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) { if (dx < 0) space?.back() else space?.forward() }
        else if (dy < 0) SpaceState.showGrid(6.0) else SpaceState.toggleWalk()
    }

    private fun showTouchpad(on: Boolean) {
        if (!::padOverlay.isInitialized) return
        padOverlay.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) hideKeyboard()
    }

    private fun onPageInputFocus(value: String) {
        if (!::kbField.isInitialized) return
        kbActive = true
        kbProgrammatic = true; kbField.setText(value); kbField.setSelection(kbField.text.length); kbProgrammatic = false
        kbField.visibility = View.VISIBLE
        kbField.requestFocus()
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(kbField, InputMethodManager.SHOW_IMPLICIT)
        SpaceState.notify("⌨ Bàn phím", "Gõ trên điện thoại · giữ touchpad để đọc chính tả", Colors.CYAN, 2.0)
    }

    private fun hideKeyboard() {
        if (!::kbField.isInitialized) return
        kbActive = false; Assistant.dictationSink = null
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(kbField.windowToken, 0)
        kbField.clearFocus(); kbField.visibility = View.GONE
    }

    private fun buildTouchpadOverlay(): FrameLayout {
        val fl = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0xFF05080C.toInt()) }
        fl.addView(TouchpadView(this, this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        kbField = EditText(this).apply {
            setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY); hint = "Gõ vào ô đang chọn trên trang"
            imeOptions = EditorInfo.IME_ACTION_GO; visibility = View.GONE
            setBackgroundColor(0xFF1C232C.toInt()); setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(e: Editable?) {
                    if (kbProgrammatic) return
                    val v = e?.toString() ?: ""
                    if (RemoteLink.isRemote) RemoteLink.send(RemoteProtocol.Msg.Text(v)) else space?.setFocusedInputValue(v)
                }
            })
            setOnEditorActionListener { _, _, _ ->
                if (RemoteLink.isRemote) RemoteLink.send(RemoteProtocol.Msg.Enter) else space?.typeText("", true)
                hideKeyboard(); true
            }
        }
        fl.addView(kbField, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        fl.addView(btn("⚙") { showTouchpad(false) }.apply { alpha = 0.5f },
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END))
        return fl
    }

    // ------------------------------------------------------------------ status / diagnostics
    private fun refreshStatus() {
        if (!::statusText.isInitialized) return
        val st = SpaceState
        val t = st.tracker
        val out = when { presentation != null -> "kính (display ${presentation?.display?.displayId})"; phoneSpace != null -> "màn hình điện thoại"; else -> "chưa chiếu" }
        statusText.text = buildString {
            append("USB: ${usb.status}\n")
            append("IMU: ${st.imuSource}  %.0f Hz  bias %s  [%.2f %.2f %.2f]°/s\n".format(t.rate, t.biasState, t.bias[0], t.bias[1], t.bias[2]))
            if (st.imuSource == SpaceState.ImuSource.GLASSES)
                append("Gói: ${usb.packets} (IMU ${usb.parsed}, khác ${usb.otherPackets})${if (usb.lastError.isNotEmpty()) "\nLỗi: ${usb.lastError}" else ""}\n")
            append("Hiển thị: $out · khoảng cách %.2f m · FOV %.1f°\n".format(st.config.distanceM, st.config.fovVDeg))
            append("Chế độ: ${st.followMode.title} · web tối ${if (st.darkWeb) "bật" else "tắt"}\n")
            append("Tốc độ: ${if (Sensors.speedRunning) "%.0f km/h".format(Sensors.speedKmh) else "GPS tắt"}${if (st.speedLocked) " · ĐANG ẨN MÀN (đi nhanh)" else ""} · thông báo ${PhoneNotifs.feed.size()}${if (PhoneNotifs.feed.doNotDisturb) " (im lặng)" else ""}\n")
            append("Màn đang nhìn: ${st.config.screens.getOrNull(st.focusedPanel)?.title ?: "-"}")
            if (st.walkMode) append(" · ĐI BỘ"); if (st.headLocked) append(" · HEAD-LOCKED"); if (!st.showMap) append(" · tắt bản đồ")
        }
    }

    private fun refreshAi() {
        if (!::aiStatus.isInitialized) return
        val st = SpaceState
        aiStatus.text = buildString {
            append("Hiểu lệnh: ${st.intentEngine}")
            when (st.intentEngine) {
                SpaceState.IntentEngine.JEV -> append(if (st.jevReady) " (key ✓)" else " (chưa có key → dùng luật)")
                SpaceState.IntentEngine.LLM -> append(if (st.llmReady) " (${st.llmProvider} ${st.llmModel})" else " (chưa cấu hình → dùng luật)")
                else -> {}
            }
            append("\nNhận giọng: ${st.sttEngine} · model Vosk: ${voice.modelStatus}")
            append("\nTừ đánh thức: ${if (st.wakeEnabled) "BẬT" else "tắt"} (${st.wakePhrases.joinToString(" / ")})")
            append("\nKhông gian đã lưu: ${st.savedWorkspaces.values.joinToString { it.title }.ifEmpty { "-" }}")
            append("\nKính chạy nền: ${if (GlassesService.canHost(this@MainActivity)) "được phép" else "chưa cấp quyền"} · Trợ năng: ${if (ControlService.instance != null) "bật" else "tắt"} · chiếu ĐT: ${if (MirrorController.active) "đang chạy" else "tắt"}")
        }
    }

    private fun refreshDiag() {
        val sb = StringBuilder()
        CrashLog.read().takeIf { it.isNotEmpty() }?.let { sb.append("== LẦN CRASH GẦN NHẤT ==\n").append(it.take(4000)).append("\n\n") }
        sb.append("== Điều khiển từ xa ==\n${RemoteLink.role} · ${RemoteLink.status}\n\n")
        sb.append("== Màn hình (DisplayManager) ==\n")
        for (d in dm.displays) {
            val mode = d.mode
            sb.append("• #${d.displayId} '${d.name}' ${mode.physicalWidth}x${mode.physicalHeight} @%.0fHz flags=0x%x%s\n".format(
                mode.refreshRate, d.flags, if (dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).any { it.displayId == d.displayId }) " [presentation]" else ""))
        }
        sb.append("\n== USB ==\n").append(usb.describeAll())
        if (usb.lastOther.isNotEmpty()) sb.append("gói không phải IMU (mẫu): ${usb.lastOther}\n")
        sb.append("\n== Log ==\n").append(SpaceState.logText().lines().takeLast(60).joinToString("\n"))
        diagText.text = sb.toString()
    }

    // ------------------------------------------------------------------ UI (built in code: no layout XML, no AndroidX)
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        setOnClickListener { onClick(); refreshStatus() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun label(text: String) = TextView(this).apply {
        this.text = text; setTextColor(Colors.CYAN); textSize = 13f; setPadding(0, dp(12), 0, dp(4)); typeface = Typeface.DEFAULT_BOLD
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildUi() {
        root = FrameLayout(this).apply { setBackgroundColor(0xFF0E1116.toInt()) }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(40)) }
        controls = ScrollView(this).apply { addView(col) }

        col.addView(TextView(this).apply { text = "XREAL Space · test build"; setTextColor(Color.WHITE); textSize = 20f; typeface = Typeface.DEFAULT_BOLD })
        statusText = TextView(this).apply { setTextColor(0xFFCFD8DC.toInt()); textSize = 12.5f; typeface = Typeface.MONOSPACE; setPadding(0, dp(8), 0, dp(4)) }
        col.addView(statusText)

        col.addView(label("Thiết lập & kiểm tra"))
        col.addView(row(btn("🩺 Kiểm tra hệ thống") { startActivity(Intent(this, HealthActivity::class.java)) },
            btn("⬆ Cập nhật") { if (Updater.updateAvailable) Updater.downloadAndInstall(this) else Updater.check(this, quiet = false) { refreshSetup() } }))
        setupText = TextView(this).apply { setTextColor(0xFFCFD8DC.toInt()); textSize = 12.5f; setPadding(0, 0, 0, dp(4)) }
        col.addView(setupText)
        col.addView(row(btn("Micro") { withMic { refreshSetup() } }, btn("Hiện trên app khác") { openOverlaySettings() },
            btn("Trợ năng") { openAccessibilitySettings() }))
        col.addView(row(btn("Đọc thông báo ĐT") { NotifListener.openSettings(this) },
            btn("Vị trí (an toàn)") { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3) },
            btn("Lịch") { requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 4) }))
        col.addView(row(btn("Thông báo") { if (android.os.Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2) },
            btn("Không tối ưu pin") { openBatterySettings() }))

        col.addView(label("Nguồn & hiển thị"))
        col.addView(row(btn("Kết nối kính (USB)") { connectGlasses() }, btn("Dùng cảm biến ĐT") { usePhoneSensors() }))
        col.addView(row(btn("Chiếu lên kính") { showOnGlasses() }, btn("Xem trên màn hình này") { showOnPhone() }))

        col.addView(label("Touchpad — chạm = click tại chấm ngắm · kéo = cuộn trang"))
        val pad = View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF1C232C.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF2F3B48.toInt()) }
            var x0 = 0f; var y0 = 0f
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; pad(MotionEvent.ACTION_DOWN, 0f, 0f) }
                    MotionEvent.ACTION_MOVE -> pad(MotionEvent.ACTION_MOVE, e.x - x0, e.y - y0)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pad(e.actionMasked, e.x - x0, e.y - y0)
                }
                true
            }
        }
        col.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        col.addView(TextView(this).apply {
            text = "Nút âm lượng: + = click · − = về giữa. Gật đầu = OK/bật-tắt bản đồ, lắc đầu = bỏ qua."
            setTextColor(Colors.GREY); textSize = 12f; setPadding(0, dp(4), 0, 0)
        })

        col.addView(label("Điều khiển từ xa qua Wi-Fi (cho Beam đời đầu / máy không màn cảm ứng)"))
        remoteText = TextView(this).apply { setTextColor(Colors.GREY); textSize = 12f }
        col.addView(remoteText)
        val ipField = EditText(this).apply {
            hint = "IP máy kính, vd 192.168.1.23 (để trống = tự tìm)"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY)
            setText(getSharedPreferences("remote", MODE_PRIVATE).getString("ip", ""))
        }
        val pinField = EditText(this).apply {
            hint = "Mã ghép đôi 4 số (hiện trên kính)"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(getSharedPreferences("remote", MODE_PRIVATE).getString("client_pin", ""))
        }
        col.addView(row(ipField, pinField).apply {
            (getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = 2f
        })
        col.addView(row(
            btn("Làm máy chủ (máy nối kính)") { RemoteLink.startHost(this); getSharedPreferences("remote", MODE_PRIVATE).edit().putBoolean("host", true).apply() },
            btn("Điều khiển máy kính") {
                val ip = ipField.text.toString().trim()
                val pin = pinField.text.toString().trim()
                if (pin.length != 4) { SpaceState.notify("Điều khiển từ xa", "Nhập mã 4 số đang hiện trên kính", Colors.AMBER, 3.0); return@btn }
                getSharedPreferences("remote", MODE_PRIVATE).edit().putString("ip", ip).putString("client_pin", pin).putBoolean("host", false).apply()
                if (ip.isEmpty()) RemoteLink.discoverAndConnect(this, pin) else RemoteLink.connect(ip, pin)
                showTouchpad(true)
            },
            btn("Tắt") { RemoteLink.stop(); getSharedPreferences("remote", MODE_PRIVATE).edit().putBoolean("host", false).apply() },
            btn("Đổi mã") { if (RemoteLink.role == RemoteLink.Role.HOST) RemoteLink.newPin(this) }))

        col.addView(label("Thông báo điện thoại & an toàn khi đi xe"))
        fun rideLabel() = "Ẩn màn khi đi nhanh: ${if (SpaceState.rideSafety) "BẬT" else "TẮT"}"
        lateinit var rideBtn: Button
        rideBtn = btn(rideLabel()) {
            SpaceState.rideSafety = !SpaceState.rideSafety; SpaceState.saveSettings()
            if (!SpaceState.rideSafety && SpaceState.speedLocked) { SpaceState.speedLocked = false }
            rideBtn.text = rideLabel()
        }
        col.addView(row(btn("Đọc thông báo") { PhoneNotifs.read() }, btn("Im lặng bật/tắt") { PhoneNotifs.mute(!PhoneNotifs.feed.doNotDisturb, null) }, rideBtn))
        col.addView(TextView(this).apply {
            text = "Nói: \"đọc thông báo\" · \"trả lời …\" · \"tắt thông báo Zalo\" · \"im lặng\". Tin nhắn hiện lên: gật = trả lời bằng giọng, lắc = bỏ qua. Trên ~15 km/h: ẩn màn, thông báo chỉ hiện tên app."
            setTextColor(Colors.GREY); textSize = 12f
        })

        col.addView(label("Bản đồ & chỉ đường (GPS thật · OpenStreetMap)"))
        val destField = EditText(this).apply {
            hint = "Đi đến… (vd: Chợ Bến Thành)"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY)
        }
        col.addView(destField)
        fun go(profile: String) {
            val q = destField.text.toString().trim()
            if (q.isEmpty()) { SpaceState.notify("Chỉ đường", "Nhập nơi muốn đến", Colors.AMBER, 2.5); return }
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3); return
            }
            hideKeyboard(); Navigation.start(q, profile)
        }
        col.addView(row(btn("🏍 Xe") { go("car") }, btn("🚲 Đạp") { go("bike") }, btn("🚶 Đi bộ") { go("foot") }, btn("Dừng") { Navigation.stop() }))

        col.addView(label("Kính (thay phần cài đặt của Nebula)"))
        val glassesInfo = TextView(this).apply { setTextColor(0xFFCFD8DC.toInt()); textSize = 12.5f }
        col.addView(glassesInfo)
        fun refreshGlassesInfo() { glassesInfo.text = GlassesDisplay.describe() }
        col.addView(row(btn("☀ −") { SpaceState.setBrightness(SpaceState.glassesBrightness - 0.1f); refreshGlassesInfo() },
            btn("☀ +") { SpaceState.setBrightness(SpaceState.glassesBrightness + 0.1f); refreshGlassesInfo() },
            btn("Tần số quét") { SpaceState.notify("Tần số quét", GlassesDisplay.cycleRefresh(), Colors.CYAN, 2.0); refreshGlassesInfo() },
            btn("Thông tin") { refreshGlassesInfo() }))
        refreshGlassesInfo()

        col.addView(label("Tài liệu & tiện ích"))
        col.addView(row(btn("📖 Mở PDF/EPUB") { pickDoc() },
            btn("Đọc tiếp") { DocPositions.lastDoc?.let { SpaceState.place(5, it, "Tài liệu") } ?: SpaceState.notify("Tài liệu", "Chưa mở tài liệu nào", Colors.AMBER, 2.0) },
            btn("◀ Trang") { SpaceHost.space?.docPage(-1, null) }, btn("Trang ▶") { SpaceHost.space?.docPage(1, null) }))
        col.addView(row(btn("🎓 Hướng dẫn trên kính") { SpaceState.startTutorial() }, btn("Bỏ qua hướng dẫn") { SpaceState.skipTutorial() }))
        col.addView(row(btn("🍅 Pomodoro") { Utilities.startTimer(25, "Pomodoro") }, btn("⏱ Huỷ") { Utilities.stopTimer() },
            btn("📅 Hôm nay") { Utilities.showAgenda() }, btn("☁ Thời tiết") { Utilities.showWeather() }))
        col.addView(row(btn("📝 Xem ghi chú") { Utilities.showNotes() }, btn("Chia sẻ ghi chú") {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, "Ghi chú XREAL Space").putExtra(Intent.EXTRA_TEXT, Utilities.exportNotes()), "Chia sẻ ghi chú"))
        }))
        col.addView(TextView(this).apply {
            text = "Nói: \"mở tài liệu\" · \"trang sau/trước\" · \"trang 12\" · \"pomodoro\" · \"hẹn giờ 10 phút\" · \"ghi chú gọi anh Khoa\" · \"đọc ghi chú\" · \"lịch hôm nay\" · \"thời tiết\""
            setTextColor(Colors.GREY); textSize = 12f
        })

        col.addView(label("Xem phim trên web & 3D"))
        fun adLabel() = "Chặn quảng cáo: ${if (SpaceState.adBlock) "BẬT" else "TẮT"}"
        fun s3dLabel() = "3D (kính chế độ SBS): ${if (SpaceState.stereo3d) "BẬT" else "TẮT"}"
        lateinit var adBtn: Button; lateinit var s3dBtn: Button
        adBtn = btn(adLabel()) { SpaceState.adBlock = !SpaceState.adBlock; SpaceState.saveSettings(); adBtn.text = adLabel() }
        s3dBtn = btn(s3dLabel()) { SpaceState.stereo3d = !SpaceState.stereo3d; SpaceState.saveSettings(); s3dBtn.text = s3dLabel() }
        col.addView(row(adBtn, btn("Bản máy tính") { space?.setDesktopFocused(true) }, btn("Bản ĐT") { space?.setDesktopFocused(false) }))
        fun speakLabel() = "Đọc to: ${SpaceState.speakMode.title}"
        lateinit var speakBtn: Button
        speakBtn = btn(speakLabel()) { SpaceState.setSpeak(SpaceState.speakMode.next()); speakBtn.text = speakLabel() }
        col.addView(row(btn("⏪ 10s") { SpaceState.execute(VoiceCommands.Cmd.Media(MediaAction.SEEK, -10)) },
            btn("⏸") { SpaceState.execute(VoiceCommands.Cmd.Media(MediaAction.PAUSE)) },
            btn("▶") { SpaceState.execute(VoiceCommands.Cmd.Media(MediaAction.PLAY)) },
            btn("⏩ 10s") { SpaceState.execute(VoiceCommands.Cmd.Media(MediaAction.SEEK, 10)) }, speakBtn))
        col.addView(row(s3dBtn,
            btn("Độ nổi +") { SpaceState.stereoConvergeM = (SpaceState.stereoConvergeM + 1).coerceAtMost(12.0); SpaceState.saveSettings()
                SpaceState.notify("Độ nổi 3D", "Mặt phẳng phẳng: %.0f m".format(SpaceState.stereoConvergeM), Colors.CYAN, 2.0) },
            btn("Độ nổi −") { SpaceState.stereoConvergeM = (SpaceState.stereoConvergeM - 1).coerceAtLeast(1.5); SpaceState.saveSettings()
                SpaceState.notify("Độ nổi 3D", "Mặt phẳng phẳng: %.1f m".format(SpaceState.stereoConvergeM), Colors.CYAN, 2.0) }))
        col.addView(TextView(this).apply {
            text = "Phim 3D: mở video SBS → toàn màn hình → giữ nút Độ sáng + trên kính ~2 giây (nghe 1 tiếng tách). Phim thường thì giữ lại để về 2D. " +
                "Trang phim tự nhớ chỗ đang xem (video nằm trong khung của trang khác thì không nhớ được)."
            setTextColor(Colors.GREY); textSize = 12f
        })

        col.addView(label("Chế độ (xem phim · làm việc · di chuyển)"))
        col.addView(row(btn("Cố định") { SpaceState.setFollow(FollowMode.WORLD) }, btn("Rạp phim") { SpaceState.setFollow(FollowMode.CINEMA) },
            btn("Di chuyển") { SpaceState.setFollow(FollowMode.TRAVEL) }))
        col.addView(row(btn("Web tối bật/tắt") { SpaceState.setDarkWeb(null) },
            btn("Rạp −") { SpaceState.cinemaScale = (SpaceState.cinemaScale - 0.05).coerceIn(0.4, 1.0); SpaceState.saveSettings() },
            btn("Rạp +") { SpaceState.cinemaScale = (SpaceState.cinemaScale + 0.05).coerceIn(0.4, 1.0); SpaceState.saveSettings() },
            btn("Touchpad") { showTouchpad(true) }))
        col.addView(row(btn("Tạm dừng màn khuất: bật/tắt") {
            SpaceState.pauseOffscreen = !SpaceState.pauseOffscreen; SpaceState.saveSettings()
            SpaceState.notify("Tạm dừng màn khuất", if (SpaceState.pauseOffscreen) "bật (đỡ nóng máy)" else "tắt (nhạc ở ô khác vẫn chạy)", Colors.CYAN, 2.0)
        }))

        col.addView(label("Không gian"))
        col.addView(row(btn("Về giữa") { SpaceState.recenter() }, btn("Đi bộ") { SpaceState.toggleWalk() },
            btn("Bản đồ") { SpaceState.setMap(null) }, btn("Head-lock") { SpaceState.headLocked = !SpaceState.headLocked }))
        col.addView(row(btn("Xa hơn") { SpaceState.changeDistance(1.1) }, btn("Gần hơn") { SpaceState.changeDistance(1 / 1.1) },
            btn("FOV −") { SpaceState.changeFov(-0.5) }, btn("FOV +") { SpaceState.changeFov(0.5) }))
        col.addView(row(btn("Đo lại bias") { SpaceState.tracker.recalibrateBias() }, btn("Hiệu chỉnh trục") { SpaceState.startWizard() },
            btn("Thông báo mẫu") { SpaceState.demoNotification() }, btn("Status") { SpaceState.showStatus = !SpaceState.showStatus }))
        col.addView(row(btn("Ghim màn mới ở hướng nhìn") {
            val (y, p) = space?.gazeYawPitch() ?: (0.0 to 0.0); SpaceState.addPanelAt(y, p)
        }, btn("Xoá màn đang nhìn") { SpaceState.removePanel(SpaceState.focusedPanel) }))

        col.addView(label("Trang web trên màn đang nhìn"))
        urlField = EditText(this).apply {
            hint = "URL hoặc từ khoá"; setSingleLine(); inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Color.WHITE); setHintTextColor(Colors.GREY)
        }
        col.addView(row(urlField, btn("Mở") { if (urlField.text.isNotBlank()) SpaceState.openUrl(null, urlField.text.toString()) }).apply {
            (getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = 3f
        })
        col.addView(row(btn("◀ Back") { space?.focusedWeb()?.let { if (it.canGoBack()) it.goBack() } },
            btn("▶ Tới") { space?.focusedWeb()?.let { if (it.canGoForward()) it.goForward() } },
            btn("⟳ Tải lại") { space?.focusedWeb()?.reload() },
            btn("Mở bằng Chrome") { openInBrowser() }))
        typeField = EditText(this).apply { hint = "Gõ chữ vào ô đang chọn trên trang"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY) }
        col.addView(row(typeField, btn("Gõ") { space?.typeText(typeField.text.toString(), false); typeField.setText("") },
            btn("Enter") { space?.typeText(typeField.text.toString(), true); typeField.setText("") }).apply {
            (getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = 2.5f
        })

        // ---------------------------------------------------------------- AI
        // ---------------------------------------------------------------- phone apps on the glasses
        col.addView(label("Điện thoại trên kính (Zalo, Netflix, Grab…)"))
        col.addView(TextView(this).apply {
            text = "Cần 2 quyền: \"Hiển thị trên ứng dụng khác\" (kính vẫn chạy khi mở app khác) và Trợ năng (bấm vào app qua chấm ngắm, nút âm lượng khi mở app khác)."
            setTextColor(Colors.GREY); textSize = 12f
        })
        col.addView(row(btn("Quyền hiện trên app khác") { openOverlaySettings() }, btn("Bật Trợ năng") { openAccessibilitySettings() }))
        col.addView(row(btn("Chiếu ĐT vào ô 4") { Assistant.mirror(4) }, btn("Tắt chiếu") { Assistant.mirrorOff() }))
        val appField = EditText(this).apply { hint = "Tên app, vd: Zalo"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY) }
        col.addView(row(appField, btn("Mở app") { if (appField.text.isNotBlank()) Assistant.launchApp(appField.text.toString()) }).apply {
            (getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = 3f
        })

        // ---------------------------------------------------------------- live captions / interpreter
        col.addView(label("Phụ đề & phiên dịch trực tiếp"))
        col.addView(row(btn("Tắt") { Assistant.setCaptions(vn.jiner.xrealspace.core.CaptionMode.OFF) },
            btn("Phụ đề Việt") { withMic { Assistant.setCaptions(vn.jiner.xrealspace.core.CaptionMode.VI) } },
            btn("English") { withMic { Assistant.setCaptions(vn.jiner.xrealspace.core.CaptionMode.EN) } }))
        col.addView(row(btn("Dịch Anh → Việt") { withMic { Assistant.setCaptions(vn.jiner.xrealspace.core.CaptionMode.EN_TO_VI) } },
            btn("Dịch Việt → Anh") { withMic { Assistant.setCaptions(vn.jiner.xrealspace.core.CaptionMode.VI_TO_EN) } }))
        col.addView(row(btn("🎬 Phụ đề phim (tiếng phim)") { withMic { Assistant.setCaptions(vn.jiner.xrealspace.core.CaptionMode.VI, media = true) } },
            btn("🎬 Dịch phim Anh → Việt") { withMic { Assistant.setCaptions(vn.jiner.xrealspace.core.CaptionMode.EN_TO_VI, media = true) } }))

        // ---------------------------------------------------------------- teleprompter
        col.addView(label("Teleprompter (chữ chạy theo giọng đọc)"))
        val scriptField = EditText(this).apply {
            hint = "Dán kịch bản vào đây"; setText(SpaceState.prompterScript); minLines = 3; maxLines = 8
            setTextColor(Color.WHITE); setHintTextColor(Colors.GREY); textSize = 13f
        }
        col.addView(scriptField)
        col.addView(row(btn("▶ Bắt đầu") { SpaceState.prompterScript = scriptField.text.toString(); SpaceState.saveSettings(); withMic { Assistant.setPrompter(true) } },
            btn("■ Dừng") { Assistant.setPrompter(false) },
            btn("◀ Lùi") { Assistant.movePrompter(-8) }, btn("Tới ▶") { Assistant.movePrompter(8) }))

        col.addView(label("AI · giọng nói → lệnh"))
        aiStatus = TextView(this).apply { setTextColor(0xFFCFD8DC.toInt()); textSize = 12f; typeface = Typeface.MONOSPACE }
        col.addView(aiStatus)
        col.addView(row(btn("🎤 Nói lệnh") { pushToTalk() }, btn("Từ đánh thức bật/tắt") { setWake(!SpaceState.wakeEnabled) }))
        col.addView(row(btn("Hiện lưới 9 ô") { SpaceState.showGrid(8.0) }, btn("Cầm / đặt màn") { SpaceState.toggleGrab() },
            btn("Tóm tắt trang") { summarizeFocused() }))
        val cmdField = EditText(this).apply { hint = "Gõ lệnh thử, vd: youtube ô 3"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY) }
        col.addView(row(cmdField, btn("Chạy") { if (cmdField.text.isNotBlank()) handleUtterance(cmdField.text.toString()); cmdField.setText("") }).apply {
            (getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = 3f
        })
        col.addView(TextView(this).apply {
            text = "Ví dụ: \"YouTube ô 3\" · \"tìm thời tiết Sài Gòn ô 6\" · \"xoá ô 7\" · \"lưu không gian code\" · \"mở không gian code\" · " +
                "\"về giữa\" · \"tóm tắt trang này\". Giữ nút âm lượng + = nói lệnh."
            setTextColor(Colors.GREY); textSize = 12f
        })

        col.addView(label("Cài đặt AI"))
        col.addView(TextView(this).apply { text = "Hiểu lệnh bằng:"; setTextColor(Colors.GREY); textSize = 12f })
        col.addView(row(btn("Luật (offline)") { SpaceState.intentEngine = SpaceState.IntentEngine.RULES; SpaceState.saveSettings(); refreshAi() },
            btn("Jev") { SpaceState.intentEngine = SpaceState.IntentEngine.JEV; SpaceState.saveSettings(); refreshAi() },
            btn("LLM") { SpaceState.intentEngine = SpaceState.IntentEngine.LLM; SpaceState.saveSettings(); refreshAi() }))
        col.addView(TextView(this).apply { text = "Nhận giọng nói bằng:"; setTextColor(Colors.GREY); textSize = 12f })
        col.addView(row(btn("Vosk offline (free)") { SpaceState.sttEngine = SpaceState.SttEngine.VOSK; SpaceState.saveSettings(); voice.ensureModel { refreshAi() }; refreshAi() },
            btn("Google (online)") { SpaceState.sttEngine = SpaceState.SttEngine.GOOGLE; SpaceState.saveSettings(); refreshAi() },
            btn("Tải model Vosk") { voice.ensureModel { refreshAi() }; refreshAi() }))

        fun field(hint: String, value: String, secret: Boolean = false) = EditText(this).apply {
            this.hint = hint; setText(value); setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY); textSize = 13f
            if (secret) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val wakeF = field("Từ đánh thức, cách nhau bằng |", SpaceState.wakePhrases.joinToString("|"))
        val jevUrlF = field("Jev endpoint", SpaceState.jevBaseUrl)
        val jevModelF = field("Jev model", SpaceState.jevModel)
        val jevKeyF = field("Jev API key (OpenRouter hoặc TypeSafe)", SpaceState.jevKey, secret = true)
        val llmUrlF = field("LLM endpoint", SpaceState.llmBaseUrl)
        val llmModelF = field("LLM model", SpaceState.llmModel)
        val llmKeyF = field("LLM API key (để trống nếu Ollama)", SpaceState.llmKey, secret = true)
        col.addView(wakeF)
        col.addView(TextView(this).apply { text = "Jev (TypeSafe, qua OpenRouter mặc định):"; setTextColor(Colors.GREY); textSize = 12f })
        col.addView(jevUrlF); col.addView(jevModelF); col.addView(jevKeyF)
        col.addView(TextView(this).apply { text = "LLM:"; setTextColor(Colors.GREY); textSize = 12f })
        col.addView(row(
            btn("Claude") {
                SpaceState.llmProvider = "anthropic"; llmUrlF.setText("https://api.anthropic.com/v1/messages"); llmModelF.setText("claude-haiku-5-5")
            },
            btn("Ollama (LAN)") {
                SpaceState.llmProvider = "openai"; llmUrlF.setText("http://192.168.1.10:11434/v1/chat/completions"); llmModelF.setText("qwen2.5:7b")
            },
            btn("OpenAI-compat") { SpaceState.llmProvider = "openai" },
        ))
        col.addView(llmUrlF); col.addView(llmModelF); col.addView(llmKeyF)
        col.addView(btn("Lưu cài đặt AI") {
            val st = SpaceState
            st.wakePhrases = wakeF.text.toString().split("|").map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { WakeWord.DEFAULT_PHRASES }
            st.jevBaseUrl = jevUrlF.text.toString().trim(); st.jevModel = jevModelF.text.toString().trim(); st.jevKey = jevKeyF.text.toString().trim()
            st.llmBaseUrl = llmUrlF.text.toString().trim(); st.llmModel = llmModelF.text.toString().trim(); st.llmKey = llmKeyF.text.toString().trim()
            if (st.llmBaseUrl.contains("anthropic.com")) st.llmProvider = "anthropic" else if (st.llmProvider == "anthropic") st.llmProvider = "openai"
            st.saveSettings()
            if (st.wakeEnabled) { voice.stopWake(); voice.startWake() }
            st.notify("Đã lưu", "Cài đặt AI", Colors.GREEN, 1.5); refreshAi()
        })
        refreshAi()

        col.addView(label("Chẩn đoán (gửi phần này khi test Beam Pro)"))
        col.addView(row(btn("Làm mới") { refreshDiag() }, btn("Copy") {
            refreshDiag()
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("xreal-diag", statusText.text.toString() + "\n\n" + diagText.text))
            SpaceState.notify("Đã copy", "Dán vào chat để gửi", Colors.GREEN, 1.5)
        }))
        diagText = TextView(this).apply { setTextColor(0xFF9FB3C8.toInt()); textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(diagText)
        col.addView(label("Cài đặt"))
        col.addView(row(btn("Reset bố cục") { SpaceState.resetLayout() }, btn("Reset hiệu chỉnh trục") { SpaceState.resetCalibration() },
            btn("Xoá crash log") { CrashLog.clear(); refreshDiag() }))

        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        backBtn = btn("⚙ Điều khiển") { leavePhoneScreen(); if (glassesDisplay() != null) showOnGlasses(quiet = true) }.apply {
            visibility = View.GONE; alpha = 0.7f
        }
        root.addView(backBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        padOverlay = buildTouchpadOverlay()
        root.addView(padOverlay, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        SpaceState.onInputFocus = { v -> onPageInputFocus(v) }
        SpaceState.onInputBlur = { ui.postDelayed({ if (kbActive && !kbField.hasFocus()) hideKeyboard() }, 400) }
        setContentView(root)
        refreshDiag()
    }
}

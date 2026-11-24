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
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import vn.jiner.xrealspace.core.VoiceCommands

/** Shows the space on the glasses (an external display) while the phone stays the controller. */
class SpacePresentation(ctx: Context, display: Display) : Presentation(ctx, display) {
    lateinit var space: SpaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        space = SpaceView(context, onPhoneScreen = false)
        setContentView(space)
    }

    override fun onStart() { super.onStart(); space.start() }
    override fun onStop() { space.stop(); super.onStop() }
}

class MainActivity : Activity() {
    private lateinit var usb: GlassesUsb
    private lateinit var phoneImu: PhoneImu
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
    private var speech: SpeechRecognizer? = null

    private val space: SpaceView? get() = phoneSpace ?: presentation?.space

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { SpaceState.log("display added $id"); if (started && wantGlassesOutput) showOnGlasses(quiet = true) }
        override fun onDisplayRemoved(id: Int) {
            SpaceState.log("display removed $id")
            if (presentation?.display?.displayId == id) { presentation?.dismiss(); presentation = null }
        }
        override fun onDisplayChanged(id: Int) {}
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpaceState.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        usb = GlassesUsb(this) { ui.post { refreshStatus() } }
        phoneImu = PhoneImu(this)
        dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        buildUi()
        usb.register()
        dm.registerDisplayListener(displayListener, ui)
        SpaceState.log("App start. Android ${android.os.Build.VERSION.RELEASE} / ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        connectGlasses()
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }

    private fun handleIntent(i: Intent?) {
        if (i?.action == android.hardware.usb.UsbManager.ACTION_USB_DEVICE_ATTACHED) connectGlasses()
    }

    override fun onStart() {
        super.onStart()
        started = true
        if (wantGlassesOutput) showOnGlasses(quiet = true)
        phoneSpace?.start()
        ui.post(ticker)
    }

    override fun onStop() {
        started = false
        ui.removeCallbacks(ticker)
        phoneSpace?.stop()
        presentation?.dismiss(); presentation = null
        SpaceState.save()
        super.onStop()
    }

    override fun onDestroy() {
        dm.unregisterDisplayListener(displayListener)
        usb.unregister(); usb.close(); phoneImu.stop()
        speech?.destroy()
        phoneSpace?.destroy()
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() { refreshStatus(); ui.postDelayed(this, 300) }
    }

    // ------------------------------------------------------------------ actions
    private fun connectGlasses() {
        if (usb.connect()) { phoneImu.stop() }
        refreshStatus()
    }

    private fun usePhoneSensors() {
        usb.close()
        if (phoneImu.start()) SpaceState.notify("Cảm biến điện thoại", "Đặt máy yên 1 giây để đo bias", Colors.CYAN, 3.0)
    }

    /** External displays that can host a Presentation (the glasses when plugged into a DP-alt-mode port). */
    private fun glassesDisplay(): Display? =
        dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull { it.displayId != Display.DEFAULT_DISPLAY }

    private fun showOnGlasses(quiet: Boolean = false) {
        wantGlassesOutput = true
        if (presentation != null) return
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
        } catch (e: Exception) {
            SpaceState.log("Presentation failed: $e"); presentation = null
        }
    }

    private fun showOnPhone() {
        wantGlassesOutput = false
        presentation?.dismiss(); presentation = null
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

    private fun startVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            SpaceState.notify("Giọng nói", "Máy không có dịch vụ nhận giọng nói (cần Google app)", Colors.RED); return
        }
        val sr = speech ?: SpeechRecognizer.createSpeechRecognizer(this).also { speech = it }
        sr.setRecognitionListener(object : RecognitionListener {
            override fun onResults(b: Bundle) {
                val text = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                SpaceState.log("voice: $text")
                val cmd = VoiceCommands.parse(text)
                if (cmd !is VoiceCommands.Cmd.Unknown) SpaceState.notify("🎤 $text", cmd.javaClass.simpleName, Colors.GREEN, 2.0)
                SpaceState.execute(cmd)
            }
            override fun onError(error: Int) { SpaceState.notify("Giọng nói", "Lỗi nhận dạng ($error)", Colors.RED, 2.0) }
            override fun onReadyForSpeech(p: Bundle?) { SpaceState.notify("🎤 Đang nghe…", "Ví dụ: “mở YouTube bên trái”, “về giữa”, “tắt bản đồ”", Colors.CYAN, 5.0) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(b: Bundle?) {}
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
        })
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) startVoice()
    }

    // ------------------------------------------------------------------ keys: volume = remote, BT keyboard = prototype hotkeys
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val st = SpaceState
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { if (space?.clickAtGaze() != true) st.notify("Click", "Không có màn nào dưới chấm ngắm", Colors.AMBER, 1.0) }
            KeyEvent.KEYCODE_VOLUME_DOWN -> st.recenter()
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

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true else super.onKeyUp(keyCode, event)

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (phoneSpace != null) { leavePhoneScreen(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
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
            append("Màn đang nhìn: ${st.config.screens.getOrNull(st.focusedPanel)?.title ?: "-"}")
            if (st.walkMode) append(" · ĐI BỘ"); if (st.headLocked) append(" · HEAD-LOCKED"); if (!st.showMap) append(" · tắt bản đồ")
        }
    }

    private fun refreshDiag() {
        val sb = StringBuilder()
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

        col.addView(label("Nguồn & hiển thị"))
        col.addView(row(btn("Kết nối kính (USB)") { connectGlasses() }, btn("Dùng cảm biến ĐT") { usePhoneSensors() }))
        col.addView(row(btn("Chiếu lên kính") { showOnGlasses() }, btn("Xem trên màn hình này") { showOnPhone() }))

        col.addView(label("Touchpad — chạm = click tại chấm ngắm · kéo = cuộn trang"))
        val pad = View(this).apply {
            background = GradientDrawable().apply { setColor(0xFF1C232C.toInt()); cornerRadius = dp(14).toFloat(); setStroke(dp(1), 0xFF2F3B48.toInt()) }
            var x0 = 0f; var y0 = 0f
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> { x0 = e.x; y0 = e.y; space?.touchpad(MotionEvent.ACTION_DOWN, 0f, 0f) }
                    MotionEvent.ACTION_MOVE -> space?.touchpad(MotionEvent.ACTION_MOVE, e.x - x0, e.y - y0)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> space?.touchpad(e.actionMasked, e.x - x0, e.y - y0)
                }
                true
            }
        }
        col.addView(pad, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        col.addView(TextView(this).apply {
            text = "Nút âm lượng: + = click · − = về giữa. Gật đầu = OK/bật-tắt bản đồ, lắc đầu = bỏ qua."
            setTextColor(Colors.GREY); textSize = 12f; setPadding(0, dp(4), 0, 0)
        })

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
            btn("⟳ Tải lại") { space?.focusedWeb()?.reload() }))
        typeField = EditText(this).apply { hint = "Gõ chữ vào ô đang chọn trên trang"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY) }
        col.addView(row(typeField, btn("Gõ") { space?.typeText(typeField.text.toString(), false); typeField.setText("") },
            btn("Enter") { space?.typeText(typeField.text.toString(), true); typeField.setText("") }).apply {
            (getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = 2.5f
        })
        col.addView(btn("🎤 Ra lệnh giọng nói") { startVoice() })

        col.addView(label("Chẩn đoán (gửi phần này khi test Beam Pro)"))
        col.addView(row(btn("Làm mới") { refreshDiag() }, btn("Copy") {
            refreshDiag()
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("xreal-diag", statusText.text.toString() + "\n\n" + diagText.text))
            SpaceState.notify("Đã copy", "Dán vào chat để gửi", Colors.GREEN, 1.5)
        }))
        diagText = TextView(this).apply { setTextColor(0xFF9FB3C8.toInt()); textSize = 11f; typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        col.addView(diagText)
        col.addView(label("Cài đặt"))
        col.addView(row(btn("Reset bố cục") { SpaceState.resetLayout() }, btn("Reset hiệu chỉnh trục") { SpaceState.resetCalibration() }))

        root.addView(controls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        backBtn = btn("⚙ Điều khiển") { leavePhoneScreen(); if (glassesDisplay() != null) showOnGlasses(quiet = true) }.apply {
            visibility = View.GONE; alpha = 0.7f
        }
        root.addView(backBtn, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        setContentView(root)
        refreshDiag()
    }
}

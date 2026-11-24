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
    }

    override fun onStart() { super.onStart(); space.start() }
    override fun onStop() { space.stop(); super.onStop() }
}

class MainActivity : Activity(), SpaceState.Controller, TouchpadView.Host {
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
    private lateinit var voice: VoiceInput
    private lateinit var padOverlay: FrameLayout
    private lateinit var kbField: EditText
    private var kbActive = false
    private var kbProgrammatic = false
    private var dictating = false
    private lateinit var aiStatus: TextView

    private val space: SpaceView? get() = phoneSpace ?: presentation?.space

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(id: Int) { SpaceState.log("display added $id"); if (started && wantGlassesOutput) showOnGlasses(quiet = true) }
        override fun onDisplayRemoved(id: Int) {
            SpaceState.log("display removed $id")
            if (presentation?.display?.displayId == id) { presentation?.dismiss(); presentation = null; showTouchpad(false) }
        }
        override fun onDisplayChanged(id: Int) {}
    }

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpaceState.init(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // plugging the glasses in = connect IMU + show the space on them + phone becomes the touchpad, no buttons
        usb = GlassesUsb(this) { ui.post { refreshStatus(); if (usb.connected && started && wantGlassesOutput) showOnGlasses(quiet = true) } }
        phoneImu = PhoneImu(this)
        dm = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        voice = VoiceInput(this) { text -> handleUtterance(text) }
        SpaceState.controller = this
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
        if (SpaceState.wakeEnabled && hasMic()) voice.startWake()
    }

    override fun onStop() {
        started = false
        ui.removeCallbacks(ticker)
        voice.stopWake()
        phoneSpace?.stop()
        presentation?.dismiss(); presentation = null
        showTouchpad(false)
        SpaceState.save()
        super.onStop()
    }

    override fun onDestroy() {
        dm.unregisterDisplayListener(displayListener)
        usb.unregister(); usb.close(); phoneImu.stop()
        voice.release()
        if (SpaceState.controller === this) SpaceState.controller = null
        phoneSpace?.destroy()
        super.onDestroy()
    }

    private val ticker = object : Runnable {
        override fun run() { refreshStatus(); if (voice.modelStatus.startsWith("đang")) refreshAi(); ui.postDelayed(this, 300) }
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
            showTouchpad(true)
        } catch (e: Exception) {
            SpaceState.log("Presentation failed: $e"); presentation = null
        }
    }

    private fun showOnPhone() {
        wantGlassesOutput = false
        presentation?.dismiss(); presentation = null; showTouchpad(false)
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

    /** Speech text -> intent (rules / Jev / LLM) -> commands. Falls back to rules when the network engine fails. */
    private fun handleUtterance(text: String) {
        val st = SpaceState
        st.log("voice: $text")
        st.voiceHeard = text
        if (dictating) {   // holding the touchpad while a page text field is focused = dictation
            dictating = false
            val cur = kbField.text.toString()
            kbField.setText(if (cur.isBlank()) text else "$cur $text"); kbField.setSelection(kbField.text.length)
            return
        }
        val rules = VoiceCommands.parse(text)
        fun runRules(prefix: String = "") {
            if (rules !is VoiceCommands.Cmd.Unknown) st.notify("🎤 $text", prefix + label(rules), Colors.GREEN, 2.0)
            st.execute(rules)
        }
        // rules first: a simple command that the rules understood runs instantly, no network round trip
        if (st.intentEngine != SpaceState.IntentEngine.RULES && FastPath.accept(text, rules)) { runRules("⚡ "); return }
        when (st.intentEngine) {
            SpaceState.IntentEngine.RULES -> runRules()
            SpaceState.IntentEngine.JEV -> {
                if (!st.jevReady) { runRules("(chưa có key Jev) "); return }
                st.voiceState = "thinking"
                AiClient.jevCommand(text) { r ->
                    st.voiceState = ""
                    r.onSuccess { (cmd, d) ->
                        st.log("jev: ${d.intent} %.2f site=${d.site} switch=${d.switch}".format(d.confidence))
                        if (cmd is VoiceCommands.Cmd.Unknown && rules !is VoiceCommands.Cmd.Unknown) { runRules("(Jev không chắc) "); return@onSuccess }
                        st.notify("🎤 $text", "Jev: ${d.intent} · %.0f%%".format(d.confidence * 100), Colors.GREEN, 2.0)
                        st.execute(cmd)
                    }.onFailure { e -> st.log("jev failed: ${AiClient.describe(e)}"); runRules("(Jev lỗi, dùng luật) ") }
                }
            }
            SpaceState.IntentEngine.LLM -> {
                if (!st.llmReady) { runRules("(chưa cấu hình LLM) "); return }
                st.voiceState = "thinking"
                AiClient.llmCommand(text) { r ->
                    st.voiceState = ""
                    r.onSuccess { reply ->
                        if (reply.cmds.isEmpty()) { runRules(); return@onSuccess }
                        st.notify("🎤 $text", reply.say ?: reply.cmds.joinToString(" → ") { label(it) }, Colors.GREEN, 2.5)
                        reply.cmds.forEach { st.execute(it) }
                    }.onFailure { e -> st.log("llm failed: ${AiClient.describe(e)}"); runRules("(LLM lỗi, dùng luật) ") }
                }
            }
        }
    }

    private fun label(c: VoiceCommands.Cmd): String = when (c) {
        is VoiceCommands.Cmd.Place -> "ô ${c.slot}: ${c.title ?: c.url}"
        is VoiceCommands.Cmd.Open -> "mở ${c.url}"
        is VoiceCommands.Cmd.ClearSlot -> if (c.slot == 0) "xoá màn đang nhìn" else "xoá ô ${c.slot}"
        is VoiceCommands.Cmd.Workspace -> "không gian ${c.name}"
        is VoiceCommands.Cmd.SaveWorkspace -> "lưu không gian ${c.name}"
        else -> c.javaClass.simpleName
    }

    // SpaceState.Controller
    override fun ask(question: String) {
        val st = SpaceState
        if (!st.llmReady) { st.notify("Trợ lý", "Cần cấu hình LLM (Cài đặt AI) để trả lời câu hỏi", Colors.AMBER, 3.0); return }
        st.voiceState = "thinking"
        AiClient.llmAsk(question) { r ->
            st.voiceState = ""
            r.onSuccess { st.showCard("❓ $question", it.trim()) }.onFailure { st.notify("Trợ lý lỗi", AiClient.describe(it), Colors.RED, 4.0) }
        }
    }

    override fun summarizeFocused() {
        val st = SpaceState
        val web = space?.focusedWeb() ?: run { st.notify("Tóm tắt", "Không có màn nào", Colors.AMBER); return }
        if (!st.llmReady) { st.notify("Tóm tắt", "Cần cấu hình LLM (Cài đặt AI)", Colors.AMBER, 3.0); return }
        st.voiceState = "thinking"; st.voiceHeard = "tóm tắt trang"
        web.evaluateJavascript("(function(){return (document.title||'')+'\\n'+(document.body?document.body.innerText:'').slice(0,15000);})()") { raw ->
            val txt = try { org.json.JSONArray("[$raw]").getString(0) } catch (e: Exception) { raw ?: "" }
            val title = txt.substringBefore('\n')
            AiClient.llmSummarize(title, txt.substringAfter('\n')) { r ->
                st.voiceState = ""
                r.onSuccess { st.showCard("📄 ${title.take(60)}", it.trim(), 40.0) }.onFailure { st.notify("Tóm tắt lỗi", AiClient.describe(it), Colors.RED, 4.0) }
            }
        }
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && res.firstOrNull() == PackageManager.PERMISSION_GRANTED) { afterMicGrant?.invoke() }
        afterMicGrant = null
    }

    // ------------------------------------------------------------------ keys: volume = remote, BT keyboard = prototype hotkeys
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val st = SpaceState
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

    private var volLongPressed = false

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (!volLongPressed && space?.clickAtGaze() != true) SpaceState.notify("Click", "Không có màn nào dưới chấm ngắm", Colors.AMBER, 1.0)
            volLongPressed = false
            return true
        }
        return if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) true else super.onKeyUp(keyCode, event)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (space?.inFullscreen == true) { space?.exitFullscreen(); return }
        if (::padOverlay.isInitialized && padOverlay.visibility == View.VISIBLE) { showTouchpad(false); return }
        if (phoneSpace != null) { leavePhoneScreen(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    // ------------------------------------------------------------------ eyes-free touchpad + phone keyboard
    override fun space(): SpaceView? = space
    override fun onHold() { if (kbActive) { dictating = true; withMic { voice.captureCommand() } } else pushToTalk() }
    override fun onTwoTap() = SpaceState.recenter()
    override fun onThreeTap() = SpaceState.setFollow(SpaceState.followMode.next())
    override fun onSwipe2(dx: Float, dy: Float) {
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
        kbActive = false; dictating = false
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
                override fun afterTextChanged(e: Editable?) { if (!kbProgrammatic) space?.setFocusedInputValue(e?.toString() ?: "") }
            })
            setOnEditorActionListener { _, _, _ -> space?.typeText("", true); hideKeyboard(); true }
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
            btn("⟳ Tải lại") { space?.focusedWeb()?.reload() }))
        typeField = EditText(this).apply { hint = "Gõ chữ vào ô đang chọn trên trang"; setSingleLine(); setTextColor(Color.WHITE); setHintTextColor(Colors.GREY) }
        col.addView(row(typeField, btn("Gõ") { space?.typeText(typeField.text.toString(), false); typeField.setText("") },
            btn("Enter") { space?.typeText(typeField.text.toString(), true); typeField.setText("") }).apply {
            (getChildAt(0).layoutParams as LinearLayout.LayoutParams).weight = 2.5f
        })

        // ---------------------------------------------------------------- AI
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
        col.addView(row(btn("Reset bố cục") { SpaceState.resetLayout() }, btn("Reset hiệu chỉnh trục") { SpaceState.resetCalibration() }))

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

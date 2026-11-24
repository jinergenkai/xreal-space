package vn.jiner.xrealspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import vn.jiner.xrealspace.core.AxisWizard
import vn.jiner.xrealspace.core.ImuTracker
import vn.jiner.xrealspace.core.M3
import vn.jiner.xrealspace.core.MapSpec
import vn.jiner.xrealspace.core.Mat3
import vn.jiner.xrealspace.core.ScreenSpec
import vn.jiner.xrealspace.core.SlotGrid
import vn.jiner.xrealspace.core.SpaceConfig
import vn.jiner.xrealspace.core.VoiceCommands
import vn.jiner.xrealspace.core.WakeWord
import vn.jiner.xrealspace.core.Workspaces
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide state shared by the phone controls (MainActivity) and the renderer (SpaceView),
 * whether the space is drawn on the glasses (Presentation) or on the phone screen.
 * Main thread only, except the tracker (thread-safe itself).
 */
object SpaceState {
    enum class ImuSource { GLASSES, PHONE, NONE }
    /** speech -> intent engine */
    enum class IntentEngine { RULES, JEV, LLM }
    /** speech -> text engine for commands */
    enum class SttEngine { VOSK, GOOGLE }

    lateinit var config: SpaceConfig
        private set
    private var prefsCtx: Context? = null

    @Volatile var tracker = ImuTracker()
        private set
    var imuSource = ImuSource.NONE
        private set

    var headLocked = false
    var walkMode = false
    var showMap = true
    var showStatus = true
    var wizard: AxisWizard? = null
    var focusedPanel = -1
    var recenterRequested = false
    /** panel carried by the head until released (-1 = none) */
    var grabbedPanel = -1
    /** show the 1..9 slot numbers until this time (ms) */
    var gridUntil = 0L
    /** voice state shown on the glasses: "" | "listening" | "thinking" */
    var voiceState = ""
    var voiceHeard = ""

    /** longer assistant answer / page summary card (head-locked) */
    var cardTitle = ""
    var cardText = ""
    var cardUntil = 0L

    /** Things only the Activity can do (network, page text). */
    interface Controller {
        fun ask(question: String)
        fun summarizeFocused()
    }
    var controller: Controller? = null

    /** Bumped whenever the list of screens changes, so renderers rebuild their panels. */
    var panelsVersion = 0
        private set
    /** Pending URL loads: panel index -> url (consumed by the renderer). */
    val pendingLoads = HashMap<Int, String>()

    data class Toast(val title: String, val body: String, val color: Int, val until: Long, val actionable: Boolean)
    var toast: Toast? = null

    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    // ------------------------------------------------------------------ AI settings (persisted)
    var intentEngine = IntentEngine.RULES
    var sttEngine = SttEngine.VOSK
    var wakeEnabled = false
    var wakePhrases: List<String> = WakeWord.DEFAULT_PHRASES
    var jevBaseUrl = "https://openrouter.ai/api/alpha/decisions"
    var jevModel = "typesafe/jev-1.13"
    var jevKey = ""
    var llmProvider = "anthropic"            // "anthropic" | "openai"
    var llmBaseUrl = "https://api.anthropic.com/v1/messages"
    var llmModel = "claude-haiku-5-5"
    var llmKey = ""
    val llmReady get() = llmBaseUrl.isNotBlank() && llmModel.isNotBlank() && (llmKey.isNotBlank() || llmProvider == "openai")
    val jevReady get() = jevBaseUrl.isNotBlank() && jevKey.isNotBlank()

    /** user-saved workspaces, key = folded name */
    val savedWorkspaces = LinkedHashMap<String, Workspaces.Workspace>()

    fun init(ctx: Context) {
        if (prefsCtx != null) return
        prefsCtx = ctx.applicationContext
        config = load(ctx)
        loadSettings(ctx)
    }

    // ------------------------------------------------------------------ IMU
    fun useTracker(src: ImuSource) {
        tracker = ImuTracker()
        imuSource = src
        log("IMU source = $src")
    }

    /** Axis mapping for the active source: glasses use the calibrated matrix, the phone maps 1:1. */
    fun axisMapping(): M3? = when (imuSource) {
        ImuSource.GLASSES -> config.imuToCam
        ImuSource.PHONE -> Mat3.eye()
        ImuSource.NONE -> null
    }

    // ------------------------------------------------------------------ log / toast / card
    fun log(msg: String) {
        synchronized(logLines) {
            logLines.addLast("${timeFmt.format(Date())} $msg")
            while (logLines.size > 300) logLines.removeFirst()
        }
        android.util.Log.i("XrealSpace", msg)
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun notify(title: String, body: String, color: Int = Colors.CYAN, secs: Double = 4.0, actionable: Boolean = false) {
        toast = Toast(title, body, color, System.currentTimeMillis() + (secs * 1000).toLong(), actionable)
    }

    fun showCard(title: String, text: String, secs: Double = 25.0) {
        cardTitle = title; cardText = text; cardUntil = System.currentTimeMillis() + (secs * 1000).toLong()
    }

    fun showGrid(secs: Double = 6.0) { gridUntil = System.currentTimeMillis() + (secs * 1000).toLong() }

    // ------------------------------------------------------------------ commands (keys, buttons, voice, gestures)
    fun recenter() { recenterRequested = true; tracker.reset() }

    fun toggleWalk() {
        walkMode = !walkMode
        notify(if (walkMode) "Chế độ đi bộ" else "Chế độ làm việc",
            if (walkMode) "Ẩn màn hình lớn, chỉ giữ bản đồ + thông tin" else "Hiện lại các màn hình", secs = 2.5)
    }

    fun setMap(on: Boolean?) { showMap = on ?: !showMap; notify("Bản đồ", if (showMap) "bật" else "tắt", Colors.GREEN, 1.5) }

    fun changeDistance(factor: Double) { config.distanceM = VoiceCommands.clampDistance(config.distanceM * factor); save() }

    fun changeFov(delta: Double) { config.fovVDeg = (config.fovVDeg + delta).coerceIn(10.0, 60.0); save() }

    fun startWizard() {
        if (imuSource != ImuSource.GLASSES) { notify("Hiệu chỉnh trục", "Chỉ dùng cho kính", Colors.AMBER); return }
        wizard = AxisWizard(tracker, nowS())
    }

    fun openUrl(panel: Int?, url: String) {
        if (config.screens.isEmpty()) { place(5, url, null); return }
        val i = (panel ?: focusedPanel).let { if (it in config.screens.indices) it else 0 }
        val u = normalizeUrl(url)
        config.screens[i].url = u
        pendingLoads[i] = u
        save()
        notify("Mở trang", "${config.screens[i].title}: $u", Colors.CYAN, 2.0)
    }

    /** Put a page into grid slot 1..9 (replaces whatever is there). */
    fun place(slot: Int, url: String, title: String?) {
        if (slot !in 1..9) return
        val u = normalizeUrl(url)
        val name = title?.takeIf { it.isNotBlank() }?.take(24) ?: hostOf(u)
        val existing = config.screens.indexOfFirst { SlotGrid.slotOf(it) == slot }
        if (existing >= 0) {
            config.screens[existing].url = u; config.screens[existing].title = name
            pendingLoads[existing] = u
        } else {
            config.screens.add(SlotGrid.spec(slot, name, u)); panelsVersion++
        }
        save(); showGrid(3.0)
        notify("Ô $slot", "$name", Colors.GREEN, 2.0)
    }

    fun clearSlot(slot: Int) {
        val i = if (slot == 0) focusedPanel else config.screens.indexOfFirst { SlotGrid.slotOf(it) == slot }
        if (i !in config.screens.indices) { notify("Xoá", if (slot == 0) "Nhìn vào một màn trước" else "Ô $slot đang trống", Colors.AMBER, 1.5); return }
        removePanel(i)
        notify("Đã xoá", if (slot == 0) "màn đang nhìn" else "ô $slot", Colors.GREEN, 1.5)
    }

    fun addPanelAt(yawDeg: Double, pitchDeg: Double) {
        config.screens.add(ScreenSpec("Màn ${config.screens.size + 1}", "https://www.google.com", yawDeg, pitchDeg, 1.0))
        panelsVersion++; save()
    }

    fun removePanel(i: Int) {
        if (i !in config.screens.indices) return
        config.screens.removeAt(i); focusedPanel = -1; grabbedPanel = -1
        panelsVersion++; save()
    }

    fun resetLayout() {
        val fresh = SpaceConfig()
        config.screens.clear(); config.screens.addAll(fresh.screens)
        config.distanceM = fresh.distanceM; config.fovVDeg = fresh.fovVDeg
        panelsVersion++; save()
    }

    fun setWorkspace(name: String) {
        val w = Workspaces.find(name, savedWorkspaces) ?: run {
            notify("Không gian", "Không có '$name'. Có: " + (Workspaces.PRESETS.map { it.title } + savedWorkspaces.values.map { it.title }).joinToString(", "), Colors.AMBER, 5.0)
            return
        }
        config.screens.clear(); config.screens.addAll(w.screens.map { it.copy() })
        config.distanceM = w.distanceM; showMap = w.showMap; walkMode = false; grabbedPanel = -1
        panelsVersion++; save(); recenter()
        notify("Không gian: ${w.title}", "${w.screens.size} màn hình", Colors.CYAN, 2.5)
    }

    fun saveWorkspace(name: String) {
        val key = VoiceCommands.fold(name).ifBlank { "cua toi" }
        savedWorkspaces[key] = Workspaces.Workspace(key, name.ifBlank { "Của tôi" }, config.screens.map { it.copy() }, config.distanceM, showMap)
        saveSettings()
        notify("Đã lưu không gian", "\"${name.ifBlank { "của tôi" }}\" · ${config.screens.size} màn", Colors.GREEN, 2.5)
    }

    fun resizeFocused(f: Double) {
        val s = config.screens.getOrNull(focusedPanel) ?: run { notify("Đổi cỡ", "Nhìn vào một màn trước", Colors.AMBER, 1.5); return }
        s.widthM = (s.widthM * f).coerceIn(0.3, 5.0); save()
    }

    fun toggleGrab() {
        if (grabbedPanel >= 0) { grabbedPanel = -1; save(); notify("Đã đặt màn", "Vị trí đã lưu", Colors.GREEN, 1.5); return }
        if (focusedPanel < 0) { notify("Cầm màn", "Nhìn vào một màn trước", Colors.AMBER, 1.5); return }
        grabbedPanel = focusedPanel
        notify("Đang cầm: ${config.screens[grabbedPanel].title}", "Quay đầu để dời · gật hoặc bấm lại để đặt", Colors.CYAN, 3.0)
    }

    fun execute(cmd: VoiceCommands.Cmd) {
        when (cmd) {
            VoiceCommands.Cmd.Recenter -> recenter()
            is VoiceCommands.Cmd.Walk -> if (cmd.on == null || cmd.on != walkMode) toggleWalk()
            is VoiceCommands.Cmd.Map -> setMap(cmd.on)
            VoiceCommands.Cmd.Farther -> changeDistance(1.25)
            VoiceCommands.Cmd.Closer -> changeDistance(0.8)
            is VoiceCommands.Cmd.Size -> resizeFocused(if (cmd.bigger) 1.15 else 1 / 1.15)
            is VoiceCommands.Cmd.HeadLock -> headLocked = cmd.on ?: !headLocked
            VoiceCommands.Cmd.Calibrate -> startWizard()
            VoiceCommands.Cmd.Notify -> demoNotification()
            VoiceCommands.Cmd.Grab -> toggleGrab()
            VoiceCommands.Cmd.Summarize -> controller?.summarizeFocused()
            VoiceCommands.Cmd.ShowGrid -> showGrid(8.0)
            is VoiceCommands.Cmd.Workspace -> setWorkspace(cmd.name)
            is VoiceCommands.Cmd.SaveWorkspace -> saveWorkspace(cmd.name)
            is VoiceCommands.Cmd.Place -> place(cmd.slot, cmd.url, cmd.title)
            is VoiceCommands.Cmd.ClearSlot -> clearSlot(cmd.slot)
            is VoiceCommands.Cmd.Open -> openUrl(cmd.panel, cmd.url)
            is VoiceCommands.Cmd.Answer -> showCard("Trợ lý", cmd.text)
            is VoiceCommands.Cmd.Ask -> controller?.ask(cmd.text) ?: notify("Trợ lý", "Cần cấu hình LLM để trả lời câu hỏi", Colors.AMBER)
            is VoiceCommands.Cmd.Unknown -> notify("Chưa hiểu lệnh", cmd.text, Colors.AMBER, 3.0)
        }
    }

    fun demoNotification() =
        notify("Tin nhắn · An", "Tối nay đi ăn không?   (gật = OK · lắc = bỏ qua)", Colors.AMBER, 10.0, true)

    fun nowS() = System.nanoTime() / 1e9

    fun normalizeUrl(s: String): String {
        val t = s.trim()
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file:") || t.startsWith("about:")) return t
        if (t.contains('.') && !t.contains(' ')) return "https://$t"
        return VoiceCommands.searchUrl(t)
    }

    private fun hostOf(u: String) = try { java.net.URI(u).host?.removePrefix("www.")?.removePrefix("m.") ?: u } catch (e: Exception) { u }.take(24)

    // ------------------------------------------------------------------ persistence
    private const val PREFS = "space"
    private const val KEY = "config_v1"
    private const val KEY_SETTINGS = "settings_v2"

    fun save() {
        val ctx = prefsCtx ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson(config).toString()).apply()
    }

    fun saveCalibration(m: M3) { config.imuToCam = m; save(); log("imu_to_cam saved: ${m.joinToString { "%.3f".format(it) }}") }

    fun resetCalibration() { config.imuToCam = SpaceConfig.DEFAULT_IMU_TO_CAM.copyOf(); save() }

    fun saveSettings() {
        val ctx = prefsCtx ?: return
        val ws = JSONArray()
        for (w in savedWorkspaces.values) ws.put(JSONObject().put("id", w.id).put("title", w.title).put("distanceM", w.distanceM)
            .put("showMap", w.showMap).put("screens", screensJson(w.screens)))
        val o = JSONObject()
            .put("intentEngine", intentEngine.name).put("sttEngine", sttEngine.name)
            .put("wakeEnabled", wakeEnabled).put("wakePhrases", wakePhrases.joinToString("|"))
            .put("jevBaseUrl", jevBaseUrl).put("jevModel", jevModel).put("jevKey", jevKey)
            .put("llmProvider", llmProvider).put("llmBaseUrl", llmBaseUrl).put("llmModel", llmModel).put("llmKey", llmKey)
            .put("workspaces", ws)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_SETTINGS, o.toString()).apply()
    }

    private fun loadSettings(ctx: Context) {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SETTINGS, null) ?: return
        try {
            val o = JSONObject(s)
            intentEngine = runCatching { IntentEngine.valueOf(o.optString("intentEngine", intentEngine.name)) }.getOrDefault(intentEngine)
            sttEngine = runCatching { SttEngine.valueOf(o.optString("sttEngine", sttEngine.name)) }.getOrDefault(sttEngine)
            wakeEnabled = o.optBoolean("wakeEnabled", wakeEnabled)
            wakePhrases = o.optString("wakePhrases", wakePhrases.joinToString("|")).split("|").map { it.trim() }.filter { it.isNotEmpty() }
                .ifEmpty { WakeWord.DEFAULT_PHRASES }
            jevBaseUrl = o.optString("jevBaseUrl", jevBaseUrl); jevModel = o.optString("jevModel", jevModel); jevKey = o.optString("jevKey", jevKey)
            llmProvider = o.optString("llmProvider", llmProvider); llmBaseUrl = o.optString("llmBaseUrl", llmBaseUrl)
            llmModel = o.optString("llmModel", llmModel); llmKey = o.optString("llmKey", llmKey)
            o.optJSONArray("workspaces")?.let { a ->
                for (k in 0 until a.length()) {
                    val w = a.getJSONObject(k)
                    val id = w.optString("id", "ws$k")
                    savedWorkspaces[id] = Workspaces.Workspace(id, w.optString("title", id),
                        w.optJSONArray("screens")?.let { screensFromJson(it) } ?: emptyList(), w.optDouble("distanceM", 2.0), w.optBoolean("showMap", true))
                }
            }
        } catch (e: Exception) { log("settings parse error: $e") }
    }

    private fun load(ctx: Context): SpaceConfig {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return SpaceConfig()
        return try { fromJson(JSONObject(s)) } catch (e: Exception) { log("config parse error: $e"); SpaceConfig() }
    }

    private fun screensJson(list: List<ScreenSpec>) = JSONArray().apply {
        list.forEach { s -> put(JSONObject().put("title", s.title).put("url", s.url).put("yaw", s.yaw).put("pitch", s.pitch).put("widthM", s.widthM)) }
    }

    private fun screensFromJson(a: JSONArray): MutableList<ScreenSpec> = MutableList(a.length()) {
        val s = a.getJSONObject(it)
        ScreenSpec(s.optString("title", "Màn ${it + 1}"), s.optString("url", "https://www.google.com"),
            s.optDouble("yaw", 0.0), s.optDouble("pitch", 0.0), s.optDouble("widthM", 1.2))
    }

    fun toJson(c: SpaceConfig): JSONObject = JSONObject().apply {
        c.imuToCam?.let { m -> put("imuToCam", JSONArray().apply { m.forEach { put(it) } }) }
        put("fovVDeg", c.fovVDeg); put("distanceM", c.distanceM); put("predictMs", c.predictMs)
        put("screens", screensJson(c.screens))
        put("map", JSONObject().put("yaw", c.map.yaw).put("pitch", c.map.pitch).put("widthM", c.map.widthM)
            .put("distanceM", c.map.distanceM).put("deadzoneDeg", c.map.deadzoneDeg))
    }

    fun fromJson(o: JSONObject): SpaceConfig {
        val d = SpaceConfig()
        val m = o.optJSONArray("imuToCam")?.let { a -> DoubleArray(9) { a.getDouble(it) } } ?: d.imuToCam
        val screens = o.optJSONArray("screens")?.let { screensFromJson(it) } ?: d.screens
        val mo = o.optJSONObject("map")
        val map = if (mo == null) MapSpec() else MapSpec(mo.optDouble("yaw", 15.0), mo.optDouble("pitch", -4.0),
            mo.optDouble("widthM", 0.22), mo.optDouble("distanceM", 1.5), mo.optDouble("deadzoneDeg", 12.0))
        return SpaceConfig(m, o.optDouble("fovVDeg", d.fovVDeg), o.optDouble("distanceM", d.distanceM),
            o.optDouble("predictMs", d.predictMs), screens, map)
    }
}

object Colors {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val CYAN = 0xFF4FD8FF.toInt()
    const val CYAN_DIM = 0xFF1B5E78.toInt()
    const val GREEN = 0xFF5BE37D.toInt()
    const val AMBER = 0xFFFFC24D.toInt()
    const val RED = 0xFFFF6464.toInt()
    const val GREY = 0xFFA0A8B0.toInt()
    const val STREET = 0xFF2A3440.toInt()
    const val STREET_MAIN = 0xFF4A5868.toInt()
    const val WATER = 0xFF12324A.toInt()
    const val BORDER = 0xFF4D99FF.toInt()
    const val BORDER_FOCUS = 0xFFFFE066.toInt()
    const val PANEL_BG = 0xF0000000.toInt()
}

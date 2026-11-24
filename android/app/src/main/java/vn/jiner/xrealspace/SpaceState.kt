package vn.jiner.xrealspace

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import vn.jiner.xrealspace.core.AxisWizard
import vn.jiner.xrealspace.core.ImuTracker
import vn.jiner.xrealspace.core.M3
import vn.jiner.xrealspace.core.Mat3
import vn.jiner.xrealspace.core.MapSpec
import vn.jiner.xrealspace.core.ScreenSpec
import vn.jiner.xrealspace.core.SpaceConfig
import vn.jiner.xrealspace.core.VoiceCommands
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Process-wide state shared by the phone controls (MainActivity) and the renderer (SpaceView),
 * whether the space is drawn on the glasses (Presentation) or on the phone screen.
 * Everything here is touched on the main thread except the tracker (thread-safe itself).
 */
object SpaceState {
    enum class ImuSource { GLASSES, PHONE, NONE }

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

    /** Bumped whenever the list of screens / their URLs changes, so renderers rebuild their panels. */
    var panelsVersion = 0
        private set
    /** Pending URL loads: panel index -> url (consumed by the renderer). */
    val pendingLoads = HashMap<Int, String>()

    data class Toast(val title: String, val body: String, val color: Int, val until: Long, val actionable: Boolean)
    var toast: Toast? = null

    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun init(ctx: Context) {
        if (prefsCtx != null) return
        prefsCtx = ctx.applicationContext
        config = load(ctx)
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

    // ------------------------------------------------------------------ log / toast
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

    // ------------------------------------------------------------------ commands (keys, buttons, voice, gestures)
    fun recenter() { recenterRequested = true; tracker.reset() }

    fun toggleWalk() {
        walkMode = !walkMode
        notify(if (walkMode) "Chế độ đi bộ" else "Chế độ làm việc",
            if (walkMode) "Ẩn màn hình lớn, chỉ giữ bản đồ + thông tin" else "Hiện lại các màn hình", secs = 2.5)
    }

    fun setMap(on: Boolean?) { showMap = on ?: !showMap; notify("Bản đồ", if (showMap) "bật" else "tắt", Colors.GREEN, 1.5) }

    fun changeDistance(factor: Double) {
        config.distanceM = VoiceCommands.clampDistance(config.distanceM * factor); save()
    }

    fun changeFov(delta: Double) { config.fovVDeg = (config.fovVDeg + delta).coerceIn(10.0, 60.0); save() }

    fun startWizard() {
        if (imuSource != ImuSource.GLASSES) { notify("Hiệu chỉnh trục", "Chỉ dùng cho kính", Colors.AMBER); return }
        wizard = AxisWizard(tracker, nowS())
    }

    fun openUrl(panel: Int?, url: String) {
        val i = (panel ?: focusedPanel).let { if (it in config.screens.indices) it else 0 }
        if (config.screens.isEmpty()) return
        val u = normalizeUrl(url)
        config.screens[i].url = u
        pendingLoads[i] = u
        save()
        notify("Mở trang", "${config.screens[i].title}: $u", Colors.CYAN, 2.0)
    }

    fun addPanelAt(yawDeg: Double, pitchDeg: Double) {
        config.screens.add(ScreenSpec("Màn ${config.screens.size + 1}", "https://www.google.com", yawDeg, pitchDeg, 1.0))
        panelsVersion++; save()
    }

    fun removePanel(i: Int) {
        if (i !in config.screens.indices) return
        config.screens.removeAt(i); focusedPanel = -1
        panelsVersion++; save()
    }

    fun resetLayout() {
        val fresh = SpaceConfig()
        config.screens.clear(); config.screens.addAll(fresh.screens)
        config.distanceM = fresh.distanceM; config.fovVDeg = fresh.fovVDeg
        panelsVersion++; save()
    }

    fun execute(cmd: VoiceCommands.Cmd) {
        when (cmd) {
            VoiceCommands.Cmd.Recenter -> recenter()
            VoiceCommands.Cmd.WalkToggle -> toggleWalk()
            is VoiceCommands.Cmd.Map -> setMap(cmd.on)
            VoiceCommands.Cmd.Farther -> changeDistance(1.25)
            VoiceCommands.Cmd.Closer -> changeDistance(0.8)
            VoiceCommands.Cmd.HeadLockToggle -> headLocked = !headLocked
            VoiceCommands.Cmd.Calibrate -> startWizard()
            VoiceCommands.Cmd.Notify -> demoNotification()
            is VoiceCommands.Cmd.Open -> openUrl(cmd.panel, cmd.url)
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
        return "https://www.google.com/search?q=" + java.net.URLEncoder.encode(t, "UTF-8")
    }

    // ------------------------------------------------------------------ persistence
    private const val PREFS = "space"
    private const val KEY = "config_v1"

    fun save() {
        val ctx = prefsCtx ?: return
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, toJson(config).toString()).apply()
    }

    fun saveCalibration(m: M3) { config.imuToCam = m; save(); log("imu_to_cam saved: ${m.joinToString { "%.3f".format(it) }}") }

    fun resetCalibration() { config.imuToCam = SpaceConfig.DEFAULT_IMU_TO_CAM.copyOf(); save() }

    private fun load(ctx: Context): SpaceConfig {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return SpaceConfig()
        return try { fromJson(JSONObject(s)) } catch (e: Exception) { log("config parse error: $e"); SpaceConfig() }
    }

    fun toJson(c: SpaceConfig): JSONObject = JSONObject().apply {
        c.imuToCam?.let { m -> put("imuToCam", JSONArray().apply { m.forEach { put(it) } }) }
        put("fovVDeg", c.fovVDeg); put("distanceM", c.distanceM); put("predictMs", c.predictMs)
        put("screens", JSONArray().apply {
            c.screens.forEach { s ->
                put(JSONObject().put("title", s.title).put("url", s.url).put("yaw", s.yaw).put("pitch", s.pitch).put("widthM", s.widthM))
            }
        })
        put("map", JSONObject().put("yaw", c.map.yaw).put("pitch", c.map.pitch).put("widthM", c.map.widthM)
            .put("distanceM", c.map.distanceM).put("deadzoneDeg", c.map.deadzoneDeg))
    }

    fun fromJson(o: JSONObject): SpaceConfig {
        val d = SpaceConfig()
        val m = o.optJSONArray("imuToCam")?.let { a -> DoubleArray(9) { a.getDouble(it) } } ?: d.imuToCam
        val screens = o.optJSONArray("screens")?.let { a ->
            MutableList(a.length()) {
                val s = a.getJSONObject(it)
                ScreenSpec(s.optString("title", "Màn ${it + 1}"), s.optString("url", "https://www.google.com"),
                    s.optDouble("yaw", 0.0), s.optDouble("pitch", 0.0), s.optDouble("widthM", 1.2))
            }
        } ?: d.screens
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

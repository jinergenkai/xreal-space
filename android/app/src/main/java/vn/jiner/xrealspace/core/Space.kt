package vn.jiner.xrealspace.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.tan

/** One world-locked virtual screen. yaw > 0 = to the left (same convention as the prototype). */
data class ScreenSpec(
    var title: String,
    var url: String,
    var yaw: Double,
    var pitch: Double = 0.0,
    var widthM: Double = 1.2,
)

data class MapSpec(
    var yaw: Double = 15.0,
    var pitch: Double = -4.0,
    var widthM: Double = 0.22,
    var distanceM: Double = 1.5,
    var deadzoneDeg: Double = 12.0,
)

data class SpaceConfig(
    var imuToCam: M3? = DEFAULT_IMU_TO_CAM.copyOf(),
    var fovVDeg: Double = 23.5,
    var distanceM: Double = 2.0,
    var predictMs: Double = 15.0,
    /** default: slots 5 (centre), 4 (left), 6 (right) of the 3x3 grid */
    val screens: MutableList<ScreenSpec> = mutableListOf(
        SlotGrid.spec(5, "Google", "https://www.google.com"),
        SlotGrid.spec(4, "YouTube", "https://m.youtube.com"),
        SlotGrid.spec(6, "Bản đồ", "https://www.openstreetmap.org"),
    ),
    val map: MapSpec = MapSpec(),
) {
    companion object {
        /** Measured by the prototype wizard on the test Air 2 (rows = camera X/Y/Z in IMU coordinates). */
        val DEFAULT_IMU_TO_CAM: M3 = Mat3.fromRows(
            listOf(
                listOf(-0.99936, 0.03481, -0.00798),
                listOf(-0.00141, 0.18480, 0.98277),
                listOf(0.03569, 0.98216, -0.18464),
            )
        )
    }
}

/** Where a world-locked quad lands on the glasses screen. */
data class Placement(
    val visible: Boolean,
    val cx: Double, val cy: Double,   // screen px of the quad centre
    val widthPx: Double,              // on-screen width
    val rollDeg: Double,              // Android View.rotation (clockwise)
    val depthM: Double,
)

object Projection {
    fun focalPx(screenH: Int, fovVDeg: Double) = (screenH / 2.0) / tan(Math.toRadians(fovVDeg) / 2)

    /** Camera orientation (cam -> world) from the IMU orientation and the axis mapping M. */
    fun camera(imuR: M3, M: M3): M3 = Mat3.mul(Mat3.mul(M, imuR), Mat3.t(M))

    /**
     * @param C   camera -> world
     * @param P   quad rotation in world (rotY(yaw) * rotX(pitch))
     */
    fun place(C: M3, P: M3, distanceM: Double, widthM: Double, w: Int, h: Int, fovVDeg: Double): Placement {
        val Ct = Mat3.t(C)
        val center = Mat3.mulV(Ct, Mat3.mulV(P, Mat3.v(0.0, 0.0, -distanceM)))
        val up = Mat3.mulV(Ct, Mat3.mulV(P, Mat3.v(0.0, 1.0, 0.0)))
        val z = -center[2]
        if (z < 0.05) return Placement(false, 0.0, 0.0, 0.0, 0.0, z)
        val f = focalPx(h, fovVDeg)
        val cx = w / 2.0 + f * center[0] / z
        val cy = h / 2.0 - f * center[1] / z
        val wpx = f * widthM / z
        val roll = Math.toDegrees(atan2(up[0], up[1]))
        // cheap frustum cull with a generous margin
        val vis = cx > -wpx && cx < w + wpx && cy > -wpx && cy < h + wpx
        return Placement(vis, cx, cy, wpx, roll, z)
    }

    /** Forward direction yaw/pitch (deg) of a camera orientation. yaw > 0 = looking left. */
    fun yawPitch(C: M3): Pair<Double, Double> {
        val f = Mat3.mulV(C, Mat3.v(0.0, 0.0, -1.0))
        return Math.toDegrees(atan2(-f[0], -f[2])) to Math.toDegrees(asin(f[1].coerceIn(-1.0, 1.0)))
    }
}

/** Lazy follow: the map only moves when you look away more than a dead zone. */
class LazyFollow(private val spec: MapSpec) {
    var yaw: Double? = null; private set
    var pitch = 0.0; private set

    fun update(C: M3, dt: Double): M3 {
        val (y, p) = Projection.yawPitch(C)
        if (yaw == null) { yaw = y; pitch = p }
        val dead = spec.deadzoneDeg
        val k = min(1.0, 3.0 * dt)
        val dy = wrap180(y - yaw!!)
        if (abs(dy) > dead) yaw = yaw!! + (dy - dead * sign(dy)) * k
        val dp = p - pitch
        if (abs(dp) > dead * 0.6) pitch += (dp - dead * 0.6 * sign(dp)) * k
        return Mat3.mul(Mat3.rotY(yaw!!), Mat3.rotX(pitch))
    }

    fun reset() { yaw = null }
}

/** Nod (down-up) -> NOD, shake (left-right-left) -> SHAKE. Input: camera-frame gyro rad/s, time in s. */
class HeadGestures(nodDps: Double = 80.0, shakeDps: Double = 90.0) {
    enum class Gesture { NOD, SHAKE }

    private val nodT = Math.toRadians(nodDps)
    private val shakeT = Math.toRadians(shakeDps)
    private var downAt = -1.0
    private val flips = ArrayList<Pair<Double, Int>>()
    private var coolUntil = 0.0

    fun update(w: V3, now: Double): Gesture? {
        if (now < coolUntil) return null
        val wx = w[0]; val wy = w[1]
        if (wx < -nodT && abs(wy) < 0.6 * abs(wx)) downAt = now
        if (wx > nodT && now - downAt < 0.6 && abs(wy) < 0.6 * abs(wx)) {
            downAt = -1.0; coolUntil = now + 1.0; flips.clear(); return Gesture.NOD
        }
        if (abs(wy) > shakeT && abs(wx) < 0.7 * abs(wy)) {
            val s = if (wy > 0) 1 else -1
            if (flips.isEmpty() || flips.last().second != s) flips.add(now to s)
        }
        flips.removeAll { now - it.first >= 0.9 }
        if (flips.size >= 3) { flips.clear(); coolUntil = now + 1.0; downAt = -1.0; return Gesture.SHAKE }
        return null
    }
}

/** Axis calibration: turn left, then look down; derive the IMU -> camera mapping (rows = camera axes). */
class AxisWizard(private val tracker: ImuTracker, startTime: Double) {
    private data class Step(val id: String, val dur: Double, val text: String)

    private val steps = listOf(
        Step("still", 0.0, "Giữ yên đầu…"),
        Step("prep_left", 1.5, "Chuẩn bị: QUAY ĐẦU SANG TRÁI ~45°"),
        Step("left", 2.0, ">>> QUAY TRÁI NGAY và giữ <<<"),
        Step("back1", 2.0, "Quay về giữa"),
        Step("prep_down", 1.5, "Chuẩn bị: CÚI XUỐNG ~30°"),
        Step("down", 2.0, ">>> CÚI XUỐNG NGAY và giữ <<<"),
        Step("back2", 2.0, "Quay về giữa, nhìn thẳng"),
    )
    private var i = 0
    private var t0 = startTime
    private var left: V3? = null
    private var down: V3? = null
    var error: String? = null; private set

    init { tracker.recalibrateBias() }

    val text: String get() = listOfNotNull("HIỆU CHỈNH TRỤC", error, steps[i].text).joinToString("\n")

    /** Returns the 3x3 mapping when finished, else null. */
    fun update(now: Double): M3? {
        val s = steps[i]
        if (s.id == "still") { if (tracker.biasState != ImuTracker.BiasState.READY) return null }
        else if (now - t0 < s.dur) return null
        if (s.id == "left") left = tracker.endGesture()
        if (s.id == "down") down = tracker.endGesture()
        i++; t0 = now
        if (i < steps.size) {
            if (steps[i].id == "left" || steps[i].id == "down") tracker.startGesture()
            return null
        }
        val l = left!!; val d = down!!
        if (Mat3.norm(l) < Math.toRadians(15.0) || Mat3.norm(d) < Math.toRadians(10.0)) {
            error = "Chuyển động quá nhỏ, làm lại"
            i = 1; t0 = now
            return null
        }
        val y = Mat3.unit(l)                       // turning left = +rotation about camera +Y
        var x = Mat3.scale(Mat3.unit(d), -1.0)     // looking down = -rotation about camera +X
        x = Mat3.unit(Mat3.sub(x, Mat3.scale(y, Mat3.dot(x, y))))
        val z = Mat3.cross(x, y)
        return doubleArrayOf(x[0], x[1], x[2], y[0], y[1], y[2], z[0], z[1], z[2])
    }
}

/** A fake city + walking route so the HUD can be designed without GPS (ported from the prototype). */
class MockRoute {
    data class Seg(val ax: Double, val ay: Double, val bx: Double, val by: Double, val main: Boolean)
    data class State(
        val x: Double, val y: Double, val headingDeg: Double, val seg: Int, val toTurn: Double,
        val turn: String?, val street: String, val nextStreet: String?, val remain: Double,
    )

    val streets = ArrayList<Seg>()
    val river = ArrayList<Pair<Double, Double>>()
    val route = listOf(0.0 to -360.0, 0.0 to -90.0, 180.0 to -90.0, 180.0 to 180.0, 360.0 to 180.0, 360.0 to 450.0)
    val names = listOf("Lê Lợi", "Nguyễn Huệ", "Hai Bà Trưng", "Đồng Khởi", "Tôn Đức Thắng")
    private val segLen: List<Double>
    val total: Double
    var s = 0.0
    var speed = 6.0 // m/s, sped-up walk for the demo

    init {
        for (i in -8..8) {
            val main = i % 4 == 0
            streets.add(Seg(i * 90.0, -800.0, i * 90.0, 800.0, main))
            streets.add(Seg(-800.0, i * 90.0, 800.0, i * 90.0, main))
        }
        streets.add(Seg(-700.0, -500.0, 700.0, 600.0, true))
        var x = -800
        while (x <= 800) { river.add(x.toDouble() to 500 + 60 * kotlin.math.sin(x / 140.0)); x += 40 }
        segLen = (0 until route.size - 1).map { i ->
            val (ax, ay) = route[i]; val (bx, by) = route[i + 1]; kotlin.math.hypot(bx - ax, by - ay)
        }
        total = segLen.sum()
    }

    fun update(dt: Double) { s = (s + speed * dt) % total }

    fun state(): State {
        var rem = s
        for (i in segLen.indices) {
            val L = segLen[i]
            if (rem <= L || i == segLen.lastIndex) {
                val (ax, ay) = route[i]; val (bx, by) = route[i + 1]
                val dx = (bx - ax) / L; val dy = (by - ay) / L
                val heading = Math.toDegrees(atan2(dx, dy))
                var turn: String? = null; var nxt: String? = null
                if (i + 1 < segLen.size) {
                    val (cx, cy) = route[i + 2]
                    val l2 = kotlin.math.hypot(cx - bx, cy - by)
                    val cross = dx * (cy - by) / l2 - dy * (cx - bx) / l2
                    turn = if (cross > 0) "left" else "right"
                    nxt = names[i + 1]
                }
                val r = min(rem, L)
                return State(ax + dx * r, ay + dy * r, heading, i, L - r, turn, names[i], nxt, total - s)
            }
            rem -= L
        }
        error("unreachable")
    }
}

/** Rule-based speech -> intent (vi + en, offline, free). Also the fallback when Jev/LLM are unavailable. */
object VoiceCommands {
    sealed class Cmd {
        object Recenter : Cmd()
        data class Walk(val on: Boolean?) : Cmd()
        data class Map(val on: Boolean?) : Cmd()
        object Farther : Cmd()
        object Closer : Cmd()
        data class Size(val bigger: Boolean) : Cmd()
        data class HeadLock(val on: Boolean?) : Cmd()
        object Calibrate : Cmd()
        object Notify : Cmd()
        /** read out the latest phone notifications on the glasses */
        object ReadNotifs : Cmd()
        /** real GPS navigation; profile "car" | "bike" | "foot" */
        data class Navigate(val dest: String, val profile: String = "car") : Cmd()
        object StopNav : Cmd()
        /** play / pause / next… on the video you're watching, else on the phone's music app; seek in seconds */
        data class Media(val action: MediaAction, val seconds: Int = 0) : Cmd()
        /** read things out loud (TTS): on / off / only while riding */
        data class Speak(val mode: SpeakMode) : Cmd()
        /** film sites: block ads / pop-ups / redirects */
        data class AdBlock(val on: Boolean) : Cmd()
        /** desktop version of the page you're looking at (true) / mobile (false) */
        data class DesktopSite(val on: Boolean) : Cmd()
        /** reply to the latest notification that has a reply box (Zalo, Messenger, SMS…) */
        data class Reply(val text: String) : Cmd()
        /** mute (on=true) / unmute an app's pop-ups; app=null means do-not-disturb for all */
        data class MuteNotifs(val on: Boolean, val app: String?) : Cmd()
        object Grab : Cmd()
        object Summarize : Cmd()
        object ShowGrid : Cmd()
        data class Workspace(val name: String) : Cmd()
        data class Follow(val mode: FollowMode) : Cmd()
        /** open an installed Android app by name (shown on the glasses through the phone mirror) */
        data class LaunchApp(val name: String) : Cmd()
        /** phone screen mirror into a grid slot (null = keep / default 4), off = stop */
        data class Mirror(val slot: Int?) : Cmd()
        object MirrorOff : Cmd()
        data class Captions(val mode: CaptionMode) : Cmd()
        data class Prompter(val on: Boolean?) : Cmd()
        object Back : Cmd()
        object Home : Cmd()
        data class DarkWeb(val on: Boolean?) : Cmd()
        data class SaveWorkspace(val name: String) : Cmd()
        /** put [url] into grid slot 1..9 */
        data class Place(val slot: Int, val url: String, val title: String?) : Cmd()
        /** slot 0 = the screen being looked at */
        data class ClearSlot(val slot: Int) : Cmd()
        /** panel = index in config.screens, null = the one being looked at */
        data class Open(val panel: Int?, val url: String) : Cmd()
        /** a ready answer to show on the glasses */
        data class Answer(val text: String) : Cmd()
        /** a question to forward to the LLM */
        data class Ask(val text: String) : Cmd()
        data class Unknown(val text: String) : Cmd()
    }

    /** folded keyword -> url (longest keys first so "google map" wins over "google"). */
    private val SITES = linkedMapOf(
        "youtube" to "https://m.youtube.com",
        "google map" to "https://www.google.com/maps",
        "gmail" to "https://mail.google.com",
        "facebook" to "https://m.facebook.com",
        "tiktok" to "https://www.tiktok.com",
        "github" to "https://github.com",
        "chatgpt" to "https://chatgpt.com",
        "claude" to "https://claude.ai",
        "lich" to "https://calendar.google.com",
        "calendar" to "https://calendar.google.com",
        "spotify" to "https://open.spotify.com",
        "bao" to "https://vnexpress.net",
        "vnexpress" to "https://vnexpress.net",
        "google" to "https://www.google.com",
    )

    fun fold(s: String): String {
        val n = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        return n.replace(Regex("\\p{M}+"), "").replace('đ', 'd').replace(Regex("[^a-z0-9.:/ ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    fun siteFromText(folded: String): String? {
        Regex("""\b([a-z0-9-]+\.(?:com|vn|net|org|io|ai|me|dev)(?:/\S*)?)""").find(folded)?.let { return "https://" + it.groupValues[1] }
        return SITES.entries.firstOrNull { Regex("\\b" + Regex.escape(it.key) + "\\b").containsMatchIn(folded) }?.value
    }

    fun searchUrl(q: String) = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(q.trim(), "UTF-8")

    private val COMMAND_WORDS = listOf(
        "kinh oi", "nay kinh", "e kinh", "hey space", "tim kiem", "tim", "search", "google", "mo", "open", "dat", "vao", "len", "cho toi", "giup toi",
        "o so", "slot", "trang", "web", "tren", "xem",
    )

    /** Remove trigger words and "ô N" so what is left is the search query. */
    fun stripCommandWords(folded: String): String {
        var t = " $folded "
        t = t.replace(Regex("""\s(?:o|slot|so)\s+(?:so\s+)?(?:[1-9]|mot|hai|ba|bon|tu|nam|lam|sau|bay|tam|chin)\b"""), " ")
        for (w in COMMAND_WORDS) t = t.replace(" $w ", " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    /** "mo ung dung zalo" -> "zalo" */
    fun appName(folded: String): String =
        folded.replace(Regex("""^.*?\b(?:mo|open)\s+"""), "").replace(Regex("""^(?:ung dung|app|ap)\s+"""), "")
            .replace(Regex("""\s+(?:len|ra|giup|cho toi|di)$"""), "").trim()

    fun workspaceName(folded: String): String {
        val m = Regex("""(?:khong gian|workspace|che do|bo cuc)\s+(.+)$""").find(folded)
        return (m?.groupValues?.get(1) ?: folded).replace(Regex("""^(?:ten|la|so)\s+"""), "").trim()
    }

    fun parse(text: String): Cmd {
        val t = fold(text)
        fun has(vararg k: String) = k.any { Regex("(?:^|\\s)" + Regex.escape(it) + "(?:\\s|$)").containsMatchIn(t) }
        val slot = SlotGrid.parseSlot(t)

        // navigation: "chỉ đường tới Bến Thành", "đi bộ đến Highlands Nguyễn Huệ", "navigate to …"
        Regex("^(?:chi duong|dan duong|di bo|dap xe|di xe dap|di xe|di|navigate|directions)\\s+(?:toi|den|ve|to)\\s+(.+)$").find(t)?.let { m ->
            val profile = when { t.startsWith("di bo") -> "foot"; t.startsWith("dap xe") || t.startsWith("di xe dap") -> "bike"; else -> "car" }
            // keep the user's own spelling/accents for the place name (search works better with diacritics)
            val words = text.trim().split(Regex("\\s+")); val skip = t.substring(0, m.groups[1]!!.range.first).trim().split(' ').size
            return Cmd.Navigate(words.drop(skip).joinToString(" ").ifBlank { m.groupValues[1] }, profile)
        }
        if (has("dung chi duong", "huy chi duong", "tat chi duong", "ket thuc chi duong", "stop navigation")) return Cmd.StopNav
        if (has("tat chan quang cao", "bo chan quang cao", "allow ads")) return Cmd.AdBlock(false)   // before the "on" rule: it contains it
        if (has("chan quang cao", "block ads")) return Cmd.AdBlock(true)
        if (has("ban may tinh", "giao dien may tinh", "desktop site", "desktop mode")) return Cmd.DesktopSite(true)
        if (has("ban dien thoai", "giao dien dien thoai", "mobile site")) return Cmd.DesktopSite(false)

        // speaking out loud
        if (has("tat doc to", "tat giong doc", "dung doc to", "speech off")) return Cmd.Speak(SpeakMode.OFF)
        if (has("doc to khi di", "chi doc khi di xe")) return Cmd.Speak(SpeakMode.RIDING)
        if (has("bat doc to", "doc to len", "bat giong doc", "speech on")) return Cmd.Speak(SpeakMode.ALWAYS)

        // media: the video in the glasses, else the phone's music / video app
        Regex("^(?:tua|lui|tua lai|tua toi|tua nhanh|seek|skip|rewind)(?:\\s+(?:lai|toi|ve|nhanh|back|forward))?(?:\\s+(\\d+))?\\s*(giay|phut|seconds?|minutes?|s)?\\b").find(t)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: 10
            val unit = if (m.groupValues[2].startsWith("phut") || m.groupValues[2].startsWith("minute")) 60 else 1
            val back = t.startsWith("lui") || t.startsWith("tua lai") || t.startsWith("rewind") || t.contains(" back")
            return Cmd.Media(MediaAction.SEEK, (if (back) -1 else 1) * n * unit)
        }
        if (has("tam dung", "dung phim", "dung nhac", "dung video", "pause")) return Cmd.Media(MediaAction.PAUSE)
        if (has("phat tiep", "tiep tuc phat", "xem tiep", "nghe tiep", "phat nhac", "play", "resume")) return Cmd.Media(MediaAction.PLAY)
        if (has("bai tiep", "bai sau", "tap tiep", "tap sau", "next track", "next song", "next episode")) return Cmd.Media(MediaAction.NEXT)
        if (has("bai truoc", "tap truoc", "previous track", "previous song")) return Cmd.Media(MediaAction.PREV)

        // phone notifications (before generic "tắt …" / "mở …" rules)
        if (t.startsWith("tra loi ") || t.startsWith("reply ")) {
            val words = text.trim().split(Regex("\\s+"))
            val body = words.drop(if (t.startsWith("reply ")) 1 else 2).joinToString(" ").trim()
            if (body.isNotEmpty()) return Cmd.Reply(body)
        }
        if (has("doc thong bao", "thong bao moi", "co tin nhan", "tin nhan moi", "read notifications")) return Cmd.ReadNotifs
        if (has("im lang", "dung lam phien", "do not disturb")) return Cmd.MuteNotifs(true, null)
        if (has("tat thong bao", "an thong bao", "mute")) {
            val app = (if (t.contains("thong bao")) t.substringAfter("thong bao") else t.substringAfter("mute", "")).trim()
            return Cmd.MuteNotifs(true, app.ifEmpty { null })
        }
        if (has("bat thong bao", "bat lai thong bao", "unmute")) {
            val app = (if (t.contains("thong bao")) t.substringAfter("thong bao") else t.substringAfter("unmute", "")).trim()
            return Cmd.MuteNotifs(false, app.ifEmpty { null })
        }
        if (has("thong bao mau", "demo notification")) return Cmd.Notify

        // workspaces
        if (has("luu khong gian", "luu bo cuc", "save workspace")) return Cmd.SaveWorkspace(workspaceName(t))
        if (has("rap phim", "xem phim")) return Cmd.Workspace("giai tri")
        if (has("che do di chuyen", "di xe", "tren xe", "may bay", "travel mode", "travel")) return Cmd.Follow(FollowMode.TRAVEL)
        if (has("co dinh", "dung yen", "world lock", "world locked")) return Cmd.Follow(FollowMode.WORLD)
        if (has("di theo dau", "theo minh", "follow")) return Cmd.Follow(FollowMode.CINEMA)
        if (has("web toi", "che do toi", "dark mode", "giam choi")) return Cmd.DarkWeb(true)
        if (has("web sang", "che do sang", "light mode")) return Cmd.DarkWeb(false)
        if (has("khong gian", "workspace", "bo cuc")) return Cmd.Workspace(workspaceName(t))
        if (has("hien so", "hien luoi", "luoi", "show grid", "cac o")) return Cmd.ShowGrid
        // phone mirror
        if (has("man hinh dien thoai", "chieu dien thoai", "mirror", "guong dien thoai")) {
            return if (has("tat", "dong", "off", "stop", "dung")) Cmd.MirrorOff else Cmd.Mirror(slot)
        }
        // live captions / interpreter
        if (has("tat phu de", "tat dich", "captions off", "stop captions")) return Cmd.Captions(CaptionMode.OFF)
        if (has("dich sang tieng anh", "dich viet anh", "viet sang anh", "translate to english")) return Cmd.Captions(CaptionMode.VI_TO_EN)
        if (has("dich tieng anh", "dich anh viet", "anh sang viet", "dich", "translate", "phien dich")) return Cmd.Captions(CaptionMode.EN_TO_VI)
        if (has("phu de tieng anh", "english captions", "english subtitles")) return Cmd.Captions(CaptionMode.EN)
        if (has("phu de", "captions", "subtitles", "caption")) return Cmd.Captions(CaptionMode.VI)
        // teleprompter
        if (has("tat nhac chu", "dung doc", "tat teleprompter", "stop teleprompter", "tat prompter")) return Cmd.Prompter(false)
        if (has("teleprompter", "prompter", "nhac chu", "bat dau doc", "kich ban")) return Cmd.Prompter(true)
        // phone navigation (works on the mirrored app via Accessibility)
        if (has("quay lai", "tro lai", "go back")) return Cmd.Back
        if (has("man hinh chinh", "go home", "ve home")) return Cmd.Home
        // slots
        if (slot != null && has("xoa", "dong", "tat", "bo", "clear", "close", "remove")) return Cmd.ClearSlot(slot)
        if (slot != null) {
            val site = siteFromText(t)
            if (site != null) return Cmd.Place(slot, site, null)
            val q = stripCommandWords(t)
            if (q.isNotBlank()) return Cmd.Place(slot, searchUrl(q), q)
        }
        if (has("xoa man", "dong man", "close screen")) return Cmd.ClearSlot(0)
        // open / search on the focused screen
        if (has("tim kiem", "tim", "search", "google")) {
            val q = stripCommandWords(t)
            if (q.isNotBlank() && siteFromText(t).let { it == null || it.endsWith("google.com") }) return Cmd.Open(null, searchUrl(q))
        }
        if (has("mo", "open", "bat")) {
            val site = siteFromText(t)
            if (site != null && !has("ban do")) return Cmd.Open(null, site)
        }
        return when {
            has("ve giua", "recenter", "dat lai", "center screen") -> Cmd.Recenter
            has("tat di bo", "het di bo", "stop walk") -> Cmd.Walk(false)
            has("di bo", "walk") -> Cmd.Walk(true)
            has("tat ban do", "an ban do", "hide map", "map off") -> Cmd.Map(false)
            has("bat ban do", "hien ban do", "mo ban do", "show map", "map on") -> Cmd.Map(true)
            has("ban do", "map") -> Cmd.Map(null)
            has("tom tat", "summarize", "summary") -> Cmd.Summarize
            has("to hon", "phong to", "bigger", "lon hon") -> Cmd.Size(true)
            has("nho hon", "thu nho", "smaller") -> Cmd.Size(false)
            has("xa hon", "day ra", "farther", "further") -> Cmd.Farther
            has("gan hon", "keo lai", "closer") -> Cmd.Closer
            has("cam man", "di chuyen man", "keo man") -> Cmd.Grab
            has("khoa dau", "head lock", "theo dau") -> Cmd.HeadLock(null)
            has("hieu chinh", "calibrate") -> Cmd.Calibrate
            has("thong bao", "notification", "notifications") -> Cmd.ReadNotifs
            has("mo", "open") && siteFromText(t) == null && appName(t).isNotBlank() -> Cmd.LaunchApp(appName(t))
            text.trim().endsWith("?") || has("la gi", "bao nhieu", "the nao", "tai sao", "o dau", "khi nao", "what", "why", "how") -> Cmd.Ask(text)
            else -> Cmd.Unknown(text)
        }
    }

    fun clampDistance(d: Double) = max(0.6, min(6.0, d))
}

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
    val screens: MutableList<ScreenSpec> = mutableListOf(
        ScreenSpec("Giữa", "https://www.google.com", 0.0),
        ScreenSpec("Trái", "https://m.youtube.com", 40.0),
        ScreenSpec("Phải", "https://www.openstreetmap.org", -40.0, widthM = 1.0),
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

/** Rule-based voice commands (vi + en). Placeholder for the "LLM function calling" layer. */
object VoiceCommands {
    sealed class Cmd {
        object Recenter : Cmd()
        object WalkToggle : Cmd()
        data class Map(val on: Boolean?) : Cmd()
        object Farther : Cmd()
        object Closer : Cmd()
        object HeadLockToggle : Cmd()
        object Calibrate : Cmd()
        object Notify : Cmd()
        data class Open(val panel: Int?, val url: String) : Cmd()
        data class Unknown(val text: String) : Cmd()
    }

    private val SITES = linkedMapOf(
        "youtube" to "https://m.youtube.com",
        "google map" to "https://www.google.com/maps",
        "ban do" to "https://www.openstreetmap.org",
        "facebook" to "https://m.facebook.com",
        "tiktok" to "https://www.tiktok.com",
        "gmail" to "https://mail.google.com",
        "google" to "https://www.google.com",
        "chatgpt" to "https://chatgpt.com",
        "claude" to "https://claude.ai",
    )

    fun fold(s: String): String {
        val n = java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD)
        return n.replace(Regex("\\p{M}+"), "").replace('đ', 'd').replace(Regex("\\s+"), " ").trim()
    }

    /** panels: 0 = giữa/center, 1 = trái/left, 2 = phải/right (matches default config order). */
    fun parse(text: String): Cmd {
        val t = fold(text)
        fun has(vararg k: String) = k.any { t.contains(it) }
        val panel = when {
            has("ben trai", "left", "trai") -> 1
            has("ben phai", "right", "phai") -> 2
            has("o giua", "chinh giua", "center", "middle", "giua") -> 0
            else -> null
        }
        if (has("mo ", "open ", "bat ")) {
            for ((k, url) in SITES) if (t.contains(k)) {
                if (k == "ban do" && !has("web", "trang")) break // "bật bản đồ" = HUD map
                return Cmd.Open(panel, url)
            }
        }
        return when {
            has("ve giua", "recenter", "dat lai", "center screen") -> Cmd.Recenter
            has("di bo", "walk") -> Cmd.WalkToggle
            has("tat ban do", "an ban do", "hide map", "map off") -> Cmd.Map(false)
            has("bat ban do", "hien ban do", "mo ban do", "show map", "map on") -> Cmd.Map(true)
            has("ban do", "map") -> Cmd.Map(null)
            has("xa hon", "day ra", "farther", "further") -> Cmd.Farther
            has("gan hon", "keo lai", "closer") -> Cmd.Closer
            has("khoa dau", "head lock", "theo dau") -> Cmd.HeadLockToggle
            has("hieu chinh", "calibrate") -> Cmd.Calibrate
            has("thong bao", "notification") -> Cmd.Notify
            else -> Cmd.Unknown(text)
        }
    }

    fun clampDistance(d: Double) = max(0.6, min(6.0, d))
}

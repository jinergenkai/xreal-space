package vn.jiner.xrealspace

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.View
import vn.jiner.xrealspace.core.MockRoute
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

private fun paint(color: Int, size: Float = 0f, bold: Boolean = false, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    if (size > 0) { textSize = size; typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT }
    if (stroke > 0) { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
}

/**
 * Lazy-follow navigation card: round heading-up mini map + turn card (port of the prototype MapPanel).
 * Drawn at a fixed 440x600 px; SpaceView scales/places it in 3D.
 * Black = transparent on the glasses, so the black disc/card hides the screens behind it.
 */
class MapPanelView(ctx: Context) : View(ctx) {
    companion object { const val W = 440; const val H = 600; const val MAP = 400 }

    val route = MockRoute()
    private val bg = paint(0xEB000000.toInt())
    private val water = paint(Colors.WATER, stroke = 16f)
    private val street = paint(Colors.STREET, stroke = 2f)
    private val streetMain = paint(Colors.STREET_MAIN, stroke = 4f)
    private val done = paint(Colors.CYAN_DIM, stroke = 8f)
    private val todo = paint(Colors.CYAN, stroke = 9f)
    private val goal = paint(Colors.GREEN, stroke = 3f)
    private val me = paint(0xFF003C5A.toInt())
    private val white = paint(Colors.WHITE)
    private val whiteStroke = paint(Colors.WHITE, stroke = 8f)
    private val ring = paint(Colors.STREET_MAIN, stroke = 2f)
    private val fN = paint(Colors.AMBER, 22f, true).apply { textAlign = Paint.Align.CENTER }
    private val fBig = paint(Colors.WHITE, 54f, true)
    private val fSmallCyan = paint(Colors.CYAN, 24f)
    private val fSmallGrey = paint(Colors.GREY, 24f)
    private val line = paint(Colors.STREET, stroke = 1f)
    private val clip = Path()
    private val hm = SimpleDateFormat("HH:mm", Locale.US)

    fun step(dt: Double) { route.update(dt); invalidate() }

    override fun onDraw(c: Canvas) {
        val st = route.state()
        val r = MAP / 2f
        val ox = (W - MAP) / 2f
        c.drawCircle(W / 2f, r, r, bg)
        c.drawRoundRect(RectF(0f, MAP + 4f, W.toFloat(), H.toFloat()), 22f, 22f, bg)

        c.save()
        clip.reset(); clip.addCircle(W / 2f, r, r - 4, Path.Direction.CW)
        c.clipPath(clip)
        val cx = ox + MAP / 2f; val cy = MAP * 0.62f
        val scale = 0.75f
        val th = Math.toRadians(st.headingDeg)
        val hx = sin(th); val hy = cos(th); val rx = cos(th); val ry = -sin(th)
        fun px(x: Double, y: Double): Float { val dx = x - st.x; val dy = y - st.y; return (cx + (dx * rx + dy * ry) * scale).toFloat() }
        fun py(x: Double, y: Double): Float { val dx = x - st.x; val dy = y - st.y; return (cy - (dx * hx + dy * hy) * scale).toFloat() }
        fun poly(pts: List<Pair<Double, Double>>, p: Paint) {
            if (pts.size < 2) return
            val path = Path(); path.moveTo(px(pts[0].first, pts[0].second), py(pts[0].first, pts[0].second))
            for (k in 1 until pts.size) path.lineTo(px(pts[k].first, pts[k].second), py(pts[k].first, pts[k].second))
            c.drawPath(path, p)
        }
        poly(route.river, water)
        for (s in route.streets) c.drawLine(px(s.ax, s.ay), py(s.ax, s.ay), px(s.bx, s.by), py(s.bx, s.by), if (s.main) streetMain else street)
        val pos = st.x to st.y
        poly(route.route.subList(0, st.seg + 1) + listOf(pos), done)
        val rest = listOf(pos) + route.route.subList(st.seg + 1, route.route.size)
        poly(rest, todo)
        val g = rest.last(); c.drawCircle(px(g.first, g.second), py(g.first, g.second), 10f, goal)
        c.drawCircle(cx, cy, 26f, me)
        val arrow = Path().apply { moveTo(cx, cy - 18); lineTo(cx - 13, cy + 13); lineTo(cx, cy + 6); lineTo(cx + 13, cy + 13); close() }
        c.drawPath(arrow, white)
        c.restore()
        c.drawCircle(W / 2f, r, r - 4, ring)
        val nang = -th
        c.drawText("N", (W / 2f + sin(nang) * (r - 22)).toFloat(), (r - cos(nang) * (r - 22)).toFloat() + 8, fN)

        // turn card
        val y0 = MAP + 12f
        turnIcon(c, 52f, y0 + 48, st.turn)
        val d = st.toTurn
        c.drawText(if (d < 1000) "%.0f m".format(d) else "%.1f km".format(d / 1000), 110f, y0 + 48, fBig)
        val verb = when (st.turn) { "left" -> "Rẽ trái vào"; "right" -> "Rẽ phải vào"; else -> "Đến" }
        c.drawText("$verb ${st.nextStreet ?: "điểm đến"}", 112f, y0 + 90, fSmallCyan)
        c.drawLine(20f, y0 + 108, W - 20f, y0 + 108, line)
        val mins = st.remain / 1.4 / 60
        val eta = hm.format(Date(System.currentTimeMillis() + (mins * 60_000).toLong()))
        c.drawText("%.0f phút · %.1f km · đến %s".format(mins, st.remain / 1000, eta), 20f, y0 + 146, fSmallGrey)
    }

    private fun turnIcon(c: Canvas, x: Float, y: Float, turn: String?) {
        c.drawLine(x, y + 36, x, y - 6, whiteStroke)
        if (turn == null) {
            c.drawPath(Path().apply { moveTo(x - 18, y - 4); lineTo(x + 18, y - 4); lineTo(x, y - 30); close() }, white); return
        }
        val s = if (turn == "left") -1 else 1
        c.drawLine(x, y - 6, x + s * 26, y - 6, whiteStroke)
        c.drawPath(Path().apply { moveTo(x + s * 24, y - 24); lineTo(x + s * 24, y + 12); lineTo(x + s * 44, y - 6); close() }, white)
    }
}

/** Head-locked info bar (clock, battery, mode) — top right. */
class InfoView(ctx: Context) : View(ctx) {
    companion object { const val W = 430; const val H = 92 }

    var mode = ""
    private val bg = paint(0xDC000000.toInt())
    private val fClock = paint(Colors.WHITE, 46f, true).apply { textAlign = Paint.Align.RIGHT }
    private val fSmall = paint(Colors.GREY, 22f)
    private val fMode = paint(Colors.CYAN, 22f)
    private val outline = paint(Colors.GREY, stroke = 2f)
    private val fill = paint(Colors.GREEN)
    private val greyFill = paint(Colors.GREY)
    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private var key = ""

    fun refresh() {
        val (pct, ac) = battery()
        val k = "${hm.format(Date())}|$pct|$ac|$mode"
        if (k != key) { key = k; invalidate() }
    }

    private fun battery(): Pair<Int?, Boolean> {
        val i: Intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null to false
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1); val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        return (if (level >= 0 && scale > 0) level * 100 / scale else null) to plugged
    }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), 18f, 18f, bg)
        c.drawText(hm.format(Date()), W - 12f, 50f, fClock)
        val (pct, ac) = battery()
        val bx = 14f; val by = 18f
        if (pct != null) {
            fill.color = if (pct > 30) Colors.GREEN else if (pct > 15) Colors.AMBER else Colors.RED
            c.drawRoundRect(RectF(bx, by, bx + 54, by + 24), 4f, 4f, outline)
            c.drawRect(bx + 54, by + 7, bx + 58, by + 17, greyFill)
            c.drawRoundRect(RectF(bx + 4, by + 4, bx + 4 + 46f * pct / 100, by + 20), 2f, 2f, fill)
            c.drawText("$pct%" + if (ac) " · sạc" else "", bx + 66, by + 20, fSmall)
        }
        c.drawText(mode, bx, by + 60, fMode)
    }
}

/** Head-locked toast (notifications, voice feedback). */
class ToastView(ctx: Context) : View(ctx) {
    companion object { const val W = 760; const val H = 120 }

    private var title = ""; private var body = ""; private var color = Colors.CYAN
    private val bg = paint(0xEB000000.toInt())
    private val bar = paint(Colors.CYAN)
    private val fTitle = paint(Colors.CYAN, 30f, true)
    private val fBody = paint(Colors.WHITE, 22f)

    fun show(t: SpaceState.Toast?) {
        if (t == null) { if (visibility != GONE) visibility = GONE; return }
        if (visibility != VISIBLE) visibility = VISIBLE
        if (t.title != title || t.body != body || t.color != color) { title = t.title; body = t.body; color = t.color; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        c.drawRoundRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), 18f, 18f, bg)
        bar.color = color; fTitle.color = color
        c.drawRoundRect(RectF(0f, 8f, 6f, H - 8f), 3f, 3f, bar)
        c.drawText(title, 22f, 46f, fTitle)
        c.drawText(body, 22f, 92f, fBody)
    }
}

/** Gaze dot in the middle of the glasses: the "mouse" for the phone touchpad / volume-key click. */
class CrosshairView(ctx: Context) : View(ctx) {
    private val outer = paint(Colors.WHITE, stroke = 3f)
    private val inner = paint(Colors.WHITE)
    override fun onDraw(c: Canvas) {
        val x = width / 2f; val y = height / 2f
        c.drawCircle(x, y, 9f, outer); c.drawCircle(x, y, 2.5f, inner)
    }
}

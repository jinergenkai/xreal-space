package vn.jiner.xrealspace

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.BatteryManager
import android.view.View
import vn.jiner.xrealspace.core.Geo
import vn.jiner.xrealspace.core.LatLon
import vn.jiner.xrealspace.core.MockRoute
import vn.jiner.xrealspace.core.NavFmt
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
    private val mePaint = paint(0xFF003C5A.toInt())
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

    init { Tiles.init(ctx.cacheDir); Tiles.onLoaded = { invalidate() } }

    fun step(dt: Double) { if (Sensors.location == null) route.update(dt); invalidate() }

    // OSM tiles are light; on the glasses black = transparent, so invert to a dark map and tint it slightly blue
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -0.85f, 0f, 0f, 0f, 235f,
            0f, -0.85f, 0f, 0f, 240f,
            0f, 0f, -0.80f, 0f, 255f,
            0f, 0f, 0f, 0.92f, 0f)))
    }
    private val fAttr = paint(Colors.GREY, 14f).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(c: Canvas) {
        val loc = Sensors.location
        if (loc != null) { drawReal(c, LatLon(loc.latitude, loc.longitude), loc.speed.toDouble()); return }
        drawMock(c)
    }

    /** Real GPS: dark OSM map, heading-up, route line, next-turn card. */
    private fun drawReal(c: Canvas, me: LatLon, speedMps: Double) {
        val r = MAP / 2f
        c.drawCircle(W / 2f, r, r, bg)
        c.drawRoundRect(RectF(0f, MAP + 4f, W.toFloat(), H.toFloat()), 22f, 22f, bg)
        val zoom = if (speedMps > 9) 15 else if (speedMps > 4) 16 else 17
        val (mx, my) = Geo.worldPx(me, zoom)
        val cx = W / 2f; val cy = MAP * 0.62f
        val heading = Sensors.headingDeg
        c.save()
        clip.reset(); clip.addCircle(W / 2f, r, r - 4, Path.Direction.CW)
        c.clipPath(clip)
        c.rotate((-heading).toFloat(), cx, cy)                    // heading-up
        // tiles within the circle (radius covers the rotated square)
        val reach = MAP.toDouble()
        val tx0 = Geo.tileOf(mx - reach); val tx1 = Geo.tileOf(mx + reach)
        val ty0 = Geo.tileOf(my - reach); val ty1 = Geo.tileOf(my + reach)
        for (tx in tx0..tx1) for (ty in ty0..ty1) {
            val bmp = Tiles.get(zoom, tx, ty) ?: continue
            val left = (cx + tx * 256 - mx).toFloat(); val top = (cy + ty * 256 - my).toFloat()
            c.drawBitmap(bmp, null, RectF(left, top, left + 256, top + 256), tilePaint)
        }
        val nav = Navigation.tracker
        val ns = Navigation.state
        if (nav != null) {
            val path = Path(); var first = true
            for (p in nav.route.points) {
                val (px, py) = Geo.worldPx(p, zoom)
                val x = (cx + px - mx).toFloat(); val y = (cy + py - my).toFloat()
                if (first) { path.moveTo(x, y); first = false } else path.lineTo(x, y)
            }
            c.drawPath(path, todo)
            val (gx, gy) = Geo.worldPx(nav.route.points.last(), zoom)
            c.drawCircle((cx + gx - mx).toFloat(), (cy + gy - my).toFloat(), 10f, goal)
        }
        c.restore()
        c.drawCircle(cx, cy, 26f, mePaint)
        val arrow = Path().apply { moveTo(cx, cy - 18); lineTo(cx - 13, cy + 13); lineTo(cx, cy + 6); lineTo(cx + 13, cy + 13); close() }
        c.drawPath(arrow, white)
        c.drawCircle(W / 2f, r, r - 4, ring)
        val nang = Math.toRadians(-heading)
        c.drawText("N", (W / 2f + sin(nang) * (r - 22)).toFloat(), (r - cos(nang) * (r - 22)).toFloat() + 8, fN)
        c.drawText("© OpenStreetMap", W / 2f, MAP - 2f, fAttr)

        val y0 = MAP + 12f
        if (nav != null && ns != null) {
            val step = ns.next
            turnIcon(c, 52f, y0 + 48, step?.arrow())
            c.drawText(NavFmt.dist(ns.toNextM), 110f, y0 + 48, fBig)
            c.drawText(step?.text() ?: "Đến ${nav.destName}", 112f, y0 + 90, fSmallCyan)
            c.drawLine(20f, y0 + 108, W - 20f, y0 + 108, line)
            val eta = hm.format(Date(System.currentTimeMillis() + (ns.remainS * 1000).toLong()))
            c.drawText("${NavFmt.mins(ns.remainS)} phút · ${NavFmt.dist(ns.remainM)} · đến $eta", 20f, y0 + 146, fSmallGrey)
        } else {
            c.drawText("%.0f km/h".format(speedMps * 3.6), 24f, y0 + 52, fBig)
            c.drawText(if (nav != null) "Đang tính đường…" else "Nói: \"chỉ đường tới …\"", 24f, y0 + 92, fSmallCyan)
            c.drawLine(20f, y0 + 108, W - 20f, y0 + 108, line)
            c.drawText("%.5f, %.5f".format(me.lat, me.lon), 20f, y0 + 146, fSmallGrey)
        }
    }

    /** No GPS yet: the old demo route, so the map still shows something on a desk. */
    private fun drawMock(c: Canvas) {
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
        c.drawCircle(cx, cy, 26f, mePaint)
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
        if (turn == "uturn") {
            c.drawLine(x + 14, y + 36, x + 14, y - 10, whiteStroke); c.drawLine(x - 14, y - 10, x - 14, y + 20, whiteStroke)
            c.drawArc(RectF(x - 14, y - 24, x + 14, y + 4), 180f, 180f, false, whiteStroke)
            c.drawPath(Path().apply { moveTo(x - 30, y + 14); lineTo(x + 2, y + 14); lineTo(x - 14, y + 34); close() }, white); return
        }
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
    companion object { const val W = 430; const val H = 124 }

    var mode = ""
    /** weather · timer · next event (Utilities.infoLine) */
    var extra = ""
    private val fExtra = paint(Colors.WHITE, 22f)
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
        val k = "${hm.format(Date())}|$pct|$ac|$mode|$extra"
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
        if (extra.isNotEmpty()) c.drawText(extra, bx, by + 94, fExtra)
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

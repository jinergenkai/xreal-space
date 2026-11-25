package vn.jiner.xrealspace.core

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

data class LatLon(val lat: Double, val lon: Double)

/** Small-distance geo math (good to a few cm over a city). */
object Geo {
    const val R = 6_371_000.0

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat); val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * R * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Compass bearing a -> b in degrees, 0 = north, 90 = east. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        val y = sin(dl) * cos(p2); val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Local metres around [o]: x = east, y = north. */
    fun toLocal(o: LatLon, p: LatLon): Pair<Double, Double> =
        Math.toRadians(p.lon - o.lon) * R * cos(Math.toRadians(o.lat)) to Math.toRadians(p.lat - o.lat) * R

    /** Web-Mercator global pixel position at [zoom] (256 px tiles), the OpenStreetMap tile scheme. */
    fun worldPx(p: LatLon, zoom: Int): Pair<Double, Double> {
        val n = 256.0 * (1 shl zoom)
        val x = (p.lon + 180) / 360 * n
        val latR = Math.toRadians(p.lat.coerceIn(-85.05, 85.05))
        val y = (1 - ln(tan(latR) + 1 / cos(latR)) / PI) / 2 * n
        return x to y
    }

    /** Metres per screen pixel at [zoom] and latitude (for scale-dependent drawing). */
    fun metresPerPx(lat: Double, zoom: Int) = 156_543.033_92 * cos(Math.toRadians(lat)) / (1 shl zoom)

    fun tileOf(px: Double) = floor(px / 256).toInt()
}

/** One turn instruction from the router. */
data class NavStep(val at: LatLon, val type: String, val modifier: String, val name: String, val distanceM: Double) {
    /** Vietnamese instruction shown on the glasses. */
    fun text(): String {
        val road = if (name.isNotBlank()) " vào $name" else ""
        return when (type) {
            "depart" -> "Đi${if (name.isNotBlank()) " theo $name" else ""}"
            "arrive" -> "Đến nơi"
            "roundabout", "rotary" -> "Vào vòng xoay$road"
            "exit roundabout", "exit rotary" -> "Ra khỏi vòng xoay$road"
            else -> when (modifier) {
                "left" -> "Rẽ trái$road"; "right" -> "Rẽ phải$road"
                "slight left" -> "Chếch trái$road"; "slight right" -> "Chếch phải$road"
                "sharp left" -> "Quẹo gắt trái$road"; "sharp right" -> "Quẹo gắt phải$road"
                "uturn" -> "Quay đầu$road"
                else -> "Đi thẳng$road"
            }
        }
    }

    /** Icon direction for the map card: "left" / "right" / "uturn" / null (straight or arrive). */
    fun arrow(): String? = when {
        type == "arrive" -> null
        modifier.contains("left") -> "left"
        modifier.contains("right") -> "right"
        modifier == "uturn" -> "uturn"
        else -> null
    }
}

data class NavRoute(val points: List<LatLon>, val steps: List<NavStep>, val distanceM: Double, val durationS: Double)
data class Place(val at: LatLon, val name: String)

/** Parsers for the free OpenStreetMap services (Nominatim search, OSRM routing). */
object OsmApi {
    const val USER_AGENT = "XrealSpace/0.7 (+https://github.com/jinergenkai/xreal-space)"

    /** Nominatim search, biased to the area around [near] (viewbox ~±0.3°, not bounded). */
    fun searchUrl(q: String, near: LatLon?): String {
        val enc = java.net.URLEncoder.encode(q, "UTF-8")
        val box = near?.let { "&viewbox=${it.lon - 0.3},${it.lat + 0.3},${it.lon + 0.3},${it.lat - 0.3}" } ?: ""
        return "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=1&accept-language=vi&q=$enc$box"
    }

    /** OSRM on routing.openstreetmap.de: profile "car" (also fine for motorbikes in VN), "bike" or "foot". */
    fun routeUrl(from: LatLon, to: LatLon, profile: String): String =
        "https://routing.openstreetmap.de/routed-$profile/route/v1/driving/" +
            "${from.lon},${from.lat};${to.lon},${to.lat}?overview=full&geometries=geojson&steps=true"

    fun tileUrl(z: Int, x: Int, y: Int) = "https://tile.openstreetmap.org/$z/$x/$y.png"

    fun parsePlace(json: String): Place? = try {
        val o = (MiniJson.parse(json) as? List<*>)?.firstOrNull() as? Map<*, *>
        o?.let {
            val name = (it["name"] as? String)?.ifBlank { null } ?: (it["display_name"] as? String)?.substringBefore(",") ?: "?"
            Place(LatLon((it["lat"] as String).toDouble(), (it["lon"] as String).toDouble()), name)
        }
    } catch (e: Exception) { null }

    fun parseRoute(json: String): NavRoute? = try {
        val root = MiniJson.parse(json) as Map<*, *>
        if (root["code"] != "Ok") null else {
            val r = (root["routes"] as List<*>).first() as Map<*, *>
            val coords = ((r["geometry"] as Map<*, *>)["coordinates"] as List<*>).map { c ->
                val l = c as List<*>; LatLon((l[1] as Number).toDouble(), (l[0] as Number).toDouble())
            }
            val steps = ArrayList<NavStep>()
            for (leg in r["legs"] as List<*>) for (s in (leg as Map<*, *>)["steps"] as List<*>) {
                val st = s as Map<*, *>; val m = st["maneuver"] as Map<*, *>
                val loc = m["location"] as List<*>
                steps.add(NavStep(LatLon((loc[1] as Number).toDouble(), (loc[0] as Number).toDouble()),
                    m["type"] as? String ?: "", m["modifier"] as? String ?: "", st["name"] as? String ?: "", (st["distance"] as Number).toDouble()))
            }
            NavRoute(coords, steps, (r["distance"] as Number).toDouble(), (r["duration"] as Number).toDouble())
        }
    } catch (e: Exception) { null }
}

/**
 * Follows the user along a route: where am I on it, how far to the next turn, am I off the route, have I arrived.
 * Pure math, fed with GPS fixes.
 */
class NavTracker(val route: NavRoute, val destName: String) {
    data class State(
        val next: NavStep?, val toNextM: Double, val remainM: Double, val remainS: Double,
        val offRouteM: Double, val arrived: Boolean, val passedIdx: Int,
    )

    private val cum = DoubleArray(route.points.size)            // distance from start at each vertex
    private var lastIdx = 0                                     // search window start, so a loop in the route doesn't jump back

    init { for (i in 1 until route.points.size) cum[i] = cum[i - 1] + Geo.distance(route.points[i - 1], route.points[i]) }

    /** distance along the route of the point on it nearest to [p], and how far [p] is from the route */
    private fun project(p: LatLon): Triple<Double, Double, Int> {
        var best = Double.MAX_VALUE; var along = 0.0; var bestI = lastIdx
        val end = min(route.points.size - 1, lastIdx + 400)
        for (i in max(0, lastIdx - 5) until end) {
            val a = route.points[i]; val b = route.points[i + 1]
            val (bx, by) = Geo.toLocal(a, b); val (px, py) = Geo.toLocal(a, p)
            val L2 = bx * bx + by * by
            val t = if (L2 < 1e-6) 0.0 else ((px * bx + py * by) / L2).coerceIn(0.0, 1.0)
            val dx = px - t * bx; val dy = py - t * by
            val d = sqrt(dx * dx + dy * dy)
            if (d < best) { best = d; along = cum[i] + t * sqrt(L2); bestI = i }
        }
        return Triple(along, best, bestI)
    }

    fun update(p: LatLon): State {
        val total = cum.lastOrNull() ?: 0.0
        val (along, off, i) = project(p)
        if (off < 40) lastIdx = i
        val remain = max(0.0, total - along)
        // next maneuver = first step whose position lies ahead of us on the route
        var next: NavStep? = null; var toNext = remain
        for (s in route.steps) {
            if (s.type == "depart") continue
            val sAlong = project1(s)
            if (sAlong > along + 3) { next = s; toNext = sAlong - along; break }
        }
        val frac = if (total > 0) remain / total else 0.0
        val arrived = Geo.distance(p, route.points.last()) < 25 || remain < 15
        return State(next, toNext, remain, route.durationS * frac, off, arrived, i)
    }

    private val stepAlong = HashMap<NavStep, Double>()
    private fun project1(s: NavStep): Double = stepAlong.getOrPut(s) {
        var best = Double.MAX_VALUE; var along = 0.0
        for (i in 0 until route.points.size - 1) {
            val a = route.points[i]; val b = route.points[i + 1]
            val (bx, by) = Geo.toLocal(a, b); val (px, py) = Geo.toLocal(a, s.at)
            val L2 = bx * bx + by * by
            val t = if (L2 < 1e-6) 0.0 else ((px * bx + py * by) / L2).coerceIn(0.0, 1.0)
            val d = sqrt((px - t * bx).let { it * it } + (py - t * by).let { it * it })
            if (d < best) { best = d; along = cum[i] + t * sqrt(L2) }
        }
        along
    }
}

/** Formats used by the map card. */
object NavFmt {
    fun dist(m: Double) = if (m < 1000) "%.0f m".format((m / 10).toInt() * 10.0) else "%.1f km".format(m / 1000)
    fun mins(s: Double) = max(1, (s / 60).toInt())
}

/**
 * Side-by-side 3D (glasses in 3840×1080 "SBS" mode: each eye sees one half).
 * A thing at distance z (m) is drawn shifted by ±[shiftPx] in each eye; things at [convergeM]
 * (where the glasses' optics put the image) get zero shift.
 */
object Stereo {
    /** true when a display looks like the glasses in SBS mode (32:9, e.g. 3840×1080). */
    fun isSbs(w: Int, h: Int) = h > 0 && w.toDouble() / h > 3.0

    /** Horizontal shift in px for ONE eye (left eye +, right eye −). Positive = in front of the convergence plane. */
    fun shiftPx(z: Double, focalPx: Double, ipdM: Double = 0.063, convergeM: Double = 4.0): Float =
        if (z <= 0.05) 0f else (focalPx * (ipdM / 2) * (1 / z - 1 / convergeM)).toFloat()
}

/** Ad / pop-up filter for watching films on the web (host suffix match, cheap enough to run on every request). */
object AdFilter {
    val HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com", "google-analytics.com",
        "googletagmanager.com", "googletagservices.com", "amazon-adsystem.com", "adnxs.com", "criteo.com", "criteo.net",
        "pubmatic.com", "rubiconproject.com", "openx.net", "taboola.com", "outbrain.com", "mgid.com", "revcontent.com",
        "popads.net", "popcash.net", "propellerads.com", "onclickads.net", "onclckads.net", "adsterra.com", "adsterratools.com",
        "exoclick.com", "exosrv.com", "juicyads.com", "trafficjunky.net", "hilltopads.net", "a-ads.com", "adcash.com",
        "clickadu.com", "galaksion.com", "monetag.com", "highperformanceformat.com", "profitablecpmrate.com",
        "realsrv.com", "zeydoo.com", "admaven.com", "ad-maven.com", "yllix.com", "bidvertiser.com", "adskeeper.com",
        "scorecardresearch.com", "quantserve.com", "zemanta.com", "adform.net", "smartadserver.com", "yieldmo.com",
        "vidazoo.com", "admicro.vn", "eclick.vn", "adtima.vn", "ambientplatform.vn", "adsota.com", "vlitag.com",
    )

    fun isAd(host: String?): Boolean {
        val h = host?.lowercase()?.trimEnd('.') ?: return false
        return HOSTS.any { h == it || h.endsWith(".$it") }
    }

    /** example.co.uk-ish registrable part; good enough to tell "same site" from "redirect to another site". */
    fun site(host: String?): String {
        val p = (host ?: "").lowercase().split('.').filter { it.isNotEmpty() }
        if (p.size <= 2) return p.joinToString(".")
        val twoLevel = p[p.size - 2] in setOf("co", "com", "net", "org", "gov", "edu", "ac")
        return p.takeLast(if (twoLevel) 3 else 2).joinToString(".")
    }

    /**
     * A page trying to send the main frame to ANOTHER site with no user tap (classic ad redirect on film sites).
     * Server redirects (login flows) are allowed.
     */
    fun isHijack(fromHost: String?, toHost: String?, hasGesture: Boolean, isRedirect: Boolean): Boolean =
        !hasGesture && !isRedirect && fromHost != null && toHost != null && site(fromHost) != site(toHost)
}

/** "Continue watching": remembers where each video stopped. */
class ResumeStore(private val max: Int = 60) {
    private val map = LinkedHashMap<String, Double>(16, 0.75f, true)

    /** Same film page with different tracking params is the same key. */
    fun key(url: String) = url.substringBefore('#').substringBefore("?utm").substringBefore("&utm").trimEnd('/')

    fun save(url: String, timeS: Double, durationS: Double) {
        val k = key(url)
        if (durationS > 0 && (timeS < 30 || timeS > durationS - 60)) { map.remove(k); return }   // barely started / finished
        map[k] = timeS
        while (map.size > max) map.remove(map.keys.first())
    }

    fun get(url: String): Double = map[key(url)] ?: 0.0
    fun entries(): Map<String, Double> = map
    fun load(entries: Map<String, Double>) { map.clear(); map.putAll(entries) }

    companion object {
        fun clock(s: Double): String { val t = s.toInt(); return if (t >= 3600) "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) else "%d:%02d".format(t / 60, t % 60) }
    }
}

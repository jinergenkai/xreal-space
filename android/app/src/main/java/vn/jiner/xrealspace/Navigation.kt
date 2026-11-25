package vn.jiner.xrealspace

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.Location
import android.os.Handler
import android.os.Looper
import vn.jiner.xrealspace.core.LatLon
import vn.jiner.xrealspace.core.NavFmt
import vn.jiner.xrealspace.core.NavTracker
import vn.jiner.xrealspace.core.OsmApi
import vn.jiner.xrealspace.core.Speech
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Plain HTTPS GET with the User-Agent the OpenStreetMap services require. */
internal object OsmHttp {
    fun get(url: String, timeoutMs: Int = 10_000): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("User-Agent", OsmApi.USER_AGENT)
        c.setRequestProperty("Accept-Language", "vi,en")
        try {
            if (c.responseCode != 200) throw RuntimeException("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }
}

/**
 * Real turn-by-turn on the glasses: "chỉ đường tới …" -> place search (Nominatim) -> route (OSRM) -> follow GPS.
 * Leaving the route for ~10 s re-routes (at most every 20 s, the free servers ask for gentle use).
 */
object Navigation {
    @Volatile var tracker: NavTracker? = null; private set
    @Volatile var state: NavTracker.State? = null; private set
    val active get() = tracker != null
    private var profile = "car"
    private var dest: LatLon? = null
    private var offSince = 0L
    private var lastRouteMs = 0L
    private var lastSpokenStep: Any? = null
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    fun start(query: String, profile: String) {
        val st = SpaceState
        val here = Sensors.location
        if (Sensors.appContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            HealthCheck.explain(vn.jiner.xrealspace.core.Health.Req.LOCATION); return
        }
        Sensors.startSpeed()                                    // make sure GPS is on even without glasses
        if (here == null) st.notify("Chỉ đường", "Đang chờ GPS… (cấp quyền Vị trí, ra chỗ thoáng)", Colors.AMBER, 4.0)
        st.notify("Chỉ đường", "Đang tìm \"$query\"…", Colors.CYAN, 4.0)
        this.profile = profile
        io.execute {
            val near = here?.let { LatLon(it.latitude, it.longitude) }
            val place = try { OsmApi.parsePlace(String(OsmHttp.get(OsmApi.searchUrl(query, near)))) } catch (e: Exception) { null }
            if (place == null) { main.post { st.notify("Chỉ đường", "Không tìm thấy \"$query\"", Colors.RED, 4.0) }; return@execute }
            dest = place.at
            val from = waitForFix() ?: run { main.post { st.notify("Chỉ đường", "Chưa có GPS — thử lại khi ra ngoài trời", Colors.RED, 4.0) }; return@execute }
            route(from, place.at, place.name)
        }
    }

    private fun waitForFix(): LatLon? {
        repeat(30) { Sensors.location?.let { return LatLon(it.latitude, it.longitude) }; Thread.sleep(1000) }
        return null
    }

    private fun route(from: LatLon, to: LatLon, name: String) {
        lastRouteMs = System.currentTimeMillis()
        val r = try { OsmApi.parseRoute(String(OsmHttp.get(OsmApi.routeUrl(from, to, profile)))) } catch (e: Exception) { null }
        main.post {
            if (r == null) { SpaceState.notify("Chỉ đường", "Không tìm được đường tới $name", Colors.RED, 4.0); return@post }
            tracker = NavTracker(r, name); state = null; offSince = 0
            SpaceState.showMap = true
            SpaceState.notify("Chỉ đường tới $name", "${NavFmt.dist(r.distanceM)} · khoảng ${NavFmt.mins(r.durationS)} phút", Colors.GREEN, 5.0)
            Speaker.say("Bắt đầu chỉ đường tới $name. ${Speech.distance(r.distanceM)}, khoảng ${NavFmt.mins(r.durationS)} phút", flush = true)
            SpaceState.log("nav: ${r.points.size} pts, ${r.steps.size} steps to $name")
        }
    }

    fun stop(quiet: Boolean = false) {
        tracker = null; state = null; dest = null
        if (!Sensors.usb.connected) Sensors.stopSpeed()
        if (!quiet) SpaceState.notify("Chỉ đường", "Đã dừng", Colors.CYAN, 2.0)
    }

    /** Every GPS fix (main thread). */
    fun onLocation(loc: Location) {
        val t = tracker ?: return
        val p = LatLon(loc.latitude, loc.longitude)
        val s = t.update(p); state = s
        if (s.arrived) { SpaceState.notify("Đã đến", t.destName, Colors.GREEN, 6.0); Speaker.say("Đã đến ${t.destName}", flush = true); stop(quiet = true); return }
        // announce each turn once, ~150 m before (or right away if the step is shorter)
        val next = s.next
        if (next != null && next !== lastSpokenStep && s.toNextM < 150) {
            lastSpokenStep = next
            SpaceState.notify("${NavFmt.dist(s.toNextM)} nữa", next.text(), Colors.CYAN, 5.0)
            Speaker.say(Speech.turn(next, s.toNextM), flush = true)
        }
        // off the route: re-route after 10 s, rate-limited
        val now = System.currentTimeMillis()
        if (s.offRouteM > 40 && loc.accuracy < 30) {
            if (offSince == 0L) offSince = now
            val d = dest
            if (d != null && now - offSince > 10_000 && now - lastRouteMs > 20_000) {
                offSince = 0
                SpaceState.notify("Chỉ đường", "Bạn đi lệch — đang tìm đường mới", Colors.AMBER, 3.0)
                Speaker.say("Đang tìm đường mới")
                io.execute { route(p, d, t.destName) }
            }
        } else offSince = 0
    }
}

/**
 * OpenStreetMap tiles for the glasses' map: memory LRU + disk cache, fetched in the background.
 * Follows the OSM tile policy for light use: real User-Agent, cached, no bulk pre-fetching, attribution drawn.
 */
object Tiles {
    private val mem = object : LinkedHashMap<String, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Bitmap>) = size > 60
    }
    private val pending = HashSet<String>()
    private val failed = HashMap<String, Long>()
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private var dir: File? = null
    var onLoaded: (() -> Unit)? = null

    fun init(cacheDir: File) { if (dir == null) dir = File(cacheDir, "osm-tiles").apply { mkdirs() } }

    /** Bitmap if ready, otherwise null and a background load starts (main thread only). */
    fun get(z: Int, x: Int, y: Int): Bitmap? {
        val n = 1 shl z
        if (y < 0 || y >= n) return null
        val xx = ((x % n) + n) % n
        val k = "$z/$xx/$y"
        mem[k]?.let { return it }
        if (k in pending || (failed[k] ?: 0) > System.currentTimeMillis()) return null
        pending.add(k)
        io.execute {
            val f = dir?.let { File(it, k.replace('/', '_') + ".png") }
            val bmp = try {
                if (f != null && f.exists() && System.currentTimeMillis() - f.lastModified() < 7L * 86_400_000) BitmapFactory.decodeFile(f.path)
                else OsmHttp.get(OsmApi.tileUrl(z, xx, y), 8000).let { bytes ->
                    f?.writeBytes(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            } catch (e: Exception) { null }
            main.post {
                pending.remove(k)
                if (bmp != null) { mem[k] = bmp; onLoaded?.invoke() } else failed[k] = System.currentTimeMillis() + 30_000
            }
        }
        return null
    }
}

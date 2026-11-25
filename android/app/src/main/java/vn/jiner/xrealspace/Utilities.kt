package vn.jiner.xrealspace

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.CalendarContract
import org.json.JSONArray
import org.json.JSONObject
import vn.jiner.xrealspace.core.Agenda
import vn.jiner.xrealspace.core.CalEvent
import vn.jiner.xrealspace.core.Countdown
import vn.jiner.xrealspace.core.NoteBook
import vn.jiner.xrealspace.core.Weather
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Small things on the glasses' info bar: today's calendar (+ a reminder 10 min before), weather, a timer /
 * Pomodoro, and voice notes. Everything is local except the weather (Open-Meteo, free, no account).
 */
object Utilities {
    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val dm = SimpleDateFormat("dd/MM HH:mm", Locale.US)

    @Volatile var weather: Weather? = null; private set
    private var weatherAt = 0L
    var events: List<CalEvent> = emptyList(); private set
    private val announced = HashSet<String>()
    var timer: Countdown? = null; private set
    val notes = NoteBook()

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        loadNotes()
        main.post(tick)
    }

    /** every 30 s: timer end, calendar reminders; calendar every 5 min; weather every 30 min */
    private var n = 0
    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            timer?.let { if (it.done(now)) finishTimer(it) }
            if (n % 10 == 0) refreshCalendar()
            Agenda.dueReminder(events, now, announced)?.let { e ->
                announced.add(Agenda.key(e))
                val mins = ((e.startMs - now) / 60_000).coerceAtLeast(1)
                SpaceState.notify("📅 $mins phút nữa", e.title, Colors.AMBER, 8.0)
                Speaker.say("$mins phút nữa: ${e.title}", force = SpaceState.glassesActive)
            }
            if (now - weatherAt > 30 * 60_000L) refreshWeather()
            n++
            main.postDelayed(this, if (timer != null) 1000 else 30_000)
        }
    }

    /** short line under the clock: "🌧 31° · ⏱ 12:30 · 14:00 Họp" */
    fun infoLine(now: Long = System.currentTimeMillis()): String = listOfNotNull(
        weather?.short(),
        timer?.let { "⏱ ${it.text(now)}" },
        Agenda.upcoming(events, now).firstOrNull { !it.allDay }?.let { "${hm.format(Date(it.startMs))} ${it.title.take(14)}" },
    ).joinToString(" · ")

    // ------------------------------------------------------------------ timer
    fun startTimer(minutes: Int, label: String) {
        timer = Countdown(label, minutes * 60_000L, System.currentTimeMillis())
        SpaceState.notify("⏱ $label", "$minutes phút", Colors.CYAN, 2.5)
        main.removeCallbacks(tick); main.post(tick)
    }

    fun stopTimer() { timer = null; SpaceState.notify("⏱ Hẹn giờ", "đã huỷ", Colors.GREY, 1.5) }

    private fun finishTimer(t: Countdown) {
        timer = null
        val text = if (t.label == "Pomodoro") "Hết 25 phút — nghỉ ${Countdown.BREAK_MIN} phút nhé (nói \"nghỉ ${Countdown.BREAK_MIN} phút\")" else "Hết giờ"
        SpaceState.notify("⏰ ${t.label}", text, Colors.GREEN, 10.0)
        Speaker.say("${t.label}: ${if (t.label == "Pomodoro") "hết giờ, nghỉ một chút" else "hết giờ"}", flush = true, force = true)
        vibrate()
    }

    @Suppress("DEPRECATION")
    private fun vibrate() = try {
        val v = app.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1)) else v.vibrate(600)
    } catch (_: Exception) {}

    // ------------------------------------------------------------------ notes
    fun addNote(text: String) {
        val n = notes.add(text, System.currentTimeMillis()) ?: return
        saveNotes()
        SpaceState.notify("📝 Đã ghi", n.text, Colors.GREEN, 3.0)
    }

    fun showNotes() {
        val list = notes.recent(8)
        SpaceState.showCard("Ghi chú (${notes.notes.size})",
            if (list.isEmpty()) "Chưa có ghi chú — nói \"ghi chú …\"" else list.joinToString("\n") { "• ${hm.format(Date(it.timeMs))}  ${it.text}" }, 20.0)
        Speaker.say(if (list.isEmpty()) "Chưa có ghi chú" else list.take(5).joinToString(". ") { it.text }, flush = true, force = true)
    }

    fun exportNotes(): String = notes.export { dm.format(Date(it)) }

    private fun saveNotes() {
        val a = JSONArray(); notes.notes.forEach { a.put(JSONObject().put("t", it.timeMs).put("x", it.text)) }
        app.getSharedPreferences("notes", Context.MODE_PRIVATE).edit().putString("list", a.toString()).apply()
    }

    private fun loadNotes() = try {
        val a = JSONArray(app.getSharedPreferences("notes", Context.MODE_PRIVATE).getString("list", "[]"))
        notes.notes.clear()
        for (i in 0 until a.length()) a.getJSONObject(i).let { notes.notes.add(vn.jiner.xrealspace.core.Note(it.getLong("t"), it.getString("x"))) }
    } catch (_: Exception) {}

    // ------------------------------------------------------------------ calendar
    val calendarAllowed get() = app.checkSelfPermission(Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun refreshCalendar() {
        if (!calendarAllowed) return
        io.execute {
            val start = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            val end = start + 86_400_000L
            val list = ArrayList<CalEvent>()
            try {
                val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                    android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end)
                }.build()
                app.contentResolver.query(uri, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY), null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                    while (c.moveToNext()) list.add(CalEvent(c.getString(0) ?: "(không tên)", c.getLong(1), c.getLong(2), c.getInt(3) == 1))
                }
            } catch (e: Exception) { SpaceState.log("calendar: $e") }
            main.post { events = list }
        }
    }

    fun showAgenda() {
        if (!calendarAllowed) { SpaceState.notify("Lịch", "Cấp quyền Lịch trong app (thẻ Thiết lập)", Colors.AMBER, 3.0); return }
        refreshCalendar()
        main.postDelayed({
            val list = Agenda.upcoming(events, System.currentTimeMillis())
            SpaceState.showCard("Hôm nay", if (list.isEmpty()) "Không còn lịch nào hôm nay" else list.joinToString("\n") { Agenda.line(it) { t -> hm.format(Date(t)) } }, 20.0)
            Speaker.say(if (list.isEmpty()) "Hôm nay không còn lịch nào" else list.take(4).joinToString(". ") { Agenda.line(it) { t -> hm.format(Date(t)) } }, flush = true, force = true)
        }, 400)
    }

    // ------------------------------------------------------------------ weather
    @Suppress("MissingPermission")
    private fun lastLocation(): android.location.Location? {
        Sensors.location?.let { return it }
        if (app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { p -> try { lm.getLastKnownLocation(p) } catch (_: Exception) { null } }.maxByOrNull { it.time }
    }

    fun refreshWeather(then: (() -> Unit)? = null) {
        val loc = lastLocation() ?: run { then?.invoke(); return }
        weatherAt = System.currentTimeMillis()
        io.execute {
            val w = try { Weather.parse(String(OsmHttp.get(Weather.url(loc.latitude, loc.longitude)))) } catch (e: Exception) { null }
            main.post { if (w != null) weather = w; then?.invoke() }
        }
    }

    fun showWeather() {
        refreshWeather {
            val w = weather
            if (w == null) { SpaceState.notify("Thời tiết", "Chưa có vị trí — cấp quyền Vị trí trong app", Colors.AMBER, 3.0); return@refreshWeather }
            SpaceState.notify("Thời tiết", w.long(), Colors.CYAN, 6.0)
            Speaker.say(w.long().replace("°C", " độ").replace("°", " độ").replace("–", " đến "), flush = true, force = true)
        }
    }
}

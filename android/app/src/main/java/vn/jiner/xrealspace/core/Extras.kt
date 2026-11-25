package vn.jiner.xrealspace.core

import kotlin.math.ceil
import kotlin.math.max

// ============================================================================ documents (PDF / EPUB)

/**
 * A portrait page on a landscape screen: the page is scaled to the panel width and shown in vertical "slices"
 * (each one panel tall, 15 % overlap so no line is cut in half). "next" = next slice, then the next page.
 */
class DocNav(private val pageAspect: (Int) -> Double, val pageCount: Int, private val viewAspect: Double = 720.0 / 1280.0) {
    var page = 0; private set
    var slice = 0; private set

    /** number of slices of a page whose height/width = [aspect] */
    fun slices(p: Int): Int {
        val a = pageAspect(p)
        if (a <= viewAspect * 1.02) return 1
        return ceil((a - viewAspect) / (viewAspect * 0.85)).toInt() + 1
    }

    /** top of the current slice, as a fraction of the page height (0 = top) */
    fun sliceTop(): Double {
        val a = pageAspect(page); val n = slices(page)
        if (n <= 1) return 0.0
        return (slice.toDouble() / (n - 1)) * (a - viewAspect) / a
    }

    fun next(): Boolean {
        if (slice < slices(page) - 1) { slice++; return true }
        if (page < pageCount - 1) { page++; slice = 0; return true }
        return false
    }

    fun prev(): Boolean {
        if (slice > 0) { slice--; return true }
        if (page > 0) { page--; slice = slices(page) - 1; return true }
        return false
    }

    fun go(p: Int, s: Int = 0) { page = p.coerceIn(0, max(0, pageCount - 1)); slice = s.coerceIn(0, slices(page) - 1) }
    fun label() = "Trang ${page + 1}/$pageCount" + if (slices(page) > 1) " · phần ${slice + 1}/${slices(page)}" else ""
}

/** Reading order of an EPUB from its OPF package file (manifest id -> href, spine order). Regex is enough for OPF. */
object Epub {
    /** path of the OPF from META-INF/container.xml */
    fun opfPath(containerXml: String): String? =
        Regex("""full-path\s*=\s*"([^"]+)"""").find(containerXml)?.groupValues?.get(1)

    /** chapter files in reading order, relative to the OPF's folder */
    fun spine(opf: String): List<String> {
        val items = HashMap<String, String>()
        for (m in Regex("""<(?:\w+:)?item\b[^>]*>""").findAll(opf)) {
            val tag = m.value
            val id = Regex("""\bid\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1) ?: continue
            val href = Regex("""\bhref\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1) ?: continue
            val type = Regex("""media-type\s*=\s*"([^"]+)"""").find(tag)?.groupValues?.get(1) ?: ""
            if (type.contains("html")) items[id] = java.net.URLDecoder.decode(href, "UTF-8")
        }
        return Regex("""<(?:\w+:)?itemref\b[^>]*\bidref\s*=\s*"([^"]+)"""").findAll(opf).mapNotNull { items[it.groupValues[1]] }.toList()
    }

    fun title(opf: String): String? = Regex("""<dc:title[^>]*>([^<]+)</dc:title>""").find(opf)?.groupValues?.get(1)?.trim()
}

// ============================================================================ timer / pomodoro

class Countdown(val label: String, val totalMs: Long, startMs: Long) {
    val endMs = startMs + totalMs
    fun remainingMs(now: Long) = max(0L, endMs - now)
    fun done(now: Long) = now >= endMs
    fun text(now: Long): String { val s = (remainingMs(now) + 999) / 1000; return "%d:%02d".format(s / 60, s % 60) }

    companion object {
        const val POMODORO_MIN = 25
        const val BREAK_MIN = 5
    }
}

// ============================================================================ weather (Open-Meteo, free, no key)

data class Weather(val tempC: Double, val code: Int, val maxC: Double?, val minC: Double?, val rainPct: Int?) {
    fun short() = "${icon(code)} ${tempC.toInt()}°"
    fun long() = "${describe(code)}, ${tempC.toInt()}°C" +
        (if (maxC != null && minC != null) " · hôm nay ${minC.toInt()}–${maxC.toInt()}°" else "") +
        (rainPct?.let { " · khả năng mưa $it%" } ?: "")

    companion object {
        fun url(lat: Double, lon: Double) = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(java.util.Locale.US, lat, lon) +
            "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&forecast_days=1&timezone=auto"

        fun parse(json: String): Weather? = try {
            val o = MiniJson.parse(json) as Map<*, *>
            val cur = o["current"] as Map<*, *>
            val d = o["daily"] as? Map<*, *>
            fun first(k: String) = ((d?.get(k) as? List<*>)?.firstOrNull() as? Number)?.toDouble()
            Weather((cur["temperature_2m"] as Number).toDouble(), (cur["weather_code"] as Number).toInt(),
                first("temperature_2m_max"), first("temperature_2m_min"), first("precipitation_probability_max")?.toInt())
        } catch (e: Exception) { null }

        /** WMO weather codes -> Vietnamese */
        fun describe(c: Int) = when (c) {
            0 -> "Trời quang"; 1, 2 -> "Ít mây"; 3 -> "Nhiều mây"; 45, 48 -> "Sương mù"
            in 51..57 -> "Mưa phùn"; in 61..67 -> "Mưa"; in 71..77 -> "Tuyết"; in 80..82 -> "Mưa rào"
            in 95..99 -> "Dông"; else -> "Thời tiết"
        }
        fun icon(c: Int) = when (c) { 0 -> "☀"; 1, 2 -> "⛅"; 3, 45, 48 -> "☁"; in 95..99 -> "⛈"; in 51..82 -> "🌧"; else -> "·" }
    }
}

// ============================================================================ voice notes

data class Note(val timeMs: Long, val text: String)

class NoteBook(private val max: Int = 200) {
    val notes = ArrayList<Note>()
    fun add(text: String, now: Long): Note? {
        val t = text.trim().trimEnd('.', ',')
        if (t.isEmpty()) return null
        val n = Note(now, t.replaceFirstChar { it.uppercase() })
        notes.add(0, n)
        while (notes.size > max) notes.removeAt(notes.lastIndex)
        return n
    }
    fun recent(n: Int = 8) = notes.take(n)
    fun export(fmt: (Long) -> String): String = notes.joinToString("\n") { "- ${fmt(it.timeMs)}  ${it.text}" }
}

// ============================================================================ calendar

data class CalEvent(val title: String, val startMs: Long, val endMs: Long, val allDay: Boolean)

object Agenda {
    /** events still to come (or in progress) today, in order */
    fun upcoming(events: List<CalEvent>, now: Long) = events.filter { it.allDay || it.endMs > now }.sortedBy { if (it.allDay) 0 else it.startMs }

    /** the next timed event starting within [leadMs] that has not been announced yet */
    fun dueReminder(events: List<CalEvent>, now: Long, announced: Set<String>, leadMs: Long = 10 * 60_000L): CalEvent? =
        events.filter { !it.allDay && it.startMs > now && it.startMs - now <= leadMs && key(it) !in announced }.minByOrNull { it.startMs }

    fun key(e: CalEvent) = "${e.startMs}|${e.title}"
    fun line(e: CalEvent, hm: (Long) -> String) = if (e.allDay) "Cả ngày · ${e.title}" else "${hm(e.startMs)} ${e.title}"
}

// ============================================================================ first-run tutorial

/** Five steps, each finished by actually doing it (not by pressing "next"). */
class Tutorial {
    enum class Step(val title: String, val how: String) {
        RECENTER("Về giữa", "Chạm 2 ngón lên touchpad (hoặc bấm nút âm lượng −) để đưa màn hình về trước mặt"),
        LOOK("Nhìn để chọn", "Quay đầu nhìn sang một màn hình khác — màn đang nhìn có viền sáng"),
        CLICK("Bấm", "Chạm 1 ngón lên touchpad (hoặc nút âm lượng +) để bấm vào chỗ chấm giữa tầm nhìn"),
        VOICE("Nói lệnh", "Giữ touchpad rồi nói: \"mở YouTube ô 5\" (hoặc nói \"kính ơi\" trước)"),
        NOD("Gật đầu", "Gật đầu một cái để trả lời \"có\" — lắc đầu là \"không\""),
    }
    enum class Event { RECENTER, LOOK, CLICK, VOICE, NOD }

    var index = 0; private set
    val done get() = index >= Step.values().size
    val step: Step? get() = Step.values().getOrNull(index)

    /** returns true if this event finished the current step */
    fun on(e: Event): Boolean {
        val s = step ?: return false
        if (s.name != e.name) return false
        index++; return true
    }
    fun skip() { index = Step.values().size }
    fun progress() = "${minOf(index + 1, Step.values().size)}/${Step.values().size}"
}

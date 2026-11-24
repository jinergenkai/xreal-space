package vn.jiner.xrealspace.core

/**
 * Teleprompter that follows your voice: the recognizer's words are matched against the script
 * a little ahead of where you are, so the text scrolls as you read (and waits when you pause or ad-lib).
 */
class PrompterTracker(script: String, private val lookahead: Int = 40) {
    /** original words (for display) and folded words (for matching) */
    val words: List<String> = script.split(Regex("\\s+")).filter { it.isNotBlank() }
    private val folded = words.map { VoiceCommands.fold(it).replace(Regex("[^a-z0-9]"), "") }
    /** index of the next word to read */
    var pos = 0; private set
    val done get() = pos >= words.size

    /**
     * Feed the latest recognized text (partial or final). Matches its last few words inside the window
     * [pos, pos+lookahead) and moves to just after the best match. Returns true when the position moved.
     */
    fun hear(text: String): Boolean {
        val heard = VoiceCommands.fold(text).split(' ').map { it.replace(Regex("[^a-z0-9]"), "") }.filter { it.isNotEmpty() }.takeLast(6)
        if (heard.isEmpty() || done) return false
        var bestEnd = -1; var bestScore = 0
        val end = minOf(words.size, pos + lookahead)
        for (i in pos until end) {
            // align heard's last word to script word i, count matching words backwards
            var score = 0; var k = heard.size - 1; var j = i
            while (k >= 0 && j >= maxOf(0, pos - 3)) {
                if (folded[j] == heard[k]) score++ else if (score == 0) break
                k--; j--
            }
            // need 2 matching words (or 1 long distinctive word) to move, prefer the nearest best
            val ok = score >= 2 || (score == 1 && folded[i].length >= 5)
            if (ok && score > bestScore) { bestScore = score; bestEnd = i }
        }
        if (bestEnd >= pos) { pos = bestEnd + 1; return true }
        return false
    }

    /** manual nudge (touchpad drag / keys) */
    fun move(delta: Int) { pos = (pos + delta).coerceIn(0, words.size) }
    fun reset() { pos = 0 }

    /** text already read / the rest, for rendering a highlight */
    fun spoken() = words.subList(0, pos).joinToString(" ")
    fun upcoming() = words.subList(pos, words.size).joinToString(" ")
}

/** Rolling caption lines: partial text updates the last line, finals commit it; keeps the screen to 2 lines. */
class CaptionBuffer(private val maxLines: Int = 2, private val maxChars: Int = 90) {
    data class Line(val text: String, var translation: String? = null)

    private val lines = ArrayList<Line>()
    var partial = ""; private set

    fun onPartial(t: String) { partial = t.trim() }

    /** Commits a final segment; returns the committed line (so a translation can be attached later). */
    fun onFinal(t: String): Line? {
        partial = ""
        val s = t.trim(); if (s.isEmpty()) return null
        // split long finals so each line fits the glasses
        var rest = s; var last: Line? = null
        while (rest.isNotEmpty()) {
            val cut = if (rest.length <= maxChars) rest.length else rest.lastIndexOf(' ', maxChars).let { if (it < 20) maxChars else it }
            last = Line(rest.substring(0, cut).trim()); lines.add(last)
            rest = rest.substring(cut).trim()
        }
        while (lines.size > maxLines) lines.removeAt(0)
        return last
    }

    fun visible(): List<Line> = lines + (if (partial.isNotEmpty()) listOf(Line(partial.takeLast(maxChars))) else emptyList())

    fun clear() { lines.clear(); partial = "" }
}

/** Live captions / interpreter modes. */
enum class CaptionMode(val title: String, val lang: String, val translateTo: String?) {
    OFF("Tắt", "", null),
    VI("Phụ đề tiếng Việt", "vi", null),
    EN("English captions", "en", null),
    EN_TO_VI("Dịch Anh → Việt", "en", "vi"),
    VI_TO_EN("Dịch Việt → Anh", "vi", "en"),
}

/** Maps a point on the mirrored phone screen (panel-local) to real phone pixels for Accessibility gestures. */
object MirrorMap {
    /** @param lx,ly point inside the mirror view, @param vw,vh mirror view size, @param pw,ph phone screen size */
    fun toPhone(lx: Float, ly: Float, vw: Float, vh: Float, pw: Int, ph: Int): Pair<Float, Float>? {
        if (vw <= 0 || vh <= 0 || lx < 0 || ly < 0 || lx > vw || ly > vh) return null
        return (lx / vw * pw).coerceIn(0f, pw - 1f) to (ly / vh * ph).coerceIn(0f, ph - 1f)
    }
}

/** Finds an installed app by spoken name ("mở Zalo", "mở Grab"). Labels are folded; best = exact, then prefix, then contains. */
object AppMatcher {
    data class App(val label: String, val pkg: String)

    fun find(spoken: String, apps: List<App>): App? {
        val q = VoiceCommands.fold(spoken).trim()
        if (q.isEmpty()) return null
        val f = apps.map { it to VoiceCommands.fold(it.label) }
        return f.firstOrNull { it.second == q }?.first
            ?: f.firstOrNull { it.second.startsWith(q) || q.startsWith(it.second) && it.second.length >= 3 }?.first
            ?: f.filter { it.second.length >= 3 && (it.second.contains(q) || q.contains(it.second)) }.maxByOrNull { it.second.length }?.first
    }
}

/** Prompt for translating one caption segment with the LLM. */
object TranslatePrompt {
    fun system(from: String, to: String) =
        "Translate the user's speech-recognized text from ${name(from)} to ${name(to)}. " +
            "It may contain recognition errors: translate the intended meaning. Reply with the translation only, one line, no quotes."

    private fun name(c: String) = when (c) { "vi" -> "Vietnamese"; "en" -> "English"; else -> c }
}

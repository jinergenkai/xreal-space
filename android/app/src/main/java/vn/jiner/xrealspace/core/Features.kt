package vn.jiner.xrealspace.core

/**
 * 9-slot spatial grid (3x3 around the head), numbered like a phone keypad:
 *   1 2 3   (look up)
 *   4 5 6   (straight)
 *   7 8 9   (look down)
 * Columns: left / centre / right. Slot 5 = straight ahead.
 */
object SlotGrid {
    const val YAW_STEP = 42.0
    const val PITCH_STEP = 26.0

    fun yaw(slot: Int) = when ((slot - 1) % 3) { 0 -> YAW_STEP; 1 -> 0.0; else -> -YAW_STEP }
    fun pitch(slot: Int) = when ((slot - 1) / 3) { 0 -> PITCH_STEP; 1 -> 0.0; else -> -PITCH_STEP }
    fun width(slot: Int) = if (slot == 5) 1.2 else 1.0

    /** Slot closest to a direction (yaw/pitch in degrees). */
    fun nearest(yawDeg: Double, pitchDeg: Double): Int =
        (1..9).minBy { s -> val dy = wrap180(yawDeg - yaw(s)); val dp = pitchDeg - pitch(s); dy * dy + dp * dp }

    /** Slot a screen currently occupies (null if it was moved off-grid). */
    fun slotOf(s: ScreenSpec): Int? = (1..9).firstOrNull { abs(wrap180(s.yaw - yaw(it))) < 1.0 && abs(s.pitch - pitch(it)) < 1.0 }

    private fun abs(x: Double) = kotlin.math.abs(x)

    fun spec(slot: Int, title: String, url: String) = ScreenSpec(title, url, yaw(slot), pitch(slot), width(slot))

    private val NUMBER_WORDS = listOf(
        "mot" to 1, "hai" to 2, "ba" to 3, "bon" to 4, "tu" to 4, "nam" to 5, "lam" to 5,
        "sau" to 6, "bay" to 7, "tam" to 8, "chin" to 9,
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9,
    )

    /** Finds "ô 3" / "o ba" / "slot three" / "số 5" in folded text. */
    fun parseSlot(folded: String): Int? {
        for (m in Regex("""(?:^|\s)(?:o|slot|so|cell|man)\s+(?:so\s+)?([1-9]|[a-z]+)\b""").findAll(folded)) {
            val w = m.groupValues[1]
            w.toIntOrNull()?.let { return it }
            NUMBER_WORDS.firstOrNull { it.first == w }?.second?.let { return it }
        }
        return null
    }
}

/** Saved layouts. Names are folded (no diacritics) for voice matching. */
object Workspaces {
    data class Workspace(val id: String, val title: String, val screens: List<ScreenSpec>, val distanceM: Double, val showMap: Boolean)

    private fun s(slot: Int, t: String, u: String) = SlotGrid.spec(slot, t, u)

    val PRESETS = listOf(
        Workspace("lam viec", "Làm việc", listOf(s(5, "Google", "https://www.google.com"), s(4, "Gmail", "https://mail.google.com"),
            s(6, "Lịch", "https://calendar.google.com")), 2.0, true),
        Workspace("code", "Code", listOf(s(5, "GitHub", "https://github.com"), s(4, "Claude", "https://claude.ai"),
            s(6, "Docs", "https://developer.android.com"), s(2, "Tìm", "https://www.google.com")), 2.0, false),
        Workspace("giai tri", "Giải trí (rạp phim)", listOf(ScreenSpec("Rạp", "https://m.youtube.com", 0.0, 0.0, 3.2)), 3.0, false),
        Workspace("hop", "Họp", listOf(s(5, "Họp", "https://meet.google.com"), s(4, "Ghi chú", "https://docs.google.com")), 2.0, false),
    )

    fun find(name: String, saved: Map<String, Workspace> = emptyMap()): Workspace? {
        val t = VoiceCommands.fold(name).trim()
        saved[t]?.let { return it }
        saved.entries.firstOrNull { t.contains(it.key) }?.let { return it.value }
        return PRESETS.firstOrNull { t.contains(it.id) }
            ?: when {
                t.contains("rap phim") || t.contains("phim") || t.contains("movie") || t.contains("cinema") -> PRESETS[2]
                t.contains("lap trinh") || t.contains("coding") -> PRESETS[1]
                t.contains("meeting") -> PRESETS[3]
                t.contains("work") || t.contains("van phong") -> PRESETS[0]
                else -> null
            }
    }
}

/** Minimal JSON reader (objects, arrays, strings, numbers, bool, null) so AI parsing stays unit-testable. */
object MiniJson {
    fun parse(s: String): Any? = P(s).run { val v = value(); ws(); v }

    private class P(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun value(): Any? {
            ws()
            require(i < s.length) { "eof" }
            return when (s[i]) {
                '{' -> obj(); '[' -> arr(); '"' -> str()
                't' -> { i += 4; true }; 'f' -> { i += 5; false }; 'n' -> { i += 4; null }
                else -> num()
            }
        }
        fun obj(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>(); i++; ws()
            if (s[i] == '}') { i++; return m }
            while (true) {
                ws(); val k = str(); ws(); require(s[i] == ':'); i++
                m[k] = value(); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == '}'); i++; return m
            }
        }
        fun arr(): List<Any?> {
            val l = ArrayList<Any?>(); i++; ws()
            if (s[i] == ']') { i++; return l }
            while (true) {
                l.add(value()); ws()
                if (s[i] == ',') { i++; continue }
                require(s[i] == ']'); i++; return l
            }
        }
        fun str(): String {
            require(s[i] == '"'); i++
            val b = StringBuilder()
            while (s[i] != '"') {
                if (s[i] == '\\') {
                    i++
                    when (s[i]) {
                        'n' -> b.append('\n'); 't' -> b.append('\t'); 'r' -> b.append('\r'); 'b' -> b.append('\b'); 'f' -> b.append('\u000c')
                        'u' -> { b.append(s.substring(i + 1, i + 5).toInt(16).toChar()); i += 4 }
                        else -> b.append(s[i])
                    }
                } else b.append(s[i])
                i++
            }
            i++; return b.toString()
        }
        fun num(): Double {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] in "+-.eE")) i++
            return s.substring(st, i).toDouble()
        }
    }

    fun quote(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '"' -> b.append("\\\""); c == '\\' -> b.append("\\\\"); c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r"); c == '\t' -> b.append("\\t"); c < ' ' -> b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }
}

/**
 * Speech -> intent with an LLM: the model gets the app's functions and answers with JSON actions.
 * Works with Anthropic Messages API or any OpenAI-compatible endpoint (Ollama, LM Studio, OpenRouter…).
 */
object LlmProtocol {
    val SYSTEM_PROMPT = """
Bạn là bộ điều khiển của app kính AR "XREAL Space". Người dùng nói tiếng Việt hoặc tiếng Anh (câu do máy nhận giọng nói, có thể sai chính tả).
Không gian có lưới 9 ô quanh đầu, đánh số như bàn phím điện thoại: 1 2 3 (trên), 4 5 6 (giữa, 5 = chính diện), 7 8 9 (dưới).
Chỉ trả về MỘT object JSON, không giải thích:
{"actions":[{"fn":"<tên hàm>", ...tham số}], "say":"<câu ngắn tiếng Việt xác nhận>"}
Các hàm:
- place {slot:1-9, url, title}   đặt trang web vào ô (tự đoán URL: YouTube = https://m.youtube.com, Gmail = https://mail.google.com …)
- open_url {url}   mở trang trên màn đang nhìn
- search {query, slot?}   tìm Google (trên ô nếu có, không thì màn đang nhìn)
- clear_slot {slot}
- set_workspace {name}   mở không gian đã lưu / dựng sẵn ("lam viec","code","giai tri","hop" hoặc tên người dùng đã lưu)
- save_workspace {name}
- show_grid {}   hiện số các ô
- recenter {}
- walk_mode {on:true|false|null}
- map {on:true|false|null}
- distance {change:"farther"|"closer"}
- size {change:"bigger"|"smaller"}   màn đang nhìn
- head_lock {on:true|false|null}
- follow_mode {mode:"world"|"cinema"|"travel"}   cố định trong phòng / rạp phim trôi theo / di chuyển (xe, máy bay)
- dark_web {on:true|false|null}   làm tối trang web cho đỡ chói
- summarize {}   tóm tắt trang đang nhìn
- open_app {name}   mở app Android đã cài (Zalo, Grab, Netflix…); app hiện trên kính qua màn hình điện thoại
- mirror {slot:1-9|null, off:true|false}   đưa màn hình điện thoại vào ô / tắt
- captions {mode:"off"|"vi"|"en"|"en_vi"|"vi_en"}   phụ đề trực tiếp / phiên dịch
- prompter {on:true|false|null}   teleprompter chạy theo giọng đọc
- back {} / home {}   quay lại / về màn hình chính của điện thoại
- navigate {dest:string, profile:"car"|"bike"|"foot"}   chỉ đường thật (GPS) tới địa điểm
- stop_navigation {}   dừng chỉ đường
- ad_block {on:bool}   chặn quảng cáo / cửa sổ bật lên trên trang web (xem phim)
- desktop_site {on:bool}   trang web bản máy tính (true) hoặc bản điện thoại (false)
- read_notifications {}   đọc các thông báo mới của điện thoại
- reply {text}   trả lời tin nhắn mới nhất (Zalo, Messenger, SMS…) bằng nội dung text
- mute_notifications {on:true|false, app:string|null}   tắt/bật thông báo một app; app null = chế độ im lặng
- answer {text}   câu hỏi thường: trả lời ngắn (tối đa 3 câu) để hiện lên kính
Nhiều bước thì trả nhiều action theo thứ tự. Không chắc thì dùng answer để hỏi lại.
""".trimIndent()

    private fun bool(v: Any?): Boolean? = v as? Boolean
    private fun slot(v: Any?): Int? = when (v) {
        is Double -> v.toInt().takeIf { it in 1..9 }
        is String -> v.trim().toIntOrNull()?.takeIf { it in 1..9 }
        else -> null
    }

    data class Reply(val cmds: List<VoiceCommands.Cmd>, val say: String?)

    /** Extract the first {...} block (models sometimes wrap JSON in prose or ``` fences). */
    fun extractJson(text: String): String? {
        val start = text.indexOf('{'); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (k in start until text.length) {
            val c = text[k]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return text.substring(start, k + 1) } }
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    fun parseReply(text: String): Reply {
        val js = extractJson(text) ?: return Reply(listOf(VoiceCommands.Cmd.Answer(text.trim().take(400))), null)
        val o = MiniJson.parse(js) as? Map<String, Any?> ?: return Reply(emptyList(), null)
        val out = ArrayList<VoiceCommands.Cmd>()
        for (a in (o["actions"] as? List<Any?>).orEmpty()) {
            val m = a as? Map<String, Any?> ?: continue
            val c: VoiceCommands.Cmd? = when (m["fn"]) {
                "place" -> { val s = slot(m["slot"]); val u = m["url"] as? String
                    if (s != null && u != null) VoiceCommands.Cmd.Place(s, u, m["title"] as? String) else null }
                "open_url" -> (m["url"] as? String)?.let { VoiceCommands.Cmd.Open(null, it) }
                "search" -> (m["query"] as? String)?.let { q ->
                    val u = VoiceCommands.searchUrl(q)
                    slot(m["slot"])?.let { VoiceCommands.Cmd.Place(it, u, q) } ?: VoiceCommands.Cmd.Open(null, u) }
                "clear_slot" -> slot(m["slot"])?.let { VoiceCommands.Cmd.ClearSlot(it) }
                "set_workspace" -> (m["name"] as? String)?.let { VoiceCommands.Cmd.Workspace(it) }
                "save_workspace" -> (m["name"] as? String)?.let { VoiceCommands.Cmd.SaveWorkspace(it) }
                "show_grid" -> VoiceCommands.Cmd.ShowGrid
                "recenter" -> VoiceCommands.Cmd.Recenter
                "walk_mode" -> VoiceCommands.Cmd.Walk(bool(m["on"]))
                "map" -> VoiceCommands.Cmd.Map(bool(m["on"]))
                "distance" -> if (m["change"] == "closer") VoiceCommands.Cmd.Closer else VoiceCommands.Cmd.Farther
                "size" -> VoiceCommands.Cmd.Size(m["change"] != "smaller")
                "head_lock" -> VoiceCommands.Cmd.HeadLock(bool(m["on"]))
                "follow_mode" -> when (m["mode"]) { "cinema" -> FollowMode.CINEMA; "travel" -> FollowMode.TRAVEL; else -> FollowMode.WORLD }.let { VoiceCommands.Cmd.Follow(it) }
                "dark_web" -> VoiceCommands.Cmd.DarkWeb(bool(m["on"]))
                "summarize" -> VoiceCommands.Cmd.Summarize
                "open_app" -> (m["name"] as? String)?.let { VoiceCommands.Cmd.LaunchApp(it) }
                "mirror" -> if (m["off"] == true) VoiceCommands.Cmd.MirrorOff else VoiceCommands.Cmd.Mirror(slot(m["slot"]))
                "captions" -> VoiceCommands.Cmd.Captions(when (m["mode"]) {
                    "vi" -> CaptionMode.VI; "en" -> CaptionMode.EN; "en_vi" -> CaptionMode.EN_TO_VI; "vi_en" -> CaptionMode.VI_TO_EN; else -> CaptionMode.OFF })
                "prompter" -> VoiceCommands.Cmd.Prompter(bool(m["on"]))
                "back" -> VoiceCommands.Cmd.Back
                "home" -> VoiceCommands.Cmd.Home
                "read_notifications" -> VoiceCommands.Cmd.ReadNotifs
                "navigate" -> (m["dest"] as? String)?.takeIf { it.isNotBlank() }?.let {
                    VoiceCommands.Cmd.Navigate(it, (m["profile"] as? String)?.takeIf { p -> p in setOf("car", "bike", "foot") } ?: "car")
                }
                "stop_navigation" -> VoiceCommands.Cmd.StopNav
                "ad_block" -> VoiceCommands.Cmd.AdBlock(m["on"] != false)
                "desktop_site" -> VoiceCommands.Cmd.DesktopSite(m["on"] != false)
                "reply" -> (m["text"] as? String)?.takeIf { it.isNotBlank() }?.let { VoiceCommands.Cmd.Reply(it) }
                "mute_notifications" -> VoiceCommands.Cmd.MuteNotifs(m["on"] != false, (m["app"] as? String)?.ifBlank { null })
                "answer" -> (m["text"] as? String)?.let { VoiceCommands.Cmd.Answer(it) }
                else -> null
            }
            if (c != null) out.add(c)
        }
        return Reply(out, o["say"] as? String)
    }

    fun anthropicBody(model: String, system: String, user: String, maxTokens: Int = 600) =
        """{"model":${MiniJson.quote(model)},"max_tokens":$maxTokens,"system":${MiniJson.quote(system)},"messages":[{"role":"user","content":${MiniJson.quote(user)}}]}"""

    fun openAiBody(model: String, system: String, user: String, maxTokens: Int = 600) =
        """{"model":${MiniJson.quote(model)},"max_tokens":$maxTokens,"messages":[{"role":"system","content":${MiniJson.quote(system)}},{"role":"user","content":${MiniJson.quote(user)}}]}"""

    /** Assistant text from either API's response. */
    @Suppress("UNCHECKED_CAST")
    fun responseText(json: String): String? {
        val o = MiniJson.parse(json) as? Map<String, Any?> ?: return null
        (o["content"] as? List<Any?>)?.let { blocks ->
            return blocks.mapNotNull { (it as? Map<String, Any?>)?.takeIf { b -> b["type"] == "text" }?.get("text") as? String }.joinToString("")
        }
        (o["choices"] as? List<Any?>)?.firstOrNull()?.let { ch ->
            return ((ch as? Map<String, Any?>)?.get("message") as? Map<String, Any?>)?.get("content") as? String
        }
        return null
    }

    @Suppress("UNCHECKED_CAST")
    fun errorText(json: String): String? = try {
        val o = MiniJson.parse(json) as? Map<String, Any?>
        ((o?.get("error") as? Map<String, Any?>)?.get("message") as? String) ?: (o?.get("error") as? String)
    } catch (e: Exception) { null }
}

/**
 * Speech -> intent with TypeSafe **Jev** (a "System One" decision model: it does not generate text,
 * it answers typed questions — here `choice` — with probabilities). Free-text parameters (URL, search
 * query, workspace name) cannot come from a classifier, so they are taken from the transcript by rules.
 *
 * Endpoints (same body): OpenRouter `POST https://openrouter.ai/api/alpha/decisions` (model "typesafe/jev-1.13")
 * or TypeSafe `POST https://api.typesafe.ai/v1/systemone` (model "jev-latest").
 */
object JevProtocol {
    val INTENTS = linkedMapOf(
        "place" to "put a website or app into a numbered slot (ô 1-9) of the grid",
        "open" to "open a website or app on the screen the user is looking at",
        "search" to "search the web for something",
        "clear_slot" to "remove / close / clear a slot or screen",
        "set_workspace" to "switch to a saved or preset workspace / layout (làm việc, code, giải trí, họp…)",
        "save_workspace" to "save the current layout as a workspace with a name",
        "show_grid" to "show the slot numbers / the grid",
        "recenter" to "bring the screens back in front / recenter",
        "walk" to "walking mode on or off",
        "map" to "show, hide or toggle the navigation map",
        "farther" to "move the screens farther away",
        "closer" to "move the screens closer",
        "bigger" to "make the current screen bigger",
        "smaller" to "make the current screen smaller",
        "head_lock" to "lock screens to the head / unlock",
        "follow_mode" to "change how screens follow: fixed in the room, cinema drift, or travel / vehicle mode",
        "dark_web" to "dark mode / reduce glare on web pages, or turn it off",
        "summarize" to "summarize the page the user is looking at",
        "open_app" to "open an installed phone app such as Zalo, Grab, Netflix, Shopee",
        "mirror" to "show or stop showing the phone screen on the glasses",
        "captions" to "live captions, subtitles, or translate what people are saying",
        "prompter" to "teleprompter: show my script and scroll it as I read, or stop it",
        "back" to "go back / previous screen on the phone",
        "read_notifications" to "read my phone notifications / new messages",
        "reply" to "reply to the latest message with what I say",
        "mute_notifications" to "mute or unmute notifications, do not disturb",
        "question" to "a general question or chat for the assistant, not a command",
        "none" to "noise, unclear, or not addressed to the app",
    )

    val SITES = linkedMapOf(
        "youtube" to "https://m.youtube.com", "gmail" to "https://mail.google.com", "google_maps" to "https://www.google.com/maps",
        "facebook" to "https://m.facebook.com", "tiktok" to "https://www.tiktok.com",
        "github" to "https://github.com", "claude" to "https://claude.ai", "chatgpt" to "https://chatgpt.com",
        "calendar" to "https://calendar.google.com", "docs" to "https://docs.google.com", "news" to "https://vnexpress.net",
        "spotify" to "https://open.spotify.com", "google" to "https://www.google.com",
    )

    fun requestBody(model: String, transcript: String): String {
        fun choice(instr: String, opts: Map<String, String>) =
            """{"type":"choice","instructions":${MiniJson.quote(instr)},"criteria":{${opts.entries.joinToString(",") { "${MiniJson.quote(it.key)}:${MiniJson.quote(it.value)}" }}}}"""
        val sites = SITES.keys.associateWith { "the user means $it" } + ("other" to "another site, or no site mentioned")
        val modes = mapOf("world" to "fixed in the room / stay still", "cinema" to "cinema, gently follow the head", "travel" to "travel, bus, car, plane, walking")
        val onOff = mapOf("on" to "turn on / show / enable", "off" to "turn off / hide / disable", "toggle" to "not stated")
        return """{"model":${MiniJson.quote(model)},"state":{"utterance":${MiniJson.quote(transcript)},"language":"Vietnamese or English, from speech recognition, may contain typos"},"questions":{""" +
            "\"intent\":" + choice("Which command did the user give to the AR glasses app?", INTENTS) + "," +
            "\"site\":" + choice("Which website or app is mentioned?", sites) + "," +
            "\"switch\":" + choice("For on/off commands, which state is wanted?", onOff) + "," +
            "\"mode\":" + choice("For follow-mode commands, which mode?", modes) +
            "}}"
    }

    data class Decision(val intent: String, val confidence: Double, val site: String?, val switch: String?, val mode: String? = null)

    @Suppress("UNCHECKED_CAST")
    fun parseDecision(json: String): Decision? {
        val o = MiniJson.parse(json) as? Map<String, Any?> ?: return null
        val answers = (o["answers"] ?: (o["result"] as? Map<String, Any?>)?.get("answers")) as? Map<String, Any?> ?: return null
        fun ch(k: String) = answers[k] as? Map<String, Any?>
        val intent = ch("intent") ?: return null
        val name = intent["choice"] as? String ?: return null
        val conf = (intent["confidence"] as? Double) ?: ((intent["probabilities"] as? Map<String, Any?>)?.get(name) as? Double) ?: 0.0
        return Decision(name, conf, ch("site")?.get("choice") as? String, ch("switch")?.get("choice") as? String, ch("mode")?.get("choice") as? String)
    }

    /** Turn the classifier's decision + the transcript into an app command. */
    fun toCmd(d: Decision, transcript: String, minConfidence: Double = 0.45): VoiceCommands.Cmd {
        if (d.confidence < minConfidence || d.intent == "none") return VoiceCommands.Cmd.Unknown(transcript)
        val t = VoiceCommands.fold(transcript)
        val siteUrl = d.site?.let { SITES[it] } ?: VoiceCommands.siteFromText(t)
        val on = when (d.switch) { "on" -> true; "off" -> false; else -> null }
        val slot = SlotGrid.parseSlot(t)
        return when (d.intent) {
            "place" -> if (slot != null && siteUrl != null) VoiceCommands.Cmd.Place(slot, siteUrl, d.site)
                       else if (slot != null) VoiceCommands.Cmd.Place(slot, VoiceCommands.searchUrl(VoiceCommands.stripCommandWords(t)), null)
                       else VoiceCommands.parse(transcript)
            "open" -> siteUrl?.let { VoiceCommands.Cmd.Open(null, it) } ?: VoiceCommands.Cmd.Open(null, VoiceCommands.searchUrl(VoiceCommands.stripCommandWords(t)))
            "search" -> VoiceCommands.searchUrl(VoiceCommands.stripCommandWords(t)).let { u -> slot?.let { VoiceCommands.Cmd.Place(it, u, null) } ?: VoiceCommands.Cmd.Open(null, u) }
            "clear_slot" -> slot?.let { VoiceCommands.Cmd.ClearSlot(it) } ?: VoiceCommands.Cmd.ClearSlot(0)
            "set_workspace" -> VoiceCommands.Cmd.Workspace(VoiceCommands.workspaceName(t))
            "save_workspace" -> VoiceCommands.Cmd.SaveWorkspace(VoiceCommands.workspaceName(t))
            "show_grid" -> VoiceCommands.Cmd.ShowGrid
            "recenter" -> VoiceCommands.Cmd.Recenter
            "walk" -> VoiceCommands.Cmd.Walk(on)
            "map" -> VoiceCommands.Cmd.Map(on)
            "farther" -> VoiceCommands.Cmd.Farther
            "closer" -> VoiceCommands.Cmd.Closer
            "bigger" -> VoiceCommands.Cmd.Size(true)
            "smaller" -> VoiceCommands.Cmd.Size(false)
            "head_lock" -> VoiceCommands.Cmd.HeadLock(on)
            "follow_mode" -> VoiceCommands.Cmd.Follow(when (d.mode) { "cinema" -> FollowMode.CINEMA; "travel" -> FollowMode.TRAVEL; else -> FollowMode.WORLD })
            "dark_web" -> VoiceCommands.Cmd.DarkWeb(on)
            "summarize" -> VoiceCommands.Cmd.Summarize
            "open_app" -> VoiceCommands.Cmd.LaunchApp(VoiceCommands.appName(t))
            "mirror" -> if (on == false) VoiceCommands.Cmd.MirrorOff else VoiceCommands.Cmd.Mirror(slot)
            "captions" -> (VoiceCommands.parse(transcript) as? VoiceCommands.Cmd.Captions) ?: VoiceCommands.Cmd.Captions(if (on == false) CaptionMode.OFF else CaptionMode.VI)
            "prompter" -> VoiceCommands.Cmd.Prompter(on)
            "back" -> VoiceCommands.Cmd.Back
            "read_notifications" -> VoiceCommands.Cmd.ReadNotifs
            "reply" -> (VoiceCommands.parse(transcript) as? VoiceCommands.Cmd.Reply) ?: VoiceCommands.Cmd.Reply(transcript)
            "mute_notifications" -> (VoiceCommands.parse(transcript) as? VoiceCommands.Cmd.MuteNotifs) ?: VoiceCommands.Cmd.MuteNotifs(on != false, null)
            "question" -> VoiceCommands.Cmd.Ask(transcript)
            else -> VoiceCommands.Cmd.Unknown(transcript)
        }
    }
}

/** Wake phrase matching on folded text (for the Vosk grammar recognizer and for typed tests). */
object WakeWord {
    val DEFAULT_PHRASES = listOf("kính ơi", "này kính", "ê kính")

    /** Vosk grammar JSON: only these phrases + [unk], so random speech maps to [unk]. */
    fun voskGrammar(phrases: List<String>): String =
        (phrases.map { MiniJson.quote(it.lowercase()) } + "\"[unk]\"").joinToString(",", "[", "]")

    fun matches(recognized: String, phrases: List<String>): Boolean {
        val r = VoiceCommands.fold(recognized)
        return phrases.any { p -> val f = VoiceCommands.fold(p); f.isNotBlank() && r.contains(f) }
    }

    /** Text said after the wake phrase in the same breath ("kính ơi mở youtube ô 3" -> "mở youtube ô 3"). */
    fun remainder(recognized: String, phrases: List<String>): String {
        val r = recognized.trim()
        val folded = VoiceCommands.fold(r)
        for (p in phrases) {
            val f = VoiceCommands.fold(p)
            val idx = folded.indexOf(f)
            if (idx >= 0) {
                // folding keeps character count for Vietnamese (NFD marks removed, đ->d), so map back by word count
                val wordsBefore = folded.substring(0, idx + f.length).split(' ').filter { it.isNotEmpty() }.size
                return r.split(Regex("\\s+")).drop(wordsBefore).joinToString(" ")
            }
        }
        return r
    }
}

fun headSpeedDps(wRad: V3): Double = Math.toDegrees(Mat3.norm(wRad))

/**
 * How the whole space reacts to head/body turns (3DoF has no position, so this is what makes it usable on the move):
 *  - WORLD:  screens stay fixed in the room (desk, sofa).
 *  - CINEMA: the big screen drifts gently back in front when you shift or lie down (small dead zone, slow).
 *  - TRAVEL: everything trails your heading (bus, plane, walking): large dead zone, quicker catch-up,
 *            so a vehicle turning does not swing the screens out of view.
 */
enum class FollowMode(val title: String, val deadzoneDeg: Double, val speed: Double, val pitchToo: Boolean) {
    WORLD("Cố định", 0.0, 0.0, false),
    CINEMA("Rạp phim", 10.0, 0.6, true),
    TRAVEL("Di chuyển", 22.0, 1.8, true);

    fun next() = entries[(ordinal + 1) % entries.size]
}

/** Anchor rotation F for the whole space: panels are drawn at F * P instead of P. */
class BodyFollow {
    var yaw = 0.0; private set
    var pitch = 0.0; private set
    private var init = false

    fun update(C: M3, mode: FollowMode, dt: Double): M3 {
        if (mode == FollowMode.WORLD) { init = false; yaw = 0.0; pitch = 0.0; return Mat3.eye() }
        val (y, p) = Projection.yawPitch(C)
        if (!init) { yaw = y; pitch = if (mode.pitchToo) p else 0.0; init = true }
        val k = kotlin.math.min(1.0, mode.speed * dt)
        val dy = wrap180(y - yaw)
        if (kotlin.math.abs(dy) > mode.deadzoneDeg) yaw += (dy - mode.deadzoneDeg * kotlin.math.sign(dy)) * k
        if (mode.pitchToo) {
            val dp = p - pitch; val dz = mode.deadzoneDeg * 0.7
            if (kotlin.math.abs(dp) > dz) pitch += (dp - dz * kotlin.math.sign(dp)) * k
        }
        return Mat3.mul(Mat3.rotY(yaw), Mat3.rotX(pitch))
    }

    /** Recenter: snap the anchor to where you look now. */
    fun reset() { init = false }
}

/** Rules first: a simple, confidently parsed command runs instantly; only the rest goes to Jev / the LLM. */
object FastPath {
    private val MULTI = listOf(" va ", " roi ", " sau do ", " xong ", " then ", " and ", " dong thoi ", " ca ")

    fun accept(text: String, cmd: VoiceCommands.Cmd): Boolean {
        if (cmd is VoiceCommands.Cmd.Unknown || cmd is VoiceCommands.Cmd.Ask) return false
        val t = " " + VoiceCommands.fold(text) + " "
        if (MULTI.any { t.contains(it) }) return false        // multi-step: let the LLM plan it
        // a search query is free text: only trust rules when the site is explicit
        if (cmd is VoiceCommands.Cmd.Open && cmd.url.contains("google.com/search")) return false
        return true
    }
}

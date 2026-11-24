package vn.jiner.xrealspace.core

import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Wire protocol between a phone ("remote") and a device that has no touchscreen and drives the glasses
 * ("host", e.g. the original XREAL Beam). One message per line: TYPE|field|field…, fields URL-encoded.
 * Phone -> host: touchpad, gestures, recognized speech, keyboard text. Host -> phone: a page text field got
 * focus (open the phone keyboard), status line.
 */
object RemoteProtocol {
    const val PORT = 47800
    const val SERVICE_TYPE = "_xspace._tcp."
    const val VERSION = 1

    sealed class Msg {
        /** phone -> box, first message: who I am + the pairing PIN shown on the glasses */
        data class Hello(val name: String, val version: Int = VERSION, val pin: String = "") : Msg()
        /** box -> phone: PIN accepted or not (reason shown to the user) */
        data class Auth(val ok: Boolean, val reason: String = "") : Msg()
        /** touchpad finger: action = MotionEvent action (0 down, 1 up, 2 move, 3 cancel), total drag since down */
        data class Pad(val action: Int, val dx: Float, val dy: Float) : Msg()
        object TwoTap : Msg()
        object ThreeTap : Msg()
        data class Swipe2(val dx: Float, val dy: Float) : Msg()
        /** a recognized utterance (speech-to-text runs on the phone, intent on the host) */
        data class Say(val text: String) : Msg()
        /** full value of the page text field being typed into */
        data class Text(val value: String) : Msg()
        object Enter : Msg()
        /** hardware keys forwarded: "click" (volume +), "recenter" (volume −) */
        data class Key(val name: String) : Msg()
        /** host -> phone: a page text field got focus (value = its current text) / lost it */
        data class Focus(val value: String) : Msg()
        object Blur : Msg()
        data class Status(val text: String) : Msg()
        object Ping : Msg()
    }

    private fun e(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun d(s: String) = URLDecoder.decode(s, "UTF-8")

    fun encode(m: Msg): String = when (m) {
        is Msg.Hello -> "HELLO|${e(m.name)}|${m.version}|${e(m.pin)}"
        is Msg.Auth -> "AUTH|${if (m.ok) 1 else 0}|${e(m.reason)}"
        is Msg.Pad -> "PAD|${m.action}|${m.dx}|${m.dy}"
        Msg.TwoTap -> "TAP2"
        Msg.ThreeTap -> "TAP3"
        is Msg.Swipe2 -> "SWIPE2|${m.dx}|${m.dy}"
        is Msg.Say -> "SAY|${e(m.text)}"
        is Msg.Text -> "TEXT|${e(m.value)}"
        Msg.Enter -> "ENTER"
        is Msg.Key -> "KEY|${e(m.name)}"
        is Msg.Focus -> "FOCUS|${e(m.value)}"
        Msg.Blur -> "BLUR"
        is Msg.Status -> "STATUS|${e(m.text)}"
        Msg.Ping -> "PING"
    }

    /** null for malformed / unknown lines (ignored, never crash the link) */
    fun decode(line: String): Msg? = try {
        val p = line.trim().split('|')
        when (p[0]) {
            "HELLO" -> Msg.Hello(d(p[1]), p.getOrNull(2)?.toIntOrNull() ?: 0, p.getOrNull(3)?.let { d(it) } ?: "")
            "AUTH" -> Msg.Auth(p[1] == "1", p.getOrNull(2)?.let { d(it) } ?: "")
            "PAD" -> Msg.Pad(p[1].toInt(), p[2].toFloat(), p[3].toFloat())
            "TAP2" -> Msg.TwoTap
            "TAP3" -> Msg.ThreeTap
            "SWIPE2" -> Msg.Swipe2(p[1].toFloat(), p[2].toFloat())
            "SAY" -> Msg.Say(d(p[1]))
            "TEXT" -> Msg.Text(if (p.size > 1) d(p[1]) else "")
            "ENTER" -> Msg.Enter
            "KEY" -> Msg.Key(d(p[1]))
            "FOCUS" -> Msg.Focus(if (p.size > 1) d(p[1]) else "")
            "BLUR" -> Msg.Blur
            "STATUS" -> Msg.Status(d(p[1]))
            "PING" -> Msg.Ping
            else -> null
        }
    } catch (e: Exception) { null }

    /** Only connect to private-network addresses typed by the user (no internet hosts). */
    fun isLanAddress(host: String): Boolean {
        val h = host.trim()
        if (h.endsWith(".local")) return true
        val parts = h.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 10 || (parts[0] == 192 && parts[1] == 168) || (parts[0] == 172 && parts[1] in 16..31) ||
            (parts[0] == 100 && parts[1] in 64..127) // Tailscale / CGNAT
    }

    /** 4-digit pairing code */
    fun newPin(rnd: java.util.Random = java.security.SecureRandom()): String = "%04d".format(rnd.nextInt(10000))
}

/**
 * Checks the PIN a phone sends. After [maxFails] wrong tries in a row it refuses everything for [lockMs]
 * (10 000 codes / 5 tries per minute = days to guess). Comparison does not stop at the first wrong digit.
 */
class PairingGuard(var pin: String, private val maxFails: Int = 5, private val lockMs: Long = 60_000) {
    enum class Result { OK, WRONG, LOCKED }
    private var fails = 0
    private var lockedUntil = 0L

    fun check(given: String, nowMs: Long): Result {
        if (nowMs < lockedUntil) return Result.LOCKED
        if (pin.isNotEmpty() && sameText(given.trim(), pin)) { fails = 0; return Result.OK }
        fails++
        if (fails >= maxFails) { fails = 0; lockedUntil = nowMs + lockMs; return Result.LOCKED }
        return Result.WRONG
    }

    fun secondsLocked(nowMs: Long) = ((lockedUntil - nowMs).coerceAtLeast(0) + 999) / 1000

    private fun sameText(a: String, b: String): Boolean {
        var diff = a.length xor b.length
        for (i in 0 until maxOf(a.length, b.length)) diff = diff or ((a.getOrNull(i)?.code ?: 0) xor (b.getOrNull(i)?.code ?: 0))
        return diff == 0
    }
}

package vn.jiner.xrealspace.core

/** One phone notification as the glasses show it (no Android types here, so it is unit-testable). */
data class PhoneNotif(
    val key: String,          // StatusBarNotification.key
    val pkg: String,
    val app: String,          // "Zalo", "Messenger"…
    val title: String,        // sender / conversation
    val text: String,
    val canReply: Boolean,    // has a RemoteInput (reply box) action
    val timeMs: Long,
)

/**
 * The glasses' notification inbox: filters noise, drops duplicates (apps re-post the same message when a
 * chat updates), keeps the last [max], remembers muted apps and do-not-disturb.
 */
class NotifFeed(private val ownPkg: String, private val max: Int = 20) {
    private val items = ArrayDeque<PhoneNotif>()
    private val seen = HashMap<String, String>()          // key -> last text shown, for de-duplication
    val muted = linkedSetOf<String>()                     // package names
    var doNotDisturb = false
    var unread = 0; private set

    /** Packages that post status noise, never worth a glance on the glasses. */
    private val noisy = setOf("android", "com.android.systemui", "com.android.providers.downloads", "com.google.android.gms")

    /**
     * Returns true when the glasses should pop this notification up now.
     * It is still stored (readable with "đọc thông báo") when muted or in do-not-disturb.
     */
    fun add(n: PhoneNotif, ongoing: Boolean = false): Boolean {
        if (n.pkg == ownPkg || n.pkg in noisy || ongoing) return false
        if (n.title.isBlank() && n.text.isBlank()) return false
        if (seen[n.key] == n.text) return false               // same message re-posted
        seen[n.key] = n.text
        if (seen.size > 200) seen.keys.take(100).forEach { seen.remove(it) }
        items.removeAll { it.key == n.key }
        items.addFirst(n)
        while (items.size > max) items.removeLast()
        unread++
        return !doNotDisturb && n.pkg !in muted
    }

    fun remove(key: String) { items.removeAll { it.key == key } }
    fun latest(): PhoneNotif? = items.firstOrNull()
    fun latestRepliable(): PhoneNotif? = items.firstOrNull { it.canReply }
    fun recent(n: Int = 5): List<PhoneNotif> { unread = 0; return items.take(n) }
    fun size() = items.size

    /** "tắt thông báo zalo" -> mute that app (matched on the visible app name). Returns the package muted. */
    fun muteByName(app: String, on: Boolean): String? {
        val f = VoiceCommands.fold(app)
        val pkg = items.firstOrNull { VoiceCommands.fold(it.app).contains(f) || f.contains(VoiceCommands.fold(it.app)) }?.pkg ?: return null
        if (on) muted.add(pkg) else muted.remove(pkg)
        return pkg
    }

    companion object {
        /** Short text to show on the glasses; while moving fast only the app name (no reading while riding). */
        fun preview(n: PhoneNotif, moving: Boolean): Pair<String, String> =
            if (moving) n.app to "Tin mới — dừng lại để đọc"
            else "${n.app} · ${n.title}".trimEnd(' ', '·') to n.text.take(140)

        /** "đọc thông báo": one line per notification, newest first. */
        fun readout(list: List<PhoneNotif>): String =
            if (list.isEmpty()) "Không có thông báo"
            else list.joinToString("\n") { "• ${it.app}: ${it.title}${if (it.text.isNotBlank()) " — " + it.text.take(80) else ""}" }
    }
}

/**
 * Decides "moving fast" from GPS speed with hysteresis, so a single noisy fix does not flip the screens.
 * Fast = above [fastMps] for [enterMs]; back to normal = below [slowMps] for [exitMs].
 * Default 15 km/h ≈ bicycle / motorbike, walking (≈5 km/h) never locks.
 */
class SpeedGuard(
    private val fastMps: Double = 15 / 3.6,
    private val slowMps: Double = 8 / 3.6,
    private val enterMs: Long = 3000,
    private val exitMs: Long = 6000,
) {
    var fast = false; private set
    var lastSpeedMps = 0.0; private set
    private var since = -1L

    /** Feed one GPS speed sample; returns true if [fast] changed. */
    fun update(speedMps: Double, nowMs: Long): Boolean {
        lastSpeedMps = speedMps
        val wantFlip = if (fast) speedMps < slowMps else speedMps > fastMps
        if (!wantFlip) { since = -1; return false }
        if (since < 0) since = nowMs
        if (nowMs - since >= (if (fast) exitMs else enterMs)) { fast = !fast; since = -1; return true }
        return false
    }

    /** No GPS fix for a while (tunnel, indoors): treat as stopped so the screens come back. */
    fun lost(): Boolean { since = -1; if (!fast) return false; fast = false; return true }

    val kmh get() = lastSpeedMps * 3.6
}

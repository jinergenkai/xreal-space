package vn.jiner.xrealspace

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crash + start-up safety net.
 *  - Any crash is written to filesDir/crash.txt AND Download/xreal-space-crash.txt (readable from a PC over USB).
 *  - A "starting" marker is set at launch and cleared once the app has run a few seconds: if it is still there at
 *    the next launch, the last start crashed -> MainActivity shows the error and offers SAFE MODE.
 *  - Optional parts are started through [guard]: one broken part is switched off and reported, the app keeps going.
 */
object CrashLog {
    private var file: File? = null
    private var app: Context? = null
    private const val PREFS = "crashlog"

    /** parts that failed to start this time: "Giọng nói" -> error */
    val startupProblems = LinkedHashMap<String, String>()

    fun install(ctx: Context) {
        if (file != null) return
        app = ctx.applicationContext
        file = File(ctx.applicationContext.filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
                val text = "Crash $ts on thread ${t.name}\nApp ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n\n$sw\n-- log --\n${SpaceState.recentLog(60)}"
                file?.writeText(text)
                exportToDownloads(text)
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    /** copy to the public Download folder (Android 10+: no permission needed for our own file) */
    private fun exportToDownloads(text: String) {
        val c = app ?: return
        if (Build.VERSION.SDK_INT < 29) return
        try {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "xreal-space-crash.txt")
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            }
            val uri = c.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
            c.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        } catch (_: Throwable) {}
    }

    fun read(): String = try { file?.takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
    fun clear() { try { file?.delete() } catch (_: Exception) {} }

    // ------------------------------------------------------------------ start-up marker / safe mode
    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** true if the previous launch never got to [markStarted] (it crashed while starting) */
    fun lastStartCrashed(c: Context) = prefs(c).getBoolean("starting", false)
    fun markStarting(c: Context) = prefs(c).edit().putBoolean("starting", true).commit()
    fun markStarted(c: Context) = prefs(c).edit().putBoolean("starting", false).apply()

    var safeMode: Boolean
        get() = app?.let { prefs(it).getBoolean("safe", false) } ?: false
        set(v) { app?.let { prefs(it).edit().putBoolean("safe", v).commit() } }

    /** start an optional part; if it throws, keep going and remember why */
    inline fun guard(name: String, block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            startupProblems[name] = e.toString()
            SpaceState.log("startup: $name failed: $e")
        }
    }
}

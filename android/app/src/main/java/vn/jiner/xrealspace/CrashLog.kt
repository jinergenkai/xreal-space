package vn.jiner.xrealspace

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Saves the last crash (stack trace + recent log) to filesDir/crash.txt; shown in Chẩn đoán on the next start. */
object CrashLog {
    private var file: File? = null

    fun install(ctx: Context) {
        if (file != null) return
        file = File(ctx.applicationContext.filesDir, "crash.txt")
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                val ts = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
                file?.writeText("Crash $ts on thread ${t.name}\n$sw\n-- log --\n${SpaceState.recentLog(60)}")
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    fun read(): String = try { file?.takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
    fun clear() { try { file?.delete() } catch (_: Exception) {} }
}

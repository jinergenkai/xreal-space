package vn.jiner.xrealspace

import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Shizuku user service: lives in its own process with the SHELL identity (like `adb shell`), which is allowed to
 * create trusted virtual displays and launch / control other apps on them. Same approach as scrcpy --new-display.
 * The app talks to it through IAppDisplay (Binder). Nothing here runs unless the user installed and allowed Shizuku.
 */
@SuppressLint("WrongConstant", "PrivateApi")
class AppDisplayService() : IAppDisplay.Stub() {
    private var ctx: Context? = null
    private val displays = HashMap<Int, VirtualDisplay>()

    /** Shizuku 13+ passes a Context when this constructor exists */
    constructor(context: Context) : this() { ctx = context }

    /** DisplayManager checks that the calling package belongs to the calling uid (shell): pretend to be the shell */
    private fun shellContext(): Context {
        val base = ctx ?: throw IllegalStateException("no context")
        return object : ContextWrapper(base) {
            override fun getPackageName() = "com.android.shell"
            override fun getOpPackageName() = "com.android.shell"
        }
    }

    private fun displayManager(): DisplayManager {
        val c = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        c.isAccessible = true
        return c.newInstance(shellContext())
    }

    override fun destroy() { displays.values.forEach { it.release() }; displays.clear(); exitProcess(0) }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int): Int = try {
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            (1 shl 6) /* SUPPORTS_TOUCH */ or (1 shl 7) /* ROTATES_WITH_CONTENT */ or (1 shl 8) /* DESTROY_CONTENT_ON_REMOVAL */
        if (Build.VERSION.SDK_INT >= 33) flags = flags or (1 shl 10) /* TRUSTED */ or (1 shl 11) /* OWN_DISPLAY_GROUP */ or
            (1 shl 12) /* ALWAYS_UNLOCKED */ or (1 shl 13) /* TOUCH_FEEDBACK_DISABLED */
        val vd = displayManager().createVirtualDisplay("xreal-space-app", width, height, dpi, surface, flags)
        val id = vd.display.displayId
        displays[id] = vd
        id
    } catch (e: Throwable) { lastError = e.toString(); -1 }

    override fun resizeDisplay(displayId: Int, width: Int, height: Int, dpi: Int) { displays[displayId]?.resize(width, height, dpi) }

    override fun releaseDisplay(displayId: Int) { displays.remove(displayId)?.release() }

    override fun launch(component: String, displayId: Int): String =
        sh("am", "start", "--display", displayId.toString(), "-f", "0x18000000", "-n", component)   // NEW_TASK | MULTIPLE_TASK

    override fun tap(displayId: Int, x: Float, y: Float) { sh("input", "-d", "$displayId", "tap", "$x", "$y") }

    override fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Int) {
        sh("input", "-d", "$displayId", "swipe", "$x1", "$y1", "$x2", "$y2", "$durationMs")
    }

    override fun key(displayId: Int, keyCode: Int) { sh("input", "-d", "$displayId", "keyevent", "$keyCode") }

    override fun text(displayId: Int, value: String) {
        // `input text` needs spaces as %s and no shell metacharacters (args are passed without a shell anyway)
        sh("input", "-d", "$displayId", "text", value.replace(" ", "%s"))
    }

    private var lastError = ""

    private fun sh(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(); out.trim()
    } catch (e: Exception) { "error: $e" }
}

package vn.jiner.xrealspace

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Surface
import rikka.shizuku.Shizuku
import java.util.concurrent.Executors

/**
 * Real Android apps inside the glasses' slots ("app:<package>" panels): one virtual display per app, created by
 * AppDisplayService through Shizuku. Without Shizuku the app still works; app panels just ask for it.
 */
object AppDisplays {
    const val PREFIX = "app:"
    const val SHIZUKU_PKG = "moe.shizuku.privileged.api"
    const val W = 1280
    const val H = 720
    const val DPI = 200
    private const val REQ = 4711

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()          // binder calls (shell commands) off the UI thread
    @Volatile private var service: IAppDisplay? = null
    private val waiting = ArrayList<() -> Unit>()
    var onChange: (() -> Unit)? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        try {
            Shizuku.addBinderReceivedListenerSticky { main.post { onChange?.invoke(); if (allowed) bind() } }
            Shizuku.addBinderDeadListener { service = null; main.post { onChange?.invoke() } }
            Shizuku.addRequestPermissionResultListener { code, result ->
                if (code == REQ && result == PackageManager.PERMISSION_GRANTED) bind()
                main.post { onChange?.invoke() }
            }
        } catch (e: Throwable) { SpaceState.log("shizuku init: $e") }
    }

    // ------------------------------------------------------------------ state for the health check
    val supported get() = Build.VERSION.SDK_INT >= 33
    val installed get() = try { app.packageManager.getPackageInfo(SHIZUKU_PKG, 0); true } catch (_: Exception) { false }
    val running get() = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
    val allowed get() = running && try { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED } catch (_: Throwable) { false }
    val ready get() = supported && allowed && service != null

    /** what the user should do next, or null when everything is in place */
    fun nextStep(): String? = when {
        !supported -> "Cần Android 13 trở lên"
        !installed -> "Cài app Shizuku (Play Store)"
        !running -> "Mở Shizuku → Khởi động qua Gỡ lỗi không dây (làm lại sau mỗi lần khởi động máy)"
        !allowed -> "Cho phép XREAL Space dùng Shizuku"
        service == null -> "Đang kết nối Shizuku…"
        else -> null
    }

    /** one tap from the health screen: install / open Shizuku / ask permission */
    fun fix(ctx: Context) {
        when {
            !installed -> ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$SHIZUKU_PKG")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            !running -> ctx.packageManager.getLaunchIntentForPackage(SHIZUKU_PKG)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            !allowed -> try { Shizuku.requestPermission(REQ) } catch (e: Throwable) { SpaceState.log("shizuku perm: $e") }
            else -> bind()
        }
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, b: IBinder?) {
            service = if (b != null && b.pingBinder()) IAppDisplay.Stub.asInterface(b) else null
            SpaceState.log("app display service connected=${service != null}")
            val w = ArrayList(waiting); waiting.clear()
            main.post { w.forEach { it() }; onChange?.invoke() }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null; main.post { onChange?.invoke() } }
    }

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(app.packageName, AppDisplayService::class.java.name))
            .daemon(false).processNameSuffix("appdisplay").debuggable(false).version(1)
    }

    fun bind() {
        if (service != null || !allowed) return
        try { Shizuku.bindUserService(args, conn) } catch (e: Throwable) { SpaceState.log("bind user service: $e") }
    }

    private fun whenReady(f: () -> Unit) { if (service != null) f() else { waiting.add(f); bind() } }

    // ------------------------------------------------------------------ panels
    /** launcher component of a package ("pkg/.Main") */
    fun component(pkg: String): String? =
        app.packageManager.getLaunchIntentForPackage(pkg)?.component?.flattenToShortString()

    fun label(pkg: String): String = try {
        app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /** a slot's surface is ready: make a display that draws into it and start the app there. cb gets the display id */
    fun attach(surface: Surface, pkg: String, cb: (Int) -> Unit) {
        if (!supported || !allowed) { main.post { cb(-1) }; return }
        whenReady {
            io.execute {
                val s = service
                if (s == null) { main.post { cb(-1) }; return@execute }
                val id = try { s.createDisplay(surface, W, H, DPI) } catch (e: Exception) { -1 }
                if (id >= 0) {
                    val comp = component(pkg)
                    val out = if (comp != null) try { s.launch(comp, id) } catch (e: Exception) { "error: $e" } else "no launcher"
                    SpaceState.log("app $pkg on display $id: $out")
                }
                main.post { cb(id) }
            }
        }
    }

    fun release(displayId: Int) { if (displayId >= 0) io.execute { try { service?.releaseDisplay(displayId) } catch (_: Exception) {} } }

    fun tap(displayId: Int, x: Float, y: Float) = io.execute { try { service?.tap(displayId, x, y) } catch (_: Exception) {} }
    fun swipe(displayId: Int, x1: Float, y1: Float, x2: Float, y2: Float, ms: Int = 250) =
        io.execute { try { service?.swipe(displayId, x1, y1, x2, y2, ms) } catch (_: Exception) {} }
    fun key(displayId: Int, code: Int) = io.execute { try { service?.key(displayId, code) } catch (_: Exception) {} }
    fun text(displayId: Int, value: String) = io.execute { try { service?.text(displayId, value) } catch (_: Exception) {} }
}

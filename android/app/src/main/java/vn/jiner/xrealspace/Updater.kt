package vn.jiner.xrealspace

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import org.json.JSONObject
import vn.jiner.xrealspace.core.Versions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * In-app updates from the PUBLIC releases repo (the code repo stays private). CI publishes each signed release APK
 * there; the app compares versions, downloads, and hands the APK to Android's installer (the user confirms).
 * Updates only install over a build signed with the same release key (see wiki "Phát hành").
 */
object Updater {
    const val REPO = "jinergenkai/xreal-space-releases"
    private const val API = "https://api.github.com/repos/$REPO/releases/latest"
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    data class Release(val tag: String, val apkUrl: String, val notes: String)

    @Volatile var latest: Release? = null; private set
    @Volatile var checked = false; private set
    @Volatile var status = ""; private set
    val updateAvailable get() = latest?.let { Versions.newer(it.tag, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) } == true

    fun check(ctx: Context, quiet: Boolean = true, then: (() -> Unit)? = null) = io.execute {
        try {
            val c = URL(API).openConnection() as HttpURLConnection
            c.setRequestProperty("Accept", "application/vnd.github+json"); c.connectTimeout = 8000; c.readTimeout = 8000
            if (c.responseCode == 404) { status = "Chưa có bản phát hành nào"; checked = true; main.post { then?.invoke() }; return@execute }
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = o.getJSONArray("assets")
            var url = ""
            for (i in 0 until assets.length()) assets.getJSONObject(i).let { if (it.getString("name").endsWith(".apk")) url = it.getString("browser_download_url") }
            latest = Release(o.getString("tag_name"), url, o.optString("body", ""))
            checked = true
            status = if (updateAvailable) "Có bản mới ${latest!!.tag} (đang dùng ${BuildConfig.VERSION_NAME})" else "Đang dùng bản mới nhất (${BuildConfig.VERSION_NAME})"
            main.post {
                if (updateAvailable && !quiet) SpaceState.notify("Cập nhật", status, Colors.GREEN, 5.0)
                else if (updateAvailable) SpaceState.notify("Có bản mới", "${latest!!.tag} — mở app trên điện thoại để cập nhật", Colors.GREEN, 6.0)
                then?.invoke()
            }
        } catch (e: Exception) {
            status = "Không kiểm tra được: ${e.message}"; checked = true
            main.post { if (!quiet) SpaceState.notify("Cập nhật", status, Colors.AMBER, 3.0); then?.invoke() }
        }
    }

    /** Android 8+: the user must allow this app to install apps once */
    fun canInstall(ctx: Context) = Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    fun allowInstalls(ctx: Context) = ctx.startActivity(
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    fun downloadAndInstall(ctx: Context) {
        val r = latest ?: return
        if (r.apkUrl.isEmpty()) { SpaceState.notify("Cập nhật", "Bản phát hành không có file APK", Colors.RED, 3.0); return }
        if (!canInstall(ctx)) { SpaceState.notify("Cập nhật", "Cho phép XREAL Space cài ứng dụng, rồi bấm Cập nhật lại", Colors.AMBER, 4.0); allowInstalls(ctx); return }
        status = "Đang tải ${r.tag}…"
        io.execute {
            try {
                val f = File(ctx.cacheDir, "update.apk")
                (URL(r.apkUrl).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true; connectTimeout = 15000; readTimeout = 30000 }
                    .inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
                status = "Đang cài…"
                install(ctx, f)
            } catch (e: Exception) {
                status = "Tải lỗi: ${e.message}"
                main.post { SpaceState.notify("Cập nhật", status, Colors.RED, 4.0) }
            }
        }
    }

    private fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("app.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val cb = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, UpdateReceiver::class.java), flags)
            s.commit(cb.intentSender)
        }
    }
}

/** Installer callbacks: show the system "install?" confirmation, report errors (e.g. different signing key). */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        when (i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION") val confirm = i.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { ctx.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> SpaceState.log("update installed")
            else -> {
                val msg = i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "lỗi"
                val hint = if (msg.contains("signature", true) || msg.contains("conflict", true)) " — bản này ký khác khoá: gỡ app rồi cài lại 1 lần" else ""
                SpaceState.notify("Cập nhật thất bại", msg + hint, Colors.RED, 6.0)
            }
        }
    }
}

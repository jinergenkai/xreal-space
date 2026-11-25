package vn.jiner.xrealspace

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import vn.jiner.xrealspace.core.Health
import vn.jiner.xrealspace.core.Health.Req
import vn.jiner.xrealspace.core.Health.Status

/** Fills Health.Req statuses from the real device, and knows how to fix each one. */
object HealthCheck {
    fun collect(ctx: Context): List<Health.Item> {
        fun perm(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        fun ok(b: Boolean, detail: String = "") = if (b) Status.OK to detail else Status.MISSING to detail
        val usb = Sensors.usb
        val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val external = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION).firstOrNull()
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val net = try {
            (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).let { cm ->
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            }
        } catch (e: Throwable) { SpaceState.log("net check: $e"); false }
        val tts = Speaker.vietnameseVoice

        val list = ArrayList<Health.Item>()
        fun add(r: Req, s: Pair<Status, String>) = list.add(Health.Item(r, s.first, s.second))

        add(Req.GLASSES_USB, ok(usb.connected, usb.status))
        add(Req.GLASSES_DISPLAY, when {
            external != null -> Status.OK to "${external.name} ${external.mode.physicalWidth}×${external.mode.physicalHeight}"
            usb.connected -> Status.MISSING to "Kính đã cắm nhưng Android không thấy màn hình ngoài → điện thoại này có thể không xuất hình qua USB-C"
            else -> Status.MISSING to "Chưa thấy màn hình kính"
        })
        add(Req.IMU_DATA, when {
            !usb.connected -> Status.NA to "cần cắm kính trước"
            usb.parsed > 0 -> Status.OK to "${usb.parsed} mẫu"
            else -> Status.MISSING to "Đã nối USB nhưng chưa nhận được dữ liệu cảm biến"
        })
        add(Req.OVERLAY, ok(GlassesService.canHost(ctx)))
        add(Req.MIC, ok(perm(Manifest.permission.RECORD_AUDIO)))
        add(Req.VOICE_MODEL, if (Assistant.voice.hasModel("vi")) Status.OK to Assistant.voice.modelStatus else Status.MISSING to "chưa tải (có thể dùng Google STT nếu máy có)")
        add(Req.TTS_VI, when (tts) { true -> Status.OK to ""; false -> Status.WARN to "đang dùng giọng tiếng Anh"; null -> Status.WARN to "chưa khởi động" })
        add(Req.ACCESSIBILITY, ok(ControlService.instance != null))
        add(Req.NOTIF_ACCESS, ok(NotifListener.isEnabled(ctx)))
        add(Req.POST_NOTIF, if (Build.VERSION.SDK_INT < 33) Status.NA to "" else ok(perm("android.permission.POST_NOTIFICATIONS")))
        add(Req.LOCATION, ok(perm(Manifest.permission.ACCESS_FINE_LOCATION)))
        add(Req.CALENDAR, ok(perm(Manifest.permission.READ_CALENDAR)))
        add(Req.BATTERY, if (pm.isIgnoringBatteryOptimizations(ctx.packageName)) Status.OK to "" else Status.WARN to "Android có thể tắt app khi chạy nền lâu")
        add(Req.INTERNET, ok(net))
        add(Req.LLM, if (SpaceState.llmReady) Status.OK to SpaceState.llmModel else Status.WARN to "tuỳ chọn — lệnh cơ bản vẫn chạy offline")
        add(Req.ANDROID_10, ok(Build.VERSION.SDK_INT >= 29, "Android ${Build.VERSION.RELEASE}"))
        add(Req.ANDROID_13, if (Build.VERSION.SDK_INT >= 33) Status.OK to "" else Status.WARN to "Android ${Build.VERSION.RELEASE}: không mở được app trong ô")
        add(Req.SHIZUKU, when {
            !AppDisplays.supported -> Status.NA to "cần Android 13+"
            AppDisplays.ready -> Status.OK to "sẵn sàng"
            else -> Status.WARN to (AppDisplays.nextStep() ?: "") + " (tuỳ chọn)"
        })
        add(Req.UPDATE, when {
            !Updater.checked -> Status.NA to "chưa kiểm tra"
            Updater.updateAvailable -> Status.WARN to Updater.status
            else -> Status.OK to Updater.status
        })
        if (SpaceState.onBeam) {
            // the Beam owns the glasses (USB, IMU, display, 3DoF) and mirrored-app control is not needed there
            val beam = setOf(Req.GLASSES_USB, Req.GLASSES_DISPLAY, Req.IMU_DATA, Req.OVERLAY, Req.ACCESSIBILITY)
            list.replaceAll { if (it.req in beam) Health.Item(it.req, Status.NA, "Trên Beam: Beam tự lo phần này") else it }
        }
        return list
    }

    /** one tap that gets the user as close as possible to fixing [r] */
    fun fix(a: Activity, r: Req) {
        fun settings(action: String) = try { a.startActivity(Intent(action)) } catch (_: Exception) { a.startActivity(Intent(Settings.ACTION_SETTINGS)) }
        when (r) {
            Req.GLASSES_USB -> Sensors.connectGlasses()
            Req.IMU_DATA -> { Sensors.usb.close(); Sensors.connectGlasses() }
            Req.OVERLAY -> a.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${a.packageName}")))
            Req.MIC -> a.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            Req.VOICE_MODEL -> Assistant.voice.ensureModel { }
            Req.TTS_VI -> settings("com.android.settings.TTS_SETTINGS")
            Req.ACCESSIBILITY -> settings(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            Req.NOTIF_ACCESS -> NotifListener.openSettings(a)
            Req.POST_NOTIF -> if (Build.VERSION.SDK_INT >= 33) a.requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 2)
            Req.LOCATION -> a.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 3)
            Req.CALENDAR -> a.requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 4)
            Req.BATTERY -> settings(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            Req.INTERNET -> settings(Settings.ACTION_WIRELESS_SETTINGS)
            Req.LLM -> a.startActivity(Intent(a, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            Req.SHIZUKU -> AppDisplays.fix(a)
            Req.UPDATE -> if (Updater.updateAvailable) Updater.downloadAndInstall(a) else Updater.check(a, quiet = false)
            Req.ANDROID_10, Req.ANDROID_13, Req.GLASSES_DISPLAY -> {}
        }
    }

    /** a feature just failed for want of [r]: say exactly what is missing (glasses toast + spoken) */
    fun explain(r: Req) {
        SpaceState.notify("Chưa dùng được", Health.why(r) + " · xem mục Kiểm tra trong app", Colors.AMBER, 6.0)
    }
}

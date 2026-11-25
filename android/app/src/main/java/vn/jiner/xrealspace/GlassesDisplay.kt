package vn.jiner.xrealspace

import android.view.Display
import android.view.Window
import vn.jiner.xrealspace.core.Stereo

/**
 * Glasses settings that replace what Nebula used to offer, done only through Android itself (no vendor commands):
 *  - refresh rate: pick one of the modes the glasses report to Android (e.g. 60 / 72 / 90 Hz) for our window,
 *  - brightness: software dimming of everything we draw (black = see-through on the glasses),
 *  - 2D / 3D: detected from the display size (3840×1080 = SBS); switching is the Brightness+ button (hold ~2 s).
 */
object GlassesDisplay {
    @Volatile var window: Window? = null
    @Volatile var display: Display? = null

    fun attach(w: Window?, d: Display?) { window = w; display = d; apply() }
    fun detach(w: Window?) { if (window === w) { window = null; display = null } }

    /** modes with our current resolution, distinct refresh rates, low → high */
    fun refreshModes(): List<Display.Mode> {
        val d = display ?: return emptyList()
        val cur = d.mode
        return d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .distinctBy { Math.round(it.refreshRate) }.sortedBy { it.refreshRate }
    }

    fun apply() {
        val w = window ?: return
        val want = SpaceState.glassesModeHz
        val mode = refreshModes().firstOrNull { Math.round(it.refreshRate) == want }
        w.attributes = w.attributes.apply { preferredDisplayModeId = mode?.modeId ?: 0 }
    }

    /** next refresh rate (wraps; 0 = let Android choose) */
    fun cycleRefresh(): String {
        val rates = refreshModes().map { Math.round(it.refreshRate) }
        if (rates.size <= 1) return "Kính chỉ báo 1 tần số: ${rates.firstOrNull() ?: "?"} Hz"
        val i = rates.indexOf(SpaceState.glassesModeHz)
        SpaceState.glassesModeHz = rates[(i + 1) % rates.size]
        SpaceState.saveSettings(); apply()
        return "${SpaceState.glassesModeHz} Hz"
    }

    fun describe(): String {
        val d = display ?: return "Kính chưa hiển thị"
        val m = d.mode
        val sbs = Stereo.isSbs(m.physicalWidth, m.physicalHeight)
        val rates = refreshModes().joinToString("/") { "${Math.round(it.refreshRate)}" }
        return "${d.name} · ${m.physicalWidth}×${m.physicalHeight} · ${Math.round(m.refreshRate)} Hz (có: $rates)\n" +
            (if (sbs) "Chế độ 3D (SBS) — giữ nút Độ sáng + ~2 giây để về 2D" else "Chế độ 2D — giữ nút Độ sáng + ~2 giây để vào 3D") +
            "\nĐộ sáng phần mềm: ${(SpaceState.glassesBrightness * 100).toInt()}%"
    }
}

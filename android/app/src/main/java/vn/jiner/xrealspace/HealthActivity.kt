package vn.jiner.xrealspace

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import vn.jiner.xrealspace.core.Health

/**
 * "Kiểm tra hệ thống": every requirement with ✓/⚠/✗, what it is for, a Fix button, and the list of features that
 * cannot work right now and why. Re-checks every time the screen comes back (after granting something in Settings).
 */
class HealthActivity : Activity() {
    private lateinit var col: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SpaceState.init(this); Assistant.init(this); Sensors.init(this)
        col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0E1116.toInt()); addView(col) })
        if (!Updater.checked) Updater.check(this) { render() }
    }

    override fun onResume() { super.onResume(); render() }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) { super.onRequestPermissionsResult(code, p, r); render() }

    private fun render() {
        col.removeAllViews()
        val items = HealthCheck.collect(this)
        col.addView(text("Kiểm tra hệ thống", 22f, Color.WHITE, bold = true))
        col.addView(text(Health.summary(items), 16f, if (items.any { it.status == Health.Status.MISSING }) Colors.AMBER else Colors.GREEN, bold = true))
        col.addView(Button(this).apply { text = "Kiểm tra lại"; isAllCaps = false; setOnClickListener { render() } })

        val blocked = Health.blocked(items)
        if (blocked.isNotEmpty()) {
            col.addView(text("Chưa dùng được", 17f, Colors.AMBER, bold = true, top = 14))
            blocked.forEach { (feature, req) -> col.addView(text("✗ $feature — thiếu: ${req.title}", 13.5f, 0xFFE0E0E0.toInt())) }
        }

        col.addView(text("Từng mục", 17f, Color.WHITE, bold = true, top = 18))
        for (it in items) {
            if (it.status == Health.Status.NA && it.req != Health.Req.UPDATE) continue
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply { setColor(0xFF161B22.toInt()); cornerRadius = dp(10).toFloat() }
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            }
            val color = when (it.status) { Health.Status.OK -> Colors.GREEN; Health.Status.WARN -> Colors.AMBER; Health.Status.MISSING -> Colors.RED; else -> Colors.GREY }
            card.addView(text("${Health.mark(it.status)}  ${it.req.title}", 15.5f, color, bold = true))
            if (it.detail.isNotBlank()) card.addView(text(it.detail, 12.5f, 0xFFB0BEC5.toInt()))
            card.addView(text("Dùng cho: " + it.req.features.joinToString(" · "), 12f, Colors.GREY))
            if (it.status == Health.Status.MISSING || it.status == Health.Status.WARN) {
                card.addView(text("Cách sửa: " + it.req.fix, 12.5f, 0xFFCFD8DC.toInt()))
                val req = it.req
                if (req != Health.Req.ANDROID_10 && req != Health.Req.ANDROID_13 && req != Health.Req.GLASSES_DISPLAY)
                    card.addView(Button(this).apply {
                        text = if (req == Health.Req.UPDATE) "Cập nhật" else "Sửa"; isAllCaps = false
                        setOnClickListener { HealthCheck.fix(this@HealthActivity, req); postDelayed({ render() }, 800) }
                    })
            }
            col.addView(card)
        }
        col.addView(text("Phiên bản ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${android.os.Build.VERSION.RELEASE} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
            11.5f, Colors.GREY, top = 16))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false, top: Int = 4) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); gravity = Gravity.START
        if (bold) typeface = Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }
    }
}

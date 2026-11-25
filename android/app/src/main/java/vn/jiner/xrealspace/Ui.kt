package vn.jiner.xrealspace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Small UI kit in the spirit of the Minimal dashboard kit (dark mode): soft dark surfaces, 12–16 dp corners,
 * green primary, grey secondary text, generous spacing. Every control shows a clear focus ring, because on the
 * XREAL Beam everything is driven with the D-pad.
 */
object Ui {
    const val BG = 0xFF141A21.toInt()
    const val PAPER = 0xFF1C252E.toInt()
    const val PAPER_2 = 0xFF28323D.toInt()
    const val LINE = 0x33919EAB
    const val TEXT = 0xFFFFFFFF.toInt()
    const val TEXT_2 = 0xFF919EAB.toInt()
    const val TEXT_3 = 0xFF637381.toInt()
    const val PRIMARY = 0xFF00A76F.toInt()
    const val PRIMARY_LIGHT = 0xFF5BE49B.toInt()
    const val PRIMARY_SOFT = 0x2900A76F
    const val INFO = 0xFF00B8D9.toInt()
    const val WARN = 0xFFFFAB00.toInt()
    const val ERROR = 0xFFFF5630.toInt()
    const val SOFT = 0x14919EAB

    val semibold: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    val bold: Typeface = Typeface.create("sans-serif", Typeface.BOLD)

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun Context.dpf(v: Int) = v * resources.displayMetrics.density

    fun shape(c: Context, fill: Int, radius: Int, stroke: Int = 0, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = c.dpf(radius); if (stroke != 0) setStroke(c.dp(strokeW), stroke)
    }

    /** normal / pressed / focused (D-pad) states */
    fun states(c: Context, fill: Int, pressed: Int, radius: Int, focusRing: Int = PRIMARY_LIGHT): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(c, pressed, radius))
        addState(intArrayOf(android.R.attr.state_focused), shape(c, pressed, radius, focusRing, 2))
        addState(intArrayOf(), shape(c, fill, radius))
    }

    private fun Button.base(c: Context, h: Int) {
        isAllCaps = false; typeface = semibold; textSize = 14f; stateListAnimator = null; elevation = 0f
        minHeight = c.dp(h); minimumHeight = c.dp(h); setPadding(c.dp(14), 0, c.dp(14), 0)
        isFocusable = true
    }

    fun primary(c: Context, text: String, onClick: () -> Unit) = Button(c).apply {
        base(c, 52); this.text = text; textSize = 16f; setTextColor(TEXT)
        background = states(c, PRIMARY, 0xFF007867.toInt(), 12, TEXT)
        setOnClickListener { onClick() }
    }

    fun soft(c: Context, text: String, onClick: () -> Unit) = Button(c).apply {
        base(c, 42); this.text = text; setTextColor(TEXT)
        background = states(c, SOFT, 0x29919EAB, 10)
        setOnClickListener { onClick() }
    }

    fun card(c: Context, pad: Int = 16) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(c.dp(pad), c.dp(pad), c.dp(pad), c.dp(pad))
        background = shape(c, PAPER, 16)
    }

    /** big tappable card: icon tile, title, one-line description */
    fun actionCard(c: Context, icon: String, tint: Int, title: String, desc: String, onClick: () -> Unit) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(c.dp(16), c.dp(16), c.dp(16), c.dp(16))
        background = states(c, PAPER, PAPER_2, 16)
        isFocusable = true; isClickable = true; setOnClickListener { onClick() }
        addView(TextView(c).apply {
            text = icon; textSize = 22f; gravity = Gravity.CENTER
            background = shape(c, (tint and 0x00FFFFFF) or 0x29000000, 12)
        }, LinearLayout.LayoutParams(c.dp(48), c.dp(48)))
        addView(TextView(c).apply { text = title; textSize = 16f; typeface = bold; setTextColor(TEXT); setPadding(0, c.dp(12), 0, c.dp(2)) })
        addView(TextView(c).apply { text = desc; textSize = 13f; setTextColor(TEXT_2); setLineSpacing(0f, 1.15f) })
    }

    fun chip(c: Context, text: String, color: Int) = TextView(c).apply {
        this.text = text; textSize = 12f; typeface = semibold; setTextColor(color)
        setPadding(c.dp(10), c.dp(5), c.dp(10), c.dp(5))
        background = shape(c, (color and 0x00FFFFFF) or 0x29000000, 8)
    }

    fun title(c: Context, text: String, size: Float = 22f) = TextView(c).apply {
        this.text = text; textSize = size; typeface = bold; setTextColor(TEXT)
    }

    fun body(c: Context, text: String, color: Int = TEXT_2, size: Float = 13.5f) = TextView(c).apply {
        this.text = text; textSize = size; setTextColor(color); setLineSpacing(0f, 1.2f)
    }

    /** section header: small grey overline like the kit's nav subheaders */
    fun overline(c: Context, text: String) = TextView(c).apply {
        this.text = text.uppercase(); textSize = 11.5f; typeface = bold; letterSpacing = 0.08f; setTextColor(TEXT_3)
        setPadding(c.dp(4), c.dp(22), 0, c.dp(8))
    }

    fun styleField(e: EditText) {
        val c = e.context
        e.setTextColor(TEXT); e.setHintTextColor(TEXT_3); e.textSize = 14f
        e.setPadding(c.dp(14), c.dp(12), c.dp(14), c.dp(12))
        e.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(c, 0x00000000, 10, TEXT, 2))
            addState(intArrayOf(), shape(c, 0x00000000, 10, 0x52919EAB, 1))
        }
        e.backgroundTintList = null
    }

    /** restyle a whole tree (the advanced section is built with plain widgets) */
    fun restyle(v: View) {
        when (v) {
            is EditText -> styleField(v)
            is Button -> if (v.background !is StateListDrawable) {
                val c = v.context
                v.isAllCaps = false; v.typeface = semibold; v.setTextColor(TEXT); v.stateListAnimator = null
                v.background = states(c, SOFT, 0x29919EAB, 10); v.backgroundTintList = null as ColorStateList?
                v.minHeight = c.dp(42); v.minimumHeight = c.dp(42)
            }
            is ViewGroup -> for (i in 0 until v.childCount) restyle(v.getChildAt(i))
        }
    }

    fun gap(c: Context, h: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, c.dp(h)) }
}

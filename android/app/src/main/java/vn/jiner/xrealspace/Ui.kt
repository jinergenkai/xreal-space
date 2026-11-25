package vn.jiner.xrealspace

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * UI kit following minimal-ui-kit/material-kit-react (src/theme): grey scale #F9FAFB…#141A21, text grey800 /
 * grey600, white cards r16 with the kit's soft "card" shadow, buttons r8 bold 14 (main action = contained
 * "inherit" grey800), outlined inputs, colored gradient widget cards (lighter→light at 48 %, text "darker").
 * Fonts: Public Sans (body; has Vietnamese, the kit's DM Sans does not) + Barlow Bold (headings), both OFL.
 *
 * Light on the phone like the kit; dark (kit greys 900/800) on the XREAL Beam, whose screen IS the glasses —
 * a white page would glare there. Every control has a visible focus ring for the Beam's D-pad.
 */
object Ui {
    var dark = false

    // kit palette (theme-config.ts)
    private const val GREY_100 = 0xFFF9FAFB.toInt()
    private const val GREY_200 = 0xFFF4F6F8.toInt()
    private const val GREY_500 = 0xFF919EAB.toInt()
    private const val GREY_600 = 0xFF637381.toInt()
    private const val GREY_800 = 0xFF1C252E.toInt()
    private const val GREY_900 = 0xFF141A21.toInt()

    class Tone(val lighter: Int, val light: Int, val main: Int, val dark: Int, val darker: Int)
    val PRIMARY_T = Tone(0xFFD0ECFE.toInt(), 0xFF73BAFB.toInt(), 0xFF1877F2.toInt(), 0xFF0C44AE.toInt(), 0xFF042174.toInt())
    val SECONDARY_T = Tone(0xFFEFD6FF.toInt(), 0xFFC684FF.toInt(), 0xFF8E33FF.toInt(), 0xFF5119B7.toInt(), 0xFF27097A.toInt())
    val INFO_T = Tone(0xFFCAFDF5.toInt(), 0xFF61F3F3.toInt(), 0xFF00B8D9.toInt(), 0xFF006C9C.toInt(), 0xFF003768.toInt())
    val SUCCESS_T = Tone(0xFFD3FCD2.toInt(), 0xFF77ED8B.toInt(), 0xFF22C55E.toInt(), 0xFF118D57.toInt(), 0xFF065E49.toInt())
    val WARNING_T = Tone(0xFFFFF5CC.toInt(), 0xFFFFD666.toInt(), 0xFFFFAB00.toInt(), 0xFFB76E00.toInt(), 0xFF7A4100.toInt())
    val ERROR_T = Tone(0xFFFFE9D5.toInt(), 0xFFFFAC82.toInt(), 0xFFFF5630.toInt(), 0xFFB71D18.toInt(), 0xFF7A0916.toInt())

    val BG get() = if (dark) GREY_900 else GREY_100
    val PAPER get() = if (dark) GREY_800 else Color.WHITE
    val NEUTRAL get() = if (dark) 0xFF28323D.toInt() else GREY_200
    val TEXT get() = if (dark) Color.WHITE else GREY_800
    val TEXT_2 get() = if (dark) GREY_500 else GREY_600
    val TEXT_3 get() = if (dark) GREY_600 else GREY_500
    val PRIMARY get() = PRIMARY_T.main
    /** text color of a "soft" label/chip in a tone */
    fun onSoft(t: Tone) = if (dark) t.light else t.dark

    private fun alpha(c: Int, a: Double) = (c and 0x00FFFFFF) or ((a * 255).toInt() shl 24)
    private val LINE get() = alpha(GREY_500, 0.32)
    private val HOVER get() = alpha(GREY_500, 0.08)
    private val FOCUS_BG get() = alpha(GREY_500, 0.16)

    // fonts
    lateinit var regular: Typeface; lateinit var semibold: Typeface; lateinit var bold: Typeface; lateinit var heading: Typeface
    private var loaded = false

    fun init(c: Context, darkMode: Boolean) {
        dark = darkMode
        if (loaded) return
        fun f(n: String, fb: Typeface) = try { Typeface.createFromAsset(c.assets, "fonts/$n") } catch (_: Throwable) { fb }
        regular = f("PublicSans_400Regular.ttf", Typeface.DEFAULT)
        semibold = f("PublicSans_600SemiBold.ttf", Typeface.create("sans-serif-medium", Typeface.NORMAL))
        bold = f("PublicSans_700Bold.ttf", Typeface.DEFAULT_BOLD)
        heading = f("Barlow_700Bold.ttf", Typeface.DEFAULT_BOLD)
        loaded = true
    }

    private fun ensure(c: Context) { if (!loaded) init(c, dark) }

    /** user choice: "auto" (follow Android dark mode), "light", "dark". The Beam is always dark: its screen is the glasses. */
    enum class Theme(val title: String) { AUTO("Tự động"), LIGHT("Sáng"), DARK("Tối");
        fun next() = entries[(ordinal + 1) % entries.size] }

    fun theme(c: Context) = try { Theme.valueOf(c.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("theme", "AUTO")!!) } catch (_: Exception) { Theme.AUTO }
    fun setTheme(c: Context, t: Theme) = c.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putString("theme", t.name).apply()

    fun wantsDark(c: Context, onBeam: Boolean): Boolean = onBeam || when (theme(c)) {
        Theme.DARK -> true
        Theme.LIGHT -> false
        Theme.AUTO -> (c.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    fun Context.dpf(v: Int) = v * resources.displayMetrics.density

    fun shape(c: Context, fill: Int, radius: Int, stroke: Int = 0, strokeW: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = c.dpf(radius); if (stroke != 0) setStroke(c.dp(strokeW), stroke)
    }

    /** normal / pressed / focused (D-pad) */
    fun states(c: Context, normal: Drawable, pressed: Drawable, focused: Drawable): Drawable = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), pressed)
        addState(intArrayOf(android.R.attr.state_focused), focused)
        addState(intArrayOf(), normal)
    }

    /** the kit's card shadow: 0 0 2px grey500/20 %, 0 12px 24px -4px grey500/12 % (approximated with elevation) */
    fun cardShadow(v: View) {
        if (dark) return
        v.elevation = v.context.dpf(3); v.translationZ = 0f
        if (Build.VERSION.SDK_INT >= 28) { v.outlineAmbientShadowColor = GREY_500; v.outlineSpotShadowColor = GREY_500 }
    }

    private fun Button.base(c: Context, h: Int) {
        ensure(c)
        isAllCaps = false; typeface = bold; textSize = 14f; stateListAnimator = null; elevation = 0f
        minHeight = c.dp(h); minimumHeight = c.dp(h); setPadding(c.dp(16), 0, c.dp(16), 0)
        isFocusable = true; backgroundTintList = null as ColorStateList?
    }

    /** contained "inherit": the kit's main action (grey800 on light, white on dark) */
    fun primary(c: Context, text: String, onClick: () -> Unit) = Button(c).apply {
        base(c, 48); this.text = text; textSize = 15f
        val fill = if (dark) Color.WHITE else GREY_800
        setTextColor(if (dark) GREY_800 else Color.WHITE)
        background = states(c, shape(c, fill, 8), shape(c, if (dark) 0xFFC4CDD5.toInt() else 0xFF454F5B.toInt(), 8),
            shape(c, fill, 8, PRIMARY_T.light, 3))
        setOnClickListener { onClick() }
    }

    /** outlined button */
    fun soft(c: Context, text: String, onClick: () -> Unit) = Button(c).apply {
        base(c, 40); this.text = text; setTextColor(TEXT)
        background = states(c, shape(c, 0, 8, LINE, 1), shape(c, HOVER, 8, TEXT, 1), shape(c, FOCUS_BG, 8, TEXT, 2))
        setOnClickListener { onClick() }
    }

    fun card(c: Context, pad: Int = 24) = LinearLayout(c).apply {
        ensure(c)
        orientation = LinearLayout.VERTICAL; setPadding(c.dp(pad), c.dp(pad), c.dp(pad), c.dp(pad))
        background = shape(c, PAPER, 16); cardShadow(this)
    }

    /** AnalyticsWidgetSummary: white + 135° gradient lighter→light at 48 %, text in "darker" */
    fun actionCard(c: Context, icon: String, t: Tone, title: String, desc: String, onClick: () -> Unit) = LinearLayout(c).apply {
        ensure(c)
        orientation = LinearLayout.VERTICAL; setPadding(c.dp(20), c.dp(20), c.dp(20), c.dp(20))
        fun face(extra: Int?, ring: Int?) = LayerDrawable(arrayOf(
            shape(c, PAPER, 16),
            GradientDrawable(GradientDrawable.Orientation.TL_BR,
                if (dark) intArrayOf(alpha(t.main, 0.24), alpha(t.main, 0.08)) else intArrayOf(alpha(t.lighter, 0.48), alpha(t.light, 0.48)))
                .apply { cornerRadius = c.dpf(16) },
            shape(c, extra ?: 0, 16, ring ?: 0, 3)))
        background = states(c, face(null, null), face(alpha(t.main, 0.12), null), face(alpha(t.main, 0.08), if (dark) t.light else t.dark))
        isFocusable = true; isClickable = true; setOnClickListener { onClick() }
        val ink = if (dark) t.lighter else t.darker
        addView(TextView(c).apply { text = icon; textSize = 30f; gravity = Gravity.START or Gravity.CENTER_VERTICAL },
            LinearLayout.LayoutParams(c.dp(48), c.dp(48)).apply { bottomMargin = c.dp(16) })
        addView(TextView(c).apply { text = title; textSize = 20f; typeface = heading; setTextColor(ink) })
        addView(TextView(c).apply {
            text = desc; textSize = 13f; typeface = regular; setTextColor(alpha(ink, 0.72)); setLineSpacing(0f, 1.2f); setPadding(0, c.dp(4), 0, 0)
        })
    }

    /** the kit's soft Label */
    fun chip(c: Context, text: String, t: Tone?) = TextView(c).apply {
        ensure(c)
        this.text = text; textSize = 12f; typeface = bold
        if (t == null) { setTextColor(TEXT_2); background = shape(c, alpha(GREY_500, 0.16), 6) }
        else { setTextColor(onSoft(t)); background = shape(c, alpha(t.main, 0.16), 6) }
        setPadding(c.dp(8), c.dp(3), c.dp(8), c.dp(3))
    }

    fun title(c: Context, text: String, size: Float = 24f) = TextView(c).apply {
        ensure(c); this.text = text; textSize = size; typeface = heading; setTextColor(TEXT)
    }

    fun subtitle(c: Context, text: String, size: Float = 16f) = TextView(c).apply {
        ensure(c); this.text = text; textSize = size; typeface = semibold; setTextColor(TEXT)
    }

    fun body(c: Context, text: String, color: Int = TEXT_2, size: Float = 14f) = TextView(c).apply {
        ensure(c); this.text = text; textSize = size; typeface = regular; setTextColor(color); setLineSpacing(0f, 1.25f)
    }

    /** nav subheader / overline */
    fun overline(c: Context, text: String) = TextView(c).apply {
        ensure(c); this.text = text.uppercase(); textSize = 12f; typeface = bold; letterSpacing = 0.06f; setTextColor(TEXT_3)
        setPadding(c.dp(4), c.dp(28), 0, c.dp(10))
    }

    fun styleField(e: EditText) {
        val c = e.context; ensure(c)
        e.setTextColor(TEXT); e.setHintTextColor(TEXT_3); e.textSize = 15f; e.typeface = regular
        e.setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(14))
        e.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(c, 0, 8, TEXT, 2))
            addState(intArrayOf(), shape(c, 0, 8, alpha(GREY_500, 0.2), 1))
        }
        e.backgroundTintList = null
    }

    private fun isGreyish(col: Int): Boolean {
        val hsv = FloatArray(3); Color.colorToHSV(col, hsv); return hsv[1] < 0.3f
    }

    /** restyle a tree built with plain widgets (the advanced section, the health screen) */
    fun restyle(v: View) {
        ensure(v.context)
        when (v) {
            is EditText -> styleField(v)
            is Button -> if (v.background !is StateListDrawable) {
                val c = v.context
                v.isAllCaps = false; v.typeface = bold; v.setTextColor(TEXT); v.stateListAnimator = null
                v.background = states(c, shape(c, 0, 8, LINE, 1), shape(c, HOVER, 8, TEXT, 1), shape(c, FOCUS_BG, 8, TEXT, 2))
                v.backgroundTintList = null as ColorStateList?
                v.minHeight = c.dp(40); v.minimumHeight = c.dp(40)
            }
            is TextView -> {
                if (v.typeface != Typeface.MONOSPACE) v.typeface = if (v.typeface?.isBold == true) bold else regular
                val col = v.currentTextColor
                if (isGreyish(col)) {
                    // light greys/white were written for the old dark screen; map them onto the kit's text colors
                    val bright = Color.luminance(col) > 0.5f
                    v.setTextColor(if (!bright) TEXT_2 else if (col == Color.WHITE) TEXT else TEXT_2)
                } else if (!dark) {
                    // saturated status colors (amber, green…) are too pale on white: use the tone's dark shade
                    val hsv = FloatArray(3); Color.colorToHSV(col, hsv)
                    hsv[2] = (hsv[2] * 0.62f).coerceAtMost(0.62f); v.setTextColor(Color.HSVToColor(hsv))
                }
            }
            is ViewGroup -> for (i in 0 until v.childCount) restyle(v.getChildAt(i))
        }
    }

    fun gap(c: Context, h: Int) = View(c).apply { layoutParams = LinearLayout.LayoutParams(1, c.dp(h)) }
}

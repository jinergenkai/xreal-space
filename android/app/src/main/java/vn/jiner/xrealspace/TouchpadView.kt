package vn.jiner.xrealspace

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The phone while you wear the glasses: one big surface you use WITHOUT looking.
 *   1 finger tap ........ click under the gaze dot          1 finger drag ...... scroll / drag
 *   1 finger hold ....... talk (voice command, or dictation when typing)
 *   2 fingers tap ....... recenter                           2 fingers ← / → ..... back / forward
 *   2 fingers ↑ ......... show slot numbers                  2 fingers ↓ ........ walk mode on/off
 *   3 fingers tap ....... next mode (fixed → cinema → travel)
 * A short buzz confirms every gesture.
 */
@SuppressLint("ViewConstructor")
class TouchpadView(ctx: Context, private val host: Host) : View(ctx) {
    interface Host {
        /** finger on the touchpad, proxied to the gaze dot (locally or to the remote glasses box) */
        fun pad(action: Int, dx: Float, dy: Float)
        fun onHold()
        fun onTwoTap()
        fun onThreeTap()
        fun onSwipe2(dx: Float, dy: Float)
    }

    private val main = Handler(Looper.getMainLooper())
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; textSize = 34f; textAlign = Paint.Align.CENTER }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x44FFFFFF; textSize = 26f; textAlign = Paint.Align.CENTER }

    private var x0 = 0f; private var y0 = 0f
    private var downAt = 0L
    private var maxPointers = 0
    private var proxying = false
    private var held = false
    private var cx0 = 0f; private var cy0 = 0f
    private val holdRunnable = Runnable {
        if (proxying && maxPointers == 1) {
            host.pad(MotionEvent.ACTION_CANCEL, 0f, 0f)
            proxying = false; held = true
            buzz(); host.onHold()
        }
    }

    init { setBackgroundColor(0xFF05080C.toInt()); isHapticFeedbackEnabled = true }

    private fun buzz() = performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)

    override fun onDraw(c: Canvas) {
        val x = width / 2f; var y = height * 0.42f
        c.drawText("Touchpad", x, y, hint); y += 60
        for (l in listOf("chạm = click · kéo = cuộn · giữ = nói",
            "2 ngón: chạm = về giữa · ← → = back/tới",
            "2 ngón ↑ = hiện ô · ↓ = đi bộ",
            "3 ngón chạm = đổi chế độ")) { c.drawText(l, x, y, small); y += 40 }
    }

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var sx = 0f; var sy = 0f
        for (i in 0 until e.pointerCount) { sx += e.getX(i); sy += e.getY(i) }
        return sx / e.pointerCount to sy / e.pointerCount
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                x0 = e.x; y0 = e.y; downAt = SystemClock.uptimeMillis(); maxPointers = 1; held = false
                host.pad(MotionEvent.ACTION_DOWN, 0f, 0f); proxying = true
                main.postDelayed(holdRunnable, 600)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                main.removeCallbacks(holdRunnable)
                if (proxying) { host.pad(MotionEvent.ACTION_CANCEL, 0f, 0f); proxying = false }
                val (cx, cy) = centroid(e); cx0 = cx; cy0 = cy
            }
            MotionEvent.ACTION_MOVE -> {
                if (proxying) {
                    val dx = e.x - x0; val dy = e.y - y0
                    if (hypot(dx, dy) > 24) main.removeCallbacks(holdRunnable)
                    host.pad(MotionEvent.ACTION_MOVE, dx, dy)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(holdRunnable)
                if (proxying) { host.pad(e.actionMasked, e.x - x0, e.y - y0); proxying = false }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (maxPointers >= 2 && e.pointerCount == maxPointers) {   // first finger lifting ends the multi-touch gesture
                    val (cx, cy) = centroid(e)
                    val dx = cx - cx0; val dy = cy - cy0
                    val quick = SystemClock.uptimeMillis() - downAt < 350
                    when {
                        abs(dx) > 120 || abs(dy) > 120 -> { buzz(); host.onSwipe2(dx, dy) }
                        quick && maxPointers == 2 -> { buzz(); host.onTwoTap() }
                        quick && maxPointers >= 3 -> { buzz(); host.onThreeTap() }
                    }
                    maxPointers = 99 // consume the rest of this gesture
                }
            }
        }
        return true
    }
}

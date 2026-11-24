package vn.jiner.xrealspace.core

import kotlin.math.abs

/**
 * 3DoF orientation from the glasses IMU (pure Kotlin, no Android deps -> unit-testable).
 *
 * R maps body(IMU) vectors to world; world = body frame at the last reset().
 * Gyro integration on SO(3) + Mahony-style gravity correction for pitch/roll. Yaw has no reference
 * and drifts slowly: recenter with reset().
 *
 * Gyro bias (fix vs. the Windows prototype, which averaged the first 1000 samples even while the head
 * was moving and then could never correct an error > 1.5 deg/s):
 *  - skip the first 0.3 s after start / recalibrate,
 *  - accept a bias only from a window where the glasses are really still (restart on motion),
 *  - keep refining while still; "still" is judged from gyro *variation* (vs. its own short-term mean)
 *    and accel ~1 g, so a wrong bias does not block its own correction.
 */
class ImuTracker(
    private val kp: Double = 0.5,
    private val calibSeconds: Double = 0.8,
) {
    enum class BiasState { WARMUP, WAITING_STILL, READY }

    private val lock = Any()
    private var R: M3 = Mat3.eye()
    private var upWorld: V3? = null
    private var resetReq = true

    var bias: V3 = DoubleArray(3); private set          // deg/s
    var biasState = BiasState.WARMUP; private set

    /** bias-corrected gyro, rad/s, body frame */
    @Volatile var lastGyro: V3 = DoubleArray(3); private set
    @Volatile var lastAccel: V3 = DoubleArray(3); private set
    @Volatile var rate = 0.0; private set
    @Volatile var samples = 0L; private set
    @Volatile var stillNow = false; private set

    private var lastTs = -1L
    private var calibStartTs = -1L
    private var warmupUntil = -1L
    private val win = ArrayList<V3>()
    private var winAccel: V3? = null
    private var winStartTs = -1L
    private var gyroEma: V3? = null
    private var stillSinceTs = -1L
    private var rateT0 = -1L
    private var rateN = 0

    private var gestureIntegral: V3? = null
    private var gestureBest: V3 = DoubleArray(3)

    fun reset() = synchronized(lock) { resetReq = true }

    fun recalibrateBias() = synchronized(lock) {
        biasState = BiasState.WARMUP
        warmupUntil = -1L
        win.clear(); winStartTs = -1L; winAccel = null
    }

    fun orientation(): M3 = synchronized(lock) { R.copyOf() }

    /** Orientation predicted [ms] ahead using the latest angular rate (latency compensation). */
    fun predicted(ms: Double): M3 = synchronized(lock) { Mat3.mul(R, Mat3.expSo3(Mat3.scale(lastGyro, ms / 1000.0))) }

    fun startGesture() = synchronized(lock) { gestureIntegral = DoubleArray(3); gestureBest = DoubleArray(3) }
    fun endGesture(): V3 = synchronized(lock) { gestureIntegral = null; gestureBest.copyOf() }

    /** Feed one IMU sample. gyro in deg/s, accel in g, timestamp in ns (device clock). */
    fun update(tsNs: Long, gyroDps: V3, accelG: V3) = synchronized(lock) {
        samples++
        // rate meter on the device clock
        if (rateT0 < 0) rateT0 = tsNs
        rateN++
        if (tsNs - rateT0 >= 1_000_000_000L) { rate = rateN * 1e9 / (tsNs - rateT0); rateT0 = tsNs; rateN = 0 }

        var dt = if (lastTs >= 0) (tsNs - lastTs) * 1e-9 else 0.0
        lastTs = tsNs
        if (!(dt > 0 && dt < 0.05)) dt = 0.0
        lastAccel = accelG
        val an = Mat3.norm(accelG)

        // --- short-term gyro mean (for stillness that does not depend on the bias)
        val ema = gyroEma ?: gyroDps.copyOf().also { gyroEma = it }
        for (i in 0..2) ema[i] += (gyroDps[i] - ema[i]) * 0.01
        val dev = Mat3.norm(Mat3.sub(gyroDps, ema))
        val still = abs(an - 1.0) < 0.05 && dev < 1.0

        // --- bias calibration
        if (biasState != BiasState.READY) {
            if (warmupUntil < 0) warmupUntil = tsNs + 300_000_000L
            if (tsNs < warmupUntil) { biasState = BiasState.WARMUP; return }
            biasState = BiasState.WAITING_STILL
            calibWindow(tsNs, gyroDps, accelG)
            if (biasState != BiasState.READY) return
        }

        val wDps = Mat3.sub(gyroDps, bias)
        if (still && Mat3.norm(wDps) < 3.0) {
            if (stillSinceTs < 0) stillSinceTs = tsNs
            if (tsNs - stillSinceTs > 500_000_000L) for (i in 0..2) bias[i] += 0.002 * wDps[i]
        } else stillSinceTs = -1L
        stillNow = still

        val w = DoubleArray(3) { Math.toRadians(gyroDps[it] - bias[it]) }
        lastGyro = w

        if (resetReq && an > 0.5) {
            R = Mat3.eye(); upWorld = Mat3.scale(accelG, 1 / an); resetReq = false
        }
        gestureIntegral?.let { gi ->
            for (i in 0..2) gi[i] += w[i] * dt
            if (Mat3.norm(gi) > Mat3.norm(gestureBest)) gestureBest = gi.copyOf()
        }
        val up = upWorld ?: return
        if (dt <= 0) return
        var corr = DoubleArray(3)
        if (an > 0.8 && an < 1.2) {
            val upBody = Mat3.mulV(Mat3.t(R), up)
            corr = Mat3.scale(Mat3.cross(Mat3.scale(accelG, 1 / an), upBody), kp)
        }
        R = Mat3.mul(R, Mat3.expSo3(Mat3.scale(Mat3.add(w, corr), dt)))
        if (samples % 500 == 0L) R = Mat3.orthonormalize(R)
    }

    private fun calibWindow(tsNs: Long, g: V3, a: V3) {
        if (win.isEmpty()) { winStartTs = tsNs; winAccel = a.copyOf() }
        win.add(g.copyOf())
        val n = win.size
        if (n >= 50) {
            val mean = DoubleArray(3)
            for (s in win) for (i in 0..2) mean[i] += s[i] / n
            val gDev = Mat3.norm(Mat3.sub(g, mean))
            val aDev = Mat3.norm(Mat3.sub(a, winAccel!!))
            if (gDev > 3.0 || aDev > 0.05 || abs(Mat3.norm(a) - 1.0) > 0.08) {
                win.clear(); return  // moved: start over
            }
            if ((tsNs - winStartTs) * 1e-9 >= calibSeconds) {
                bias = mean
                biasState = BiasState.READY
                win.clear()
                resetReq = true
            }
        }
    }
}

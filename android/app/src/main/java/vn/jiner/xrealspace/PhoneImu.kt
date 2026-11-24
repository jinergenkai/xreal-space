package vn.jiner.xrealspace

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * Fallback head tracker: the phone's own gyro + accelerometer feed the same ImuTracker.
 * Lets you test the whole space on a phone without glasses (hold the phone like a window).
 * Phone sensor axes (x right, y up, z towards you) already match the camera convention -> mapping = identity.
 */
class PhoneImu(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val lastAcc = doubleArrayOf(0.0, 1.0, 0.0)
    var running = false; private set
    val available get() = gyro != null && acc != null

    fun start(): Boolean {
        if (!available) { SpaceState.log("Điện thoại không có gyro/accelerometer"); return false }
        SpaceState.useTracker(SpaceState.ImuSource.PHONE)
        sm.registerListener(this, gyro, SensorManager.SENSOR_DELAY_FASTEST)
        sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST)
        running = true
        return true
    }

    fun stop() { sm.unregisterListener(this); running = false }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> for (i in 0..2) lastAcc[i] = e.values[i] / SensorManager.GRAVITY_EARTH.toDouble()
            Sensor.TYPE_GYROSCOPE -> SpaceState.tracker.update(
                e.timestamp,
                doubleArrayOf(Math.toDegrees(e.values[0].toDouble()), Math.toDegrees(e.values[1].toDouble()), Math.toDegrees(e.values[2].toDouble())),
                lastAcc.copyOf(),
            )
        }
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
}

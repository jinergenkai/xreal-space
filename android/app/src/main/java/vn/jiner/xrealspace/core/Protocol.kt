package vn.jiner.xrealspace.core

import java.util.zip.CRC32

/**
 * XREAL Air / Air 2 IMU protocol (ported from the Windows prototype, confirmed on a real Air 2).
 *
 * Command: 0xAA | crc32 LE | len u16 LE (=3+n) | msgid u8 | data   — CRC over len+msgid+data.
 * Sample : 01 02 ..., ts u64 ns @4, gyro mult i16 @12, div i32 @14, xyz i24 @18/21/24 (deg/s),
 *          accel mult @27, div @29, xyz i24 @33/36/39 (g).
 * On raw USB (Android) there is no report-id byte: packets start directly with 01 02.
 */
object Protocol {
    const val VENDOR_ID = 0x3318
    const val MSG_IMU_STREAM = 0x19
    const val REPORT_SIZE = 64

    data class Model(val name: String, val imuInterface: Int)

    /** PID -> model. Air 2 Ultra uses interface 2 with a different (512 B) payload: listed but not parsed. */
    val MODELS = mapOf(
        0x0424 to Model("Air", 3),
        0x0428 to Model("Air 2", 3),
        0x0432 to Model("Air 2 Pro", 3),
        0x0426 to Model("Air 2 Ultra (chưa hỗ trợ)", 2),
    )

    fun makeMsg(msgId: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val len = 3 + data.size
        val body = ByteArray(3 + data.size)
        body[0] = (len and 0xFF).toByte()
        body[1] = ((len shr 8) and 0xFF).toByte()
        body[2] = msgId.toByte()
        data.copyInto(body, 3)
        val crc = CRC32().apply { update(body) }.value
        val out = ByteArray(5 + body.size)
        out[0] = 0xAA.toByte()
        for (i in 0 until 4) out[1 + i] = ((crc shr (8 * i)) and 0xFF).toByte()
        body.copyInto(out, 5)
        return out
    }

    fun imuStream(on: Boolean) = makeMsg(MSG_IMU_STREAM, byteArrayOf(if (on) 1 else 0))

    /** Pad to a full HID report. */
    fun report(payload: ByteArray, size: Int = REPORT_SIZE): ByteArray = ByteArray(size).also { payload.copyInto(it, 0, 0, minOf(size, payload.size)) }

    class Sample(val tsNs: Long, val gyroDps: V3, val accelG: V3)

    private fun u8(p: ByteArray, i: Int) = p[i].toInt() and 0xFF
    private fun i16(p: ByteArray, i: Int) = ((u8(p, i) or (u8(p, i + 1) shl 8)).toShort()).toInt()
    private fun i32(p: ByteArray, i: Int) = u8(p, i) or (u8(p, i + 1) shl 8) or (u8(p, i + 2) shl 16) or (u8(p, i + 3) shl 24)
    private fun i24(p: ByteArray, i: Int): Int {
        val v = u8(p, i) or (u8(p, i + 1) shl 8) or (u8(p, i + 2) shl 16)
        return (v shl 8) shr 8 // sign-extend
    }
    private fun u64(p: ByteArray, i: Int): Long {
        var v = 0L
        for (k in 7 downTo 0) v = (v shl 8) or (p[i + k].toLong() and 0xFF)
        return v
    }

    /** Returns null for non-IMU packets. `len` = number of valid bytes in [p]. */
    fun parseSample(p: ByteArray, len: Int = p.size): Sample? {
        if (len < 42 || u8(p, 0) != 0x01 || u8(p, 1) != 0x02) return null
        val ts = u64(p, 4)
        val gm = i16(p, 12); val gd = i32(p, 14)
        val am = i16(p, 27); val ad = i32(p, 29)
        if (gd == 0 || ad == 0) return null
        val g = DoubleArray(3) { i24(p, 18 + 3 * it).toDouble() * gm / gd }
        val a = DoubleArray(3) { i24(p, 33 + 3 * it).toDouble() * am / ad }
        return Sample(ts, g, a)
    }

    /** Builds a sample packet (used by tests and the built-in simulator). */
    fun encodeSample(tsNs: Long, gyroDps: V3, accelG: V3): ByteArray {
        val p = ByteArray(REPORT_SIZE)
        p[0] = 1; p[1] = 2
        for (k in 0 until 8) p[4 + k] = ((tsNs shr (8 * k)) and 0xFF).toByte()
        fun put16(i: Int, v: Int) { p[i] = v.toByte(); p[i + 1] = (v shr 8).toByte() }
        fun put32(i: Int, v: Int) { for (k in 0 until 4) p[i + k] = (v shr (8 * k)).toByte() }
        fun put24(i: Int, v: Int) { for (k in 0 until 3) p[i + k] = (v shr (8 * k)).toByte() }
        val gm = 1; val gd = 8192; val am = 1; val ad = 8192
        put16(12, gm); put32(14, gd); put16(27, am); put32(29, ad)
        for (i in 0 until 3) {
            put24(18 + 3 * i, Math.round(gyroDps[i] * gd / gm).toInt())
            put24(33 + 3 * i, Math.round(accelG[i] * ad / am).toInt())
        }
        return p
    }
}

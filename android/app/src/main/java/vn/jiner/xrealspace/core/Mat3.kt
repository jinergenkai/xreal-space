package vn.jiner.xrealspace.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small 3-vector / 3x3 matrix helpers (row-major DoubleArray(9)). No allocations-free tricks: clarity first. */
typealias V3 = DoubleArray
typealias M3 = DoubleArray

object Mat3 {
    fun v(x: Double, y: Double, z: Double): V3 = doubleArrayOf(x, y, z)
    fun eye(): M3 = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    fun fromRows(rows: List<List<Double>>): M3 = DoubleArray(9) { rows[it / 3][it % 3] }
    fun toRows(m: M3): List<List<Double>> = List(3) { r -> List(3) { c -> m[r * 3 + c] } }

    fun mul(a: M3, b: M3): M3 = DoubleArray(9) { i ->
        val r = i / 3; val c = i % 3
        a[r * 3] * b[c] + a[r * 3 + 1] * b[3 + c] + a[r * 3 + 2] * b[6 + c]
    }

    fun mulV(a: M3, v: V3): V3 = doubleArrayOf(
        a[0] * v[0] + a[1] * v[1] + a[2] * v[2],
        a[3] * v[0] + a[4] * v[1] + a[5] * v[2],
        a[6] * v[0] + a[7] * v[1] + a[8] * v[2],
    )

    fun t(a: M3): M3 = doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    fun norm(v: V3) = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
    fun dot(a: V3, b: V3) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    fun cross(a: V3, b: V3): V3 = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0],
    )
    fun scale(v: V3, s: Double): V3 = doubleArrayOf(v[0] * s, v[1] * s, v[2] * s)
    fun add(a: V3, b: V3): V3 = doubleArrayOf(a[0] + b[0], a[1] + b[1], a[2] + b[2])
    fun sub(a: V3, b: V3): V3 = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    fun unit(v: V3): V3 { val n = norm(v); return if (n < 1e-12) v.copyOf() else scale(v, 1 / n) }

    fun skew(v: V3): M3 = doubleArrayOf(0.0, -v[2], v[1], v[2], 0.0, -v[0], -v[1], v[0], 0.0)

    /** Rodrigues: rotation matrix for rotation vector w (rad). */
    fun expSo3(w: V3): M3 {
        val th = norm(w)
        val e = eye()
        if (th < 1e-9) { val k = skew(w); return DoubleArray(9) { e[it] + k[it] } }
        val k = skew(scale(w, 1 / th))
        val kk = mul(k, k)
        val s = sin(th); val c = 1 - cos(th)
        return DoubleArray(9) { e[it] + s * k[it] + c * kk[it] }
    }

    /** Gram-Schmidt re-orthonormalization (cheap stand-in for SVD, fine for tiny drift). */
    fun orthonormalize(m: M3): M3 {
        val x = unit(doubleArrayOf(m[0], m[3], m[6]))
        var y = doubleArrayOf(m[1], m[4], m[7])
        y = unit(sub(y, scale(x, dot(x, y))))
        val z = cross(x, y)
        return doubleArrayOf(x[0], y[0], z[0], x[1], y[1], z[1], x[2], y[2], z[2])
    }

    fun rotY(deg: Double): M3 {
        val a = Math.toRadians(deg); val c = cos(a); val s = sin(a)
        return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
    }

    fun rotX(deg: Double): M3 {
        val a = Math.toRadians(deg); val c = cos(a); val s = sin(a)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
    }

    /** Rotation angle (deg) between two rotation matrices. */
    fun angleDeg(a: M3, b: M3): Double {
        val r = mul(t(a), b)
        val tr = (r[0] + r[4] + r[8] - 1) / 2
        return Math.toDegrees(kotlin.math.acos(tr.coerceIn(-1.0, 1.0)))
    }
}

fun wrap180(a: Double): Double { var x = (a + 180.0) % 360.0; if (x < 0) x += 360.0; return x - 180.0 }

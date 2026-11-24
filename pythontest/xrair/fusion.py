"""
Sensor fusion: Madgwick IMU (gyro + accel, no magnetometer) + head-pose helper.

The sensor's mounting inside the glasses is not assumed. Instead:
  * "up"      is measured from gravity at recenter time (automatic),
  * "forward" is a configurable sensor axis (default -Y, from ar-drivers-rs axis remap);
    `python -m xrair axes` helps find the right one on real hardware.
"""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass
from typing import Optional, Tuple

Vec = Tuple[float, float, float]
Quat = Tuple[float, float, float, float]  # (w, x, y, z)

AXES = {"+x": (1, 0, 0), "-x": (-1, 0, 0), "+y": (0, 1, 0), "-y": (0, -1, 0),
        "+z": (0, 0, 1), "-z": (0, 0, -1)}


def q_mul(a: Quat, b: Quat) -> Quat:
    aw, ax, ay, az = a
    bw, bx, by, bz = b
    return (aw * bw - ax * bx - ay * by - az * bz,
            aw * bx + ax * bw + ay * bz - az * by,
            aw * by - ax * bz + ay * bw + az * bx,
            aw * bz + ax * by - ay * bx + az * bw)


def q_rotate(q: Quat, v: Vec) -> Vec:
    """Rotate vector v (sensor frame) into world frame."""
    w, x, y, z = q
    p = (0.0, *v)
    r = q_mul(q_mul(q, p), (w, -x, -y, -z))
    return r[1], r[2], r[3]


def _norm(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v) if n > 1e-12 else v


class Madgwick:
    """Madgwick 2010 IMU update. World frame: Z up. Accel expected ~ +1g on the up axis."""

    def __init__(self, beta: float = 0.08):
        self.beta = beta
        self.q: Quat = (1.0, 0.0, 0.0, 0.0)
        self.initialized = False

    def init_from_accel(self, a: Vec):
        # Choose q so that the measured gravity direction maps onto world +Z.
        ax, ay, az = _norm(a)
        # rotation from a to (0,0,1)
        d = az
        if d < -0.999999:
            self.q = (0.0, 1.0, 0.0, 0.0)
        else:
            cx, cy, cz = ay * 1 - az * 0, az * 0 - ax * 1, 0.0  # cross(a, z)
            w = 1.0 + d
            self.q = _norm((w, cx, cy, cz))
        self.initialized = True

    def update(self, gyro_rad: Vec, accel: Vec, dt: float):
        if not self.initialized:
            self.init_from_accel(accel)
            return
        q0, q1, q2, q3 = self.q
        gx, gy, gz = gyro_rad
        # rate of change from gyroscope
        qd0 = 0.5 * (-q1 * gx - q2 * gy - q3 * gz)
        qd1 = 0.5 * (q0 * gx + q2 * gz - q3 * gy)
        qd2 = 0.5 * (q0 * gy - q1 * gz + q3 * gx)
        qd3 = 0.5 * (q0 * gz + q1 * gy - q2 * gx)
        ax, ay, az = accel
        n = math.sqrt(ax * ax + ay * ay + az * az)
        if 0.5 < n < 1.5:  # only trust accel when not shaking hard
            ax, ay, az = ax / n, ay / n, az / n
            _2q0, _2q1, _2q2, _2q3 = 2 * q0, 2 * q1, 2 * q2, 2 * q3
            _4q0, _4q1, _4q2 = 4 * q0, 4 * q1, 4 * q2
            _8q1, _8q2 = 8 * q1, 8 * q2
            q0q0, q1q1, q2q2, q3q3 = q0 * q0, q1 * q1, q2 * q2, q3 * q3
            s0 = _4q0 * q2q2 + _2q2 * ax + _4q0 * q1q1 - _2q1 * ay
            s1 = _4q1 * q3q3 - _2q3 * ax + 4 * q0q0 * q1 - _2q0 * ay - _4q1 + _8q1 * q1q1 + _8q1 * q2q2 + _4q1 * az
            s2 = 4 * q0q0 * q2 + _2q0 * ax + _4q2 * q3q3 - _2q3 * ay - _4q2 + _8q2 * q1q1 + _8q2 * q2q2 + _4q2 * az
            s3 = 4 * q1q1 * q3 - _2q1 * ax + 4 * q2q2 * q3 - _2q2 * ay
            sn = math.sqrt(s0 * s0 + s1 * s1 + s2 * s2 + s3 * s3)
            if sn > 1e-12:
                qd0 -= self.beta * s0 / sn
                qd1 -= self.beta * s1 / sn
                qd2 -= self.beta * s2 / sn
                qd3 -= self.beta * s3 / sn
        q0 += qd0 * dt
        q1 += qd1 * dt
        q2 += qd2 * dt
        q3 += qd3 * dt
        self.q = _norm((q0, q1, q2, q3))


@dataclass
class HeadPose:
    yaw: float = 0.0     # degrees, + = turned right
    pitch: float = 0.0   # degrees, + = looking up
    roll: float = 0.0


class HeadTracker:
    """Turns raw IMU samples into a recenterable yaw/pitch/roll."""

    def __init__(self, forward_axis: str = "-y", flip_yaw: bool = False, flip_pitch: bool = False,
                 beta: float = 0.08, bias_seconds: float = 1.5):
        self.forward_s: Vec = AXES[forward_axis]
        self.flip_yaw = flip_yaw
        self.flip_pitch = flip_pitch
        self.filter = Madgwick(beta)
        self.bias_seconds = bias_seconds
        self._bias_sum = [0.0, 0.0, 0.0]
        self._bias_n = 0
        self._bias_t = 0.0
        self._recent = deque(maxlen=50)  # recent gyro samples for motion detection
        self._skip_t = 0.3               # ignore the first 0.3 s after stream start (transient)
        self._calib_elapsed = 0.0
        self.gyro_bias: Optional[Vec] = None
        self.status = "Đang chờ đứng yên để hiệu chỉnh gyro..."
        self._still_t = 0.0
        self._last_ts: Optional[int] = None
        self._yaw0 = 0.0
        self._pitch0 = 0.0
        self.pose = HeadPose()
        self.warning = ""
        self.rate_hz = 0.0
        self._right_s = None
        self._rate_n = 0
        self._rate_t = 0.0

    @property
    def calibrating(self) -> bool:
        return self.gyro_bias is None

    def recenter(self):
        self._yaw0 = self._raw_yaw()
        f = q_rotate(self.filter.q, self.forward_s)
        self._pitch0 = math.degrees(math.asin(max(-1.0, min(1.0, f[2]))))

    def _raw_yaw(self) -> float:
        f = q_rotate(self.filter.q, self.forward_s)
        return math.degrees(math.atan2(f[1], f[0]))

    def _calibrate_step(self, dt: float, gyro_dps: Vec, accel_g: Vec):
        """Collect a still window; restart it whenever motion is seen. Accept after bias_seconds."""
        self._calib_elapsed += dt
        if self._skip_t > 0:
            self._skip_t -= dt
            return
        self._recent.append(gyro_dps)
        for i in range(3):
            self._bias_sum[i] += gyro_dps[i]
        self._bias_n += 1
        self._bias_t += dt
        mean = [c / self._bias_n for c in self._bias_sum]
        dev = max(abs(g[i] - mean[i]) for g in self._recent for i in range(3))
        anorm = math.sqrt(sum(a * a for a in accel_g))
        if dev > 3.0 or not (0.9 < anorm < 1.1):
            if self._calib_elapsed > 15.0:
                self.warning = "Không đứng yên được 15s — hiệu chỉnh tạm, có thể bị trôi. Bấm R khi đã yên."
            else:
                self.status = "Đang chuyển động — giữ yên đầu để hiệu chỉnh..."
                self._bias_sum = [0.0, 0.0, 0.0]
                self._bias_n = 0
                self._bias_t = 0.0
                return
        else:
            self.status = f"Giữ yên... {max(0.0, self.bias_seconds - self._bias_t):.1f}s"
        if self._bias_t < self.bias_seconds:
            return
        self.gyro_bias = tuple(mean)
        self.status = ""
        self.filter.init_from_accel(accel_g)
        up = _norm(accel_g)
        fx, fy, fz = self.forward_s
        self._right_s = _norm((fy * up[2] - fz * up[1], fz * up[0] - fx * up[2], fx * up[1] - fy * up[0]))
        up_dot = abs(sum(a * b for a, b in zip(up, self.forward_s)))
        if up_dot > 0.8:
            self.warning = ("Trục forward đang gần trùng trục trọng lực. "
                            "Chạy `python -m xrair axes` để chọn lại --forward.")
        self.recenter()

    def feed(self, timestamp_ns: int, gyro_dps: Vec, accel_g: Vec):
        if self._last_ts is None:
            self._last_ts = timestamp_ns
            return
        dt = (timestamp_ns - self._last_ts) / 1e9
        self._last_ts = timestamp_ns
        if not (0 < dt < 0.1):
            return
        self._rate_n += 1
        self._rate_t += dt
        if self._rate_t >= 1.0:
            self.rate_hz = self._rate_n / self._rate_t
            self._rate_n, self._rate_t = 0, 0.0

        if self.gyro_bias is None:
            self._calibrate_step(dt, gyro_dps, accel_g)
            return

        # Continuous bias refinement while the head is still (zero-velocity update).
        corr = [gyro_dps[i] - self.gyro_bias[i] for i in range(3)]
        if max(abs(c) for c in corr) < 1.0:
            self._still_t += dt
            if self._still_t > 0.5:
                a = min(1.0, dt / 10.0)  # ~10 s time constant
                self.gyro_bias = tuple(self.gyro_bias[i] + a * corr[i] for i in range(3))
        else:
            self._still_t = 0.0

        g = tuple(math.radians(gyro_dps[i] - self.gyro_bias[i]) for i in range(3))
        self.filter.update(g, accel_g, dt)
        f = q_rotate(self.filter.q, self.forward_s)
        yaw = math.degrees(math.atan2(f[1], f[0])) - self._yaw0
        yaw = (yaw + 180) % 360 - 180
        pitch = math.degrees(math.asin(max(-1.0, min(1.0, f[2])))) - self._pitch0
        r = q_rotate(self.filter.q, self._right_s) if self._right_s else (0, 0, 0)
        roll = math.degrees(math.asin(max(-1.0, min(1.0, r[2]))))
        # Convention: + yaw = turn right. atan2 grows counter-clockwise (turn left) -> negate.
        yaw = -yaw
        if self.flip_yaw:
            yaw = -yaw
        if self.flip_pitch:
            pitch = -pitch
        self.pose = HeadPose(yaw=yaw, pitch=pitch, roll=roll)

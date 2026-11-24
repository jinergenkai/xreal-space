import math, time, unittest
from xrair import protocol as P
from tests.fake_hid import make_imu_packet

# Frames captured on real hardware (BladeRunner28/XrealBeam README, AirAPI_Windows source)
GROUND_TRUTH = {
    (0x19, b"\x00"): "aa53e1263504001900",
    (0x1A, b""): "aa319e650003001a",
    (0x14, b""): "aa36b3dde7030014",
    (0x15, b""): "aaa083da90030015",
    (0x19, b"\x01"): "aac5d1214204001901",
}


class TestProtocol(unittest.TestCase):
    def test_imu_frames_match_hardware(self):
        for (cmd, data), expect in GROUND_TRUTH.items():
            self.assertEqual(P.trim(P.build_imu_command(cmd, data)).hex(), expect)

    def test_mcu_frame_matches_airapi(self):
        f = P.build_mcu_command(P.MCU_GET_BRIGHTNESS, request_id=0)
        self.assertEqual(f[1:5].hex(), "1eb9f068")
        self.assertTrue(P.verify_crc(f))
        p = P.parse_mcu_packet(f)
        self.assertEqual((p.cmd_id, p.data), (0x03, b""))

    def test_imu_sample_roundtrip(self):
        pkt = make_imu_packet(123456789, (10.5, -3.25, 0.0), (0.0, -0.5, 0.866))
        s = P.parse_imu_sample(pkt)
        self.assertEqual(s.timestamp_ns, 123456789)
        for a, b in zip(s.gyro_dps, (10.5, -3.25, 0.0)):
            self.assertAlmostEqual(a, b, places=2)
        for a, b in zip(s.accel_g, (0.0, -0.5, 0.866)):
            self.assertAlmostEqual(a, b, places=3)
        self.assertAlmostEqual(s.temperature_c, 30.0, places=1)

    def test_reject_non_data(self):
        self.assertIsNone(P.parse_imu_sample(P.build_imu_command(0x19, b"\x01")))


class TestFusion(unittest.TestCase):
    BIAS = (0.3, -0.2, 0.1)

    @staticmethod
    def _sim(segments, tilt_deg=0.0, bias=(0.3, -0.2, 0.1), spike_first=0):
        """segments: list of (gyro_dps tuple, seconds). Returns tracker and yaw history."""
        from xrair.fusion import HeadTracker, q_mul, q_rotate
        ht, t, dt = HeadTracker(), 0.0, 0.001
        a = math.radians(tilt_deg)
        q = (math.cos(a / 2), math.sin(a / 2), 0.0, 0.0)  # sensor mounted pitched by tilt_deg
        hist, n = [], 0
        for gyro, secs in segments:
            for _ in range(int(secs / dt)):
                t += dt
                w = [math.radians(g) for g in gyro]
                qd = q_mul(q, (0, *w)); q = tuple(x + 0.5 * y * dt for x, y in zip(q, qd))
                m = math.sqrt(sum(c * c for c in q)); q = tuple(c / m for c in q)
                acc = q_rotate((q[0], -q[1], -q[2], -q[3]), (0, 0, 1))
                g = tuple(x + b for x, b in zip(gyro, bias))
                if n < spike_first:
                    g = (40.0, -25.0, 60.0)  # garbage right after stream start
                n += 1
                ht.feed(int(t * 1e9), g, acc)
                hist.append(ht.pose.yaw)
        return ht, hist

    def test_turn_right_and_look_up(self):
        ht, _ = self._sim([((0, 0, 0), 2.5), ((0, 0, -30), 1.0)])
        self.assertAlmostEqual(ht.pose.yaw, 30, delta=0.5)
        ht, _ = self._sim([((0, 0, 0), 2.5), ((-20, 0, 0), 1.0)])
        self.assertAlmostEqual(ht.pose.pitch, 20, delta=0.5)

    def test_startup_spike_does_not_poison_bias(self):
        ht, hist = self._sim([((0, 0, 0), 2.5), ((0, 0, 0), 10.0)], spike_first=200)
        self.assertLess(abs(hist[-1]), 0.5, "yaw drifted while still")

    def test_motion_during_calibration_restarts_window(self):
        ht, hist = self._sim([((0, 0, 0), 0.5), ((0, 0, 25), 0.8), ((0, 0, 0), 2.5), ((0, 0, 0), 10.0)])
        self.assertIsNotNone(ht.gyro_bias)
        for got, want in zip(ht.gyro_bias, self.BIAS):
            self.assertAlmostEqual(got, want, delta=0.1)
        self.assertLess(abs(hist[-1]), 0.5)

    def test_tilted_mount_recenters_pitch(self):
        # like the real log: sensor sits ~20 deg pitched; looking straight must read pitch ~0
        ht, _ = self._sim([((0, 0, 0), 2.5)], tilt_deg=-20)
        self.assertAlmostEqual(ht.pose.pitch, 0, delta=0.5)
        ht, _ = self._sim([((0, 0, 0), 2.5), ((0, 0, -30), 1.0)], tilt_deg=-20)
        self.assertGreater(ht.pose.yaw, 25)


class TestDeviceWithFakeHid(unittest.TestCase):
    def test_end_to_end(self):
        import tests.fake_hid as fake
        import xrair.device as D
        D.hid = fake
        for win in (False, True):
            D.IS_WINDOWS = win
            fake.STATE.writes.clear(); fake.STATE.streaming = False
            g = D.AirGlasses()
            self.assertEqual(g.model, "XREAL Air 2")
            self.assertEqual(g.get_brightness(), fake.STATE.brightness)
            self.assertTrue(g.set_brightness(3))
            self.assertEqual(g.get_brightness(), 3)
            self.assertEqual(g.get_serial(), "TESTSERIAL123")
            g.start_imu(read_calibration=True)
            self.assertIn("IMU", g.calibration)
            got = []
            g.on_sample = got.append
            g.run_background(listen_mcu=False)
            time.sleep(0.3)
            g.close()
            self.assertGreater(len(got), 20)
            self.assertAlmostEqual(got[0].gyro_dps[2], -30.0, places=2)
            # nothing but documented commands was ever sent
            allowed_imu = {0x19, 0x14, 0x15, 0x1A}
            allowed_mcu = {0x03, 0x04, 0x07, 0x15, 0x26}
            for iface, w in fake.STATE.writes:
                if w[0] == 0xAA:
                    self.assertIn(w[7], allowed_imu)
                else:
                    self.assertIn(P.parse_mcu_packet(w).cmd_id, allowed_mcu)


if __name__ == "__main__":
    unittest.main()

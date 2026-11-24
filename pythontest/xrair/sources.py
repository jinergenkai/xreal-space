"""Pose sources for the demo app: real glasses, CSV replay, or mouse simulator."""
from __future__ import annotations

import csv
import threading
import time
from typing import Optional

from .fusion import HeadPose, HeadTracker

CSV_HEADER = ["t_ns", "gx_dps", "gy_dps", "gz_dps", "ax_g", "ay_g", "az_g", "mx", "my", "mz", "temp_c"]


class GlassesSource:
    name = "glasses"

    def __init__(self, tracker: HeadTracker, verbose=False, record_path: Optional[str] = None):
        from .device import AirGlasses
        self.tracker = tracker
        self.g = AirGlasses(verbose=verbose)
        self.events = []  # MCU events for UI
        self._rec = None
        if record_path:
            f = open(record_path, "w", newline="")
            self._rec = (f, csv.writer(f))
            self._rec[1].writerow(CSV_HEADER)
        self.g.on_sample = self._on_sample
        self.g.on_mcu_event = lambda p: self.events.append(p)
        self.brightness = self.g.get_brightness() if self.g.mcu else None
        self.g.start_imu(read_calibration=False)
        self.g.run_background()

    def _on_sample(self, s):
        self.tracker.feed(s.timestamp_ns, s.gyro_dps, s.accel_g)
        if self._rec:
            self._rec[1].writerow([s.timestamp_ns, *s.gyro_dps, *s.accel_g, *s.mag_raw, round(s.temperature_c, 2)])

    @property
    def pose(self) -> HeadPose:
        return self.tracker.pose

    def close(self):
        self.g.close()
        if self._rec:
            self._rec[0].close()


class ReplaySource:
    name = "replay"

    def __init__(self, tracker: HeadTracker, path: str, loop: bool = True):
        self.tracker = tracker
        self.events = []
        self.brightness = None
        with open(path, newline="") as f:
            rows = list(csv.DictReader(f))
        self.rows = [(int(r["t_ns"]), (float(r["gx_dps"]), float(r["gy_dps"]), float(r["gz_dps"])),
                      (float(r["ax_g"]), float(r["ay_g"]), float(r["az_g"]))) for r in rows]
        if not self.rows:
            raise ValueError("File replay rỗng")
        self._stop = threading.Event()
        self.loop = loop
        threading.Thread(target=self._run, daemon=True).start()

    def _run(self):
        while not self._stop.is_set():
            t0_dev = self.rows[0][0]
            t0 = time.perf_counter()
            for ts, g, a in self.rows:
                if self._stop.is_set():
                    return
                target = (ts - t0_dev) / 1e9
                while time.perf_counter() - t0 < target:
                    time.sleep(0.0005)
                self.tracker.feed(ts, g, a)
            if not self.loop:
                return
            # restart: reset timestamp continuity
            self.tracker._last_ts = None

    @property
    def pose(self) -> HeadPose:
        return self.tracker.pose

    def close(self):
        self._stop.set()


class MouseSource:
    """No hardware: drag with the mouse (or arrow keys) to move the 'head'."""
    name = "mouse"

    def __init__(self):
        self._pose = HeadPose()
        self.events = []
        self.brightness = None
        self.tracker = None

    def nudge(self, dyaw: float, dpitch: float):
        self._pose = HeadPose(self._pose.yaw + dyaw, max(-80, min(80, self._pose.pitch + dpitch)), 0)

    def recenter(self):
        self._pose = HeadPose()

    @property
    def pose(self) -> HeadPose:
        return self._pose

    def close(self):
        pass

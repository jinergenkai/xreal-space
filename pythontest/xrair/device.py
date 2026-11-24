"""
USB/HID access for XREAL Air family. Depends only on `hidapi` (pip install hidapi -> `import hid`).

Safety policy: this module only sends commands that the community has documented and that are
reversible (start/stop IMU stream, read calibration, read serial/firmware/brightness/display mode,
set brightness). Firmware/update commands are never sent.
"""
from __future__ import annotations

import json
import sys
import threading
import time
from dataclasses import dataclass
from typing import Callable, List, Optional

from . import protocol as P

try:
    import hid  # type: ignore
except ImportError:  # pragma: no cover
    hid = None

IS_WINDOWS = sys.platform.startswith("win")


class GlassesError(RuntimeError):
    pass


def require_hid():
    if hid is None:
        raise GlassesError("Thiếu thư viện hidapi. Chạy: pip install hidapi")


@dataclass
class HidInfo:
    path: bytes
    vid: int
    pid: int
    interface: int
    usage_page: int
    usage: int
    product: str
    manufacturer: str
    serial: str

    @property
    def model(self) -> str:
        return P.MODELS.get(self.pid, f"unknown PID 0x{self.pid:04x}")


def enumerate_glasses() -> List[HidInfo]:
    """List every HID interface with the XREAL vendor id. Read-only, never opens anything."""
    require_hid()
    out = []
    for d in hid.enumerate(P.VID, 0):
        out.append(HidInfo(
            path=d.get("path", b""),
            vid=d.get("vendor_id", 0),
            pid=d.get("product_id", 0),
            interface=d.get("interface_number", -1),
            usage_page=d.get("usage_page", 0),
            usage=d.get("usage", 0),
            product=d.get("product_string") or "",
            manufacturer=d.get("manufacturer_string") or "",
            serial=d.get("serial_number") or "",
        ))
    return sorted(out, key=lambda x: (x.pid, x.interface, x.usage_page))


class HidChannel:
    """One opened HID interface with the platform quirks handled."""

    def __init__(self, info: HidInfo, log: Callable[[str], None] = lambda s: None):
        require_hid()
        self.info = info
        self.log = log
        self.dev = hid.device()
        try:
            self.dev.open_path(info.path)
        except (IOError, OSError) as e:
            raise GlassesError(
                f"Không mở được interface {info.interface} ({e}). "
                "Đóng Nebula/app khác đang giữ kính rồi thử lại."
            )
        self.dev.set_nonblocking(0)
        self.lock = threading.Lock()

    def write(self, frame: bytes):
        # Windows HID writes need a leading report-id byte (0x00) for unnumbered reports.
        payload = (b"\x00" + frame) if IS_WINDOWS else frame
        with self.lock:
            n = self.dev.write(list(payload))
        if n is None or n < 0:
            raise GlassesError(f"Ghi HID thất bại trên interface {self.info.interface}")
        self.log(f"  -> if{self.info.interface}: {P.hexdump(P.trim(frame), 40)}")

    def read(self, timeout_ms: int = 250) -> bytes:
        data = self.dev.read(P.PACKET_SIZE + 1, timeout_ms)
        return bytes(data or b"")

    def close(self):
        try:
            self.dev.close()
        except Exception:
            pass


def _pick(infos: List[HidInfo], iface: int) -> Optional[HidInfo]:
    for i in infos:
        if i.interface == iface:
            return i
    return None


class AirGlasses:
    """High-level handle: IMU stream + MCU commands/events."""

    def __init__(self, verbose: bool = False):
        self.verbose = verbose
        infos = [i for i in enumerate_glasses() if i.pid in P.MODELS]
        if not infos:
            raise GlassesError(
                "Không thấy kính XREAL (VID 3318). Kiểm tra cáp, thử cổng USB khác, "
                "hoặc cổng không đủ điện."
            )
        self.pid = infos[0].pid
        self.model = P.MODELS[self.pid]
        imu_info = _pick(infos, P.imu_interface(self.pid))
        mcu_info = _pick(infos, P.mcu_interface(self.pid))
        if imu_info is None:
            raise GlassesError(
                f"Thấy {self.model} nhưng không thấy IMU interface "
                f"{P.imu_interface(self.pid)}. Các interface đang có: "
                f"{sorted({i.interface for i in infos})}"
            )
        self.imu = HidChannel(imu_info, self._log)
        self.mcu = HidChannel(mcu_info, self._log) if mcu_info else None
        self.calibration: Optional[dict] = None
        self._stop = threading.Event()
        self._threads: List[threading.Thread] = []
        self.on_sample: Optional[Callable[[P.ImuSample], None]] = None
        self.on_mcu_event: Optional[Callable[[P.McuPacket], None]] = None
        self.sample_count = 0
        self.crc_fail = 0

    def _log(self, s: str):
        if self.verbose:
            print(s)

    # ------------------------------------------------------------------ IMU
    def imu_command(self, cmd_id: int, data: bytes = b"", timeout_s: float = 1.0) -> Optional[bytes]:
        """Send one IMU command and wait for the reply echoing the same cmd id.
        Every command gets exactly one reply; data packets in between are skipped."""
        self.imu.write(P.build_imu_command(cmd_id, data))
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            pkt = self.imu.read(100)
            if not pkt:
                continue
            rep = P.parse_imu_reply(pkt)
            if rep and rep.cmd_id == cmd_id:
                self._log(f"  <- if{self.imu.info.interface}: reply 0x{cmd_id:02x} {P.hexdump(rep.data)}")
                return rep.data
        self._log(f"  !! timeout waiting reply 0x{cmd_id:02x}")
        return None

    def start_imu(self, read_calibration: bool = True):
        # The first command after opening can time out (known behaviour) -> retry once.
        if self.imu_command(P.IMU_CMD_STREAM, b"\x00") is None:
            self.imu_command(P.IMU_CMD_STREAM, b"\x00")
        if read_calibration:
            try:
                self.calibration = self.read_calibration()
            except Exception as e:  # calibration is optional for our use
                self._log(f"  calibration skipped: {e}")
        self.imu_command(P.IMU_CMD_STREAM, b"\x01")

    def read_calibration(self) -> Optional[dict]:
        raw = self.imu_command(P.IMU_CMD_CAL_LEN)
        if not raw or len(raw) < 4:
            return None
        total = int.from_bytes(raw[:4], "little")
        if not (0 < total < 200_000):
            return None
        buf = bytearray()
        for _ in range(total // 8 + 64):
            if len(buf) >= total:
                break
            part = self.imu_command(P.IMU_CMD_CAL_NEXT)
            if not part:
                break
            buf += part
        text = bytes(buf[:total]).decode("utf-8", errors="replace")
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            return {"_raw": text}

    def stop_imu(self):
        try:
            self.imu_command(P.IMU_CMD_STREAM, b"\x00", timeout_s=0.5)
        except Exception:
            pass

    def _imu_loop(self):
        while not self._stop.is_set():
            try:
                pkt = self.imu.read(250)
            except Exception as e:
                print(f"[IMU] lỗi đọc: {e}")
                break
            if not pkt:
                continue
            s = P.parse_imu_sample(pkt)
            if s is None:
                continue
            self.sample_count += 1
            if self.on_sample:
                self.on_sample(s)

    # ------------------------------------------------------------------ MCU
    def mcu_command(self, cmd_id: int, data: bytes = b"", timeout_s: float = 1.0) -> Optional[bytes]:
        if not self.mcu:
            raise GlassesError("Không có MCU interface")
        self.mcu.write(P.build_mcu_command(cmd_id, data))
        deadline = time.time() + timeout_s
        while time.time() < deadline:
            pkt = self.mcu.read(100)
            p = P.parse_mcu_packet(pkt) if pkt else None
            if not p:
                continue
            if p.cmd_id == cmd_id:
                self._log(f"  <- if{self.mcu.info.interface}: reply 0x{cmd_id:04x} {P.hexdump(p.data)}")
                return p.data
            if self.on_mcu_event:
                self.on_mcu_event(p)
        return None

    def get_serial(self) -> Optional[str]:
        d = self.mcu_command(P.MCU_GET_SERIAL)
        return d[1:].split(b"\x00")[0].decode(errors="replace") if d else None

    def get_firmware(self) -> Optional[str]:
        d = self.mcu_command(P.MCU_GET_FW_VERSION)
        return d[1:].split(b"\x00")[0].decode(errors="replace") if d else None

    def get_brightness(self) -> Optional[int]:
        d = self.mcu_command(P.MCU_GET_BRIGHTNESS)
        return d[1] if d and len(d) > 1 else None

    def set_brightness(self, level: int) -> bool:
        level = max(0, min(8, int(level)))
        d = self.mcu_command(P.MCU_SET_BRIGHTNESS, bytes([level]))
        return bool(d) and d[0] == 0

    def send_brightness_nowait(self, level: int):
        """Fire-and-forget (used while the background MCU loop owns reading)."""
        if self.mcu:
            self.mcu.write(P.build_mcu_command(P.MCU_SET_BRIGHTNESS, bytes([max(0, min(8, int(level)))])))

    def get_display_mode(self) -> Optional[int]:
        d = self.mcu_command(P.MCU_GET_DISPLAY_MODE)
        return d[1] if d and len(d) > 1 else None

    def _mcu_loop(self):
        while not self._stop.is_set():
            try:
                pkt = self.mcu.read(250) if self.mcu else b""
            except Exception:
                break
            p = P.parse_mcu_packet(pkt) if pkt else None
            if p and self.on_mcu_event:
                self.on_mcu_event(p)

    # ------------------------------------------------------------------ lifecycle
    def run_background(self, listen_mcu: bool = True):
        self._stop.clear()
        t = threading.Thread(target=self._imu_loop, daemon=True)
        t.start()
        self._threads.append(t)
        if listen_mcu and self.mcu:
            t2 = threading.Thread(target=self._mcu_loop, daemon=True)
            t2.start()
            self._threads.append(t2)

    def close(self):
        self._stop.set()
        for t in self._threads:
            t.join(timeout=1)
        self.stop_imu()
        self.imu.close()
        if self.mcu:
            self.mcu.close()

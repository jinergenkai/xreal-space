"""
XREAL Air / Air 2 IMU reader for Windows (pure ctypes, no hidapi needed).

Protocol (from the community driver wheaney/nrealAirLinuxDriver):
  - IMU lives on USB interface 3 (Air, Air 2, Air 2 Pro), 64-byte reports.
  - Start stream: 0xAA | crc32 (LE, over the rest) | len u16 LE (=3+n) | msgid u8 | data[n]
    msgid 0x19 with data [0x01] starts the IMU stream.
  - Sample report: signature 01 02, gyro in deg/s, accel in g.
"""
import ctypes
import ctypes.wintypes as wt
import binascii
import struct
import threading
import time
import math

import numpy as np

VID = 0x3318
IMU_IFACE = {0x0424: 3, 0x0428: 3, 0x0432: 3, 0x0426: 2}  # Air, Air 2, Air 2 Pro, Air 2 Ultra

# ---------------------------------------------------------------- Win32 HID
_hid = ctypes.WinDLL("hid")
_setupapi = ctypes.WinDLL("setupapi")
_k32 = ctypes.WinDLL("kernel32", use_last_error=True)


class GUID(ctypes.Structure):
    _fields_ = [("Data1", wt.DWORD), ("Data2", wt.WORD), ("Data3", wt.WORD), ("Data4", ctypes.c_ubyte * 8)]


class SP_DEVICE_INTERFACE_DATA(ctypes.Structure):
    _fields_ = [("cbSize", wt.DWORD), ("InterfaceClassGuid", GUID), ("Flags", wt.DWORD),
                ("Reserved", ctypes.c_void_p)]


_setupapi.SetupDiGetClassDevsW.restype = ctypes.c_void_p
_setupapi.SetupDiGetClassDevsW.argtypes = [ctypes.POINTER(GUID), wt.LPCWSTR, wt.HWND, wt.DWORD]
_setupapi.SetupDiEnumDeviceInterfaces.argtypes = [ctypes.c_void_p, ctypes.c_void_p, ctypes.POINTER(GUID),
                                                  wt.DWORD, ctypes.POINTER(SP_DEVICE_INTERFACE_DATA)]
_setupapi.SetupDiGetDeviceInterfaceDetailW.argtypes = [ctypes.c_void_p, ctypes.POINTER(SP_DEVICE_INTERFACE_DATA),
                                                       ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD),
                                                       ctypes.c_void_p]
_setupapi.SetupDiDestroyDeviceInfoList.argtypes = [ctypes.c_void_p]
_k32.CreateFileW.restype = wt.HANDLE
_k32.CreateFileW.argtypes = [wt.LPCWSTR, wt.DWORD, wt.DWORD, ctypes.c_void_p, wt.DWORD, wt.DWORD, wt.HANDLE]
_k32.ReadFile.argtypes = [wt.HANDLE, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD), ctypes.c_void_p]
_k32.WriteFile.argtypes = [wt.HANDLE, ctypes.c_void_p, wt.DWORD, ctypes.POINTER(wt.DWORD), ctypes.c_void_p]
_k32.CloseHandle.argtypes = [wt.HANDLE]
_k32.CancelIoEx.argtypes = [wt.HANDLE, ctypes.c_void_p]
_hid.HidD_GetPreparsedData.argtypes = [wt.HANDLE, ctypes.POINTER(ctypes.c_void_p)]
_hid.HidD_FreePreparsedData.argtypes = [ctypes.c_void_p]
_hid.HidP_GetCaps.argtypes = [ctypes.c_void_p, ctypes.c_void_p]

INVALID_HANDLE = wt.HANDLE(-1).value


def list_hid_paths():
    guid = GUID()
    _hid.HidD_GetHidGuid(ctypes.byref(guid))
    hdev = _setupapi.SetupDiGetClassDevsW(ctypes.byref(guid), None, None, 0x12)  # PRESENT | DEVICEINTERFACE
    paths = []
    i = 0
    while True:
        ifd = SP_DEVICE_INTERFACE_DATA()
        ifd.cbSize = ctypes.sizeof(ifd)
        if not _setupapi.SetupDiEnumDeviceInterfaces(hdev, None, ctypes.byref(guid), i, ctypes.byref(ifd)):
            break
        i += 1
        need = wt.DWORD()
        _setupapi.SetupDiGetDeviceInterfaceDetailW(hdev, ctypes.byref(ifd), None, 0, ctypes.byref(need), None)
        buf = ctypes.create_string_buffer(need.value)
        ctypes.cast(buf, ctypes.POINTER(wt.DWORD))[0] = 8 if ctypes.sizeof(ctypes.c_void_p) == 8 else 6
        if _setupapi.SetupDiGetDeviceInterfaceDetailW(hdev, ctypes.byref(ifd), buf, need, None, None):
            paths.append(ctypes.wstring_at(ctypes.addressof(buf) + 4))
    _setupapi.SetupDiDestroyDeviceInfoList(hdev)
    return paths


def find_imu_path():
    for p in list_hid_paths():
        lp = p.lower()
        if f"vid_{VID:04x}" not in lp:
            continue
        for pid, iface in IMU_IFACE.items():
            if f"pid_{pid:04x}" in lp and f"mi_{iface:02d}" in lp:
                return p, pid
    return None, None


def _open(path):
    h = _k32.CreateFileW(path, 0xC0000000, 0x3, None, 3, 0, None)  # RW, share RW, OPEN_EXISTING
    if h in (None, INVALID_HANDLE):
        raise OSError(f"CreateFile failed ({ctypes.get_last_error()}) for {path}")
    return h


def _caps(h):
    pp = ctypes.c_void_p()
    if not _hid.HidD_GetPreparsedData(h, ctypes.byref(pp)):
        return 65, 65
    caps = (ctypes.c_ushort * 64)()
    _hid.HidP_GetCaps(pp, caps)
    _hid.HidD_FreePreparsedData(pp)
    return caps[2], caps[3]  # InputReportByteLength, OutputReportByteLength


class HidDevice:
    def __init__(self, path):
        self.rh = _open(path)   # separate handles so a blocking read never stalls a write
        self.wh = _open(path)
        self.in_len, self.out_len = _caps(self.rh)

    def write(self, payload: bytes):
        buf = (b"\x00" + payload).ljust(self.out_len, b"\x00")[: self.out_len]
        n = wt.DWORD()
        if not _k32.WriteFile(self.wh, buf, len(buf), ctypes.byref(n), None):
            raise OSError(f"WriteFile failed ({ctypes.get_last_error()})")

    def read(self) -> bytes:
        buf = ctypes.create_string_buffer(self.in_len)
        n = wt.DWORD()
        if not _k32.ReadFile(self.rh, buf, self.in_len, ctypes.byref(n), None):
            raise OSError(f"ReadFile failed ({ctypes.get_last_error()})")
        return buf.raw[1:n.value]  # strip report id

    def close(self):
        for h in (self.rh, self.wh):
            _k32.CancelIoEx(h, None)
            _k32.CloseHandle(h)


# ---------------------------------------------------------------- protocol
def make_msg(msgid: int, data: bytes = b"") -> bytes:
    body = struct.pack("<HB", 3 + len(data), msgid) + data
    return b"\xAA" + struct.pack("<I", binascii.crc32(body) & 0xFFFFFFFF) + body


def _i24(b):
    return int.from_bytes(b, "little", signed=True)


def parse_sample(p: bytes):
    """Return (timestamp_ns, gyro_dps[3], accel_g[3]) or None."""
    if len(p) < 42 or p[0] != 0x01 or p[1] != 0x02:
        return None
    ts = struct.unpack_from("<Q", p, 4)[0]
    gm, gd = struct.unpack_from("<hi", p, 12)
    am, ad = struct.unpack_from("<hi", p, 27)
    if gd == 0 or ad == 0:
        return None
    g = [_i24(p[18 + 3 * i: 21 + 3 * i]) * gm / gd for i in range(3)]
    a = [_i24(p[33 + 3 * i: 36 + 3 * i]) * am / ad for i in range(3)]
    return ts, g, a


# ---------------------------------------------------------------- orientation
def _skew(v):
    return np.array([[0, -v[2], v[1]], [v[2], 0, -v[0]], [-v[1], v[0], 0]])


def _expm_so3(w):
    th = np.linalg.norm(w)
    if th < 1e-9:
        return np.eye(3) + _skew(w)
    k = _skew(w / th)
    return np.eye(3) + math.sin(th) * k + (1 - math.cos(th)) * (k @ k)


def _orthonormalize(R):
    u, _, vt = np.linalg.svd(R)
    return u @ vt


class ImuTracker:
    """
    Reads the IMU in a background thread and integrates orientation.

    R maps body(IMU) vectors to world, world = body frame at the last reset().
    Gyro bias is estimated while the glasses are still; gravity corrects pitch/roll
    drift (Mahony-style). Yaw drifts slowly, use reset() to recenter.
    """

    def __init__(self, kp=0.5):
        path, pid = find_imu_path()
        if not path:
            raise RuntimeError("XREAL IMU HID interface not found. Glasses plugged in?")
        self.pid = pid
        self.dev = HidDevice(path)
        self.kp = kp
        self.lock = threading.Lock()
        self.R = np.eye(3)
        self.up_world = None
        self.bias = np.zeros(3)
        self.bias_ready = False
        self._bias_acc = []
        self.last_gyro = np.zeros(3)   # bias-corrected, rad/s, body frame
        self.last_accel = np.zeros(3)
        self.rate = 0.0
        self.samples = 0
        self.gesture_integral = None   # for calibration gestures
        self._still_since = None
        self._running = True
        self._reset_req = True
        self.dev.write(make_msg(0x19, b"\x01"))  # start IMU stream
        self.thread = threading.Thread(target=self._loop, daemon=True)
        self.thread.start()

    # --- public
    def reset(self):
        self._reset_req = True

    def recalibrate_bias(self):
        with self.lock:
            self.bias_ready = False
            self._bias_acc = []

    def orientation(self):
        with self.lock:
            return self.R.copy()

    def start_gesture(self):
        with self.lock:
            self.gesture_integral = np.zeros(3)
            self._gesture_best = np.zeros(3)

    def end_gesture(self):
        with self.lock:
            best = self._gesture_best.copy()
            self.gesture_integral = None
            return best

    def close(self):
        self._running = False
        try:
            self.dev.write(make_msg(0x19, b"\x00"))
        except OSError:
            pass
        self.dev.close()

    # --- worker
    def _loop(self):
        last_ts = None
        t_rate, n_rate = time.perf_counter(), 0
        while self._running:
            try:
                pkt = self.dev.read()
            except OSError:
                if self._running:
                    time.sleep(0.01)
                continue
            s = parse_sample(pkt)
            if not s:
                continue
            ts, g, a = s
            g = np.radians(np.array(g))
            a = np.array(a)
            dt = (ts - last_ts) * 1e-9 if last_ts is not None else 0.0
            last_ts = ts
            if not (0 < dt < 0.05):
                dt = 0.0
            n_rate += 1
            now = time.perf_counter()
            if now - t_rate > 1.0:
                self.rate = n_rate / (now - t_rate)
                t_rate, n_rate = now, 0
            self._update(g, a, dt)

    def _update(self, g, a, dt):
        with self.lock:
            self.samples += 1
            an = np.linalg.norm(a)
            if not self.bias_ready:
                self._bias_acc.append(g)
                if len(self._bias_acc) >= 1000:  # ~1 s at 1 kHz
                    self.bias = np.mean(self._bias_acc, axis=0)
                    self.bias_ready = True
                    self._reset_req = True
                return
            w = g - self.bias
            # slow online bias tracking while clearly still
            if np.linalg.norm(w) < math.radians(1.5) and abs(an - 1.0) < 0.05:
                self._still_since = self._still_since or time.perf_counter()
                if time.perf_counter() - self._still_since > 0.5:
                    self.bias += 0.002 * w
            else:
                self._still_since = None
            self.last_gyro = w
            self.last_accel = a
            if self._reset_req and an > 0.5:
                self.R = np.eye(3)
                self.up_world = a / an
                self._reset_req = False
            if self.gesture_integral is not None:
                self.gesture_integral += w * dt
                if np.linalg.norm(self.gesture_integral) > np.linalg.norm(self._gesture_best):
                    self._gesture_best = self.gesture_integral.copy()
            if dt <= 0 or self.up_world is None:
                return
            corr = np.zeros(3)
            if 0.8 < an < 1.2:
                up_body = self.R.T @ self.up_world
                corr = self.kp * np.cross(a / an, up_body)
            self.R = self.R @ _expm_so3((w + corr) * dt)
            if self.samples % 500 == 0:
                self.R = _orthonormalize(self.R)


if __name__ == "__main__":
    # Quick console test: prints rate + integrated angles.
    t = ImuTracker()
    print(f"IMU opened (pid 0x{t.pid:04x}, in={t.dev.in_len} out={t.dev.out_len}). Keep glasses still for 1 s...")
    try:
        while True:
            time.sleep(0.25)
            R = t.orientation()
            # rough euler (body axes) for eyeballing
            ang = np.degrees(np.array([math.atan2(R[2, 1], R[2, 2]), -math.asin(max(-1, min(1, R[2, 0]))),
                                       math.atan2(R[1, 0], R[0, 0])]))
            print(f"rate {t.rate:6.0f} Hz  bias_ready={t.bias_ready}  "
                  f"gyro(dps) {np.degrees(t.last_gyro).round(1)}  accel(g) {t.last_accel.round(2)}  "
                  f"angles xyz {ang.round(1)}", flush=True)
    except KeyboardInterrupt:
        t.close()

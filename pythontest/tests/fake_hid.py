"""A fake `hid` module that behaves like XREAL Air 2 firmware, for offline tests."""
import builtins, struct, time
from xrair import protocol as P

CAL = b'{"IMU":{"device_1":{"gyro_bias":[0,0,0],"accel_bias":[0,0,0]}}}'


def make_imu_packet(ts_ns, gyro_dps, accel_g, mult=1, div=1000):
    pkt = bytearray(64)
    pkt[0], pkt[1] = 0x01, 0x02
    struct.pack_into("<h", pkt, 2, int((30 - 25) * 132.48))
    struct.pack_into("<Q", pkt, 4, ts_ns)
    def i24(off, v):
        v = int(round(v)) & 0xFFFFFF
        pkt[off:off + 3] = bytes((v & 0xFF, (v >> 8) & 0xFF, (v >> 16) & 0xFF))
    struct.pack_into("<HI", pkt, 12, mult, div)
    for k, off in builtins.enumerate((18, 21, 24)):
        i24(off, gyro_dps[k] * div / mult)
    struct.pack_into("<HI", pkt, 27, mult, div * 100)
    for k, off in builtins.enumerate((33, 36, 39)):
        i24(off, accel_g[k] * div * 100 / mult)
    return bytes(pkt)


class _State:
    streaming = False
    brightness = 5
    writes = []

STATE = _State()


def _imu_reply(cmd, data):
    pkt = bytearray(P.build_imu_command(cmd, data))
    return bytes(pkt)


class device:
    def __init__(self):
        self.q = []
        self.iface = None
        self.t0 = time.time()
        self.ts = 0

    def open_path(self, path):
        self.iface = int(path.decode().split("_")[-1])

    def set_nonblocking(self, v):
        pass

    def write(self, data):
        data = bytes(data)
        if data[0] == 0x00:          # windows report id
            data = data[1:]
        STATE.writes.append((self.iface, data))
        if self.iface == 3 and data[0] == 0xAA:
            assert P.verify_crc(data), "bad IMU crc"
            cmd = data[7]
            if cmd == 0x19:
                STATE.streaming = data[8] == 1
                self.q.append(_imu_reply(0x19, b""))
            elif cmd == 0x14:
                self.q.append(_imu_reply(0x14, struct.pack("<I", len(CAL))))
            elif cmd == 0x15:
                sent = sum(1 for i, w in STATE.writes if i == 3 and w[7] == 0x15) - 1
                self.q.append(_imu_reply(0x15, CAL[sent * 40:(sent + 1) * 40]))
        elif self.iface == 4 and data[0] == 0xFD:
            assert P.verify_crc(data), "bad MCU crc"
            p = P.parse_mcu_packet(data)
            if p.cmd_id == P.MCU_GET_BRIGHTNESS:
                self.q.append(P.build_mcu_command(p.cmd_id, bytes([0, STATE.brightness])))
            elif p.cmd_id == P.MCU_SET_BRIGHTNESS:
                STATE.brightness = p.data[0]
                self.q.append(P.build_mcu_command(p.cmd_id, b"\x00"))
            elif p.cmd_id == P.MCU_GET_SERIAL:
                self.q.append(P.build_mcu_command(p.cmd_id, b"\x00TESTSERIAL123"))
            else:
                self.q.append(P.build_mcu_command(p.cmd_id, b"\x00\x01"))
        return len(data)

    def read(self, n, timeout_ms=0):
        if self.q:
            return list(self.q.pop(0))
        if self.iface == 3 and STATE.streaming:
            time.sleep(0.001)
            self.ts += 1_000_000
            return list(make_imu_packet(self.ts, (0.0, 0.0, -30.0), (0.0, 0.0, 1.0)))
        time.sleep(min(timeout_ms, 5) / 1000)
        return []

    def close(self):
        pass


def enumerate(vid=0, pid=0):
    return [dict(path=f"fake_{i}".encode(), vendor_id=0x3318, product_id=0x0428, interface_number=i,
                 usage_page=0xFF00, usage=1, product_string="XREAL Air 2", manufacturer_string="XREAL",
                 serial_number="") for i in (0, 1, 2, 3, 4)]

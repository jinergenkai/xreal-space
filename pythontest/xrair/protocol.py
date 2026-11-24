"""
Pure protocol layer for XREAL Air / Air 2 / Air 2 Pro (no I/O here, fully unit-testable).

Knowledge sources (all MIT licensed, used as reference only):
  - badicsalex/ar-drivers-rs   src/nreal_air.rs
  - MSmithDev/AirAPI_Windows   AirAPI_Windows.cpp
  - BladeRunner28/XrealBeam    README "Protocol" (ground-truth USB frames)
  - thejackimonster/xreal-imu  (original reverse engineering)

Two HID interfaces matter (Air / Air 2 / Air 2 Pro):
  interface 3 = IMU  -> frames start with 0xAA (commands/replies) or 0x01 0x02 (sensor data)
  interface 4 = MCU  -> frames start with 0xFD (commands/replies/events)
(Air 2 Ultra uses interface 2 for IMU and 0 for MCU.)
"""
from __future__ import annotations

import struct
import zlib
from dataclasses import dataclass, field
from typing import List, Optional

VID = 0x3318
MODELS = {
    0x0424: "XREAL Air",
    0x0428: "XREAL Air 2",
    0x0432: "XREAL Air 2 Pro",
    0x0426: "XREAL Air 2 Ultra",
}
PACKET_SIZE = 64


def imu_interface(pid: int) -> int:
    return 2 if pid == 0x0426 else 3


def mcu_interface(pid: int) -> int:
    return 0 if pid == 0x0426 else 4


def crc32(buf: bytes) -> int:
    """Standard reflected CRC-32 (same as zlib). Verified against frames captured on hardware."""
    return zlib.crc32(bytes(buf)) & 0xFFFFFFFF


# --------------------------------------------------------------------------- IMU interface

# IMU command ids (interface 3)
IMU_CMD_STREAM = 0x19       # data [0] = stop, [1] = start
IMU_CMD_CAL_LEN = 0x14      # reply: u32 LE length of calibration JSON
IMU_CMD_CAL_NEXT = 0x15     # reply: next chunk of calibration JSON
IMU_CMD_STATIC_ID = 0x1A    # reply: 4 bytes


def build_imu_command(cmd_id: int, data: bytes = b"") -> bytes:
    """[0]=0xAA [1..4]=CRC32 LE [5..6]=length LE [7]=cmd [8..]=data, padded to 64 bytes.
    length = 3 + len(data); CRC over bytes[5 : 5+length]."""
    length = 3 + len(data)
    body = struct.pack("<HB", length, cmd_id) + bytes(data)
    pkt = bytearray(PACKET_SIZE)
    pkt[0] = 0xAA
    pkt[5:5 + len(body)] = body
    pkt[1:5] = struct.pack("<I", crc32(pkt[5:5 + length]))
    return bytes(pkt)


@dataclass
class ImuReply:
    cmd_id: int
    data: bytes


def parse_imu_reply(pkt: bytes) -> Optional[ImuReply]:
    if len(pkt) < 8 or pkt[0] != 0xAA:
        return None
    length = struct.unpack_from("<H", pkt, 5)[0]
    data_len = max(0, length - 3)
    end = 8 + data_len
    if end > len(pkt):
        return None
    return ImuReply(cmd_id=pkt[7], data=bytes(pkt[8:end]))


def _i24(buf: bytes, off: int) -> int:
    v = buf[off] | (buf[off + 1] << 8) | (buf[off + 2] << 16)
    return v - (1 << 24) if v & 0x800000 else v


@dataclass
class ImuSample:
    timestamp_ns: int
    gyro_dps: tuple          # raw sensor axes, degrees/second
    accel_g: tuple           # raw sensor axes, g
    mag_raw: tuple           # raw counts (experimental, not used for fusion)
    temperature_c: float


def is_imu_data(pkt: bytes) -> bool:
    return len(pkt) >= 42 and pkt[0] == 0x01 and pkt[1] == 0x02


def parse_imu_sample(pkt: bytes) -> Optional[ImuSample]:
    """Parse a 64-byte sensor report (signature 0x01 0x02).

    Offsets: temp i16 @2 | ts u64 @4 (ns) | gyro mult u16 @12, div u32 @14, xyz i24 @18/21/24
             accel mult u16 @27, div u32 @29, xyz i24 @33/36/39 | mag @42.. (experimental)
    value = raw * mult / div  (gyro in deg/s, accel in g)
    """
    if not is_imu_data(pkt):
        return None
    temp_raw = struct.unpack_from("<h", pkt, 2)[0]
    ts = struct.unpack_from("<Q", pkt, 4)[0]
    g_mul = struct.unpack_from("<H", pkt, 12)[0]
    g_div = struct.unpack_from("<I", pkt, 14)[0] or 1
    gyro = tuple(_i24(pkt, o) * g_mul / g_div for o in (18, 21, 24))
    a_mul = struct.unpack_from("<H", pkt, 27)[0]
    a_div = struct.unpack_from("<I", pkt, 29)[0] or 1
    accel = tuple(_i24(pkt, o) * a_mul / a_div for o in (33, 36, 39))
    mag = (0, 0, 0)
    if len(pkt) >= 54:
        mag = struct.unpack_from("<hhh", pkt, 48)
    return ImuSample(
        timestamp_ns=ts,
        gyro_dps=gyro,
        accel_g=accel,
        mag_raw=mag,
        temperature_c=temp_raw / 132.48 + 25.0,
    )


# --------------------------------------------------------------------------- MCU interface

# MCU command ids (interface 4). Only safe, documented commands are listed on purpose.
MCU_GET_BRIGHTNESS = 0x03
MCU_SET_BRIGHTNESS = 0x04    # data [level 0..8]
MCU_GET_DISPLAY_MODE = 0x07
MCU_SET_DISPLAY_MODE = 0x08  # NOT used by default tools (display re-enumerates)
MCU_GET_SERIAL = 0x15
MCU_GET_FW_VERSION = 0x26

MCU_EVT_KEY = 0x6C05         # data[0] = key code
MCU_EVT_ERROR = 0x6C09
MCU_EVT_PANEL = 0x6C18       # data[0] = 0 panel off / 1 panel on

KEY_NAMES = {1: "power/menu?", 2: "brightness+ (up)", 3: "brightness- (down)"}  # tentative

DISPLAY_MODES = {
    1: "2D mirror 60Hz", 3: "3D SBS 60Hz", 4: "3D SBS 72Hz", 5: "2D 72Hz",
    8: "half SBS 60Hz", 9: "3D SBS 90Hz", 10: "2D 90Hz", 11: "2D 120Hz",
}

# Layout (64 bytes): head u8 | crc u32 | length u16 | request_id u32 | timestamp u32 |
#                    cmd u16 | reserved 5 | data 42      length = 17 + len(data)
_MCU_HDR = struct.Struct("<BIHIIH5s")


def build_mcu_command(cmd_id: int, data: bytes = b"", request_id: int = 0x1337) -> bytes:
    if len(data) > 42:
        raise ValueError("MCU data too long")
    length = 17 + len(data)
    pkt = bytearray(PACKET_SIZE)
    _MCU_HDR.pack_into(pkt, 0, 0xFD, 0, length, request_id, 0, cmd_id, b"\x00" * 5)
    pkt[22:22 + len(data)] = data
    struct.pack_into("<I", pkt, 1, crc32(pkt[5:5 + length]))
    return bytes(pkt)


@dataclass
class McuPacket:
    cmd_id: int
    data: bytes = field(default=b"")


def parse_mcu_packet(pkt: bytes) -> Optional[McuPacket]:
    if len(pkt) < 22 or pkt[0] != 0xFD:
        return None
    length = struct.unpack_from("<H", pkt, 5)[0]
    cmd = struct.unpack_from("<H", pkt, 15)[0]
    n = max(0, min(length - 17, len(pkt) - 22))
    return McuPacket(cmd_id=cmd, data=bytes(pkt[22:22 + n]))


def verify_crc(pkt: bytes) -> bool:
    """Works for both 0xAA (IMU) and 0xFD (MCU) frames."""
    if len(pkt) < 7:
        return False
    length = struct.unpack_from("<H", pkt, 5)[0]
    if 5 + length > len(pkt):
        return False
    return struct.unpack_from("<I", pkt, 1)[0] == crc32(pkt[5:5 + length])


def hexdump(b: bytes, n: int = 24) -> str:
    return " ".join(f"{x:02x}" for x in b[:n]) + (" ..." if len(b) > n else "")


def trim(frame: bytes) -> bytes:
    """Strip zero padding of a command frame for logging (8+data for IMU, 22+data for MCU)."""
    if frame and frame[0] == 0xAA:
        return frame[: 5 + struct.unpack_from("<H", frame, 5)[0]]
    if frame and frame[0] == 0xFD:
        return frame[: 5 + struct.unpack_from("<H", frame, 5)[0]]
    return frame


def split_ids(data: bytes) -> List[int]:  # small helper for debug printing
    return list(data)

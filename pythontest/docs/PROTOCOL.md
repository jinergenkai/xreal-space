# Giao thức USB của XREAL Air / Air 2 / Air 2 Pro

Tài liệu nội bộ của dự án. Tổng hợp từ các nguồn open-source license MIT (liệt kê cuối file) và
**đã đối chiếu byte-for-byte** với các frame được bắt từ phần cứng thật (xem `tests/test_protocol.py`).

## Hình dung nhanh

Kính là một "màn hình có tai trong":
- **Màn hình** nhận hình qua DisplayPort Alt Mode (như monitor thường) — không cần driver.
- **Loa + mic** là thiết bị USB Audio chuẩn — không cần driver.
- **IMU** (tai trong: gyroscope + accelerometer + magnetometer) và **MCU** (người gác cổng: nút bấm,
  độ sáng, chế độ 2D/3D) nói chuyện qua **USB HID** — đây là phần app phải tự "nói đúng ngôn ngữ".

## Định danh USB

| Model | VID | PID | IMU interface | MCU interface |
|---|---|---|---|---|
| XREAL Air | 0x3318 | 0x0424 | 3 | 4 |
| **XREAL Air 2** | 0x3318 | **0x0428** | **3** | **4** |
| XREAL Air 2 Pro | 0x3318 | 0x0432 | 3 | 4 |
| XREAL Air 2 Ultra | 0x3318 | 0x0426 | 2 | 0 |

Mọi report dài 64 byte. **Windows:** khi ghi phải thêm 1 byte report-id `0x00` ở đầu (65 byte).

## Checksum

CRC-32 chuẩn (giống `zlib.crc32`), tính trên `frame[5 : 5 + length]`, ghi little-endian vào `frame[1:5]`.

## IMU interface (frame bắt đầu bằng `0xAA`)

Lệnh gửi đi:
```
[0] 0xAA | [1..4] CRC32 LE | [5..6] length LE | [7] cmd | [8..] data      length = 3 + len(data)
```
Mỗi lệnh được trả lời đúng **một** frame `0xAA` có cùng `cmd`.

| cmd | data | ý nghĩa | frame thật |
|---|---|---|---|
| 0x19 | 00 | dừng stream IMU | `aa 53 e1 26 35 04 00 19 00` |
| 0x19 | 01 | **bật stream IMU** | `aa c5 d1 21 42 04 00 19 01` |
| 0x14 | – | độ dài calibration JSON (u32) | `aa 36 b3 dd e7 03 00 14` |
| 0x15 | – | đọc tiếp 1 đoạn calibration | `aa a0 83 da 90 03 00 15` |
| 0x1A | – | static id (4 byte) | `aa 31 9e 65 00 03 00 1a` |

Lưu ý: lệnh đầu tiên sau khi mở thiết bị có thể timeout — gửi lại là được.

### Gói dữ liệu cảm biến (bắt đầu bằng `0x01 0x02`)

| offset | kiểu | trường |
|---|---|---|
| 2 | i16 | nhiệt độ: raw / 132.48 + 25 (°C) |
| 4 | u64 | timestamp (ns) |
| 12 / 14 | u16 / u32 | gyro mult / div |
| 18, 21, 24 | i24 ×3 | gyro x y z → `raw * mult / div` = độ/giây |
| 27 / 29 | u16 / u32 | accel mult / div |
| 33, 36, 39 | i24 ×3 | accel x y z → đơn vị g (để yên ≈ 1g trên trục hướng lên) |
| 42.. | – | magnetometer (chưa giải mã chắc chắn, chưa dùng) |

Tần số: ~1000 Hz trên tài liệu; một bản port đo được ~489 Hz khi đọc blocking đơn giản.

## MCU interface (frame bắt đầu bằng `0xFD`)

```
[0] 0xFD | [1..4] CRC32 | [5..6] length | [7..10] request_id | [11..14] timestamp |
[15..16] cmd u16 | [17..21] reserved | [22..] data (≤42)            length = 17 + len(data)
```

| cmd | data | ý nghĩa | trạng thái trong repo |
|---|---|---|---|
| 0x03 | – | đọc độ sáng (reply data[1]) | dùng |
| 0x04 | [0..8] | đặt độ sáng | dùng |
| 0x07 | – | đọc display mode | dùng |
| 0x08 | [mode] | đặt display mode (1=2D, 3=SBS, 9=SBS 90Hz, 11=2D 120Hz) | **chưa dùng** (màn hình reconnect) |
| 0x15 | – | serial number | dùng |
| 0x26 | – | firmware version | dùng |
| 0x6C05 | event | **bấm nút**: data[0] = mã nút | nghe |
| 0x6C18 | event | panel tắt (0) / bật (1) khi đổi mode | nghe |
| 0x6C09 | event | lỗi (chuỗi text) | nghe |

**Không bao giờ** gửi lệnh firmware/update từ repo này.

## Đã kiểm chứng trên kính thật (Air 2, firmware 09.1.00.180_20240507, Windows, 11/2026)

- Nhận diện PID 0x0428; Windows liệt kê interface 3 (IMU), 4 (MCU), 5, và 6 (usage page 0x000C =
  Consumer Control, nhiều khả năng là nút âm lượng). Mở được cả 4 interface bằng hidapi, không driver.
- Handshake IMU (stop → calibration → start) chạy; calibration JSON đọc được.
- Tốc độ IMU thực tế **~1000 Hz**; |accel| khi để yên ≈ 1.01 g.
- Lệnh MCU đọc serial, firmware, độ sáng, display mode đều trả lời.
- Với `--forward -y`: quay phải → yaw tăng, ngẩng lên → pitch tăng (đúng dấu, không cần flip).
- Khi người đeo nhìn thẳng, cảm biến nghiêng ~20° xuống (accel ≈ (-0.02, 0.37, 0.95)) → phải bù pitch khi recenter.
- **Bài học:** các gói IMU ngay sau khi bật stream có thể nhiễu; bias gyro đo lẫn các gói này (hoặc lúc đầu
  đang cử động) gây trôi yaw ~11°/s. Từ v0.2: bỏ 0.3s đầu, chỉ nhận hiệu chỉnh khi đứng yên thật,
  và tinh chỉnh bias liên tục mỗi khi đầu đứng yên.

## Việc còn mở (cần kiểm chứng trên kính thật)

- Mã nút cụ thể của Air 2 (2 = sáng+, 3 = sáng- theo AirAPI; nút còn lại chưa rõ).
- Trục cảm biến so với đầu người đeo → dùng `python -m xrair axes`.
- Wear sensor (biết đang đeo/tháo) có báo qua MCU event không.
- Magnetometer.

## Nguồn tham khảo (MIT)

- badicsalex/ar-drivers-rs — `src/nreal_air.rs`
- MSmithDev/AirAPI_Windows
- BladeRunner28/XrealBeam — README phần Protocol (frame bắt từ phần cứng)
- thejackimonster/xreal-imu (nghiên cứu gốc)
- TAconsel/xr-display-mode (ghi chú MCU, dải độ sáng 0–8)

Không dùng code GPL (ví dụ XRLinuxDriver).

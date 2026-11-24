# xr-air-lab

PoC bằng Python để **tự điều khiển kính XREAL Air 2 mà không cần app chính hãng (Nebula)**:
đọc cảm biến xoay đầu (IMU), nghe nút bấm, chỉnh độ sáng, và hiển thị các panel "ghim" trong không gian.

> Hình dung: kính là một cái màn hình có "tai trong". App này nghe tai trong đó để biết bạn quay đầu
> hướng nào, rồi dịch hình ngược lại, nên panel trông như đứng yên trong phòng.

## Cài đặt (nhẹ, ~1 MB ngoài Python)

Cần Python ≥ 3.9 (bản cài từ python.org trên Windows đã có sẵn `tkinter`).

```bash
pip install hidapi
```

Không cần driver. Windows tự nhận kính là monitor + USB audio + HID.

## Các lệnh

| Lệnh | Làm gì | Gửi lệnh tới kính? |
|---|---|---|
| `python -m xrair doctor` | kiểm tra Python, hidapi, màn hình, kính | không |
| `python -m xrair probe` | liệt kê HID interface của kính, thử mở | không |
| `python -m xrair info` | serial, firmware, độ sáng, display mode, tốc độ IMU | chỉ lệnh đọc + bật/tắt stream IMU |
| `python -m xrair imu --raw` | in yaw/pitch/roll realtime | như trên |
| `python -m xrair imu --record rec.csv --seconds 30` | ghi raw IMU để replay | như trên |
| `python -m xrair axes` | dò trục cảm biến, gợi ý `--forward` | như trên |
| `python -m xrair mcu --listen 10` | nghe sự kiện nút bấm 10 giây | đọc độ sáng |
| `python -m xrair mcu --set-brightness 6` | đổi độ sáng (0–8, đổi lại được) | có (lệnh đã tài liệu hóa) |
| `python -m xrair demo` | app panel ghim, chạy full-screen trên kính | có |
| `python -m xrair demo --source mouse --windowed` | demo **không cần kính** (kéo chuột = quay đầu) | không |
| `python -m xrair demo --source replay --replay tools/sample_head_motion.csv --windowed` | phát lại chuyển động đã ghi | không |

Thêm `-v` trước tên lệnh (`python -m xrair -v info`) để in mọi frame gửi/nhận.

### Phím trong demo
`R` recenter · `M` đổi chế độ (Ghim trong không gian / Đi theo đầu / Thu nhỏ ở góc) · `+ -` zoom ·
`Tab` chọn panel · `Shift + mũi tên` dời panel · `[ ]` độ sáng kính · `H` ẩn trợ giúp · `Esc` thoát

## Cắm kính vào đâu?

| Cách cắm | Có hình trên kính | Đọc IMU / nút / mic |
|---|---|---|
| Laptop có USB-C xuất hình (ThinkPad, MacBook...) | có | có |
| Laptop chỉ có USB-A (qua cáp C→A) | **không** | có thể (nếu cổng đủ điện) |
| Adapter HDMI → USB-C cho kính AR | có | **không** (adapter chỉ truyền hình) |
| Điện thoại Android có DP Alt Mode / Beam Pro | có | có (bản Android sau này) |

Không có hình thì vẫn dev được: chạy `demo --windowed`, cửa sổ trên laptop đóng vai màn hình kính.

## Cấu trúc

```
xrair/protocol.py   giao thức (thuần, test được)      docs/PROTOCOL.md   tài liệu giao thức
xrair/device.py     mở HID, IMU handshake, MCU        tests/             unit test + kính giả lập
xrair/fusion.py     Madgwick + yaw/pitch/roll         tools/             file chuyển động mẫu
xrair/demo.py       app panel (tkinter)               GEMINI_TEST.md     kịch bản test cho Gemini
```

Chạy test (không cần kính): `python -m unittest -v tests.test_protocol`

## Giới hạn hiện tại

- Chỉ xoay đầu (3DoF); Air 2 không có camera nên không ghim theo vị trí di chuyển.
- Panel mới là text; hiển thị web, voice, map là bước sau (bản Android / Tauri).
- Gyro trôi chậm theo thời gian → bấm `R` để recenter.

## License & nguồn

MIT. Protocol tham khảo từ các dự án MIT: ar-drivers-rs, AirAPI_Windows, XrealBeam, xreal-imu.
Không dùng code GPL. Một phần protocol do cộng đồng reverse-engineer; dự án không liên kết với XREAL.

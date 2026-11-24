# XREAL Air 2 – Spatial Screens Prototype (Python, Windows)

Prototype kiểm chứng: hiển thị nhiều màn hình ảo **cố định trong không gian** trên kính XREAL Air 2
(quay đầu màn hình vẫn đứng yên), lớp HUD (bản đồ đi theo người, giờ/pin, thông báo) và thao tác
bằng cử chỉ đầu. **Đây là code thử nghiệm**, code sản phẩm thật sẽ viết lại ở repo khác
(Android / Rust). Xem lộ trình tính năng và gói sản phẩm trong [docs/FEATURES.md](docs/FEATURES.md).
Slide pitch (riêng tư, cần Share mới mở được): https://claude.ai/artifact/XqayN75dyCchRNk24DQou2

## Kết quả đã kiểm chứng (23/11/2026)

| Hạng mục | Kết quả | Ghi chú |
|---|---|---|
| Đọc IMU Air 2 qua USB HID | ✅ 1000 Hz | ctypes thuần, không cần hidapi |
| Head tracking 3DoF | ✅ Màn hình đứng yên khi quay đầu | Gyro + hiệu chỉnh trọng lực; hướng ngang trôi dần |
| Hiệu chỉnh trục tự động | ✅ Wizard 2 cử chỉ (quay trái, cúi xuống) | Lưu vào `config.json` |
| Chụp desktop thật | ✅ ~30 fps/màn (dxcam), ~15 fps/cửa sổ (PrintWindow) | Có vẽ con trỏ chuột |
| Render | ✅ 60 fps (vsync) | pygame + moderngl |
| 3 màn hình cùng lúc | ✅ laptop + màn phụ + 1 cửa sổ app | Kính phải ở chế độ **Extend** |
| HUD bản đồ (lazy-follow) | ✅ góc dưới trái, tuyến đường giả | Chưa có GPS thật |
| Giờ / pin / thông báo (head-locked) | ✅ | |
| Gật / lắc đầu | ⚠️ Đã code, **chưa thử kỹ khi đeo** | Ngưỡng 80–90°/s |
| Chuột theo màn đang nhìn | ❌ Chưa làm | |
| Giọng nói | ❌ Chưa làm (để bản thật, hướng LLM gọi hàm) | |

## Chạy thử

Yêu cầu: Windows 10/11, Python 3.12+ (đã test 3.15), kính Air 2 cắm USB-C (DP Alt mode).

```powershell
py -m pip install -r requirements.txt
```

1. Nhấn **Win+P → Extend** (kính phải là màn hình riêng; ở chế độ Duplicate app sẽ báo lỗi).
2. Chạy:

```powershell
py app.py
```

3. Lần đầu: làm theo chữ hiện trên kính để hiệu chỉnh (giữ yên → quay trái → về giữa → cúi xuống → về giữa).

Các tùy chọn:
- `--window`: xem thử trong cửa sổ trên laptop, không cần chế độ Extend.
- `--still`: bỏ qua cảm biến, luôn nhìn thẳng (để xem bố cục).
- `--snap`: lưu khung hình ra `snap.png` sau 3 giây.
- `--notif`: hiện thông báo mẫu ngay (dùng chung với `--snap`).

Đọc IMU riêng (in tốc độ, gyro, gia tốc):

```powershell
py xreal_imu.py
```

### Phím tắt (toàn cục, Ctrl+Alt+…)

| Phím | Tác dụng |
|---|---|
| R | Đưa màn hình về trước mặt |
| W | Chế độ đi bộ (ẩn màn lớn, giữ bản đồ + thông tin) |
| M | Bật/tắt bản đồ |
| Q | Thoát |
| ↑ / ↓ | Đẩy màn hình ra xa / kéo lại gần |
| [ / ] | Chỉnh FOV cho khớp kích thước thật |
| F | So sánh world-locked / head-locked |
| H | Bật/tắt dòng trạng thái |
| N | Gọi thông báo mẫu |
| C | Hiệu chỉnh lại trục |
| B | Đo lại độ lệch gyro (giữ yên 1 giây) |

Cử chỉ đầu: **gật** = đồng ý (hoặc bật/tắt bản đồ khi không có thông báo), **lắc** = bỏ qua.

## Cấu trúc

| File | Nội dung |
|---|---|
| `xreal_imu.py` | Đọc HID trên Windows (SetupAPI + CreateFile), giao thức IMU, bộ lọc hướng (`ImuTracker`) |
| `app.py` | Tìm màn hình kính, chụp màn hình/cửa sổ, render màn hình ảo, wizard hiệu chỉnh, phím tắt |
| `hud.py` | Bản đồ (lazy-follow), thanh thông tin, thông báo, nhận diện gật/lắc đầu |
| `config.json` | Hiệu chỉnh trục, FOV, khoảng cách, danh sách màn hình, vị trí bản đồ |

### `config.json`

- `imu_to_cam`: ma trận 3×3 do wizard sinh ra (hàng = trục camera X/Y/Z biểu diễn trong hệ IMU).
  Bản kèm theo là của chiếc Air 2 dùng khi test, các kính Air 2 khác có thể dùng lại được. Xóa key này để chạy lại wizard.
- `screens`: mỗi màn có `source` (`monitor` / `window`), `match` (`primary`, `secondary`, tên DISPLAYn,
  hoặc một phần tiêu đề cửa sổ / `auto`), `yaw` (độ, dương = bên trái), `pitch`, `width_m`.
- `map`: `yaw`, `pitch`, `width_m`, `distance_m`, `follow_deadzone_deg` (mặc định 12).

## Ghi chú kỹ thuật (quan trọng khi viết bản thật)

**Giao thức IMU Air 2** (theo driver cộng đồng `wheaney/nrealAirLinuxDriver`, đã xác nhận chạy):
- USB VID `0x3318`, PID `0x0428` (Air 2). IMU nằm ở **interface 3**, report 64 byte.
  Air = `0x0424`, Air 2 Pro = `0x0432` (cùng interface 3); Air 2 Ultra = `0x0426` (interface 2, payload 512).
- Lệnh bật luồng: `0xAA | crc32 LE | len u16 LE (=3+n) | msgid u8 | data`.
  CRC32 chuẩn (zlib) tính trên `len + msgid + data`. Bật: msgid `0x19`, data `[0x01]`; tắt: data `[0x00]`.
- Gói mẫu: chữ ký `01 02`, timestamp u64 (ns) ở offset 4, gyro (mult i16 @12, div i32 @14, xyz i24 @18/21/24) đơn vị **độ/s**,
  accel (mult @27, div @29, xyz i24 @33/36/39) đơn vị **g**.
- Trên Windows phải thêm byte report ID `0x00` đầu mỗi lần ghi và bỏ byte đầu khi đọc. Dùng 2 handle riêng cho đọc và ghi
  (handle đồng bộ sẽ chặn ghi khi đang đọc).
- Trục đo được từ wizard: camera X ≈ −IMU X, camera Y (lên) ≈ IMU Z, camera Z (ra sau) ≈ IMU Y, lệch khoảng 10° theo góc gọng.

**Màn hình kính**: EDID mã hãng `MRG` (`MONITOR\MRG3134`). App tìm màn hình theo mã này, nên kính phải ở chế độ Extend.

**Thiết kế cho màn hình cộng sáng**: trên kính, màu đen = trong suốt. HUD dùng nét sáng, mảnh trên nền đen.
Đặt nền đen đục phía sau bản đồ và thông báo để che màn hình ảo bên dưới mà vẫn nhìn xuyên ra đời thật.

**Bộ lọc hướng**: tích phân gyro bằng ma trận quay (exp SO(3)). Bias gyro đo 1 giây lúc đầu, sau đó cập nhật chậm khi đứng yên.
Hiệu chỉnh nghiêng bằng gia tốc kiểu Mahony (kp = 0.5). Dự đoán trước 15 ms để bù độ trễ.
Hướng ngang (yaw) không có tham chiếu nên trôi dần, cần phím R để đưa về giữa. Bản thật nên thử thêm từ kế
(gói IMU có sẵn dữ liệu từ trường) hoặc dùng SDK chính thức.

**Lỗi đã gặp và đã sửa**
- `hidapi` không có bản build cho Python 3.15 → đã viết lại phần đọc HID bằng ctypes.
- dxcam dùng chung một instance cho mỗi output; giải phóng instance khi dò màn khác sẽ làm màn kia đứng hình → không release nữa.
- Phím ↑/↓ trước đây không đổi được kích thước nhìn thấy (bù theo khoảng cách) → đã đổi sang kích thước vật lý cố định.
  Sửa sau buổi test cuối, chưa chạy lại khi đeo kính.

**Giới hạn đã biết**
- Chỉ 3DoF; hiển thị mono (chưa dùng chế độ SBS 3D 3840×1080 của kính).
- Chụp cửa sổ bằng PrintWindow chậm (~15 fps), không chụp được cửa sổ bị thu nhỏ.
- Chuột chỉ di chuyển trong màn hình thật; màn hình ảo chỉ để xem.
- Bản đồ là tuyến đường giả, chạy nhanh hơn tốc độ đi bộ để dễ demo.
- Chỉ chạy trên Windows.

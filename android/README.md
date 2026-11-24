# XREAL Space — Android test build (Beam Pro / điện thoại Android)

Bản port Android của prototype Python `xreal-spatial-prototype` (23/11/2026). Mục tiêu: kiểm chứng trên
**Beam Pro** và điện thoại Android rằng app tự viết đọc được IMU kính Air 2 và vẽ được không gian lên kính,
**không cần SDK hay app của hãng**.

Kotlin thuần, không AndroidX, không thư viện ngoài. UI dựng bằng code nên build nhanh và ít lỗi phiên bản.

## Tính năng (so với prototype PC)

| Prototype PC (Python) | Bản Android này |
|---|---|
| Đọc IMU Air 2 1000 Hz qua HID (ctypes) | ✅ USB host API (`UsbManager`), claim interface 3, lệnh `0x19` bật luồng |
| Head tracking 3DoF, Mahony, dự đoán 15 ms | ✅ port nguyên, **đã sửa lỗi bias gyro** (xem dưới) |
| 3 màn hình chụp desktop | ✅ thay bằng **3 WebView thật** (bấm, cuộn, gõ được), world-locked |
| Bản đồ lazy-follow (tuyến giả) | ✅ port nguyên |
| Giờ / pin / thông báo head-locked | ✅ |
| Gật / lắc đầu | ✅ (ngưỡng 80–90°/s như cũ, vẫn cần thử khi đeo) |
| Wizard hiệu chỉnh trục 2 cử chỉ | ✅ (mặc định dùng sẵn ma trận đo được trên kính Air 2 hôm 23/11) |
| Phím tắt Ctrl+Alt+… | ✅ bàn phím Bluetooth: R, W, M, N, F, H, C, B, ↑/↓, [ / ] (không cần Ctrl+Alt) |
| Chuột theo màn đang nhìn | ✅ **chấm ngắm giữa kính + touchpad trên điện thoại** (chạm = click, kéo = cuộn) |
| Giọng nói | ✅ bản đầu: SpeechRecognizer tiếng Việt + bộ luật đơn giản ("mở YouTube bên trái", "về giữa", "tắt bản đồ", "xa hơn"…). Lớp LLM gọi hàm để sau |
| — | ➕ Nút âm lượng làm remote: **+ = click**, **− = về giữa** (nhẫn/remote Bluetooth gửi phím âm lượng cũng dùng được) |
| — | ➕ Ghim màn mới ở hướng đang nhìn, xoá màn đang nhìn, đổi URL, back/forward/reload, gõ chữ vào ô trên trang |
| — | ➕ Chế độ **cảm biến điện thoại**: test cả không gian trên điện thoại khi không có kính |
| — | ➕ **Thẻ chẩn đoán**: liệt kê mọi màn hình + thiết bị USB app nhìn thấy, log, nút Copy để gửi lại |

Giới hạn giữ nguyên: chỉ 3DoF, hiển thị mono, yaw vẫn trôi chậm (về giữa bằng nút − hoặc "về giữa").
Màn hình ảo dùng phép chiếu "billboard": đúng vị trí, kích thước, độ nghiêng đầu (roll), nhưng không có méo phối cảnh
khi nhìn xiên. Bản GL/stereo SBS để bước sau.

### Lỗi bias đã sửa so với prototype

Prototype lấy 1000 mẫu gyro đầu tiên làm bias, kể cả khi đầu đang động; sau đó chỉ tự sửa nếu sai số < 1.5°/s.
Với file ghi thật `my_motion.csv`, cách đó cho bias trục yaw ≈ **6°/s** → màn hình tự trôi 6°/giây và không bao giờ tự hết.
Bản này: bỏ 0.3 s đầu, chỉ nhận bias trong cửa sổ đứng yên thật (động là đo lại), và tiếp tục tinh chỉnh mỗi khi đứng yên
(đánh giá "đứng yên" theo độ dao động của gyro, nên bias sai không tự khoá việc sửa của chính nó). Có test chạy trên đoạn ghi thật.

## Build APK

Cần JDK 17 + Android SDK (API 35). Chọn một cách:

1. **Android Studio**: Open thư mục này → đợi Gradle sync → Run ▶ (cắm Beam Pro/điện thoại bật USB debugging).
2. **Dòng lệnh** (đã có Android SDK): `gradlew.bat assembleDebug` → APK ở `app/build/outputs/apk/debug/app-debug.apk`.
3. **Server Linux có Docker** (không cần cài gì trên PC), ví dụ:
   ```bash
   docker run --rm -v "$PWD":/project -w /project mingc/android-build-box bash -c "./gradlew assembleDebug"
   ```
4. **GitHub Actions**: push lên một repo private, workflow `.github/workflows/build.yml` tự build và chạy test,
   tải APK ở tab Actions → Artifacts.

Cài: `adb install -r app-debug.apk` (hoặc copy APK sang máy rồi mở).

Test lõi (giao thức, tracker, wizard, bản đồ, giọng nói): `gradlew test` — 13 test, có 1 test chạy trên 4 giây dữ liệu Air 2 thật.

## Kịch bản test trên Beam Pro

Mục tiêu số 1 là trả lời 2 câu: **app có đọc được IMU không** và **app có vẽ được lên kính không**.

1. Cắm kính vào Beam Pro, mở app **XREAL Space** (nếu hệ thống hỏi quyền USB cho app → Cho phép).
2. Bấm **Kết nối kính (USB)**. Nhìn dòng `IMU:` ở đầu màn hình:
   - `~1000 Hz` và `bias READY` sau ~1 giây giữ yên → **đọc IMU OK**.
   - `Không thấy kính trên USB` hoặc `claimInterface… thất bại` → hệ thống Beam Pro đang giữ kính. Gửi lại phần chẩn đoán.
3. Bấm **Chiếu lên kính**. Nếu kính hiện 3 trang web + bản đồ góc dưới + đồng hồ góc trên → **vẽ lên kính OK**.
   - Nếu báo `Không thấy màn hình kính` → bấm **Xem trên màn hình này** (khi Beam Pro đang mirror màn hình của nó lên kính,
     chế độ này cũng hiện trên kính).
4. Quay đầu trái/phải: màn hình phải **đứng yên trong không gian**. Bấm **Về giữa** (hoặc nút âm lượng −) để đặt lại.
5. Nhìn chấm ngắm vào một trang web, dùng touchpad: chạm = click, kéo = cuộn. Thử gõ chữ vào ô tìm kiếm.
6. Thử gật đầu (bật/tắt bản đồ), **Thông báo mẫu** rồi gật = OK / lắc = bỏ qua.
7. Thử **🎤 Ra lệnh giọng nói**: "mở YouTube bên trái", "tắt bản đồ", "về giữa".
8. Kéo xuống **Chẩn đoán** → **Copy** → dán vào chat. Kèm ghi chú: bước nào được, bước nào không.

Trên điện thoại thường không xuất hình được qua USB-C (như Redmi 13C): vẫn test được bước 2 (đọc IMU) rồi dùng
**Xem trên màn hình này** để xem không gian chạy theo đầu ngay trên điện thoại.
Không có kính: **Dùng cảm biến ĐT** → **Xem trên màn hình này**, cầm điện thoại như một ô cửa sổ và xoay người.

## Cấu trúc

| File | Nội dung |
|---|---|
| `core/Protocol.kt` | Lệnh bật/tắt luồng IMU (CRC32), parse gói 64 byte, bảng model (Air/Air 2/Air 2 Pro/Ultra) |
| `core/ImuTracker.kt` | Bộ lọc hướng (SO(3) + Mahony + dự đoán), calibrate bias kiểu mới |
| `core/Space.kt` | Cấu hình, phép chiếu, lazy-follow, gật/lắc, wizard trục, tuyến đường giả, lệnh giọng nói |
| `GlassesUsb.kt` | Tìm kính, xin quyền, claim interface, luồng đọc, mô tả USB cho chẩn đoán |
| `PhoneImu.kt` | Nguồn IMU dự phòng từ gyro/gia tốc của điện thoại |
| `SpaceView.kt` | Cảnh không gian: WebView world-locked, bản đồ, HUD, chấm ngắm, chuyển chạm từ touchpad |
| `HudViews.kt` | Vẽ bản đồ, thanh giờ/pin, thông báo, chấm ngắm |
| `MainActivity.kt` | Bảng điều khiển trên điện thoại, Presentation lên kính, phím, giọng nói, chẩn đoán |

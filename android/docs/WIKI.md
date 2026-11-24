# XREAL Space — Wiki & danh sách tính năng

Cập nhật: 25/11/2026 · bản 0.4.0
Đây là wiki duy nhất của dự án (không còn bản online). Mọi thay đổi ghi vào file này.

## 1. Tổng quan

XREAL Space là app Android tự viết cho kính XREAL Air 2: đọc IMU kính qua USB, đặt nhiều trang web đứng yên trong không gian,
điều khiển bằng điện thoại, đầu và giọng nói. Không dùng SDK hay app của hãng. Hợp tác với anh Khoa; đối tượng là người có kính
đời cũ mất hỗ trợ, dùng để **xem phim, làm việc và tiện ích khi di chuyển**.

Trạng thái: code đã có, test lõi chạy qua, **chưa build APK và chưa chạy trên kính thật** (bản Android).
Bản PC Python đã test trên kính thật ngày 23/11/2026.

| Thư mục trong `D:\project\xreal-space` | Là gì | Trạng thái |
| --- | --- | --- |
| `xreal-space-android\` | App Android (Kotlin), bản chính | Code xong, chờ build |
| `xreal-space-android.zip` | Như trên + file build tự động GitHub Actions | Dùng khi build trên GitHub |
| `xreal-spatial-prototype\` | Prototype PC: 3 màn hình desktop, HUD, bản đồ | Đã test trên kính thật |
| `xr-air-lab\` | PoC Python đọc IMU, giao thức | Cũ, giữ tham khảo |

Build APK: Android Studio (mở thư mục → Run), Docker trên server (`mingc/android-build-box`), hoặc GitHub Actions. Lệnh ở `README.md`.

## 2. Danh sách tính năng

Trạng thái: **PC ✓** = đã chạy trên kính thật ở prototype PC · **Chờ test** = code xong trên Android, chưa chạy thiết bị · **Chưa** = chưa làm.

| Nhóm | Tính năng | Trạng thái | Từ bản |
| --- | --- | --- | --- |
| Kính | Đọc IMU Air 2 qua USB, 1000 Hz, không cần app hãng | PC ✓ · Android chờ test | 0.1 |
| Kính | Head tracking 3DoF, dự đoán 15 ms, sửa lỗi bias gyro | PC ✓ · Android chờ test | 0.1 |
| Kính | Wizard hiệu chỉnh trục 2 cử chỉ (mặc định đã có ma trận Air 2) | PC ✓ | 0.1 |
| Kính | Cắm kính là tự kết nối, tự chiếu, tự đo bias ("Giữ yên đầu…") | Chờ test | 0.3 |
| Kính | Cảm biến điện thoại thay kính (test không cần kính) | Chờ test | 0.1 |
| Kính | Thẻ chẩn đoán: màn hình, USB, log, nút Copy | Chờ test | 0.1 |
| Không gian | Nhiều trang web thật (bấm, cuộn, gõ) đứng yên trong không gian | PC ✓ (desktop) · Android chờ test | 0.1 |
| Không gian | Lưới 9 ô quanh đầu, đặt trang vào ô bằng giọng | Chờ test | 0.2 |
| Không gian | Không gian lưu sẵn + tự lưu ("lưu không gian code") | Chờ test | 0.2 |
| Không gian | Cầm màn theo đầu để dời, phóng to / thu nhỏ màn đang nhìn | Chờ test | 0.2 |
| Không gian | Bản đồ lazy-follow (tuyến đường giả), giờ / pin / thông báo | PC ✓ | 0.1 |
| Điều khiển | Touchpad toàn màn hình khi đeo kính (dùng không cần nhìn) | Chờ test | 0.3 |
| Điều khiển | Chấm ngắm giữa kính = con trỏ | Chờ test | 0.1 |
| Điều khiển | Nút âm lượng: + click, giữ + nói lệnh, − về giữa | Chờ test | 0.1 |
| Điều khiển | Bàn phím điện thoại tự bật khi chạm ô nhập trên trang | Chờ test | 0.3 |
| Điều khiển | Đọc chính tả vào ô nhập (giữ touchpad khi đang gõ) | Chờ test | 0.3 |
| Điều khiển | Gật = OK / bật-tắt bản đồ / đặt màn; lắc = bỏ qua | PC (code, chưa thử kỹ) | 0.1 |
| Điều khiển | Bàn phím Bluetooth: phím tắt như prototype | Chờ test | 0.1 |
| AI | Từ đánh thức miễn phí "kính ơi" (Vosk offline) | Chờ test | 0.2 |
| AI | Giọng nói → chữ: Vosk offline hoặc Google | Chờ test | 0.2 |
| AI | Chữ → lệnh: luật offline / Jev / LLM, lỗi thì tự về luật | Chờ test | 0.2 |
| AI | Luật chạy trước, chỉ gọi Jev/LLM khi luật không chắc | Chờ test | 0.3 |
| AI | Tóm tắt trang đang nhìn, trả lời câu hỏi (thẻ trên kính) | Chờ test | 0.2 |
| Xem phim | Nút toàn màn hình của YouTube chạy được, chế độ rạp phim | Chờ test | 0.3 |
| Xem phim | Rạp phim: màn hình trôi nhẹ về trước mặt khi đổi tư thế | Chờ test | 0.3 |
| Xem phim | Web tối (đỡ chói trên kính) | Chờ test | 0.3 |
| Làm việc | Không gian "Làm việc", "Code", "Họp" dựng sẵn | Chờ test | 0.2 |
| Di chuyển | Chế độ di chuyển: màn hình đi theo hướng người, xe rẽ không lệch | Chờ test | 0.3 |
| Di chuyển | Chế độ đi bộ: ẩn màn lớn, giữ bản đồ + giờ | PC ✓ | 0.1 |
| Pin | Tạm dừng trang không nhìn quá 8 giây | Chờ test | 0.3 |
| Điện thoại | Màn hình điện thoại vào một ô (Zalo, Netflix, Grab… trên kính) | Chờ test | 0.4 |
| Điện thoại | Mở app bằng giọng ("mở Zalo"), tự bật chiếu màn hình | Chờ test | 0.4 |
| Điện thoại | Bấm / vuốt vào app qua chấm ngắm (Trợ năng), "quay lại", "màn hình chính" | Chờ test | 0.4 |
| Điện thoại | Kính vẫn chạy khi mở app khác; nút âm lượng vẫn làm remote | Chờ test | 0.4 |
| Teleprompter | Kịch bản chạy theo giọng đọc (Vosk vi/en), lùi/tới bằng vuốt 2 ngón | Chờ test | 0.4 |
| Phụ đề | Phụ đề trực tiếp tiếng Việt / tiếng Anh (offline) | Chờ test | 0.4 |
| Phụ đề | Phiên dịch Anh → Việt, Việt → Anh (cần LLM) | Chờ test | 0.4 |
| Chưa | Thông báo thật của điện thoại hiện trên kính, trả lời bằng giọng | Chưa | — |
| Chưa | Bản đồ GPS thật, 3D stereo SBS, méo phối cảnh khi nhìn xiên | Chưa | — |
| Chưa | Backend giữ key Jev/LLM, gói trả phí | Chưa | — |

## 3. Hướng dẫn dùng nhanh

**Cắm kính:** cắm kính vào Beam Pro / điện thoại → cho phép quyền USB (một lần) → kính tự hiện không gian, điện thoại tự thành touchpad.
Giữ yên đầu 1 giây khi kính hiện "Giữ yên đầu…". Muốn vào cài đặt: nút ⚙ góc dưới phải touchpad, hoặc nút Back.

**Touchpad (không cần nhìn điện thoại):**

| Cử chỉ | Tác dụng |
| --- | --- |
| 1 ngón chạm | Click tại chấm ngắm (rạp phim: play/pause) |
| 1 ngón kéo | Cuộn / kéo trang |
| 1 ngón giữ 0.6 giây | Nói lệnh (khi đang gõ ô nhập: đọc chính tả) |
| 2 ngón chạm | Về giữa |
| 2 ngón vuốt ← / → | Back (thoát toàn màn hình) / Tới |
| 2 ngón vuốt ↑ | Hiện số 9 ô |
| 2 ngón vuốt ↓ | Bật/tắt chế độ đi bộ |
| 3 ngón chạm | Đổi chế độ: Cố định → Rạp phim → Di chuyển |
| 2 ngón vuốt ← / → khi đang nhắc chữ | Teleprompter tới / lùi 8 từ |

**Nút âm lượng:** + bấm = click · + giữ = nói lệnh · − = về giữa. Nhẫn / remote Bluetooth gửi phím âm lượng cũng dùng được.

**Bàn phím Bluetooth:** R về giữa · W đi bộ · M bản đồ · T đổi chế độ · D web tối · G cầm/đặt màn · V nói lệnh · N thông báo mẫu ·
F head-lock · H status · C hiệu chỉnh trục · B đo lại bias · ↑/↓ xa/gần · [ / ] FOV · Esc back.

**Bàn phím Bluetooth thêm:** P bật/tắt teleprompter · ← → / PageUp PageDown = lùi/tới khi nhắc chữ.

**Điện thoại trên kính (bản 0.4):**
1. Cấp 2 quyền trong app: **Quyền hiện trên app khác** (kính vẫn chạy khi bạn mở Zalo, Netflix…) và **Bật Trợ năng** (bấm vào app qua chấm ngắm; nút âm lượng vẫn làm remote khi app khác đang mở).
2. Nói "mở Zalo" (hoặc gõ tên app → **Mở app**). Lần đầu điện thoại hỏi quyền chiếu màn hình → **Bắt đầu**. Màn hình điện thoại hiện ở ô 4.
3. Dùng app: chạm thẳng lên điện thoại (mắt nhìn ô trên kính), hoặc nhìn chấm ngắm vào nút trong ô rồi bấm âm lượng +. "Quay lại", "màn hình chính" bằng giọng.
4. "tắt màn hình điện thoại" để dừng chiếu. Khi app XREAL Space đang mở trên điện thoại, ô chiếu sẽ hiện chính touchpad.

**Teleprompter:** dán kịch bản vào ô Teleprompter → **▶ Bắt đầu** (hoặc nói "bật nhắc chữ"). Cứ đọc, chữ tự chạy; từ sắp đọc tô vàng. Kịch bản có dấu tiếng Việt dùng model Việt, không dấu dùng model Anh.

**Phụ đề:** chọn **Phụ đề Việt**, **English**, **Dịch Anh → Việt** hoặc **Dịch Việt → Anh** (hoặc nói "bật phụ đề", "dịch tiếng Anh", "tắt phụ đề"). Lần đầu tải model ngôn ngữ đó (~32–40 MB). Dịch cần cấu hình LLM.

**Đầu:** gật = OK / đặt màn đang cầm / bật-tắt bản đồ · lắc = bỏ qua.

**Gõ chữ:** chạm (qua chấm ngắm) vào ô tìm kiếm trên trang → bàn phím điện thoại tự bật, chữ hiện trên kính khi gõ, Enter = gửi.

## 4. Lệnh giọng nói & lưới 9 ô

```
 1 2 3     ← nhìn lên (26°)
 4 5 6     ← nhìn thẳng, 5 = chính diện; cột trái/giữa/phải cách nhau 42°
 7 8 9     ← nhìn xuống
```

Khi đang nghe lệnh, kính hiện số trên các ô: ô trống màu xanh, ô có trang màu vàng.

| Nhóm | Câu mẫu |
| --- | --- |
| Đặt trang vào ô | "YouTube ô 3" · "đặt Gmail vào ô năm" · "tìm thời tiết Sài Gòn ô 6" |
| Mở trên màn đang nhìn | "mở YouTube" · "tìm giá vàng hôm nay" · "mở vnexpress.net" |
| Xoá / xem ô | "xoá ô 7" · "xoá màn" · "hiện lưới" |
| Không gian | "mở không gian code" · "lưu không gian đọc báo" · "chế độ rạp phim" |
| Chế độ | "chế độ di chuyển" · "cố định màn hình" · "đi theo đầu" · "chế độ đi bộ" · "tắt đi bộ" |
| Hiển thị | "về giữa" · "xa hơn" · "gần hơn" · "to hơn" · "nhỏ hơn" · "bật web tối" · "tắt bản đồ" |
| Màn hình | "cầm màn" (rồi gật để đặt) · "khoá đầu" |
| Điện thoại | "mở Zalo" · "mở ứng dụng Grab" · "màn hình điện thoại ô 4" · "tắt màn hình điện thoại" · "quay lại" · "màn hình chính" |
| Phụ đề | "bật phụ đề" · "phụ đề tiếng Anh" · "dịch tiếng Anh" · "dịch sang tiếng Anh" · "tắt phụ đề" |
| Teleprompter | "bật nhắc chữ" · "teleprompter" · "tắt nhắc chữ" |
| AI (cần LLM) | "tóm tắt trang này" · "thủ đô của Pháp là gì" · lệnh nhiều bước: "mở YouTube ô 3 rồi tắt bản đồ" |

Không gian dựng sẵn: **làm việc** (Google, Gmail, Lịch) · **code** (GitHub, Claude, Docs, Tìm) · **giải trí** (một màn rạp phim, tự bật chế độ rạp) · **họp** (Meet, Ghi chú).

## 5. Cấu hình AI

```
mic → [từ đánh thức "kính ơi" · Vosk offline]  hoặc nút 🎤 / giữ touchpad / giữ âm lượng +
    → [giọng nói → chữ]  Vosk offline tiếng Việt  |  Google SpeechRecognizer
    → [luật offline hiểu chắc chắn?] — có → chạy ngay
                                      không → [Jev] hoặc [LLM] → lệnh   (lỗi → quay về luật)
```

| Thành phần | Lựa chọn | Chi phí | Ghi chú |
| --- | --- | --- | --- |
| Từ đánh thức | Vosk `vosk-model-small-vn-0.4` (~32 MB, tự tải lần đầu) | Miễn phí, Apache 2.0 | Grammar chỉ gồm cụm đánh thức + `[unk]`. Ngoài đường ồn: dùng giữ-để-nói |
| Phụ đề / teleprompter | Vosk `vosk-model-small-vn-0.4` và `vosk-model-small-en-us-0.15` (~40 MB), nghe liên tục | Miễn phí | Phiên dịch: mỗi câu gửi LLM dịch |
| Giọng nói → chữ | Vosk offline / Google | Vosk miễn phí; Google cần dịch vụ Google trên máy | Mic của kính có được Android dùng không: cần test |
| Chữ → lệnh: luật | Có sẵn | Miễn phí | Chạy trước mọi engine |
| Chữ → lệnh: Jev (TypeSafe) | OpenRouter `https://openrouter.ai/api/alpha/decisions`, model `typesafe/jev-1.13`; hoặc `https://api.typesafe.ai/v1/systemone`, model `jev-latest` | ~0.042 USD / 1 triệu token vào, output miễn phí, không có gói free | Chỉ chọn lệnh + xác suất; URL / từ khoá lấy từ câu nói |
| Chữ → lệnh: LLM | Claude `https://api.anthropic.com/v1/messages` (`claude-haiku-5-5`), Ollama LAN, OpenAI-compatible | Theo nhà cung cấp; Ollama miễn phí | Lệnh nhiều bước, trả lời câu hỏi, tóm tắt trang |

Key lưu không mã hoá trên máy, chỉ dùng cho bản test. Bản thật: một **backend nhỏ** giữ key, gọi Jev/LLM, giới hạn lượt theo người dùng,
đổi model không cần ra bản app mới, chỗ gắn gói Premium AI.

## 6. Kiến trúc & file code

```
Kính Air 2 ──USB HID──► GlassesUsb ──► ImuTracker (bias, SO(3), Mahony) ──► SpaceView (mỗi vsync)
Điện thoại (gyro) ─────► PhoneImu ───┘                                        │  BodyFollow (chế độ) · Projection (đặt màn)
                                                                               │  WebView × N · rạp phim · bản đồ · HUD · số ô
MainActivity ◄── TouchpadView (cử chỉ) · bàn phím · nút âm lượng                ▼
     │                                                                 Presentation trên kính / màn điện thoại
     └── VoiceInput (Vosk / Google) ──► VoiceCommands (luật) ─┬─► SpaceState.execute(lệnh)
                                        AiClient (Jev / LLM) ─┘
```

| File | Nội dung |
| --- | --- |
| `core/Protocol.kt` | Lệnh bật/tắt luồng IMU (CRC32), parse gói 64 byte, bảng model |
| `core/ImuTracker.kt` | Bộ lọc hướng, calibrate bias kiểu mới |
| `core/Space.kt` | Cấu hình, phép chiếu, lazy-follow bản đồ, gật/lắc, wizard trục, tuyến đường giả, luật hiểu lệnh |
| `core/Features.kt` | Lưới 9 ô, không gian, giao thức Jev + LLM, từ đánh thức, chế độ đi theo, luật chạy trước |
| `GlassesUsb.kt` · `PhoneImu.kt` | Nguồn IMU: kính qua USB, hoặc cảm biến điện thoại |
| `SpaceView.kt` | Cảnh không gian: WebView, rạp phim, web tối, tạm dừng màn khuất, cầu nối ô nhập |
| `HudViews.kt` | Bản đồ, giờ/pin, thông báo, chấm ngắm |
| `TouchpadView.kt` | Touchpad toàn màn hình, cử chỉ 1–3 ngón |
| `VoiceInput.kt` · `AiClient.kt` | Vosk + Google STT · gọi Jev / LLM |
| `Assistant.kt` | "Bộ não" chạy độc lập với màn hình: giọng nói → lệnh, trợ lý, phụ đề, teleprompter, mở app |
| `GlassesService.kt` | Giữ kính chạy khi mở app khác (overlay), chiếu màn hình điện thoại (MediaProjection), Trợ năng (bấm vào app, nút âm lượng) |
| `core/Assist.kt` | Teleprompter theo giọng, dòng phụ đề, quy đổi toạ độ ô chiếu → điện thoại, tìm app theo tên |
| `SpaceState.kt` · `MainActivity.kt` | Trạng thái chung + lưu cài đặt · màn điều khiển, Presentation, bàn phím, phím tắt |
| `app/src/test/.../CoreTest.kt` | 22 test lõi, có 1 test trên 4 giây dữ liệu Air 2 thật |

## 7. Checklist test trên Beam Pro / điện thoại

- [ ] Build APK (README) và cài được
- [ ] Cắm kính: có hỏi quyền USB, kính tự hiện không gian, điện thoại thành touchpad
- [ ] Dòng IMU ~1000 Hz và `bias READY` sau khi giữ yên 1 giây
- [ ] Quay đầu trái/phải: màn đứng yên; 2 ngón chạm = về giữa
- [ ] Touchpad: chạm = click, kéo = cuộn, 2 ngón ← = back
- [ ] Chạm ô tìm kiếm Google: bàn phím điện thoại tự bật, chữ hiện trên kính, Enter gửi
- [ ] YouTube: bấm toàn màn hình → rạp phim; 2 ngón ← thoát; "Rạp −/+" đổi cỡ
- [ ] Web tối bật/tắt có tác dụng
- [ ] 3 ngón chạm đổi chế độ; ngồi ghế xoay quay người: chế độ Di chuyển đi theo
- [ ] Gõ "YouTube ô 3" vào ô Gõ lệnh thử → Chạy
- [ ] 🎤 Nói lệnh (lần đầu tải model Vosk) · bật từ đánh thức, nói "kính ơi" rồi "Gmail ô 4"
- [ ] Mic của kính có thu được giọng không (thử khi điện thoại để xa)
- [ ] (có key) Jev / LLM: "mở YouTube ô 3 rồi tắt bản đồ", "tóm tắt trang này"
- [ ] Cấp quyền hiện trên app khác + Trợ năng; "mở Zalo" → màn hình ĐT hiện ở ô 4, vẫn hiện khi Zalo ở phía trước
- [ ] Nhìn chấm ngắm vào nút trong ô điện thoại, bấm âm lượng + → nút được bấm; "quay lại" chạy
- [ ] Netflix trong ô điện thoại: hình có bị đen không (DRM)
- [ ] Teleprompter: dán đoạn văn, đọc to, chữ có chạy theo không
- [ ] Phụ đề Việt khi người khác nói; Dịch Anh → Việt với video tiếng Anh (cần LLM)
- [ ] Sau 15 phút dùng: máy nóng tới đâu, pin tụt bao nhiêu
- [ ] Gửi lại: Chẩn đoán → Copy, kèm bước nào được / không

Không có kính: **Dùng cảm biến ĐT** → **Xem trên màn hình này**, cầm điện thoại như cửa sổ.

## 8. Giới hạn đã biết & lộ trình

**Giới hạn:**
- Chỉ 3DoF, hiển thị mono; màn đặt kiểu "billboard": đúng vị trí, cỡ, nghiêng đầu, nhưng không méo phối cảnh khi nhìn xiên.
- Yaw vẫn trôi chậm: về giữa bằng 2 ngón chạm hoặc nút −.
- App gốc hiện qua chiếu màn hình điện thoại: app có DRM (Netflix, một số app ngân hàng) có thể ra màn đen. Video có DRM trong WebView cũng có thể không chạy.
- Chiếu màn hình chỉ hiện đúng một thứ đang ở phía trước điện thoại: khi XREAL Space đang mở, ô chiếu hiện chính touchpad.
- Beam Pro dùng NebulaOS: cửa sổ overlay lên kính và chiếu màn hình có chạy được không: cần test.
- Phụ đề / teleprompter dùng model Vosk nhỏ: nơi ồn hoặc nói nhanh sẽ sai nhiều.
- Rạp phim hiện bằng cách dịch chuyển khung video; với vài kiểu video (SurfaceView) có thể không bám theo được: cần test.
- Beam Pro có thể giữ cổng HID của kính, làm app không đọc được IMU: cần test bằng thẻ chẩn đoán.
- Đeo kính khi lái xe máy / ô tô là nguy hiểm: app chưa chặn.

**Lộ trình đề xuất:**
1. Build + test trên Beam Pro, sửa theo log chẩn đoán.
2. Backend giữ key AI, đếm lượt, gói Premium.
3. Thông báo thật của điện thoại trên kính, trả lời tin nhắn bằng giọng.
4. Bản đồ GPS thật, chặn hiển thị khi đi nhanh.
5. Render GL: méo phối cảnh, 3D stereo SBS.
6. Chạy trên XREAL Beam (đời đầu, không màn hình): điện thoại làm touchpad qua Wi-Fi.

## 9. Nhật ký phiên bản

| Ngày | Bản | Thay đổi |
| --- | --- | --- |
| 25/11/2026 | 0.4.0 | Màn hình điện thoại vào một ô + mở app bằng giọng + bấm qua chấm ngắm (Trợ năng); kính chạy nền khi mở app khác; teleprompter theo giọng đọc; phụ đề Việt/Anh và phiên dịch Anh↔Việt |
| 24/11/2026 | 0.3.0 | Touchpad toàn màn hình; tự kết nối / tự chiếu; bàn phím điện thoại + đọc chính tả; YouTube toàn màn hình (rạp phim); web tối; 3 chế độ Cố định / Rạp phim / Di chuyển; luật chạy trước Jev/LLM; tạm dừng màn khuất; wiki |
| 24/11/2026 | 0.2.0 | AI giọng nói: Vosk (từ đánh thức + nhận lệnh offline), Google STT, hiểu lệnh bằng luật / Jev / LLM; lưới 9 ô; không gian lưu sẵn; tóm tắt trang; cầm màn |
| 24/11/2026 | 0.1.0 | Port Android: IMU qua USB, 3 WebView world-locked, bản đồ, HUD, gật/lắc, wizard trục, touchpad, phím âm lượng, chẩn đoán; sửa lỗi bias gyro |
| 23/11/2026 | PC | Prototype Python: 3 màn hình desktop, HUD, bản đồ, test trên kính thật |
| 16/11/2026 | xr-air-lab 0.2 | PoC Python đọc IMU, sửa trôi yaw |

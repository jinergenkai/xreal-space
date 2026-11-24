# XREAL Space — Wiki & danh sách tính năng

Cập nhật: 25/11/2026 · bản 0.6.0
Đây là wiki duy nhất của dự án (không còn bản online). Mọi thay đổi ghi vào file này.

## 1. Tổng quan

XREAL Space là app Android tự viết cho kính XREAL Air 2: đọc IMU kính qua USB, đặt nhiều trang web đứng yên trong không gian,
điều khiển bằng điện thoại, đầu và giọng nói. Không dùng SDK hay app của hãng. Hợp tác với anh Khoa; đối tượng là người có kính
đời cũ mất hỗ trợ, dùng để **xem phim, làm việc và tiện ích khi di chuyển**.

Trạng thái: **APK build được** (GitHub Actions, 25/11/2026), test lõi chạy qua, **chưa chạy trên kính thật** (bản Android).
Bản PC Python đã test trên kính thật ngày 23/11/2026.

Mã nguồn: repo GitHub private **`jinergenkai/xreal-space`** (monorepo). Trên máy: `git clone` về `D:\project\xreal-space\xreal-space`.

| Thư mục | Là gì | Trạng thái |
| --- | --- | --- |
| `android/` | App Android (Kotlin), bản chính | Code xong, chờ build |
| `pc/` | Prototype PC (Python): 3 màn hình desktop, HUD, bản đồ | Đã test trên kính thật |
| `pythontest/` | xr-air-lab: PoC Python đọc IMU, tài liệu giao thức, test | Giữ tham khảo; test chạy trên CI |
| `docs/WIKI.md` | Wiki duy nhất của dự án (file này) | — |

Build APK: **Codemagic** (`codemagic.yaml`, free 500 phút/tháng, không cần thẻ) tự build mỗi lần push vào `android/` → codemagic.io → build mới nhất → Artifacts.
GitHub Actions (`.github/workflows/`) cũng có sẵn nhưng đang không chạy: tài khoản GitHub bị khoá billing (thẻ bị từ chối khi xác minh, 11/2026).
Hoặc Android Studio (mở thư mục `android/`), hoặc Docker (`android/scripts/build-apk.sh`). Chi tiết ở `android/README.md`.

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
| Beam đời đầu | Điện thoại điều khiển qua Wi-Fi: touchpad, nói lệnh (nhận giọng trên điện thoại), bàn phím, nút âm lượng | Chờ test | 0.5 |
| Beam đời đầu | Máy không có màn cảm ứng tự bật chế độ máy chủ; điện thoại tự tìm máy kính trong Wi-Fi (hoặc nhập IP) | Chờ test | 0.5 |
| Ổn định | Đọc IMU/USB chạy ở cấp ứng dụng + service nền, không chết khi Android đóng màn điều khiển | Chờ test | 0.5 |
| Ổn định | Thẻ "Thiết lập lần đầu" (✓/✗ từng quyền + nút mở cài đặt), nhắc Tự khởi chạy trên Xiaomi/Oppo/Vivo | Chờ test | 0.5 |
| Ổn định | Ghi crash vào máy, hiện trong Chẩn đoán → Copy gửi lại | Chờ test | 0.5 |
| Web | Sửa đăng nhập Google (UA không còn "wv") + nút "Mở bằng Chrome" cho trang chặn WebView | Chờ test | 0.5 |
| Khác | Icon app; script build APK bằng Docker (`android/scripts/build-apk.sh`) | Chờ build | 0.5 |
| Thông báo | Thông báo thật của điện thoại hiện trên kính (Zalo, Messenger, SMS…), bỏ trùng lặp, bỏ thông báo hệ thống | Chờ test | 0.6 |
| Thông báo | Gật đầu = trả lời bằng giọng, lắc = bỏ qua; "trả lời …", "đọc thông báo", "tắt thông báo Zalo", "im lặng" | Chờ test | 0.6 |
| An toàn | Đi nhanh hơn ~15 km/h (GPS): ẩn màn hình, thoát rạp phim, thông báo chỉ hiện tên app, không cho trả lời | Chờ test | 0.6 |
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

**XREAL Beam đời đầu (không màn hình) — điều khiển bằng điện thoại qua Wi-Fi (bản 0.5):**
1. Cài app lên cả Beam (sideload APK) và điện thoại. Beam và điện thoại cùng một Wi-Fi (hoặc điện thoại phát hotspot cho Beam).
2. Trên Beam: app tự nhận ra máy không có màn cảm ứng và bật **máy chủ** (cổng 47800). Máy có màn hình thì bấm **Làm máy chủ (máy nối kính)**.
3. Trên kính hiện **mã ghép đôi 4 số** (hiện lại mỗi khi điện thoại ngắt). Trên điện thoại: mục **Điều khiển từ xa** → nhập mã → **Điều khiển máy kính**. Để trống ô IP = tự tìm; không tìm thấy thì nhập IP của Beam (hiện trên kính / trong Chẩn đoán). Mã được nhớ, lần sau không phải nhập lại.
4. Khi đã nối: touchpad, cử chỉ 2–3 ngón, nút âm lượng, giữ để nói lệnh, bàn phím (chạm vào ô nhập trên kính → bàn phím điện thoại bật) đều gửi sang Beam.
5. Chỉ nối được IP mạng nội bộ (192.168.x.x, 10.x.x.x, 172.16–31.x.x, Tailscale 100.64–127.x.x). Sai mã 5 lần liền → máy kính khoá 60 giây. Người lạ không đá được điện thoại đang nối (phải đúng mã mới thay chỗ). Lộ mã thì bấm **Đổi mã** trên máy kính.

**Thông báo điện thoại (bản 0.6):** thẻ Thiết lập → **Đọc thông báo ĐT** → chọn XREAL Space → Cho phép.
Tin nhắn mới hiện ở góc kính: **gật đầu** = nói câu trả lời (im 1 giây là gửi), **lắc** = bỏ qua. Hoặc nói:
"đọc thông báo" · "trả lời tối nay 7h nhé" (trả lời tin mới nhất) · "tắt thông báo Zalo" / "bật thông báo Zalo" · "im lặng" / "bật thông báo".
Trả lời dùng ô trả lời có sẵn trong thông báo của app (giống trả lời từ thanh thông báo), nên app nào không có ô đó thì không trả lời được.

**An toàn khi đi xe:** cấp quyền **Vị trí** (chỉ đọc tốc độ, không lưu, không gửi). Khi đang cắm kính mà đi nhanh hơn ~15 km/h trong 3 giây:
màn hình ẩn, video thoát toàn màn hình, thông báo chỉ hiện tên app. Chậm dưới ~8 km/h trong 6 giây thì hiện lại. Tắt được ở mục Thông báo & an toàn.

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
| `Sensors.kt` | Giữ GlassesUsb + PhoneImu ở cấp ứng dụng; bật service nền khi có kính |
| `core/Remote.kt` · `RemoteLink.kt` | Giao thức điều khiển từ xa (1 dòng / lệnh) · máy chủ TCP + NSD trên Beam, client trên điện thoại |
| `CrashLog.kt` | Ghi lần crash gần nhất vào máy |
| `android/scripts/build-apk.sh` | Build APK bằng Docker, không cần Android Studio |
| `android/app/src/test/.../CoreTest.kt` | 23 test lõi, có 1 test trên 4 giây dữ liệu Air 2 thật |

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
- [ ] Thẻ Thiết lập lần đầu: các dòng ✗ chuyển ✓ sau khi cấp quyền
- [ ] Mở app khác 5 phút rồi quay lại: kính vẫn bám đầu (IMU chạy nền)
- [ ] Trang Google đăng nhập: được không; nếu không, "Mở bằng Chrome" + chiếu màn hình
- [ ] Beam đời đầu: app tự vào chế độ máy chủ; điện thoại "Điều khiển máy kính" tự tìm thấy (hoặc nhập IP)
- [ ] Cấp "Đọc thông báo ĐT"; nhờ người nhắn Zalo → tin hiện trên kính; gật → nói → người kia nhận được
- [ ] "đọc thông báo", "tắt thông báo Zalo", "im lặng" có tác dụng
- [ ] Cấp Vị trí, cắm kính, đi xe máy (người khác lái!) hoặc chạy xe đạp > 15 km/h: màn ẩn, dừng lại thì hiện
- [ ] Mã ghép đôi: nhập sai → điện thoại báo sai mã, kính hiện mã đúng; nhập đúng → nối; tắt Wi-Fi điện thoại → kính hiện lại mã
- [ ] Beam: touchpad, 2 ngón về giữa, giữ để nói lệnh, gõ chữ vào ô tìm kiếm qua điện thoại
- [ ] Nếu app crash: mở lại → Chẩn đoán có mục "LẦN CRASH GẦN NHẤT" → Copy gửi
- [ ] Sau 15 phút dùng: máy nóng tới đâu, pin tụt bao nhiêu
- [ ] Gửi lại: Chẩn đoán → Copy, kèm bước nào được / không

Không có kính: **Dùng cảm biến ĐT** → **Xem trên màn hình này**, cầm điện thoại như cửa sổ.

## 8. Giới hạn đã biết

**Của app hiện tại:**
- Chỉ 3DoF, hiển thị mono; màn đặt kiểu "billboard": đúng vị trí, cỡ, nghiêng đầu, nhưng không méo phối cảnh khi nhìn xiên.
- Yaw vẫn trôi chậm: về giữa bằng 2 ngón chạm hoặc nút −.
- Chiếu màn hình chỉ hiện đúng một thứ đang ở phía trước điện thoại: khi XREAL Space đang mở, ô chiếu hiện chính touchpad.
- Phụ đề / teleprompter dùng model Vosk nhỏ: nơi ồn hoặc nói nhanh sẽ sai nhiều.
- Rạp phim dịch chuyển khung video; vài kiểu video (SurfaceView) có thể không bám theo: cần test.
- Đeo kính khi lái xe máy / ô tô là nguy hiểm: từ 0.6 app ẩn màn khi đi nhanh (GPS), nhưng không thay được ý thức người lái.

**Giới hạn nền tảng — XREAL Beam (đời đầu):**

| Giới hạn | Ảnh hưởng | Cách app xử lý |
| --- | --- | --- |
| Không có màn hình cảm ứng | Không bấm được nút trong app, không có touchpad | Điện thoại làm remote qua Wi-Fi (bản 0.5) |
| Không có Play Store, không có Google Play Services | Phải sideload APK; Google STT có thể không có | Dùng Vosk offline; nhận giọng chạy trên điện thoại khi làm remote |
| Hệ điều hành tuỳ biến cũ (Android ~11, chưa xác nhận) | Overlay, MediaProjection, Trợ năng có thể bị khoá | Cần test; nếu khoá thì chỉ dùng phần web trong app |
| Beam có chế độ "neo màn hình" 3DoF riêng | Có thể giữ cổng IMU của kính → app không đọc được | Thử tắt chế độ của Beam; xem thẻ Chẩn đoán |
| Mic của Beam / kính | Chưa biết app có lấy được không | Mic điện thoại qua remote |
| Chip yếu, pin nhỏ | Nhiều WebView cùng lúc có thể giật | Tạm dừng màn khuất (đã có); nên ≤ 3–4 màn |

**Giới hạn nền tảng — Android (điện thoại) & Beam Pro:**

| Giới hạn | Ảnh hưởng | Ghi chú |
| --- | --- | --- |
| Điện thoại phải có cổng USB-C xuất hình (DP Alt Mode) | Máy không xuất hình thì kính không hiện gì | Samsung S/Note, Pixel 8+, nhiều máy cao cấp có; máy tầm trung thường không. Xiaomi 13C: nhiều khả năng không |
| Beam Pro (NebulaOS) tự chiếu "Air Casting" | Có thể đè hoặc tranh màn với app | Tắt "Default Air Casting" trong My Glasses; cần test |
| Chiếu màn hình (MediaProjection) | Hỏi đồng ý mỗi lần bật; Android 14 chỉ 1 màn ảo / lần đồng ý | Đã xử lý: 1 VirtualDisplay, đổi bề mặt khi cần |
| App có DRM (Netflix, Disney+, vài app ngân hàng) | Ô chiếu ra màn đen | Không vượt được; xem Netflix bằng web trong ô (nếu web cho) |
| Trợ năng + QUERY_ALL_PACKAGES | Google Play soi rất kỹ, dễ bị từ chối | Phát hành APK ngoài Play, hoặc tách bản Play không có 2 quyền này |
| Hãng điện thoại tự tắt app chạy nền (Xiaomi, Oppo, Vivo, Samsung) | Kính đứng hình khi tắt màn / mở app khác lâu | Thẻ Thiết lập: không tối ưu pin + Tự khởi chạy |
| Một app giữ mic tại một thời điểm | Mở app khác dùng mic (gọi điện, Zalo voice) → mất lệnh giọng | Không có cách sạch; app nhường mic khi bị chiếm |
| Google chặn đăng nhập trong WebView | Gmail, YouTube đăng nhập có thể lỗi | Đã đổi UA; vẫn lỗi thì "Mở bằng Chrome" + chiếu màn hình |
| Kính chỉ 3DoF, không camera | Không bám vật thật, không nhận tay | Giới hạn phần cứng Air 2 |
| Nóng & pin | Xuất hình + nhiều WebView + mic liên tục tốn pin nhanh | Cần đo khi test (mục 7) |

## 9. Nợ kỹ thuật

| # | Nợ | Mức | Trạng thái |
| --- | --- | --- | --- |
| 1 | USB/IMU nằm trong Activity → Android đóng màn điều khiển là mất tracking | Cao | **Đã trả (0.5)**: `Sensors` + service nền |
| 2 | Chưa từng build thật; code Android chỉ được kiểm tra kiểu với thư viện giả (stub) | Cao | **Đã trả**: build thật trên GitHub Actions từ 25/11/2026 |
| 3 | Chưa chạy trên máy thật nào (điện thoại, Beam, Beam Pro) | Cao | Còn — làm theo mục 7 |
| 4 | API key Jev / LLM lưu thường trong máy (SharedPreferences), chưa mã hoá | Trung bình | Còn — chuyển sang backend giữ key (mục 10) |
| 5 | Cho phép HTTP không mã hoá (`usesCleartextTraffic`) để dùng Ollama trong mạng nhà | Trung bình | Còn — giới hạn bằng network-security-config chỉ cho IP nội bộ |
| 6 | Điều khiển từ xa qua Wi-Fi chưa có mã ghép đôi / mật khẩu | Trung bình | **Đã trả (0.5.1)**: mã 4 số, khoá 60 s sau 5 lần sai. Còn lại: dữ liệu chưa mã hoá (chỉ dùng trong Wi-Fi tin cậy) |
| 7 | Đăng nhập Google trong WebView | Trung bình | Đã giảm (0.5): đổi UA + mở Chrome; chưa chắc hết |
| 8 | Chưa có hướng dẫn lần đầu | Thấp | **Đã trả (0.5)**: thẻ Thiết lập lần đầu |
| 9 | Chưa có icon, crash không ai biết | Thấp | **Đã trả (0.5)**: icon + crash log |
| 10 | Tên "XREAL" là thương hiệu của hãng | Thấp (cao nếu phát hành) | Còn — đổi tên trước khi đưa người ngoài dùng |
| 11 | `MainActivity.kt` quá dài (UI dựng bằng code, ~800 dòng) | Thấp | Còn — tách màn cài đặt khi UI ổn định |
| 12 | Quyền Trợ năng / QUERY_ALL_PACKAGES khó qua Play | Thấp (cao nếu lên Play) | Còn — quyết định kênh phát hành |

## 10. Checklist việc cần làm

**Hùng (cần máy / người):**
- [ ] Build APK lần đầu: `android/scripts/build-apk.sh` trên server có Docker (hoặc Android Studio / GitHub Actions). Lỗi build → gửi log.
- [ ] Cài lên điện thoại có DP Alt Mode, chạy mục 7 (checklist test), gửi lại thẻ Chẩn đoán (nút Copy).
- [ ] Cài lên Beam đời đầu, thử điều khiển từ xa bằng điện thoại; xem Beam có giữ cổng IMU không.
- [ ] Hỏi anh Khoa: có Beam Pro không? Ưu tiên máy nào? Điện thoại nào sẽ dùng chính?
- [ ] Đo pin / nhiệt sau 30 phút xem phim và 30 phút làm việc.

**Code (Claude làm được không cần máy):**
- [x] Mã ghép đôi cho điều khiển từ xa (0.5.1).
- [ ] network-security-config: chỉ cho HTTP tới IP nội bộ.
- [x] Thông báo thật của điện thoại lên kính, đọc / trả lời bằng giọng (0.6).
- [x] Ẩn màn khi đi nhanh theo GPS (0.6).
- [ ] Bản đồ GPS thật thay tuyến giả.
- [ ] Backend nhỏ giữ key Jev / LLM, đếm lượt (Docker, chạy trên server của Hùng).
- [ ] Render GL: méo phối cảnh, 3D SBS cho phim 3D.

**Quyết định cần chốt (Hùng + anh Khoa):**
- [ ] Tên app và icon (không dùng chữ "XREAL").
- [ ] Kênh phát hành: APK tự cài / GitHub Releases / Play Store (Play phải bỏ Trợ năng + QUERY_ALL_PACKAGES).
- [ ] Thiết bị mục tiêu chính: điện thoại, Beam Pro hay Beam đời đầu.
- [ ] Có thu phí không: miễn phí + Premium AI (cần backend), hay bán một lần.

**Lộ trình (sau khi test):** sửa theo log → backend AI → thông báo thật → GPS → GL/stereo.

## 11. Nhật ký phiên bản

| Ngày | Bản | Thay đổi |
| --- | --- | --- |
| 25/11/2026 | 0.6.0 | Thông báo thật của điện thoại trên kính + trả lời bằng giọng (gật đầu / "trả lời …"), tắt thông báo theo app, im lặng; ẩn màn khi đi nhanh theo GPS |
| 25/11/2026 | 0.5.1 | Mã ghép đôi 4 số cho điều khiển từ xa (khoá 60 s sau 5 lần sai, người lạ không đá được máy đang nối); APK build thật lần đầu trên GitHub Actions |
| 25/11/2026 | repo | Gom về monorepo GitHub `jinergenkai/xreal-space`: `android/` (giữ lịch sử commit), `pc/`, `pythontest/`, wiki ở `docs/`; CI build APK + test Python |
| 25/11/2026 | 0.5.0 | Điều khiển Beam đời đầu bằng điện thoại qua Wi-Fi; IMU/USB chạy nền ở cấp ứng dụng; thẻ Thiết lập lần đầu; crash log; sửa đăng nhập Google + Mở bằng Chrome; icon; script build Docker; wiki thêm giới hạn nền tảng, nợ kỹ thuật, checklist |
| 25/11/2026 | 0.4.0 | Màn hình điện thoại vào một ô + mở app bằng giọng + bấm qua chấm ngắm (Trợ năng); kính chạy nền khi mở app khác; teleprompter theo giọng đọc; phụ đề Việt/Anh và phiên dịch Anh↔Việt |
| 24/11/2026 | 0.3.0 | Touchpad toàn màn hình; tự kết nối / tự chiếu; bàn phím điện thoại + đọc chính tả; YouTube toàn màn hình (rạp phim); web tối; 3 chế độ Cố định / Rạp phim / Di chuyển; luật chạy trước Jev/LLM; tạm dừng màn khuất; wiki |
| 24/11/2026 | 0.2.0 | AI giọng nói: Vosk (từ đánh thức + nhận lệnh offline), Google STT, hiểu lệnh bằng luật / Jev / LLM; lưới 9 ô; không gian lưu sẵn; tóm tắt trang; cầm màn |
| 24/11/2026 | 0.1.0 | Port Android: IMU qua USB, 3 WebView world-locked, bản đồ, HUD, gật/lắc, wizard trục, touchpad, phím âm lượng, chẩn đoán; sửa lỗi bias gyro |
| 23/11/2026 | PC | Prototype Python: 3 màn hình desktop, HUD, bản đồ, test trên kính thật |
| 16/11/2026 | xr-air-lab 0.2 | PoC Python đọc IMU, sửa trôi yaw |

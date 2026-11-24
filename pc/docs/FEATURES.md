# Lộ trình tính năng và gói sản phẩm – Spatial Screens trên kính AR

Tài liệu cho đội phát triển bản thật. Nguyên tắc: **trước hết làm ra trải nghiệm "quá tuyệt vời"**,
rồi mới tối ưu chi phí. Phần AI đi theo hướng **LLM gọi hàm** (không dùng BERT ở giai đoạn này).

Ký hiệu: ✅ đã kiểm chứng trong prototype · 🟡 làm được với phần cứng hiện tại · 🔵 cần phần cứng mới hơn (kính có camera / Beam Pro).

---

## 1. Nền tảng đã kiểm chứng (prototype Python)

- ✅ Đọc IMU Air 2 ở 1000 Hz, head tracking 3DoF, màn hình đứng yên khi quay đầu.
- ✅ 3 màn hình làm việc lấy từ desktop thật, 60 fps.
- ✅ 3 kiểu neo: **world-locked** (màn hình làm việc), **lazy-follow** (bản đồ), **head-locked** (giờ, pin, thông báo).
- ✅ Chế độ đi bộ: ẩn màn lớn, giữ HUD nhỏ.
- ✅ Gật / lắc đầu (đã code, cần tinh chỉnh ngưỡng khi đeo thật).
- ✅ Hiệu chỉnh trục tự động bằng 2 cử chỉ.

---

## 2. Danh mục tính năng mở rộng

### A. Không gian và hiển thị

| Tính năng | Mô tả | Trạng thái |
|---|---|---|
| Màn hình cong / siêu rộng | Một màn cong 180° thay cho 3 màn rời; kéo giãn tùy ý | 🟡 |
| 3D thật (stereo SBS) | Dùng chế độ 3840×1080 của kính: mỗi mắt một hình, màn hình có chiều sâu thật, đặt gần/xa có cảm giác thật | 🟡 |
| Không gian làm việc lưu sẵn | "Văn phòng", "Code", "Họp", "Giải trí": mỗi bộ có bố cục, app, vị trí riêng; đổi bằng giọng nói | 🟡 |
| Rạp phim cá nhân | Màn hình khổng lồ, làm tối viền, âm thanh tập trung; xem video 3D SBS | 🟡 |
| Xem mô hình 3D | Xoay mô hình sản phẩm, kiến trúc bằng cử chỉ đầu hoặc điện thoại | 🟡 |
| Neo theo hướng / theo bàn | Ghim màn hình theo hướng la bàn; với kính có camera thì ghim lên bàn, tường | 🟡 / 🔵 |
| Tự làm mờ khi di chuyển | Phát hiện đang đi (gia tốc) thì tự chuyển sang chế độ đi bộ | 🟡 |

### B. Đa màn hình và điều khiển

| Tính năng | Mô tả | Trạng thái |
|---|---|---|
| Màn hình ảo thật trên Windows/Mac | Driver màn hình ảo: hệ điều hành thấy 3–5 màn thật, kéo cửa sổ qua lại, chuột chạy liền mạch | 🟡 |
| Chuột theo ánh nhìn | Nhìn màn nào, con trỏ tự nhảy sang màn đó | 🟡 |
| Tiêu điểm theo ánh nhìn | Màn đang nhìn sáng hơn, nhận bàn phím; màn khác mờ nhẹ | 🟡 |
| Nhiều trang web trong một app (Android) | App "vỏ không gian", mỗi màn là một WebView: YouTube, Maps, Docs, dashboard cùng lúc | 🟡 |
| Chạy nhiều app gốc cùng lúc | YouTube, Chrome, Zalo trên các màn riêng | 🔵 Beam Pro / DeX / quyền hệ thống |
| Màn hình nổi (picture-in-picture) | Một màn nhỏ luôn ở góc: camera an ninh, cuộc gọi, tỉ số trận đấu | 🟡 |
| Chuyển liền mạch PC ↔ điện thoại | Rút kính từ laptop cắm sang điện thoại, giữ nguyên bố cục và trang đang mở | 🟡 |

### C. Tương tác

| Cách | Dùng cho | Trạng thái |
|---|---|---|
| Gật / lắc đầu | Đồng ý, bỏ qua | ✅ |
| Nhìn giữ (dwell) + dấu ngắm | Chọn màn, bấm nút không cần tay | 🟡 |
| Điện thoại làm **laser ảo** | Chĩa điện thoại vào màn hình ảo để chọn (dùng IMU điện thoại) | 🟡 |
| Điện thoại làm touchpad / bàn phím | Thao tác chính xác, gõ chữ | 🟡 |
| Nút tai nghe, nhẫn, remote Bluetooth | Xác nhận kín đáo, bấm-để-nói | 🟡 |
| Nút trên gọng kính | Tận dụng nút độ sáng làm phím tắt (qua MCU HID, cần kiểm chứng) | 🟡 chưa xác nhận |
| Giọng nói "Hey Space" | Ra lệnh tự nhiên (xem mục D) | 🟡 |
| Theo dõi tay | Chụm ngón để click, kéo thả màn hình | 🔵 |

### D. AI – lớp tạo khác biệt

Kiến trúc: từ đánh thức chạy trên máy → giọng nói thành chữ → **LLM gọi hàm** → app thực hiện.
LLM nhận danh sách hàm của app (`open_windows`, `open_app`, `move_window`, `screen_off`, `set_workspace`, `toggle_map`...)
và trả về JSON. Ưu điểm: không cần dữ liệu train, hiểu câu phức tạp và trộn Việt–Anh.

| Tính năng | Ví dụ | Trạng thái |
|---|---|---|
| Lệnh giọng nói điều khiển không gian | "Hey Space, mở 3 màn hình, YouTube bên trái, bản đồ bên phải" | 🟡 |
| Lệnh nhiều bước | "Chuẩn bị họp": mở lịch, mở Meet, đưa ghi chú sang trái, tắt thông báo | 🟡 |
| Trợ lý hiểu màn hình | "Tóm tắt trang này", "Bảng này có gì bất thường?" (chụp màn đang nhìn gửi kèm) | 🟡 |
| Phụ đề trực tiếp | Phụ đề hội thoại ngay trước mắt, cho người khiếm thính hoặc trong môi trường ồn | 🟡 |
| Dịch trực tiếp | Nghe tiếng nước ngoài, hiện phụ đề tiếng Việt trong kính | 🟡 |
| Biên bản cuộc họp | Ghi âm, tóm tắt, danh sách việc cần làm sau họp | 🟡 |
| Máy nhắc chữ thông minh | Kịch bản tự cuộn theo giọng người nói | 🟡 |
| Lọc và tóm tắt thông báo | Chỉ hiện tin quan trọng; trả lời nhanh bằng một cái gật | 🟡 |
| Chỉ đường bằng lời | "Còn bao xa?", "Tìm quán cà phê gần nhất" | 🟡 |
| AI nhìn thấy | "Tôi đang nhìn cái gì?", dịch biển hiệu, đọc chữ | 🔵 cần camera |

### E. Di chuyển

| Tính năng | Mô tả | Trạng thái |
|---|---|---|
| Bản đồ HUD với GPS thật | Vị trí và hướng từ điện thoại (GPS + la bàn), mũi tên rẽ, khoảng cách | 🟡 |
| Cảnh báo an toàn | Tự ẩn nội dung khi đi nhanh hoặc qua đường; nội dung chỉ ở góc dưới | 🟡 |
| Thông báo khi đi | Tin nhắn, cuộc gọi: gật để nghe, lắc để từ chối | ✅ demo |

### F. Doanh nghiệp

| Tính năng | Mô tả |
|---|---|
| Hỗ trợ hiện trường | Kỹ thuật viên xem quy trình, sơ đồ; chuyên gia từ xa nhìn cùng (khi có camera) |
| Checklist rảnh tay | Từng bước hiện trong kính, "xong" bằng giọng nói hoặc gật đầu, tự ghi log |
| Kho vận | Hiện vị trí hàng cần lấy, xác nhận bằng giọng nói |
| Trình bày, bán hàng | Máy nhắc chữ, số liệu riêng chỉ người nói thấy |
| Quản lý thiết bị | Cấu hình hàng loạt, khóa chế độ kiosk, LLM chạy nội bộ (on-premise) cho dữ liệu nhạy cảm |

---

## 3. Đóng gói sản phẩm (đề xuất)

| Gói | Đối tượng | Nội dung | Giá |
|---|---|---|---|
| **Free** | Người mới mua kính | 1–2 màn hình, neo cố định / theo đầu, đưa về giữa, gật / lắc đầu | Miễn phí |
| **Pro** (mua một lần) | Người dùng làm việc | 3+ màn hình, màn hình ảo thật trên PC, màn cong, không gian lưu sẵn, chuột theo ánh nhìn, điện thoại làm laser / touchpad, 3D stereo, bản đồ HUD, chế độ đi bộ | [__] |
| **Premium AI** (thuê bao tháng) | Người dùng cao cấp | Toàn bộ Pro + "Hey Space" bằng LLM, lệnh nhiều bước, trợ lý hiểu màn hình, phụ đề, dịch trực tiếp, biên bản họp, tóm tắt thông báo | [__]/tháng |
| **Enterprise** | Doanh nghiệp | Checklist, hỗ trợ hiện trường, quản lý thiết bị, LLM nội bộ, SLA, tích hợp hệ thống riêng | Theo hợp đồng |

Gợi ý: phần mềm Pro bán một lần dễ bán hơn thuê bao. AI có chi phí vận hành nên mới đặt vào thuê bao.
Người dùng trả tiền vì **giá trị nhận được**, không phải vì đã có kính, nên Premium phải có 2–3 tính năng "không thể thiếu"
(đề xuất: lệnh giọng nói không gian, phụ đề / dịch trực tiếp, biên bản họp).

---

## 4. Giới hạn cuối cùng của phần cứng hiện tại (Air 2)

| Giới hạn | Hệ quả | Cách xử lý / thứ mở khóa |
|---|---|---|
| Chỉ 3DoF, không camera | Không biết người dùng đi tới, không ghim vật lên bàn, không theo dõi tay | Chế độ đi bộ, lazy-follow; kính có camera (Air 2 Ultra, thế hệ sau) mở khóa 6DoF + tay |
| Hướng ngang trôi | Màn hình lệch dần sau vài phút | Đưa về giữa bằng phím / giọng nói; thử dùng từ kế; SDK chính thức |
| Góc nhìn ~46° | Chỉ một màn nằm gọn trong tầm mắt | Bố cục quay đầu, màn cong, thu nhỏ màn phụ |
| Phụ thuộc thiết bị nguồn | Cần laptop / điện thoại / Beam cắm dây | Thiết kế app chạy được trên cả ba |
| Giao thức IMU là reverse-engineer | Firmware mới có thể đổi giao thức | Theo dõi driver cộng đồng; cân nhắc SDK chính thức của XREAL cho Android |
| Android giới hạn đa app | App thứ ba khó chạy song song | Hướng WebView trong app vỏ; Beam Pro / DeX cho app gốc |

---

## 5. Sau đó mới tối ưu chi phí

1. Từ đánh thức luôn chạy trên máy (không tốn API).
2. Cache phần prompt mô tả hàm (phần lớn token đầu vào).
3. Chọn model nhỏ, rẻ cho lệnh điều khiển; model lớn chỉ cho tóm tắt, phân tích.
4. Dùng lịch sử lệnh (có nhãn từ LLM) để train model nhỏ chạy trên máy cho các lệnh hay dùng nhất.
5. STT trên máy cho lệnh ngắn; STT cloud cho phụ đề dài.

## 6. Rủi ro cần theo dõi

- An toàn khi đi đường: giới hạn nội dung, có thể cần cảnh báo pháp lý.
- Quyền riêng tư: mic luôn nghe từ đánh thức, phải xử lý trên máy và nói rõ với người dùng.
- Phụ thuộc nhà cung cấp LLM: thiết kế lớp trừu tượng để đổi model dễ dàng.
- Pin và nhiệt trên điện thoại khi vừa xuất hình vừa chạy AI.

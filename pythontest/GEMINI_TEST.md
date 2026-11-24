# Kịch bản test cho Gemini (copy toàn bộ phần dưới đường kẻ vào Gemini)

---

Bạn là trợ lý test phần cứng. Tôi có kính **XREAL Air 2** (USB VID 0x3318, PID 0x0428) và repo Python
`xr-air-lab` (đang mở trong thư mục hiện tại). Nhiệm vụ: chạy lần lượt các bước dưới, **dừng lại sau
mỗi bước có ghi "HỎI TÔI"**, rồi điền báo cáo ở cuối. Không sửa code trừ khi tôi đồng ý; nếu cần sửa,
đề xuất diff trước.

Quy tắc an toàn:
- Chỉ chạy các lệnh `python -m xrair ...` liệt kê ở đây. Không tự viết lệnh gửi byte lạ tới kính.
- Không cài thêm gói nào ngoài `hidapi` (ổ đĩa đang gần đầy).
- Nếu lệnh treo quá 30 giây, Ctrl+C và ghi lại.

## Bước 0 — Môi trường
```
python --version
pip install hidapi
python -m unittest -v tests.test_protocol
```
Kỳ vọng: 9 test OK (test này không cần kính).

## Bước 1 — Nhận diện (không gửi lệnh nào tới kính)
Cắm kính vào máy. Chạy:
```
python -m xrair doctor
python -m xrair probe
```
Kỳ vọng: thấy PID 0x0428, có interface **3 (IMU)** và **4 (MCU)**, mở được cả hai.
Ghi lại: danh sách interface, màn hình kính có xuất hiện không (có hình hay chỉ USB).
Nếu không thấy kính: thử cổng/cáp khác, xem Device Manager > Human Interface Devices, ghi triệu chứng.
**HỎI TÔI** trước khi sang bước 2.

## Bước 2 — Đọc thông tin + IMU
```
python -m xrair -v info
```
Kỳ vọng: in Serial, Firmware, Độ sáng, Display mode, `IMU rate` > 50 gói/giây, `|accel|` ≈ 1g,
dòng cuối `KẾT QUẢ: PASS`. Lưu lại `calibration.json` nếu có (không gửi nội dung lên mạng).
Nếu FAIL: chép toàn bộ log `-v` vào báo cáo.

## Bước 3 — Xoay đầu (đã chạy 1 lần ở v0.1: dấu đúng, nhưng yaw trôi ~11°/s → v0.2 đã sửa)
Đã biết từ lần chạy trước: `--forward -y` đúng, không cần flip. Dùng `<F>` = `-y`.

**3a. Đo drift** — đặt kính NẰM YÊN trên bàn, không chạm vào, chạy:
```
python -m xrair imu --forward -y --still --seconds 60
```
Kỳ vọng: dòng cuối `Drift: ... OK (< 3°/phút)`. Ghi lại con số °/phút.

**3b. Xoay đầu** — đeo kính, lúc bắt đầu giữ yên đầu tới khi hết dòng "Giữ yên...":
```
python -m xrair imu --forward -y --raw --seconds 40
```
Nhờ tôi: quay phải ~45° → về giữa → quay trái ~45° → về giữa → ngẩng lên → về giữa.
Kỳ vọng: nhìn thẳng thì yaw ≈ 0 **và pitch ≈ 0** (v0.2 bù độ nghiêng ~20° của cảm biến);
quay phải yaw tăng, ngẩng lên pitch tăng; mỗi lần về giữa sai lệch < 5°.

## Bước 4 — Nút bấm và độ sáng
```
python -m xrair mcu --listen 15
```
Nhờ tôi bấm lần lượt từng nút trên gọng kính. Ghi lại mã `code` của từng nút.
```
python -m xrair mcu --set-brightness 2
python -m xrair mcu --set-brightness 6
```
Kỳ vọng: độ sáng trên kính đổi thật và đọc lại đúng giá trị.

## Bước 5 — Ghi mẫu chuyển động
```
python -m xrair imu --forward <F> --record my_motion.csv --seconds 30
```
Nhờ tôi quay đầu tự do. Giữ file `my_motion.csv` (dùng để dev offline).

## Bước 6 — Demo app
Nếu kính CÓ hình (máy có USB-C xuất hình):
```
python -m xrair demo --forward <F>
```
Nếu kính KHÔNG có hình (cắm qua USB-A):
```
python -m xrair demo --forward <F> --windowed
```
Nhờ tôi kiểm tra: panel "Đồng hồ" ở giữa đứng yên khi quay đầu; quay trái thấy "Ghi chú", quay phải thấy
"Trạng thái", cúi xuống thấy "Bản đồ"; phím `M` đổi sang "Thu nhỏ ở góc"; `R` recenter; `[ ]` đổi độ sáng;
bấm nút trên kính thì hiện thông báo mã nút.

## Báo cáo (điền và gửi lại cho tôi)

| Bước | Kết quả (PASS/FAIL) | Ghi chú / số liệu |
|---|---|---|
| 0 Môi trường | | Python version, test |
| 1 Nhận diện | | interface list, có hình không |
| 2 Info + IMU | | serial?, firmware, rate Hz, \|accel\| |
| 3a Drift | | °/phút khi để yên 60s |
| 3b Xoay đầu | | về giữa lệch bao nhiêu độ, pitch khi nhìn thẳng |
| 4 Nút + độ sáng | | mã từng nút, độ sáng đổi được không |
| 5 Ghi mẫu | | tên file |
| 6 Demo | | cảm giác ghim, độ trễ, lỗi |

Kèm: toàn bộ log của bước nào FAIL, và mọi điểm khác với kỳ vọng.

package vn.jiner.xrealspace.core

/**
 * "What is missing, and what does it break?" — every requirement the app depends on, the features that need it,
 * and how to fix it. The Android side only fills in the statuses; the wording and the feature map live here
 * (testable, one place to keep in sync with the wiki).
 */
object Health {
    enum class Status { OK, WARN, MISSING, NA }

    enum class Req(val title: String, val fix: String, val features: List<String>) {
        GLASSES_USB("Kính đã cắm (USB)", "Cắm kính, bấm Cho phép khi Android hỏi quyền USB",
            listOf("Bám đầu (màn đứng yên)", "Cử chỉ gật/lắc", "Hướng dẫn trên kính")),
        GLASSES_DISPLAY("Điện thoại xuất hình ra kính", "Cần điện thoại có cổng USB-C xuất hình (DP Alt Mode); máy không hỗ trợ thì dùng Beam / Beam Pro",
            listOf("Mọi thứ hiện trên kính")),
        IMU_DATA("Cảm biến đầu gửi dữ liệu", "Rút cắm lại kính; trên Beam: tắt chế độ neo màn hình của Beam",
            listOf("Bám đầu", "Cử chỉ gật/lắc")),
        OVERLAY("Hiện trên app khác", "Cài đặt → Hiển thị trên ứng dụng khác → XREAL Space → Cho phép",
            listOf("Kính vẫn chạy khi mở app khác", "Màn hình điện thoại trên kính", "App Android trong ô")),
        MIC("Micro", "Cấp quyền Micro", listOf("Lệnh giọng nói", "Phụ đề / dịch", "Nhắc chữ", "Trả lời tin nhắn bằng giọng")),
        VOICE_MODEL("Model giọng nói tiếng Việt (offline)", "Bấm Tải model ở mục Giọng nói (≈ 32 MB, cần mạng 1 lần)",
            listOf("\"Kính ơi\"", "Lệnh giọng nói offline", "Phụ đề")),
        TTS_VI("Giọng đọc tiếng Việt", "Cài \"Speech Services by Google\" → Cài đặt → tải giọng tiếng Việt", listOf("Đọc to thông báo, chỉ đường, hướng dẫn")),
        ACCESSIBILITY("Trợ năng", "Cài đặt → Trợ năng → XREAL Space → Bật", listOf("Bấm vào app điện thoại từ kính", "Nút âm lượng ở mọi app")),
        NOTIF_ACCESS("Đọc thông báo", "Cài đặt → Truy cập thông báo → XREAL Space → Cho phép",
            listOf("Tin nhắn hiện trên kính + trả lời", "Điều khiển app nhạc")),
        POST_NOTIF("Thông báo của app", "Cấp quyền Thông báo (Android 13+)", listOf("Biểu tượng chạy nền ổn định")),
        LOCATION("Vị trí", "Cấp quyền Vị trí", listOf("Bản đồ thật + chỉ đường", "Ẩn màn khi đi nhanh", "Thời tiết")),
        CALENDAR("Lịch", "Cấp quyền Lịch", listOf("Lịch hôm nay + nhắc trước 10 phút")),
        BATTERY("Không tối ưu pin", "Cài đặt → Pin → XREAL Space → Không tối ưu hoá (Xiaomi/Oppo/Vivo: bật thêm Tự khởi chạy)",
            listOf("Chạy nền lâu không bị tắt")),
        INTERNET("Mạng Internet", "Bật Wi-Fi / 4G", listOf("Web", "Bản đồ", "Thời tiết", "AI", "Tải model giọng nói")),
        LLM("AI (LLM) đã cấu hình", "Nhập API key / địa chỉ Ollama ở mục Cài đặt AI", listOf("Hỏi AI", "Tóm tắt trang", "Dịch phụ đề")),
        ANDROID_10("Android 10 trở lên", "Không sửa được trên máy này", listOf("Phụ đề từ tiếng phim")),
        SHIZUKU("Shizuku đang chạy + đã cho phép", "Cài Shizuku (Play Store) → khởi động qua Gỡ lỗi không dây → mở XREAL Space → Cho phép",
            listOf("App Android trong ô (nhiều app cùng lúc)")),
        ANDROID_13("Android 13 trở lên", "Không sửa được trên máy này", listOf("App Android trong ô")),
        UPDATE("Bản mới nhất", "Bấm Cập nhật", listOf("Sửa lỗi + tính năng mới")),
    }

    data class Item(val req: Req, val status: Status, val detail: String = "")

    /** features that do not work right now, with the requirement that blocks each (first missing wins) */
    fun blocked(items: List<Item>): Map<String, Req> {
        val out = LinkedHashMap<String, Req>()
        for (it in items) if (it.status == Status.MISSING) for (f in it.req.features) out.putIfAbsent(f, it.req)
        return out
    }

    fun summary(items: List<Item>): String {
        val relevant = items.filter { it.status != Status.NA }
        val ok = relevant.count { it.status == Status.OK }
        val missing = relevant.count { it.status == Status.MISSING }
        val warn = relevant.count { it.status == Status.WARN }
        return when {
            missing == 0 && warn == 0 -> "✓ Đủ hết ($ok/${relevant.size})"
            missing == 0 -> "✓ Chạy được · $warn mục nên xem"
            else -> "✗ Thiếu $missing mục · ${blocked(items).size} tính năng chưa dùng được"
        }
    }

    fun mark(s: Status) = when (s) { Status.OK -> "✓"; Status.WARN -> "⚠"; Status.MISSING -> "✗"; Status.NA -> "–" }

    /** one-line explanation for a feature that just failed: "Cần: Micro — Cấp quyền Micro" */
    fun why(req: Req) = "Cần: ${req.title} — ${req.fix}"
}

/** Semantic version check for the updater: tag "v0.10.0" or "0.10.0-11" (code after the dash wins when present). */
object Versions {
    fun code(tag: String): Int? = tag.substringAfterLast('-', "").toIntOrNull()
    fun parts(tag: String) = tag.trimStart('v', 'V').substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }

    fun newer(remoteTag: String, localName: String, localCode: Int): Boolean {
        code(remoteTag)?.let { return it > localCode }
        val a = parts(remoteTag); val b = parts(localName)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }; val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}

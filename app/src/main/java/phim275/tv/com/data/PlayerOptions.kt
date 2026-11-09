package phim275.tv.com.data

import org.json.JSONObject

/** Trạng thái trình phát video chính trên trang (do extension tvnav đọc trong frame có video). */
data class VideoInfo(
    /** "jwplayer", "hls.js" hoặc "html5" (chỉ chỉnh được tốc độ). */
    val engine: String,
    val rate: Double,
    /** Các mức chất lượng theo thứ tự của trình phát (có thể gồm "Tự động"); rỗng = trang không cho chọn. */
    val levels: List<String>,
    /** Chỉ số mức đang dùng trong [levels], -1 = không rõ. */
    val level: Int,
    /** Chiều cao khung hình thật đang phát (px), 0 = chưa biết. */
    val height: Int = 0,
) {
    val currentLevelLabel: String? get() = levels.getOrNull(level)

    /** Có từ 2 mức trở lên mới cho chọn. */
    val canChooseLevel: Boolean get() = levels.size >= 2

    /** Độ phân giải đang phát: "720p", kèm "Tự động" nếu trình phát đang tự chọn. */
    val qualitySummary: String get() {
        val real = if (height > 0) "${height}p" else null
        val label = currentLevelLabel?.takeUnless { it.startsWith("Mức ") }
        return when {
            label == "Tự động" && real != null -> "Tự động · $real"
            label != null -> label
            real != null -> real
            else -> "—"
        }
    }
}

/** Tùy chọn video: tốc độ, chất lượng (thuần, có unit test). */
object PlayerOptions {
    val RATES = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 1.75, 2.0)

    /** 1.0 → "Bình thường", 1.25 → "1,25×", 2.0 → "2×". */
    fun rateLabel(rate: Double): String {
        if (kotlin.math.abs(rate - 1.0) < 0.001) return "Bình thường"
        val text = if (rate % 1.0 == 0.0) rate.toInt().toString() else "%.2f".format(java.util.Locale.US, rate).trimEnd('0').trimEnd('.')
        return text.replace('.', ',') + "×"
    }

    /** Tốc độ trong [RATES] gần với tốc độ thực nhất (trang có thể đặt 1.0000001…). */
    fun nearestRate(rate: Double): Double = RATES.minBy { kotlin.math.abs(it - rate) }

    /** Nhãn chất lượng của trình phát → tiếng Việt gọn ("Auto" → "Tự động", "1080p HD" giữ nguyên). */
    fun levelLabel(raw: String): String {
        val s = raw.trim()
        return when {
            s.isEmpty() -> "Không tên"
            s.equals("auto", ignoreCase = true) || s.equals("automatic", ignoreCase = true) -> "Tự động"
            else -> s
        }
    }

    /** Thứ tự hiển thị các mức: "Tự động" (và mức không có số) trước, rồi độ phân giải cao → thấp. */
    fun displayOrder(levels: List<String>): List<Int> {
        val number = { s: String -> Regex("""(\d{3,4})""").find(s)?.groupValues?.get(1)?.toIntOrNull() }
        return levels.indices.sortedWith(compareBy({ number(levels[it]) != null }, { -(number(levels[it]) ?: 0) }, { it }))
    }

    fun parse(json: JSONObject?): VideoInfo? {
        if (json == null) return null
        val rate = json.optDouble("rate", Double.NaN)
        if (rate.isNaN() || rate <= 0) return null
        val arr = json.optJSONArray("levels")
        val levels = if (arr == null) emptyList() else (0 until arr.length()).map { levelLabel(arr.optString(it)) }
        val level = json.optInt("level", -1).takeIf { it in levels.indices } ?: -1
        return VideoInfo(json.optString("engine", "html5"), rate, levels, level, json.optInt("height", 0).coerceAtLeast(0))
    }
}

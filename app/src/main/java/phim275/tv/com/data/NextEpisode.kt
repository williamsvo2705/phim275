package phim275.tv.com.data

/** Khi nào hiện thẻ "Tập tiếp theo" (thuần, có unit test). */
object NextEpisodePolicy {
    /** Thẻ hiện khi còn khoảng 4% thời lượng (phần chữ chạy cuối phim), trong khoảng 30 giây – 2 phút. */
    const val LEAD_FRACTION = 0.04
    const val LEAD_MIN_SEC = 30.0
    const val LEAD_MAX_SEC = 120.0
    /** Phim hết mà người xem không bấm gì → tự sang tập sau sau ngần này giây. */
    const val AUTO_ADVANCE_SEC = 10

    fun leadSec(durationSec: Double): Double = (durationSec * LEAD_FRACTION).coerceIn(LEAD_MIN_SEC, LEAD_MAX_SEC)

    /** Đang ở đoạn cuối của video chính (không tính quảng cáo ngắn, phát trực tiếp, vị trí lạ). */
    fun isCardDue(positionSec: Double, durationSec: Double): Boolean {
        if (!PlaybackCodec.isTrackable(durationSec) || !positionSec.isFinite()) return false
        return durationSec - positionSec <= leadSec(durationSec)
    }

    /** Tên hiển thị gọn: chuỗi rỗng → "Tập tiếp theo"; quá dài thì cắt. */
    fun displayLabel(raw: String?): String {
        val s = raw?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        if (s.isEmpty()) return "Tập tiếp theo"
        return if (s.length > MAX_LABEL) s.take(MAX_LABEL - 1).trimEnd() + "…" else s
    }

    private const val MAX_LABEL = 48
}

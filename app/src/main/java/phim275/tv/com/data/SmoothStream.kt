package phim275.tv.com.data

import org.json.JSONObject

/**
 * Luồng video của trang (playlist HLS hoặc file video) để phát bằng trình phát mượt (ExoPlayer, đường video phần cứng
 * của TV) thay cho trình phát web — Gecko ghép video bằng GPU nên phim 24 hình/giây trên màn 60Hz bị lệch nhịp.
 */
data class SmoothStream(
    val url: String,
    val isHls: Boolean,
    val referer: String?,
    val origin: String?,
    val userAgent: String?,
    /** Vị trí / độ dài video trên trang lúc hỏi (giây; 0 = chưa biết / phát trực tiếp). */
    val positionSec: Double,
    val durationSec: Double,
    /** Bản chép các playlist trang đã tải (URL → nội dung): link playlist dùng một lần thì không xin lại được. */
    val playlists: Map<String, String> = emptyMap(),
    /** Trang đang hiện phụ đề rời (WebVTT…) — trình phát mượt không hiện được → không tự chuyển. */
    val hasSubtitles: Boolean = false,
) {
    companion object {
        /** Trả lời của tvnav cho `{ type: 'stream' }`: `{ stream: { url, kind, referer, origin, ua }, video: { time, duration } }`. */
        fun parse(json: JSONObject?): SmoothStream? {
            val s = json?.optJSONObject("stream") ?: return null
            val url = s.optString("url")
            if (!url.startsWith("https://") && !url.startsWith("http://")) return null
            val kind = s.optString("kind")
            if (kind != "hls" && kind != "file") return null
            val video = json.optJSONObject("video")
            return SmoothStream(
                url = url,
                isHls = kind == "hls",
                referer = s.text("referer"),
                origin = s.text("origin"),
                userAgent = s.text("ua"),
                positionSec = video?.optDouble("time", 0.0)?.takeIf { it.isFinite() && it >= 0 } ?: 0.0,
                durationSec = video?.optDouble("duration", 0.0)?.takeIf { it.isFinite() && it > 0 } ?: 0.0,
                playlists = s.optJSONObject("playlists")?.let { o ->
                    o.keys().asSequence().mapNotNull { k -> o.optString(k).takeIf { it.startsWith("#EXTM3U") }?.let { k to it } }.toMap()
                }.orEmpty(),
                hasSubtitles = (video?.optInt("subs", 0) ?: 0) > 0,
            )
        }

        private fun JSONObject.text(key: String): String? = optString(key).takeIf { it.isNotBlank() && it != "null" }
    }
}

/** Kết quả trình phát mượt trả về trang. */
object SmoothResult {
    /** Thoát bằng Back: về trang, dừng ở chỗ đang xem. */
    const val BACK = 0
    /** Phát hết video: trang hiện thẻ "Tập tiếp theo" như khi video web kết thúc. */
    const val ENDED = 1
    /** Người xem chọn "Dùng trình phát của trang": tiếp tục trên web ở cùng vị trí. */
    const val USE_WEB = 2
    /** Không phát được luồng: quay lại trình phát web, không thử lại trên trang này. */
    const val ERROR = 3

    /** Số giây cần tua video web để khớp vị trí trình phát mượt vừa dừng (web đứng yên tại [webSec] lúc chuyển). */
    fun seekDelta(webSec: Double, smoothSec: Double): Double = smoothSec - webSec
}

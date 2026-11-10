package phim275.tv.com.data

import android.content.Context
import org.json.JSONObject

/** Vị trí đang xem của video chính trên một trang (giây). */
data class Playback(val positionSec: Double, val durationSec: Double, val timeMs: Long) {
    val fraction: Double get() = if (durationSec > 0) (positionSec / durationSec).coerceIn(0.0, 1.0) else 0.0
}

/** Logic "xem tiếp đúng chỗ" (thuần, có unit test). */
object PlaybackCodec {
    /** Video ngắn hơn thế này là quảng cáo chèn đầu / đoạn xem trước → không ghi nhớ. */
    const val MIN_DURATION_SEC = 120.0
    /** Mới xem chưa tới mức này → coi như chưa xem, phát từ đầu. */
    const val MIN_RESUME_SEC = 30.0
    /** Còn ít hơn thế này là đã xem hết (thường chỉ còn phần giới thiệu tập sau) → phát từ đầu. */
    const val END_MARGIN_SEC = 90.0
    /** Video bắt đầu phát ở quá mốc này → trang tự xem tiếp (hoặc người xem đã tua): app không can thiệp. */
    const val FRESH_START_SEC = 15.0

    /** Bỏ phần #… của URL: cùng trang dù trang đổi hash. */
    fun key(url: String): String = url.substringBefore('#')

    fun isTrackable(durationSec: Double): Boolean = durationSec.isFinite() && durationSec >= MIN_DURATION_SEC

    /** Đã xem hết (hoặc gần hết) → không cần nhớ vị trí. */
    fun isFinished(positionSec: Double, durationSec: Double): Boolean = positionSec >= durationSec - END_MARGIN_SEC

    /** Video bắt đầu phát ở giữa phim mà app chưa tua → trang có tính năng xem tiếp riêng. */
    fun siteResumedItself(startSec: Double): Boolean = startSec > FRESH_START_SEC

    /**
     * Vị trí cần tua tới khi video (dài [durationSec]) bắt đầu phát ở [startSec], hoặc null nếu nên để yên.
     * Độ dài phải khớp: cùng URL nhưng trình phát đã đổi sang video khác thì không áp vị trí cũ.
     * Trang có tính năng xem tiếp riêng ([siteResumes]: hộp "Tiếp tục xem / Xem lại từ đầu") → nhường trang,
     * kẻo người xem chọn "xem lại từ đầu" mà app lại tua đi.
     */
    fun resumePosition(saved: Playback?, durationSec: Double, startSec: Double = 0.0, siteResumes: Boolean = false): Double? {
        if (siteResumes || siteResumedItself(startSec)) return null
        if (saved == null || !isTrackable(durationSec)) return null
        val tolerance = maxOf(5.0, durationSec * 0.02)
        if (kotlin.math.abs(saved.durationSec - durationSec) > tolerance) return null
        if (saved.positionSec < MIN_RESUME_SEC || isFinished(saved.positionSec, durationSec)) return null
        return saved.positionSec
    }

    /** Ghi vị trí mới (bỏ mục khi đã xem hết), giữ tối đa [max] trang gần nhất. */
    fun record(map: Map<String, Playback>, url: String, positionSec: Double, durationSec: Double, nowMs: Long, max: Int): Map<String, Playback> {
        if (!isTrackable(durationSec) || !positionSec.isFinite() || positionSec < 0) return map
        val k = key(url)
        val next = LinkedHashMap(map)
        next.remove(k)
        if (!isFinished(positionSec, durationSec)) next[k] = Playback(positionSec, durationSec, nowMs)
        if (next.size <= max) return next
        return next.entries.sortedByDescending { it.value.timeMs }.take(max).associateTo(LinkedHashMap()) { it.key to it.value }
    }

    fun encode(map: Map<String, Playback>): String {
        val o = JSONObject()
        map.forEach { (url, p) -> o.put(url, JSONObject().put("p", p.positionSec).put("d", p.durationSec).put("t", p.timeMs)) }
        return o.toString()
    }

    fun decode(json: String?): Map<String, Playback> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            val o = JSONObject(json)
            val out = LinkedHashMap<String, Playback>()
            for (url in o.keys()) {
                val e = o.optJSONObject(url) ?: continue
                val p = e.optDouble("p", Double.NaN)
                val d = e.optDouble("d", Double.NaN)
                if (p.isNaN() || d.isNaN()) continue
                out[url] = Playback(p, d, e.optLong("t", 0L))
            }
            out
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** "1:02:03" hoặc "12:34". */
    fun formatTime(sec: Double): String {
        val s = sec.toLong().coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }
}

/** Vị trí đang xem theo trang (SharedPreferences); ghi xuống đĩa thưa để đỡ hao bộ nhớ flash. */
class PlaybackStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phim275_playback", Context.MODE_PRIVATE)
    private var cache: Map<String, Playback>? = null
    private var lastSaveMs = 0L
    private var selfResuming: Set<String>? = null

    private fun all(): Map<String, Playback> = cache ?: PlaybackCodec.decode(prefs.getString(KEY, null)).also { cache = it }

    fun get(url: String): Playback? = all()[PlaybackCodec.key(url)]

    /** [flush] = ghi ngay (tạm dừng, rời trang); còn lại gộp, tối đa mỗi 15 giây một lần. */
    fun record(url: String, positionSec: Double, durationSec: Double, flush: Boolean) {
        val now = System.currentTimeMillis()
        val next = PlaybackCodec.record(all(), url, positionSec, durationSec, now, MAX)
        cache = next
        if (flush || now - lastSaveMs >= SAVE_INTERVAL_MS) {
            lastSaveMs = now
            prefs.edit().putString(KEY, PlaybackCodec.encode(next)).apply()
        }
    }

    /** Tên miền tự xem tiếp được (đã thấy video tự bắt đầu ở giữa phim). */
    fun siteResumes(host: String): Boolean = host in selfResumingHosts()

    fun markSiteResumes(host: String) {
        val hosts = selfResumingHosts()
        if (host in hosts) return
        val next = (hosts + host).toList().takeLast(MAX_HOSTS).toSet()
        selfResuming = next
        prefs.edit().putStringSet(KEY_HOSTS, next).apply()
    }

    private fun selfResumingHosts(): Set<String> =
        selfResuming ?: (prefs.getStringSet(KEY_HOSTS, null)?.toSet() ?: emptySet()).also { selfResuming = it }

    /** Quên vị trí đang xem của một trang (người xem xóa ô "Xem tiếp" trên trang chủ). */
    fun remove(url: String) {
        val k = PlaybackCodec.key(url)
        if (k !in all()) return
        val next = LinkedHashMap(all()).apply { remove(k) }
        cache = next
        prefs.edit().putString(KEY, PlaybackCodec.encode(next)).apply()
    }

    fun clear() {
        cache = emptyMap()
        prefs.edit().remove(KEY).apply()
    }

    private companion object {
        const val KEY = "positions"
        const val KEY_HOSTS = "self_resuming_hosts"
        const val MAX_HOSTS = 100
        const val MAX = 150
        const val SAVE_INTERVAL_MS = 15_000L
    }
}

package phim275.tv.com.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tab đã lưu để mở lại khi khởi động app (sau khi cập nhật, hoặc khi Android đóng app vì thiếu RAM). */
data class SavedTab(val url: String, val title: String?, val desktopMode: Boolean)

data class SavedSession(val tabs: List<SavedTab>, val currentIndex: Int)

/** Mã hóa phiên duyệt web thành JSON (tách riêng để unit test). */
object SessionCodec {
    /** Không lưu trang lỗi (data:), trang của extension, about:. */
    fun isRestorable(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("resource://android/"))

    fun encode(session: SavedSession): String {
        val arr = JSONArray()
        session.tabs.forEach { t ->
            arr.put(JSONObject().put("u", t.url).put("t", t.title ?: "").put("d", t.desktopMode))
        }
        return JSONObject().put("c", session.currentIndex).put("tabs", arr).toString()
    }

    fun decode(json: String?): SavedSession? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val arr = o.getJSONArray("tabs")
            val tabs = (0 until arr.length()).mapNotNull { i ->
                val t = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = t.optString("u").takeIf(::isRestorable) ?: return@mapNotNull null
                SavedTab(url, t.optString("t").takeIf { it.isNotEmpty() }, t.optBoolean("d", true))
            }
            if (tabs.isEmpty()) null else SavedSession(tabs, o.optInt("c", 0).coerceIn(0, tabs.size - 1))
        } catch (_: Exception) {
            null
        }
    }
}

class SessionStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("phim275_session", Context.MODE_PRIVATE)

    fun save(session: SavedSession) = prefs.edit().putString(KEY, SessionCodec.encode(session)).apply()

    fun load(): SavedSession? = SessionCodec.decode(prefs.getString(KEY, null))

    private companion object {
        const val KEY = "last_session"
    }
}

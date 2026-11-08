package phim275.tv.com.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Link(val url: String, val title: String, val timeMs: Long)

/** Mã hóa danh sách liên kết thành JSON (tách riêng để unit test). */
object LinkCodec {
    fun encode(links: List<Link>): String {
        val arr = JSONArray()
        links.forEach { arr.put(JSONObject().put("u", it.url).put("t", it.title).put("d", it.timeMs)) }
        return arr.toString()
    }

    fun decode(json: String?): List<Link> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("u").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                Link(url, o.optString("t", url), o.optLong("d", 0L))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Thêm lên đầu, bỏ bản trùng URL, giới hạn số lượng. */
    fun pushFront(links: List<Link>, link: Link, max: Int): List<Link> =
        (listOf(link) + links.filter { it.url != link.url }).take(max)
}

/** Danh sách liên kết lưu trong SharedPreferences (bookmark, lịch sử). */
class LinkStore(context: Context, private val key: String, private val max: Int) {
    private val prefs = context.applicationContext.getSharedPreferences("phim275_links", Context.MODE_PRIVATE)
    private var cache: List<Link>? = null

    fun all(): List<Link> = cache ?: LinkCodec.decode(prefs.getString(key, null)).also { cache = it }

    fun contains(url: String) = all().any { it.url == url }

    fun add(url: String, title: String?) {
        save(LinkCodec.pushFront(all(), Link(url, title?.takeIf { it.isNotBlank() } ?: url, System.currentTimeMillis()), max))
    }

    fun remove(url: String) = save(all().filter { it.url != url })

    fun clear() = save(emptyList())

    private fun save(links: List<Link>) {
        cache = links
        prefs.edit().putString(key, LinkCodec.encode(links)).apply()
    }

    companion object {
        fun bookmarks(context: Context) = LinkStore(context, "bookmarks", 200)
        fun history(context: Context) = LinkStore(context, "history", 300)

        /** Không lưu trang nội bộ vào lịch sử. */
        fun isRecordable(url: String?): Boolean =
            url != null && (url.startsWith("http://") || url.startsWith("https://"))
    }
}

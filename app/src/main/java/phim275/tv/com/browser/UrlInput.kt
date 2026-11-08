package phim275.tv.com.browser

import java.net.URLEncoder

/** Chuyển chữ người dùng gõ trên thanh địa chỉ thành URL để tải. */
object UrlInput {
    const val SEARCH_TEMPLATE = "https://www.google.com/search?q=%s"

    private val PASS_THROUGH_SCHEMES = listOf(
        "http://", "https://", "about:", "resource://", "moz-extension://", "file://", "view-source:",
    )
    private val HOST_LIKE = Regex(
        """^(localhost|(\d{1,3}\.){3}\d{1,3}|([a-z0-9-]+\.)+[a-z]{2,})(:\d{1,5})?([/?#].*)?$""",
        RegexOption.IGNORE_CASE,
    )
    private val LOCAL_HOST = Regex("""^(localhost|(\d{1,3}\.){3}\d{1,3})([:/?#].*)?$""")

    /** Trả về null nếu chuỗi rỗng. */
    fun toLoadableUrl(raw: String, searchTemplate: String = SEARCH_TEMPLATE): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase()
        if (PASS_THROUGH_SCHEMES.any { lower.startsWith(it) }) return text
        if (!text.contains(' ') && HOST_LIKE.matches(text)) {
            // Mạng nội bộ thường không có HTTPS.
            return if (LOCAL_HOST.matches(lower)) "http://$text" else "https://$text"
        }
        return searchTemplate.format(URLEncoder.encode(text, "UTF-8"))
    }
}

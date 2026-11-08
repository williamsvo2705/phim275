package phim275.tv.com.browser

import java.net.URI

/**
 * Chính sách chặn popup / chuyển hướng ở tầng native — lớp phòng thủ thứ hai
 * bên cạnh uBlock Origin (uBO chặn theo bộ lọc, lớp này chặn theo hành vi).
 */
object SitePolicy {

    enum class NewWindowDecision {
        /** Cùng trang web và do người dùng bấm: mở ngay trong tab hiện tại. */
        OPEN_IN_CURRENT_TAB,

        /** Khác trang hoặc tự bật lên: chặn (người dùng có thể chủ động mở lại). */
        BLOCK,
    }

    private val WEB_SCHEMES = setOf(
        "http", "https", "about", "data", "blob", "javascript", "resource", "moz-extension", "file", "view-source",
    )

    /** Hậu tố 2 cấp phổ biến — đủ dùng cho PoC; bản sau dùng Public Suffix List đầy đủ. */
    private val TWO_LEVEL_SUFFIXES = setOf(
        "com.vn", "net.vn", "org.vn", "edu.vn", "gov.vn", "info.vn", "biz.vn", "name.vn", "pro.vn", "health.vn",
        "co.uk", "org.uk", "ac.uk", "com.au", "net.au", "co.jp", "ne.jp", "com.br", "co.kr", "co.in", "com.cn",
        "com.tw", "com.hk", "com.sg", "com.my", "co.th", "co.id", "com.ph",
    )
    private val IPV4 = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    fun schemeOf(url: String): String? = url.substringBefore(':', "").lowercase().takeIf { it.isNotEmpty() }

    /** intent://, market://, zalo://… — trang quảng cáo hay dùng để bật app khác hoặc Play Store. */
    fun isExternalAppScheme(url: String): Boolean {
        val scheme = schemeOf(url) ?: return false
        return scheme !in WEB_SCHEMES
    }

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }

    fun registrableDomain(host: String): String {
        val h = host.lowercase().trimEnd('.').removePrefix("www.")
        if (IPV4.matches(h)) return h
        val labels = h.split('.')
        if (labels.size <= 2) return h
        val lastTwo = labels.takeLast(2).joinToString(".")
        return if (lastTwo in TWO_LEVEL_SUFFIXES) labels.takeLast(3).joinToString(".") else lastTwo
    }

    fun isSameSite(urlA: String?, urlB: String?): Boolean {
        val a = urlA?.let(::hostOf) ?: return false
        val b = urlB?.let(::hostOf) ?: return false
        return registrableDomain(a) == registrableDomain(b)
    }

    fun decideNewWindow(currentUrl: String?, targetUrl: String, hasUserGesture: Boolean): NewWindowDecision =
        if (hasUserGesture && isSameSite(currentUrl, targetUrl)) {
            NewWindowDecision.OPEN_IN_CURRENT_TAB
        } else {
            NewWindowDecision.BLOCK
        }
}

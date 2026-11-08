package phim275.tv.com.data

/** Công cụ tìm kiếm cho thanh địa chỉ và trang chủ. `%s` là từ khóa đã mã hóa URL. */
enum class SearchEngine(val id: String, val label: String, val template: String) {
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s"),
    COCCOC("coccoc", "Cốc Cốc", "https://coccoc.com/search?query=%s"),
    BING("bing", "Bing", "https://www.bing.com/search?q=%s"),
    DUCKDUCKGO("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
    YOUTUBE("youtube", "YouTube", "https://www.youtube.com/results?search_query=%s"),
    ;

    companion object {
        fun fromId(id: String?): SearchEngine = entries.firstOrNull { it.id == id } ?: GOOGLE
    }
}

package phim275.tv.com.browser

/** Quyết định tab nào bị đóng/giải phóng khi vượt giới hạn — tách riêng để unit test. */
object TabPolicy {
    /** Máy chiếu/TV box ~2GB RAM: mỗi tab đang sống tốn ~100–300MB. */
    const val MAX_TABS = 6
    const val MAX_LIVE_TABS = 2

    data class TabInfo(val id: Int, val lastActiveMs: Long, val isCurrent: Boolean, val isLive: Boolean)

    /** Tab cần đóng hẳn khi mở thêm tab mới mà đã đủ [max] tab: tab cũ nhất không phải tab hiện tại. */
    fun tabToClose(tabs: List<TabInfo>, max: Int = MAX_TABS): Int? {
        if (tabs.size < max) return null
        return tabs.filter { !it.isCurrent }.minByOrNull { it.lastActiveMs }?.id
    }

    /** Các tab nền cần giải phóng (giữ URL, tải lại khi chọn) để số tab đang sống không vượt [maxLive]. */
    fun tabsToDiscard(tabs: List<TabInfo>, maxLive: Int = MAX_LIVE_TABS): List<Int> {
        val live = tabs.filter { it.isLive }
        if (live.size <= maxLive) return emptyList()
        return live.filter { !it.isCurrent }
            .sortedBy { it.lastActiveMs }
            .take(live.size - maxLive)
            .map { it.id }
    }
}

package phim275.tv.com.browser

/** Quyết định tab nào bị đóng/giải phóng khi vượt giới hạn — tách riêng để unit test. */
object TabPolicy {
    /** Máy chiếu/TV box ~2GB RAM: mỗi tab đang sống tốn ~100–300MB. */
    const val MAX_TABS = 6
    const val MAX_LIVE_TABS = 2

    /** Dưới ngưỡng này (vd. máy chiếu OnePlus H93A: 1656MB dùng được) chỉ giữ 1 tab trong RAM. */
    const val LOW_RAM_THRESHOLD_BYTES = 2_500L * 1024 * 1024

    fun maxLiveTabsFor(totalRamBytes: Long): Int = if (totalRamBytes < LOW_RAM_THRESHOLD_BYTES) 1 else MAX_LIVE_TABS

    /** Box 1GB (chế độ RAM thấp): tối đa 3 tab, mỗi tab nền chỉ giữ URL. */
    const val MAX_TABS_LOW_RAM = 3

    fun maxTabsFor(lowRam: Boolean): Int = if (lowRam) MAX_TABS_LOW_RAM else MAX_TABS

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

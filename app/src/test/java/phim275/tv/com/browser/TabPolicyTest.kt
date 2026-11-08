package phim275.tv.com.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import phim275.tv.com.browser.TabPolicy.TabInfo

class TabPolicyTest {

    @Test fun `chưa đủ tab thì không đóng tab nào`() {
        val tabs = listOf(TabInfo(1, 10, isCurrent = true, isLive = true))
        assertNull(TabPolicy.tabToClose(tabs, max = 3))
    }

    @Test fun `đủ tab thì đóng tab cũ nhất không phải tab hiện tại`() {
        val tabs = listOf(
            TabInfo(1, lastActiveMs = 5, isCurrent = true, isLive = true),
            TabInfo(2, lastActiveMs = 30, isCurrent = false, isLive = false),
            TabInfo(3, lastActiveMs = 20, isCurrent = false, isLive = true),
        )
        assertEquals(3, TabPolicy.tabToClose(tabs, max = 3))
    }

    @Test fun `giải phóng tab nền cũ nhất khi quá số tab đang sống`() {
        val tabs = listOf(
            TabInfo(1, 100, isCurrent = true, isLive = true),
            TabInfo(2, 10, isCurrent = false, isLive = true),
            TabInfo(3, 50, isCurrent = false, isLive = true),
            TabInfo(4, 5, isCurrent = false, isLive = false),
        )
        assertEquals(listOf(2), TabPolicy.tabsToDiscard(tabs, maxLive = 2))
        assertEquals(listOf(2, 3), TabPolicy.tabsToDiscard(tabs, maxLive = 1))
        assertEquals(emptyList<Int>(), TabPolicy.tabsToDiscard(tabs, maxLive = 3))
    }

    @Test fun `máy RAM thấp chỉ giữ 1 tab trong RAM`() {
        val mb = 1024L * 1024
        assertEquals(1, TabPolicy.maxLiveTabsFor(1656 * mb)) // OnePlus H93A
        assertEquals(TabPolicy.MAX_LIVE_TABS, TabPolicy.maxLiveTabsFor(4096 * mb))
    }

    @Test fun `không bao giờ giải phóng tab hiện tại`() {
        val tabs = listOf(TabInfo(1, 1, isCurrent = true, isLive = true), TabInfo(2, 2, isCurrent = false, isLive = true))
        assertEquals(listOf(2), TabPolicy.tabsToDiscard(tabs, maxLive = 0))
    }
}

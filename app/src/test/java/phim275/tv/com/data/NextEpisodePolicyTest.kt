package phim275.tv.com.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NextEpisodePolicyTest {

    @Test fun `hiện thẻ ở đoạn cuối, độ dài báo trước tùy thời lượng`() {
        val ep = 45 * 60.0 // 45 phút → báo trước 108 giây
        assertEquals(108.0, NextEpisodePolicy.leadSec(ep), 0.001)
        assertFalse(NextEpisodePolicy.isCardDue(ep - 200, ep))
        assertTrue(NextEpisodePolicy.isCardDue(ep - 100, ep))
        assertTrue(NextEpisodePolicy.isCardDue(ep, ep))
        // Tập ngắn: tối thiểu 30 giây; phim dài: tối đa 2 phút.
        assertEquals(30.0, NextEpisodePolicy.leadSec(5 * 60.0), 0.001)
        assertEquals(120.0, NextEpisodePolicy.leadSec(2 * 3600.0), 0.001)
    }

    @Test fun `không hiện thẻ cho quảng cáo, phát trực tiếp hay vị trí lạ`() {
        assertFalse(NextEpisodePolicy.isCardDue(50.0, 60.0)) // quảng cáo 1 phút
        assertFalse(NextEpisodePolicy.isCardDue(100.0, Double.POSITIVE_INFINITY))
        assertFalse(NextEpisodePolicy.isCardDue(Double.NaN, 2700.0))
        // Video 3 phút: 30 giây cuối vẫn phải qua nửa video.
        assertTrue(NextEpisodePolicy.isCardDue(160.0, 180.0))
        assertFalse(NextEpisodePolicy.isCardDue(60.0, 180.0))
    }

    @Test fun `tên tập gọn gàng`() {
        assertEquals("Tập tiếp theo", NextEpisodePolicy.displayLabel(null))
        assertEquals("Tập tiếp theo", NextEpisodePolicy.displayLabel("   "))
        assertEquals("Tập 6", NextEpisodePolicy.displayLabel("  Tập\n 6 "))
        val long = NextEpisodePolicy.displayLabel("x".repeat(80))
        assertEquals(48, long.length)
        assertTrue(long.endsWith("…"))
    }
}

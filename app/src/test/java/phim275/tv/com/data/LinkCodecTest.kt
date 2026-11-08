package phim275.tv.com.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkCodecTest {

    @Test fun `mã hóa rồi giải mã giữ nguyên dữ liệu`() {
        val links = listOf(Link("https://a.vn", "Trang A", 1L), Link("https://b.vn/x?y=1", "Tiếng Việt có dấu", 2L))
        assertEquals(links, LinkCodec.decode(LinkCodec.encode(links)))
    }

    @Test fun `dữ liệu hỏng hoặc rỗng trả về danh sách rỗng`() {
        assertTrue(LinkCodec.decode(null).isEmpty())
        assertTrue(LinkCodec.decode("").isEmpty())
        assertTrue(LinkCodec.decode("{không phải json").isEmpty())
        assertEquals(1, LinkCodec.decode("""[{"u":"https://ok.vn"},{"t":"thiếu url"}]""").size)
    }

    @Test fun `thêm lên đầu, bỏ trùng, giới hạn số lượng`() {
        val a = Link("https://a.vn", "A", 1)
        val b = Link("https://b.vn", "B", 2)
        val c = Link("https://c.vn", "C", 3)
        val a2 = Link("https://a.vn", "A mới", 4)
        assertEquals(listOf(a2, b), LinkCodec.pushFront(listOf(a, b), a2, max = 10))
        assertEquals(listOf(c, a), LinkCodec.pushFront(listOf(a, b), c, max = 2))
    }

    @Test fun `chỉ ghi lịch sử trang web thật`() {
        assertTrue(LinkStore.isRecordable("https://vnexpress.net"))
        assertFalse(LinkStore.isRecordable("resource://android/assets/home/index.html"))
        assertFalse(LinkStore.isRecordable("moz-extension://abc/dashboard.html"))
        assertFalse(LinkStore.isRecordable(null))
    }

    @Test fun `công cụ tìm kiếm không rõ thì dùng Google`() {
        assertEquals(SearchEngine.GOOGLE, SearchEngine.fromId("không-có"))
        assertEquals(SearchEngine.COCCOC, SearchEngine.fromId("coccoc"))
    }
}

package phim275.tv.com.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackCodecTest {

    private val ep = 45 * 60.0 // một tập phim 45 phút

    @Test fun `tua tới vị trí đã lưu khi cùng video`() {
        val saved = Playback(754.0, ep, 1L)
        assertEquals(754.0, PlaybackCodec.resumePosition(saved, ep)!!, 0.0)
        // Độ dài lệch chút (trình phát làm tròn khác) vẫn coi là cùng video.
        assertEquals(754.0, PlaybackCodec.resumePosition(saved, ep + 3)!!, 0.0)
    }

    @Test fun `không tua khi video khác, mới xem, đã xem hết hoặc là quảng cáo`() {
        assertNull(PlaybackCodec.resumePosition(null, ep))
        assertNull(PlaybackCodec.resumePosition(Playback(754.0, ep, 1L), 20 * 60.0)) // cùng URL, video khác
        assertNull(PlaybackCodec.resumePosition(Playback(12.0, ep, 1L), ep)) // mới xem vài giây
        assertNull(PlaybackCodec.resumePosition(Playback(ep - 30, ep, 1L), ep)) // đang ở phần giới thiệu tập sau
        assertNull(PlaybackCodec.resumePosition(Playback(40.0, 60.0, 1L), 60.0)) // quảng cáo 1 phút
        assertNull(PlaybackCodec.resumePosition(Playback(40.0, ep, 1L), Double.POSITIVE_INFINITY)) // phát trực tiếp
    }

    @Test fun `nhường trang có tính năng xem tiếp riêng`() {
        val saved = Playback(754.0, ep, 1L)
        // Trang tự tua tới giữa phim (hộp "Tiếp tục xem" của trang) → app để yên.
        assertTrue(PlaybackCodec.siteResumedItself(128.0))
        assertNull(PlaybackCodec.resumePosition(saved, ep, startSec = 128.0))
        // Trang đã biết là tự xem tiếp được: người xem chọn "Xem lại từ đầu" → app không tua đi.
        assertNull(PlaybackCodec.resumePosition(saved, ep, startSec = 0.0, siteResumes = true))
        // Trang không tự xem tiếp → app tua.
        assertEquals(754.0, PlaybackCodec.resumePosition(saved, ep, startSec = 2.0)!!, 0.0)
    }

    @Test fun `ghi vị trí theo URL bỏ phần hash, xem hết thì xóa`() {
        var map = PlaybackCodec.record(emptyMap(), "https://phim.vn/tap-1#t", 300.0, ep, 1L, max = 10)
        assertEquals(300.0, map["https://phim.vn/tap-1"]!!.positionSec, 0.0)
        map = PlaybackCodec.record(map, "https://phim.vn/tap-1", ep - 10, ep, 2L, max = 10)
        assertTrue(map.isEmpty())
        // Quảng cáo / video ngắn không ghi.
        assertTrue(PlaybackCodec.record(emptyMap(), "https://a.vn", 20.0, 30.0, 1L, max = 10).isEmpty())
    }

    @Test fun `giữ các trang xem gần nhất khi vượt giới hạn`() {
        var map: Map<String, Playback> = emptyMap()
        for (i in 1..5) map = PlaybackCodec.record(map, "https://a.vn/$i", 100.0, ep, i.toLong(), max = 3)
        assertEquals(setOf("https://a.vn/3", "https://a.vn/4", "https://a.vn/5"), map.keys)
    }

    @Test fun `mã hóa rồi giải mã giữ nguyên, dữ liệu hỏng trả về rỗng`() {
        val map = mapOf("https://a.vn/x?tap=2" to Playback(123.5, ep, 9L))
        assertEquals(map, PlaybackCodec.decode(PlaybackCodec.encode(map)))
        assertTrue(PlaybackCodec.decode(null).isEmpty())
        assertTrue(PlaybackCodec.decode("{hỏng").isEmpty())
        assertEquals(1, PlaybackCodec.decode("""{"a":{"p":1,"d":200},"b":{"p":1}}""").size)
    }

    @Test fun `định dạng thời gian`() {
        assertEquals("0:05", PlaybackCodec.formatTime(5.4))
        assertEquals("12:34", PlaybackCodec.formatTime(754.0))
        assertEquals("1:02:03", PlaybackCodec.formatTime(3723.0))
    }
}

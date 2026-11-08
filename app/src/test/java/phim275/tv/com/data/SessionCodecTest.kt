package phim275.tv.com.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCodecTest {

    @Test fun `lưu rồi đọc lại phiên duyệt web`() {
        val session = SavedSession(
            listOf(
                SavedTab("https://cobephim.my/phim/abc", "Phim ABC", desktopMode = true),
                SavedTab("https://vnexpress.net/", null, desktopMode = false),
            ),
            currentIndex = 1,
        )
        assertEquals(session, SessionCodec.decode(SessionCodec.encode(session)))
    }

    @Test fun `bỏ trang không mở lại được và giữ chỉ số tab hợp lệ`() {
        val json = """{"c":5,"tabs":[{"u":"data:text/html,lỗi"},{"u":"https://a.vn","t":"A"},{"u":"moz-extension://x/a.html"}]}"""
        val s = SessionCodec.decode(json)!!
        assertEquals(listOf(SavedTab("https://a.vn", "A", true)), s.tabs)
        assertEquals(0, s.currentIndex)
    }

    @Test fun `dữ liệu rỗng hoặc hỏng thì không khôi phục`() {
        assertNull(SessionCodec.decode(null))
        assertNull(SessionCodec.decode("{hỏng"))
        assertNull(SessionCodec.decode("""{"c":0,"tabs":[]}"""))
    }

    @Test fun `trang nào được khôi phục`() {
        assertTrue(SessionCodec.isRestorable("https://x.vn"))
        assertTrue(SessionCodec.isRestorable("resource://android/assets/home/index.html#abc"))
        assertFalse(SessionCodec.isRestorable("data:text/html;base64,AAA"))
        assertFalse(SessionCodec.isRestorable(null))
    }
}

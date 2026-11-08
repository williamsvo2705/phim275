package phim275.tv.com.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputTest {

    @Test fun `chuỗi rỗng trả về null`() {
        assertNull(UrlInput.toLoadableUrl("   "))
    }

    @Test fun `giữ nguyên URL đã có scheme`() {
        assertEquals("https://vnexpress.net/abc", UrlInput.toLoadableUrl(" https://vnexpress.net/abc "))
        assertEquals("about:blank", UrlInput.toLoadableUrl("about:blank"))
        assertEquals(
            "resource://android/assets/home.html",
            UrlInput.toLoadableUrl("resource://android/assets/home.html"),
        )
    }

    @Test fun `tên miền trần được thêm https`() {
        assertEquals("https://youtube.com", UrlInput.toLoadableUrl("youtube.com"))
        assertEquals("https://m.phim.com.vn/xem?id=1", UrlInput.toLoadableUrl("m.phim.com.vn/xem?id=1"))
    }

    @Test fun `mạng nội bộ dùng http`() {
        assertEquals("http://192.168.1.10:8080", UrlInput.toLoadableUrl("192.168.1.10:8080"))
        assertEquals("http://localhost:3000/a", UrlInput.toLoadableUrl("localhost:3000/a"))
    }

    @Test fun `từ khóa chuyển thành tìm kiếm Google`() {
        assertEquals(
            "https://www.google.com/search?q=phim+h%C3%A0nh+%C4%91%E1%BB%99ng",
            UrlInput.toLoadableUrl("phim hành động"),
        )
        assertEquals("https://www.google.com/search?q=hello", UrlInput.toLoadableUrl("hello"))
    }
}

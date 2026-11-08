package phim275.tv.com.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInfoTest {

    private val sample = """
        {
          "versionCode": 21,
          "versionName": "0.2.0",
          "apks": {
            "armeabi-v7a": { "url": "https://x/phim275-armeabi-v7a.apk", "sha256": "ABCDEF" },
            "arm64-v8a":   { "url": "https://x/phim275-arm64-v8a.apk",   "sha256": "123456" }
          }
        }
    """.trimIndent()

    @Test fun `đọc latest_json`() {
        val info = UpdateInfo.parse(sample)
        assertEquals(21, info.versionCode)
        assertEquals("0.2.0", info.versionName)
        assertEquals("https://x/phim275-arm64-v8a.apk", info.apks.getValue("arm64-v8a").url)
        assertEquals("abcdef", info.apks.getValue("armeabi-v7a").sha256) // chuẩn hóa chữ thường
    }

    @Test fun `so sánh phiên bản bỏ qua hậu tố ABI`() {
        assertTrue(UpdateInfo.isNewer(remoteVersionCode = 21, localVersionCode = 11))
        assertTrue(UpdateInfo.isNewer(remoteVersionCode = 21, localVersionCode = 12))
        assertFalse(UpdateInfo.isNewer(remoteVersionCode = 21, localVersionCode = 22)) // cùng 0.2.0, khác ABI
        assertFalse(UpdateInfo.isNewer(remoteVersionCode = 11, localVersionCode = 21))
    }

    @Test fun `nhận ABI từ versionCode`() {
        assertEquals("armeabi-v7a", UpdateInfo.abiOf(11))
        assertEquals("arm64-v8a", UpdateInfo.abiOf(22))
        assertEquals("x86_64", UpdateInfo.abiOf(13))
        assertNull(UpdateInfo.abiOf(10))
    }
}

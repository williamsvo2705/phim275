package phim275.tv.com.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import phim275.tv.com.data.DeviceProfile.LowRamMode

class DeviceProfileTest {
    private val mb = 1024L * 1024

    @Test fun `tự động bật trên box 1GB, tắt trên máy chiếu 1,6GB`() {
        assertTrue(DeviceProfile.shouldUseLowRam(LowRamMode.AUTO, 960 * mb, isLowRamDevice = false))
        assertFalse(DeviceProfile.shouldUseLowRam(LowRamMode.AUTO, 1656 * mb, isLowRamDevice = false))
    }

    @Test fun `Android báo máy RAM thấp thì luôn bật khi để tự động`() {
        assertTrue(DeviceProfile.shouldUseLowRam(LowRamMode.AUTO, 4096 * mb, isLowRamDevice = true))
    }

    @Test fun `người dùng chọn bật hoặc tắt thì theo người dùng`() {
        assertTrue(DeviceProfile.shouldUseLowRam(LowRamMode.ON, 8192 * mb, isLowRamDevice = false))
        assertFalse(DeviceProfile.shouldUseLowRam(LowRamMode.OFF, 512 * mb, isLowRamDevice = true))
    }

    @Test fun `không đọc được RAM (0) thì không tự bật`() {
        assertFalse(DeviceProfile.shouldUseLowRam(LowRamMode.AUTO, 0, isLowRamDevice = false))
    }

    @Test fun `giá trị lạ thì mặc định tự động`() {
        assertEquals(LowRamMode.AUTO, LowRamMode.fromId("xyz"))
    }
}

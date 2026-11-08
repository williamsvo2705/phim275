package phim275.tv.com.cursor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorPhysicsTest {

    @Test fun `mới bấm thì chạy tốc độ thấp nhất`() {
        assertEquals(CursorPhysics.MIN_SPEED_PX_PER_SEC, CursorPhysics.speed(0f), 0.01f)
        assertEquals(CursorPhysics.MIN_SPEED_PX_PER_SEC, CursorPhysics.speed(CursorPhysics.RAMP_DELAY_SEC), 0.01f)
    }

    @Test fun `giữ lâu thì đạt tốc độ tối đa và không vượt quá`() {
        assertEquals(CursorPhysics.MAX_SPEED_PX_PER_SEC, CursorPhysics.speed(5f), 0.01f)
    }

    @Test fun `tốc độ tăng đơn điệu theo thời gian giữ`() {
        var prev = 0f
        var t = 0f
        while (t < 2f) {
            val s = CursorPhysics.speed(t)
            assertTrue("t=$t", s >= prev)
            prev = s
            t += 0.05f
        }
    }

    @Test fun `hệ số theo độ rộng màn hình`() {
        assertEquals(1f, CursorPhysics.scaleFor(1920), 0.0001f)
        assertEquals(2f, CursorPhysics.scaleFor(3840), 0.0001f)
        assertEquals(1f, CursorPhysics.scaleFor(0), 0.0001f)
    }
}

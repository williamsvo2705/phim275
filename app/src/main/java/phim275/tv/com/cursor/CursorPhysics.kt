package phim275.tv.com.cursor

/** Tốc độ con trỏ ảo: chậm khi mới bấm (để chỉnh chính xác), nhanh dần khi giữ phím. */
object CursorPhysics {
    /** Tốc độ tính theo màn hình rộng 1920px; nhân thêm hệ số theo độ rộng thật. */
    const val MIN_SPEED_PX_PER_SEC = 380f
    const val MAX_SPEED_PX_PER_SEC = 1700f
    const val RAMP_DELAY_SEC = 0.18f
    const val RAMP_DURATION_SEC = 0.9f

    /** Bước nhảy ngay khi vừa nhấn phím (một lần nhấn nhanh = dịch chuyển nhỏ). */
    const val TAP_STEP_PX = 14f

    fun speed(heldSeconds: Float): Float {
        val t = ((heldSeconds - RAMP_DELAY_SEC) / RAMP_DURATION_SEC).coerceIn(0f, 1f)
        val eased = t * t * (3f - 2f * t) // smoothstep
        return MIN_SPEED_PX_PER_SEC + (MAX_SPEED_PX_PER_SEC - MIN_SPEED_PX_PER_SEC) * eased
    }

    fun scaleFor(viewWidthPx: Int): Float = if (viewWidthPx <= 0) 1f else viewWidthPx / 1920f
}

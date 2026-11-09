package phim275.tv.com.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerOptionsTest {

    @Test fun `nhãn tốc độ`() {
        assertEquals("Bình thường", PlayerOptions.rateLabel(1.0))
        assertEquals("2×", PlayerOptions.rateLabel(2.0))
        assertEquals("1,25×", PlayerOptions.rateLabel(1.25))
        assertEquals("0,5×", PlayerOptions.rateLabel(0.5))
        assertEquals("0,75×", PlayerOptions.rateLabel(0.75))
        assertEquals(1.25, PlayerOptions.nearestRate(1.2600001), 0.0)
        assertEquals(1.0, PlayerOptions.nearestRate(1.0000001), 0.0)
    }

    @Test fun `đọc trạng thái JW Player`() {
        val json = JSONObject()
            .put("engine", "jwplayer").put("rate", 1.0)
            .put("levels", JSONArray(listOf("Auto", "1080p", "720p", "480p"))).put("level", 2)
        val info = PlayerOptions.parse(json)!!
        assertEquals(listOf("Tự động", "1080p", "720p", "480p"), info.levels)
        assertEquals("720p", info.currentLevelLabel)
        assertEquals("jwplayer", info.engine)
        assertEquals("720p", info.qualitySummary)
        assertEquals(true, info.canChooseLevel)
    }

    @Test fun `xếp mức chất lượng - tự động trước, cao xuống thấp`() {
        assertEquals(listOf(0, 2, 1), PlayerOptions.displayOrder(listOf("Tự động", "360p", "720p")))
        assertEquals(listOf(0, 1, 2, 3), PlayerOptions.displayOrder(listOf("Tự động", "1080p", "720p", "480p")))
        assertEquals(listOf(1, 0), PlayerOptions.displayOrder(listOf("480p", "Mức 2")))
    }

    @Test fun `tóm tắt chất lượng dùng độ phân giải thật`() {
        val auto = VideoInfo("jwplayer", 1.0, listOf("Tự động", "1080p", "720p"), 0, height = 1080)
        assertEquals("Tự động · 1080p", auto.qualitySummary)
        // Trình phát chỉ có 1 mức, không tên → dùng chiều cao khung hình.
        val single = VideoInfo("jwplayer", 1.0, listOf("Mức 1"), 0, height = 720)
        assertEquals("720p", single.qualitySummary)
        assertEquals(false, single.canChooseLevel)
        assertEquals("—", VideoInfo("html5", 1.0, emptyList(), -1).qualitySummary)
    }

    @Test fun `đọc nút nguồn phát trên trang`() {
        val json = JSONObject().put(
            "sources",
            JSONArray()
                .put(JSONObject().put("l", " Thuyết  Minh ").put("a", true))
                .put(JSONObject().put("l", "Vietsub").put("a", false))
                .put(JSONObject().put("l", "1080").put("a", true))
                .put(JSONObject().put("l", "   ")),
        )
        val sources = PlayerOptions.parseSources(json)
        assertEquals(listOf("Thuyết Minh", "Vietsub", "1080"), sources.map { it.label })
        assertEquals("Thuyết Minh · 1080", PlayerOptions.sourcesSummary(sources))
        assertEquals("Chọn", PlayerOptions.sourcesSummary(sources.map { it.copy(active = false) }))
        // Chỉ một nút → không có gì để chọn.
        val single = JSONObject().put("sources", JSONArray().put(JSONObject().put("l", "Server 1")))
        assertEquals(emptyList<VideoSource>(), PlayerOptions.parseSources(single))
        assertEquals(emptyList<VideoSource>(), PlayerOptions.parseSources(null))
    }

    @Test fun `trang không cho chọn chất lượng hoặc dữ liệu hỏng`() {
        val html5 = PlayerOptions.parse(JSONObject().put("engine", "html5").put("rate", 1.5))!!
        assertEquals(emptyList<String>(), html5.levels)
        assertNull(html5.currentLevelLabel)
        // Chỉ số ngoài danh sách → không rõ mức đang dùng.
        assertEquals(-1, PlayerOptions.parse(JSONObject().put("rate", 1.0).put("levels", JSONArray(listOf("720p"))).put("level", 5))!!.level)
        assertNull(PlayerOptions.parse(JSONObject().put("engine", "jwplayer")))
        assertNull(PlayerOptions.parse(null))
    }
}

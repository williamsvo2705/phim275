package phim275.tv.com.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmoothStreamTest {
    private fun reply(stream: String, video: String? = null) =
        JSONObject("""{"stream": $stream${if (video != null) ""","video": $video""" else ""}}""")

    @Test
    fun parsesHlsWithHeadersAndPosition() {
        val s = SmoothStream.parse(
            reply(
                """{"url":"https://embed.example/eyJ0Ijoi","kind":"hls","referer":"https://embed.example/","origin":"https://embed.example","ua":"Mozilla/5.0"}""",
                """{"time":2517.4,"duration":3974.0,"paused":false}""",
            ),
        )!!
        assertTrue(s.isHls)
        assertEquals("https://embed.example/eyJ0Ijoi", s.url)
        assertEquals("https://embed.example/", s.referer)
        assertEquals("https://embed.example", s.origin)
        assertEquals("Mozilla/5.0", s.userAgent)
        assertEquals(2517.4, s.positionSec, 0.001)
        assertEquals(3974.0, s.durationSec, 0.001)
    }

    @Test
    fun emptyHeadersBecomeNull() {
        val s = SmoothStream.parse(reply("""{"url":"https://cdn.example/v.mp4","kind":"file","referer":"","origin":"","ua":""}"""))!!
        assertFalse(s.isHls)
        assertNull(s.referer)
        assertNull(s.origin)
        assertNull(s.userAgent)
        assertEquals(0.0, s.positionSec, 0.0)
    }

    @Test
    fun rejectsBlobDataAndUnknownKinds() {
        assertNull(SmoothStream.parse(reply("""{"url":"blob:https://x/1","kind":"hls"}""")))
        assertNull(SmoothStream.parse(reply("""{"url":"https://x/a.mpd","kind":"dash"}""")))
        assertNull(SmoothStream.parse(JSONObject("{}")))
        assertNull(SmoothStream.parse(null))
    }

    @Test
    fun keepsOnlyRealPlaylistCopies() {
        val s = SmoothStream.parse(
            reply(
                """{"url":"https://e.x/eyJ","kind":"hls","playlists":{"https://e.x/eyJ":"#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nv.m3u8","https://e.x/bad":"<html>403</html>"}}""",
            ),
        )!!
        assertEquals(setOf("https://e.x/eyJ"), s.playlists.keys)
        assertTrue(s.playlists.getValue("https://e.x/eyJ").startsWith("#EXTM3U"))
    }

    @Test
    fun detectsPageSubtitles() {
        assertTrue(SmoothStream.parse(reply("""{"url":"https://x/a.m3u8","kind":"hls"}""", """{"subs":1}"""))!!.hasSubtitles)
        assertFalse(SmoothStream.parse(reply("""{"url":"https://x/a.m3u8","kind":"hls"}""", """{"subs":0}"""))!!.hasSubtitles)
    }

    @Test
    fun liveStreamHasNoDuration() {
        val s = SmoothStream.parse(reply("""{"url":"https://x/live.m3u8","kind":"hls"}""", """{"time":12,"duration":0}"""))!!
        assertEquals(0.0, s.durationSec, 0.0)
        assertEquals(12.0, s.positionSec, 0.0)
    }

    @Test
    fun seekDeltaMovesWebToSmoothPosition() {
        assertEquals(95.5, SmoothResult.seekDelta(webSec = 1000.0, smoothSec = 1095.5), 0.001)
        assertEquals(-30.0, SmoothResult.seekDelta(webSec = 600.0, smoothSec = 570.0), 0.001)
    }
}

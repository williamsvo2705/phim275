package phim275.tv.com.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import phim275.tv.com.browser.SitePolicy.NewWindowDecision

class SitePolicyTest {

    @Test fun `nhận diện scheme mở app ngoài`() {
        assertTrue(SitePolicy.isExternalAppScheme("intent://scan/#Intent;scheme=zxing;end"))
        assertTrue(SitePolicy.isExternalAppScheme("market://details?id=com.ads"))
        assertTrue(SitePolicy.isExternalAppScheme("zalo://chat"))
        assertFalse(SitePolicy.isExternalAppScheme("https://example.com"))
        assertFalse(SitePolicy.isExternalAppScheme("about:blank"))
        assertFalse(SitePolicy.isExternalAppScheme("blob:https://x.com/123"))
        assertFalse(SitePolicy.isExternalAppScheme("moz-extension://abc/dashboard.html"))
    }

    @Test fun `tên miền đăng ký với hậu tố hai cấp`() {
        assertEquals("example.com", SitePolicy.registrableDomain("a.b.example.com"))
        assertEquals("phim.com.vn", SitePolicy.registrableDomain("www.m.phim.com.vn"))
        assertEquals("bbc.co.uk", SitePolicy.registrableDomain("news.bbc.co.uk"))
        assertEquals("10.0.0.1", SitePolicy.registrableDomain("10.0.0.1"))
    }

    @Test fun `cùng trang web`() {
        assertTrue(SitePolicy.isSameSite("https://www.phim.com.vn/a", "https://cdn.phim.com.vn/b"))
        assertFalse(SitePolicy.isSameSite("https://phim.com.vn", "https://ads.example.com"))
        assertFalse(SitePolicy.isSameSite(null, "https://a.com"))
    }

    @Test fun `quyết định cửa sổ mới`() {
        val cur = "https://phim.com.vn/xem/1"
        assertEquals(
            NewWindowDecision.OPEN_IN_CURRENT_TAB,
            SitePolicy.decideNewWindow(cur, "https://phim.com.vn/xem/2", hasUserGesture = true),
        )
        // Popunder kinh điển: bấm vào video thì bật trang quảng cáo khác tên miền.
        assertEquals(
            NewWindowDecision.BLOCK,
            SitePolicy.decideNewWindow(cur, "https://casino-ads.xyz/landing", hasUserGesture = true),
        )
        // Tự bật lên không do người dùng bấm.
        assertEquals(
            NewWindowDecision.BLOCK,
            SitePolicy.decideNewWindow(cur, "https://phim.com.vn/xem/2", hasUserGesture = false),
        )
    }
}

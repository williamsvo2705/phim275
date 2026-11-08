package phim275.tv.com.browser

import android.os.SystemClock
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView

/** Một tab. session == null nghĩa là tab đã được giải phóng khỏi RAM (giữ URL để tải lại). */
class BrowserTab(val id: Int) {
    var session: GeckoSession? = null
    var url: String? = null
    var title: String? = null
    var lastActiveMs: Long = 0
    var canGoBack = false
    var canGoForward = false
    var desktopMode = true

    val isLive: Boolean get() = session != null

    fun label(): String = title?.takeIf { it.isNotBlank() } ?: url?.takeIf { it.isNotBlank() } ?: "Tab mới"
}

/**
 * Quản lý nhiều tab trên một GeckoView duy nhất. Chỉ tab hiện tại hiển thị; tối đa
 * [TabPolicy.MAX_LIVE_TABS] tab được giữ trong RAM, các tab nền khác bị giải phóng.
 */
class TabManager(
    private val runtime: GeckoRuntime,
    private val geckoView: GeckoView,
    /** Gắn delegate cho session mới tạo (trước khi open). */
    private val configureSession: (GeckoSession) -> Unit,
    private val listener: Listener,
) {
    interface Listener {
        fun onCurrentTabChanged(tab: BrowserTab)
        fun onTabsChanged()
    }

    private val _tabs = mutableListOf<BrowserTab>()
    val tabs: List<BrowserTab> get() = _tabs
    var current: BrowserTab? = null
        private set
    private var nextId = 1

    fun tabOf(session: GeckoSession): BrowserTab? = _tabs.firstOrNull { it.session === session }

    fun isCurrent(session: GeckoSession): Boolean = current?.session === session

    fun newTab(url: String, desktopMode: Boolean): BrowserTab {
        TabPolicy.tabToClose(infos())?.let { id -> _tabs.firstOrNull { it.id == id }?.let { closeInternal(it) } }
        val tab = BrowserTab(nextId++).also {
            it.url = url
            it.desktopMode = desktopMode
        }
        _tabs += tab
        select(tab)
        listener.onTabsChanged()
        return tab
    }

    fun select(tab: BrowserTab) {
        val previous = current
        if (previous != null && previous !== tab) {
            previous.session?.let {
                it.setActive(false)
                setExtensionTabActive(it, false)
            }
        }
        current = tab
        tab.lastActiveMs = SystemClock.uptimeMillis()
        val session = tab.session ?: createSession(tab).also { s -> tab.url?.let { s.loadUri(it) } }
        geckoView.releaseSession()
        geckoView.setSession(session)
        session.setActive(true)
        setExtensionTabActive(session, true)
        TabPolicy.tabsToDiscard(infos()).forEach { id -> _tabs.firstOrNull { it.id == id }?.let(::discard) }
        listener.onCurrentTabChanged(tab)
    }

    fun close(tab: BrowserTab) {
        if (_tabs.size == 1) return // luôn giữ ít nhất một tab
        closeInternal(tab)
        listener.onTabsChanged()
    }

    fun closeOthers() {
        val keep = current ?: return
        _tabs.filter { it !== keep }.forEach { closeInternal(it) }
        listener.onTabsChanged()
    }

    /** Thiếu RAM: giải phóng mọi tab nền. */
    fun discardBackground() = _tabs.filter { it !== current }.forEach(::discard)

    /** Process nội dung crash/bị giết: tạo lại session cho tab rồi tải lại URL. */
    fun recover(session: GeckoSession) {
        val tab = tabOf(session) ?: return
        if (tab === current) geckoView.releaseSession()
        session.close()
        tab.session = null
        if (tab === current) select(tab)
    }

    fun setDesktopMode(tab: BrowserTab, desktop: Boolean) {
        tab.desktopMode = desktop
        tab.session?.settings?.apply {
            setUserAgentMode(userAgentMode(desktop))
            setViewportMode(viewportMode(desktop))
        }
        tab.session?.reload()
    }

    /** Gọi khi uBO vừa sẵn sàng: báo cho uBO biết tab nào đang hiển thị. */
    fun refreshExtensionActiveTab() {
        current?.session?.let { setExtensionTabActive(it, true) }
    }

    fun closeAll() {
        geckoView.releaseSession()
        _tabs.forEach { it.session?.close() }
        _tabs.clear()
        current = null
    }

    private fun closeInternal(tab: BrowserTab) {
        val index = _tabs.indexOf(tab)
        if (tab === current) geckoView.releaseSession()
        tab.session?.close()
        tab.session = null
        _tabs.remove(tab)
        if (tab === current) {
            current = null
            select(_tabs[index.coerceAtMost(_tabs.size - 1)])
        }
    }

    private fun discard(tab: BrowserTab) {
        if (tab === current) return
        tab.session?.close()
        tab.session = null
    }

    private fun createSession(tab: BrowserTab): GeckoSession {
        val session = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentMode(userAgentMode(tab.desktopMode))
                .viewportMode(viewportMode(tab.desktopMode))
                .suspendMediaWhenInactive(true)
                .build(),
        )
        configureSession(session)
        session.open(runtime)
        tab.session = session
        return session
    }

    private fun setExtensionTabActive(session: GeckoSession, active: Boolean) {
        if (BrowserRuntime.ublockState is BrowserRuntime.UblockState.Ready) {
            runtime.webExtensionController.setTabActive(session, active)
        }
    }

    private fun infos() = _tabs.map {
        TabPolicy.TabInfo(it.id, it.lastActiveMs, isCurrent = it === current, isLive = it.isLive)
    }

    private fun userAgentMode(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE

    private fun viewportMode(desktop: Boolean) =
        if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
}

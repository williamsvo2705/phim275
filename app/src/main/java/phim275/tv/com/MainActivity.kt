package phim275.tv.com

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.ComponentCallbacks2
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognizerIntent
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputMethodManager
import android.webkit.URLUtil
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate.ContextElement
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse
import phim275.tv.com.browser.BrowserRuntime
import phim275.tv.com.browser.BrowserRuntime.UblockState
import phim275.tv.com.browser.BrowserTab
import phim275.tv.com.browser.ErrorPages
import phim275.tv.com.browser.MediaKeys
import phim275.tv.com.browser.SitePolicy
import phim275.tv.com.browser.TabManager
import phim275.tv.com.browser.TabPolicy
import phim275.tv.com.browser.TvNavBridge
import phim275.tv.com.browser.UblockBridge
import phim275.tv.com.browser.UrlInput
import phim275.tv.com.cursor.CursorController
import phim275.tv.com.cursor.CursorView
import phim275.tv.com.data.AppSettings
import phim275.tv.com.data.AppSettings.NavMode
import phim275.tv.com.data.DeviceProfile
import phim275.tv.com.data.LinkStore
import phim275.tv.com.data.NextEpisodePolicy
import phim275.tv.com.data.PlaybackCodec
import phim275.tv.com.data.PlaybackStore
import phim275.tv.com.data.PlayerOptions
import phim275.tv.com.data.SeekPolicy
import phim275.tv.com.data.VideoSource
import phim275.tv.com.data.VideoInfo
import phim275.tv.com.data.SavedSession
import phim275.tv.com.data.SavedTab
import phim275.tv.com.data.SearchEngine
import phim275.tv.com.data.SessionCodec
import phim275.tv.com.data.SessionStore
import org.mozilla.geckoview.MediaSession
import phim275.tv.com.diag.CrashLog
import phim275.tv.com.diag.Diagnostics
import phim275.tv.com.ui.PanelItem
import phim275.tv.com.ui.PanelSection
import phim275.tv.com.ui.SidePanel
import phim275.tv.com.ui.choose
import phim275.tv.com.ui.confirm
import phim275.tv.com.ui.menu
import phim275.tv.com.ui.showProgress
import phim275.tv.com.update.ApkInstaller
import phim275.tv.com.update.UpdateInfo
import phim275.tv.com.update.UpdateManager
import java.net.URLEncoder

class MainActivity : Activity(), CursorController.Callbacks, TabManager.Listener {

    private lateinit var geckoView: GeckoView
    private lateinit var cursorView: CursorView
    private lateinit var cursor: CursorController
    private lateinit var toolbar: View
    private lateinit var urlBar: TextView
    private lateinit var progress: ProgressBar
    private lateinit var diagPanel: TextView
    private lateinit var btnTabs: TextView
    private lateinit var btnBookmark: ImageView
    private lateinit var snackbar: TextView
    private lateinit var nextCard: View
    private lateinit var nextCardTitle: TextView
    private lateinit var nextCardHint: TextView
    private lateinit var nextCardCountdown: ProgressBar
    private lateinit var panel: SidePanel

    private lateinit var settings: AppSettings
    private lateinit var bookmarks: LinkStore
    private lateinit var history: LinkStore
    private lateinit var playback: PlaybackStore
    private lateinit var sessionStore: SessionStore
    private lateinit var diagnostics: Diagnostics
    private lateinit var updater: UpdateManager
    private lateinit var runtime: GeckoRuntime
    private lateinit var tabs: TabManager

    private var pageScrollY = 0
    private var fullScreen = false
    private var pseudoFullscreen = false
    private var lastFullscreenOkMs = 0L
    private var pageStartMs = 0L
    private var lastBackPressMs = 0L
    private var backLongPressHandled = false
    private var centerLongPressFired = false
    private var touchDownTime = 0L
    private var lastHoverMs = 0L
    private var topPushAccumPx = 0f
    private var blockedPopupUrl: String? = null
    private var blockedPopupAtMs = 0L

    /** Tập kế tiếp theo trang (URL bỏ #) do extension tvnav tìm thấy; vài trang gần nhất. */
    private val nextEpisodes = LinkedHashMap<String, String>()
    private var nextCardPage: String? = null      // thẻ "Tập tiếp theo" đang hiện cho trang này
    private var nextCardDismissedFor: String? = null
    private var nextCountdownLeft = 0
    private var nextCardOkDown = false
    private var nextRequestedAtMs = 0L

    private val handler = Handler(Looper.getMainLooper())
    private val diagUpdater = object : Runnable {
        override fun run() {
            if (diagPanel.visibility == View.VISIBLE) {
                UblockBridge.refreshStats()
                updateDiagnostics()
                handler.postDelayed(this, DIAG_REFRESH_MS)
            }
        }
    }

    /** Giữ OK mà trang không mở menu chuột phải (không phải liên kết/ảnh) → mở menu của app. */
    private val centerLongPressFallback = Runnable {
        centerLongPressFired = true
        showMainMenu()
    }
    private val ublockObserver: (UblockState) -> Unit = { state -> onUblockState(state) }

    /** Giữ OK ở chế độ chuyển khối: hỏi extension liên kết của khối đang chọn (phím F9). */
    private var awaitingBlockMenu = false
    private val spatialLongPress = Runnable {
        centerLongPressFired = true
        awaitingBlockMenu = true
        sendKeyToPage(KeyEvent.KEYCODE_F9, 0)
        handler.postDelayed(blockMenuFallback, BLOCK_MENU_TIMEOUT_MS)
    }

    /** Trang không có extension (trang nội bộ, chưa chọn khối…) → menu chung. */
    private val blockMenuFallback = Runnable {
        if (awaitingBlockMenu) {
            awaitingBlockMenu = false
            showMainMenu()
        }
    }

    private fun onBlockContext(href: String?, text: String, media: Boolean) {
        if (!awaitingBlockMenu) return
        awaitingBlockMenu = false
        handler.removeCallbacks(blockMenuFallback)
        if (media) { // giữ OK trên trình phát → tốc độ, chất lượng
            showVideoOptions()
            return
        }
        if (href == null) {
            showMainMenu()
            return
        }
        beforePanel()
        panel.show(text.ifBlank { "Liên kết" }.take(60), SitePolicy.hostOf(href) ?: href.take(60)) {
            listOf(PanelSection(null, linkItems(href, text) + PanelItem("Menu trang…", R.drawable.ic_menu) { showMainMenu() }))
        }
    }

    private val currentTab: BrowserTab? get() = tabs.current
    private val currentSession: GeckoSession? get() = tabs.current?.session
    private val currentUrl: String? get() = tabs.current?.url

    // ───────────────────────────── Vòng đời ─────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        diagnostics = Diagnostics(this) // đo RAM nền trước khi Gecko khởi động
        settings = AppSettings(this)
        bookmarks = LinkStore.bookmarks(this)
        history = LinkStore.history(this)
        playback = PlaybackStore(this)
        sessionStore = SessionStore(this)
        updater = UpdateManager(this)
        setContentView(R.layout.activity_main)

        geckoView = findViewById(R.id.gecko_view)
        cursorView = findViewById(R.id.cursor)
        toolbar = findViewById(R.id.toolbar)
        urlBar = findViewById(R.id.url_bar)
        progress = findViewById(R.id.progress)
        diagPanel = findViewById(R.id.diag_panel)
        btnTabs = findViewById(R.id.btn_tabs)
        btnBookmark = findViewById(R.id.btn_bookmark)
        snackbar = findViewById(R.id.snackbar)
        nextCard = findViewById(R.id.next_card)
        nextCardTitle = findViewById(R.id.next_card_title)
        nextCardHint = findViewById(R.id.next_card_hint)
        nextCardCountdown = findViewById(R.id.next_card_countdown)
        panel = SidePanel(this).apply {
            onHidden = { if (onboarding == null && !isToolbarMode()) geckoView.requestFocus() }
        }
        findViewById<ViewGroup>(R.id.root).addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setStartIcon(btnTabs, R.drawable.ic_tabs, R.color.text)
        cursor = CursorController(cursorView, this)

        runtime = BrowserRuntime.get(this)
        val lowRam = BrowserRuntime.isLowRam
        val maxLive = if (lowRam) 1 else TabPolicy.maxLiveTabsFor(DeviceProfile.totalRamBytes(this))
        tabs = TabManager(runtime, geckoView, ::configureSession, this, maxLive, TabPolicy.maxTabsFor(lowRam))
        setupToolbar()
        applyNavMode()
        BrowserRuntime.observeUblock(ublockObserver)
        TvNavBridge.tapListener = ::tapAtFraction
        TvNavBridge.contextListener = ::onBlockContext
        TvNavBridge.pseudoFullscreenListener = { on ->
            pseudoFullscreen = on
            if (on) {
                enterPageMode()
                hintVideoOptions()
            }
        }
        TvNavBridge.playbackListener = ::onPlaybackPosition
        TvNavBridge.resumeProvider = ::resumePositionFor
        TvNavBridge.nextEpisodeListener = ::onNextEpisode

        // Mở lại các tab lần trước (sau khi cập nhật / Android đóng app vì thiếu RAM).
        val saved = if (settings.restoreTabs) sessionStore.load() else null
        if (saved != null) {
            tabs.restore(saved.tabs.map { Triple(it.url, it.title, it.desktopMode) }, saved.currentIndex)
        }
        val startUrl = intent?.dataString
        if (startUrl != null) {
            tabs.newTab(startUrl, settings.desktopModeDefault)
        } else if (saved == null) {
            tabs.newTab(homeUrl(), settings.desktopModeDefault)
        }
        enterPageMode()
        scheduleAutoUpdateCheck()
        if (!settings.onboardingDone) showOnboarding()
    }

    private fun saveSession() {
        if (!settings.restoreTabs) return
        val restorable = tabs.tabs.filter { SessionCodec.isRestorable(it.url) }
        if (restorable.isEmpty()) return
        val current = restorable.indexOf(currentTab).coerceAtLeast(0)
        sessionStore.save(SavedSession(restorable.map { SavedTab(it.url!!, it.title, it.desktopMode) }, current))
    }

    /** Đang phát video → không cho trình bảo vệ màn hình của TV bật lên giữa phim. */
    private fun keepScreenOn(on: Boolean) {
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private val mediaSessionDelegate = object : MediaSession.Delegate {
        override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
            if (tabs.isCurrent(session)) keepScreenOn(true)
        }

        override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
            if (tabs.isCurrent(session)) keepScreenOn(false)
        }

        override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
            if (tabs.isCurrent(session)) keepScreenOn(false)
        }

        override fun onDeactivated(session: GeckoSession, mediaSession: MediaSession) {
            if (tabs.isCurrent(session)) keepScreenOn(false)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.dataString?.let { tabs.newTab(it, settings.desktopModeDefault) }
    }

    override fun onResume() {
        super.onResume()
        currentSession?.setActive(true)
    }

    override fun onPause() {
        saveSession()
        currentSession?.setActive(false)
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Hệ thống sắp thiếu RAM: giải phóng các tab nền trước khi Android giết process.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) tabs.discardBackground()
    }

    override fun onDestroy() {
        BrowserRuntime.removeObserver(ublockObserver)
        TvNavBridge.tapListener = null
        TvNavBridge.pseudoFullscreenListener = null
        TvNavBridge.contextListener = null
        TvNavBridge.playbackListener = null
        TvNavBridge.resumeProvider = null
        TvNavBridge.nextEpisodeListener = null
        handler.removeCallbacks(flushSeek)
        handler.removeCallbacks(nextCountdownTick)
        handler.removeCallbacksAndMessages(null)
        cursor.release()
        if (isFinishing) tabs.closeAll()
        super.onDestroy()
    }

    @Deprecated("Activity API cũ, đủ dùng cho nhận dạng giọng nói")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { navigate(it) }
        }
    }

    // ───────────────────────────── Tab & phiên duyệt web ─────────────────────────────

    private fun configureSession(s: GeckoSession) {
        s.navigationDelegate = navigationDelegate
        s.progressDelegate = progressDelegate
        s.contentDelegate = contentDelegate
        s.scrollDelegate = scrollDelegate
        s.permissionDelegate = permissionDelegate
        s.promptDelegate = promptDelegate
        s.textInput.setDelegate(textInputDelegate)
        s.mediaSessionDelegate = mediaSessionDelegate
    }

    // ───────────────────────────── Nhập chữ vào trang ─────────────────────────────

    private val imm by lazy { getSystemService(InputMethodManager::class.java) }
    private var pageInputActive = false

    /** Bàn phím vẫn không hiện sau khi trang yêu cầu → mở ô nhập của app. */
    private val imeCheck = Runnable {
        if (pageInputActive && !isImeVisible()) {
            pageInputActive = false
            showTextEntryDialog(forPage = true)
        }
    }

    /**
     * Thay delegate mặc định của GeckoView: delegate mặc định chỉ "xin" hiện bàn phím (SHOW_IMPLICIT),
     * nhiều TV/máy chiếu bỏ qua → bấm vào ô tìm kiếm không có bàn phím. Ở đây yêu cầu tường minh,
     * và nếu vẫn không hiện thì dùng ô nhập dự phòng.
     */
    private val textInputDelegate = object : GeckoSession.TextInputDelegate {
        override fun restartInput(session: GeckoSession, reason: Int) {
            if (tabs.isCurrent(session)) imm.restartInput(geckoView)
        }

        override fun showSoftInput(session: GeckoSession) {
            if (!tabs.isCurrent(session) || isToolbarMode()) return
            pageInputActive = true
            geckoView.requestFocus()
            imm.showSoftInput(geckoView, 0)
            handler.removeCallbacks(imeCheck)
            handler.postDelayed(imeCheck, IME_CHECK_MS)
        }

        override fun hideSoftInput(session: GeckoSession) {
            if (!tabs.isCurrent(session)) return
            pageInputActive = false
            handler.removeCallbacks(imeCheck)
            imm.hideSoftInputFromWindow(geckoView.windowToken, 0)
        }

        override fun updateSelection(session: GeckoSession, selStart: Int, selEnd: Int, compositionStart: Int, compositionEnd: Int) {
            if (tabs.isCurrent(session)) imm.updateSelection(geckoView, selStart, selEnd, compositionStart, compositionEnd)
        }

        override fun updateExtractedText(session: GeckoSession, request: ExtractedTextRequest, text: ExtractedText) {
            if (tabs.isCurrent(session)) imm.updateExtractedText(geckoView, request.token, text)
        }

        override fun updateCursorAnchorInfo(session: GeckoSession, info: CursorAnchorInfo) {
            if (tabs.isCurrent(session)) imm.updateCursorAnchorInfo(geckoView, info)
        }
    }

    /** Android 11+ kiểm tra được bàn phím có đang hiện không; máy cũ hơn coi như đã hiện. */
    private fun isImeVisible(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            geckoView.rootWindowInsets?.isVisible(WindowInsets.Type.ime()) == true
        } else {
            true
        }

    /**
     * Ô nhập chữ của app (EditText thường luôn bật được bàn phím TV).
     * forPage = true: điền chữ vào ô đang chọn trên trang; false: tìm kiếm / mở địa chỉ.
     */
    private fun showTextEntryDialog(forPage: Boolean, prefill: String = "") {
        val input = EditText(this).apply {
            setSingleLine()
            textSize = 22f
            setText(prefill)
            setSelectAllOnFocus(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            hint = if (forPage) "Gõ nội dung cần nhập" else getString(R.string.url_hint)
            if (!forPage) inputType = android.text.InputType.TYPE_TEXT_VARIATION_URI or android.text.InputType.TYPE_CLASS_TEXT
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        fun submit(dialog: android.content.DialogInterface, pressEnter: Boolean) {
            val text = input.text.toString()
            dialog.dismiss()
            if (forPage) handler.post { commitTextToPage(text, pressEnter) } else navigate(text)
        }
        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(if (forPage) "Nhập chữ vào ô trên trang" else "Tìm kiếm hoặc nhập địa chỉ web")
            .setView(container)
            .setNegativeButton(R.string.cancel, null)
        if (forPage) {
            builder.setPositiveButton("Nhập & Enter") { d, _ -> submit(d, true) }
            builder.setNeutralButton("Chỉ nhập") { d, _ -> submit(d, false) }
        } else {
            builder.setPositiveButton("Đi") { d, _ -> submit(d, true) }
        }
        val dialog = builder.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        input.setOnEditorActionListener { _, actionId, event ->
            val go = actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (go) submit(dialog, true)
            go
        }
        dialog.show()
        input.requestFocus()
    }

    /** Gửi cả chuỗi vào ô đang được chọn trên trang (sự kiện phím ACTION_MULTIPLE), rồi Enter nếu cần. */
    private fun commitTextToPage(text: String, pressEnter: Boolean) {
        if (isToolbarMode()) enterPageMode() // trả focus về trang trước khi gửi chữ
        geckoView.requestFocus()
        if (text.isNotEmpty()) {
            geckoView.dispatchKeyEvent(KeyEvent(SystemClock.uptimeMillis(), text, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
        if (pressEnter) sendKeyToPage(KeyEvent.KEYCODE_ENTER, 0)
    }

    override fun onCurrentTabChanged(tab: BrowserTab) {
        keepScreenOn(false)
        pageScrollY = 0
        progress.visibility = View.GONE
        renderUrlBar(tab.url) // đổi tab → luôn hiện URL của tab mới
        hideNextCard()
        updateTabButton()
        updateBookmarkButton()
    }

    override fun onTabsChanged() = updateTabButton()

    private fun updateTabButton() {
        btnTabs.text = getString(R.string.btn_tabs_count, tabs.tabs.size)
    }

    private fun updateBookmarkButton() {
        val url = currentUrl
        val marked = url != null && bookmarks.contains(url)
        btnBookmark.setImageResource(if (marked) R.drawable.ic_star else R.drawable.ic_star_border)
        btnBookmark.imageTintList = ColorStateList.valueOf(getColor(if (marked) R.color.star else R.color.text))
    }

    private fun navigate(text: String) {
        UrlInput.toLoadableUrl(text, settings.searchEngine.template)?.let { currentSession?.loadUri(it) }
    }

    private fun onUblockState(state: UblockState) {
        when (state) {
            is UblockState.Ready -> tabs.refreshExtensionActiveTab()
            is UblockState.Failed -> showToast(getString(R.string.ublock_failed, state.message))
            UblockState.Loading -> Unit
        }
    }

    private fun ublockStatusText(): String = when (val s = BrowserRuntime.ublockState) {
        UblockState.Loading -> "đang tải…"
        is UblockState.Ready -> "${s.extension.metaData.version} ✓" + if (UblockBridge.isConnected) " · cầu nối ✓" else " · cầu nối …"
        is UblockState.Failed -> "LỖI: ${s.message}"
    }

    private fun openUblockPage(path: String) {
        val state = BrowserRuntime.ublockState
        if (state is UblockState.Ready) {
            tabs.newTab(state.extension.metaData.baseUrl + path, desktopMode = true)
        } else {
            showToast(getString(R.string.ublock_loading))
        }
    }

    // ───────────────────────────── Delegate GeckoView ─────────────────────────────

    private val navigationDelegate = object : NavigationDelegate {
        override fun onLocationChange(
            session: GeckoSession,
            url: String?,
            perms: MutableList<PermissionDelegate.ContentPermission>,
            hasUserGesture: Boolean,
        ) {
            val tab = tabs.tabOf(session) ?: return
            tab.url = url
            tab.title = null
            if (tabs.isCurrent(session)) {
                renderUrlBar(url)
                updateBookmarkButton()
                if (nextCardPage != null && nextCardPage != url?.let { PlaybackCodec.key(it) }) hideNextCard()
            }
        }

        override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
            tabs.tabOf(session)?.canGoBack = canGoBack
        }

        override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
            tabs.tabOf(session)?.canGoForward = canGoForward
        }

        override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
            val uri = request.uri
            // Lệnh nội bộ từ trang chủ: phim275://search, phim275://update.
            if (uri.startsWith("phim275:")) {
                handler.post {
                    if (uri.contains("update")) checkForUpdates(silent = false) else showTextEntryDialog(forPage = false)
                }
                return GeckoResult.deny()
            }
            if (SitePolicy.isExternalAppScheme(uri)) {
                diagnostics.blockedAppRedirects++
                showToast(getString(R.string.scheme_blocked, SitePolicy.schemeOf(uri)))
                return GeckoResult.deny()
            }
            if (request.target == NavigationDelegate.TARGET_WINDOW_NEW) {
                handleNewWindow(session, uri, request.hasUserGesture)
                return GeckoResult.deny()
            }
            return GeckoResult.allow()
        }

        override fun onSubframeLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? =
            if (SitePolicy.isExternalAppScheme(request.uri)) GeckoResult.deny() else GeckoResult.allow()

        /** Lưới an toàn: không bao giờ để trang tự tạo tab mới. */
        override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
            handleNewWindow(session, uri, hasUserGesture = true)
            return null
        }

        override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? =
            GeckoResult.fromValue(ErrorPages.dataUri(uri, error))
    }

    private val progressDelegate = object : GeckoSession.ProgressDelegate {
        override fun onPageStart(session: GeckoSession, url: String) {
            if (!tabs.isCurrent(session)) return
            pageStartMs = SystemClock.uptimeMillis()
            progress.progress = 0
            progress.visibility = View.VISIBLE
        }

        override fun onProgressChange(session: GeckoSession, progress: Int) {
            if (tabs.isCurrent(session)) this@MainActivity.progress.progress = progress
        }

        override fun onPageStop(session: GeckoSession, success: Boolean) {
            if (!tabs.isCurrent(session)) return
            progress.visibility = View.GONE
            resumeAfterKill?.let { fs ->
                resumeAfterKill = null
                // tvnav: F11 = tự phát lại, F12 = tự phát + toàn màn hình (vị trí cũ do "Xem tiếp đúng chỗ" tua tới).
                if (success) handler.postDelayed({ sendKeyToPage(if (fs) KeyEvent.KEYCODE_F12 else KeyEvent.KEYCODE_F11, 0) }, 600)
            }
            if (pageStartMs > 0) diagnostics.lastPageLoadMs = SystemClock.uptimeMillis() - pageStartMs
            saveSession() // phòng khi Android đóng app đột ngột vì thiếu RAM
        }
    }

    private val contentDelegate = object : GeckoSession.ContentDelegate {
        override fun onTitleChange(session: GeckoSession, title: String?) {
            val tab = tabs.tabOf(session) ?: return
            tab.title = title
            val url = tab.url
            if (LinkStore.isRecordable(url)) history.add(url!!, title)
        }

        override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
            this@MainActivity.fullScreen = fullScreen
            if (fullScreen) {
                enterPageMode()
                hintVideoOptions()
            }
        }

        override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContextElement) {
            handler.removeCallbacks(centerLongPressFallback)
            centerLongPressFired = true
            showContextMenu(element)
        }

        override fun onExternalResponse(session: GeckoSession, response: WebResponse) = startDownload(response)

        override fun onCrash(session: GeckoSession) = recoverSession(session)

        override fun onKill(session: GeckoSession) = recoverSession(session)
    }

    private fun recoverSession(session: GeckoSession) {
        if (tabs.isCurrent(session)) {
            // Đang xem phim mà trang bị đóng (box thiếu RAM) → tải lại rồi xem tiếp đúng chỗ, tự phát.
            val watching = SystemClock.uptimeMillis() - lastPlayingMs < RESUME_AFTER_KILL_WINDOW_MS
            resumeAfterKill = if (watching) isAnyFullscreen() else null
            fullScreen = false
            pseudoFullscreen = false
            showToast(if (watching) "Trang bị đóng vì thiếu bộ nhớ — đang mở lại và xem tiếp…" else getString(R.string.session_crashed))
        }
        handler.post { tabs.recover(session) }
    }

    private val scrollDelegate = object : GeckoSession.ScrollDelegate {
        override fun onScrollChanged(session: GeckoSession, scrollX: Int, scrollY: Int) {
            if (tabs.isCurrent(session)) pageScrollY = scrollY
        }
    }

    private val permissionDelegate = object : PermissionDelegate {
        override fun onContentPermissionRequest(
            session: GeckoSession,
            perm: PermissionDelegate.ContentPermission,
        ): GeckoResult<Int>? = when (perm.permission) {
            // Cho phép video tự phát (trang phim); từ chối thông báo đẩy, vị trí, v.v.
            PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE,
            PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE,
            -> GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_ALLOW)
            else -> GeckoResult.fromValue(PermissionDelegate.ContentPermission.VALUE_DENY)
        }

        override fun onAndroidPermissionsRequest(
            session: GeckoSession,
            permissions: Array<out String>?,
            callback: PermissionDelegate.Callback,
        ) = callback.reject()

        override fun onMediaPermissionRequest(
            session: GeckoSession,
            uri: String,
            video: Array<out PermissionDelegate.MediaSource>?,
            audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback,
        ) = callback.reject()
    }

    private val promptDelegate = object : PromptDelegate {
        /** alert() — trang quảng cáo/lừa đảo hay spam; tắt im lặng. */
        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse>? =
            GeckoResult.fromValue(prompt.dismiss())

        /** Popup bị bộ chặn của Gecko giữ lại (window.open không do người dùng bấm). */
        override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptResponse>? {
            prompt.targetUri?.let { rememberBlockedPopup(it) }
            return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.DENY))
        }

        /** "Bạn có chắc muốn rời trang?" — luôn cho rời. */
        override fun onBeforeUnloadPrompt(
            session: GeckoSession,
            prompt: PromptDelegate.BeforeUnloadPrompt,
        ): GeckoResult<PromptResponse>? = GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))

        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse>? =
            GeckoResult.fromValue(prompt.dismiss())

        /** confirm() — vài trang dùng để xác nhận độ tuổi. */
        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse>? {
            val result = GeckoResult<PromptResponse>()
            AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(prompt.title)
                .setMessage(prompt.message)
                .setPositiveButton(R.string.ok) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
                .setNegativeButton(R.string.cancel) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
                .setOnCancelListener { result.complete(prompt.dismiss()) }
                .show()
            return result
        }

        /** Thẻ <select> (chọn tập phim, server…) — danh sách điều khiển được bằng remote. */
        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse>? {
            val flat = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
            fun collect(choices: Array<PromptDelegate.ChoicePrompt.Choice>) {
                for (c in choices) {
                    val children = c.items
                    when {
                        children != null -> collect(children)
                        !c.separator && !c.disabled -> flat += c
                    }
                }
            }
            collect(prompt.choices)
            if (flat.isEmpty()) return GeckoResult.fromValue(prompt.dismiss())

            val result = GeckoResult<PromptResponse>()
            val labels = flat.map { it.label }.toTypedArray()
            val builder = AlertDialog.Builder(this@MainActivity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(prompt.title)
                .setOnCancelListener { result.complete(prompt.dismiss()) }
            if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
                val checked = BooleanArray(flat.size) { flat[it].selected }
                builder.setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
                    .setPositiveButton(R.string.ok) { _, _ ->
                        result.complete(prompt.confirm(flat.filterIndexed { i, _ -> checked[i] }.toTypedArray()))
                    }
                    .setNegativeButton(R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            } else {
                builder.setSingleChoiceItems(labels, flat.indexOfFirst { it.selected }) { dialog, which ->
                    result.complete(prompt.confirm(flat[which]))
                    dialog.dismiss()
                }
            }
            builder.show()
            return result
        }
    }

    // ───────────────────────────── Chặn popup ─────────────────────────────

    private fun handleNewWindow(session: GeckoSession, uri: String, hasUserGesture: Boolean) {
        val pageUrl = tabs.tabOf(session)?.url
        when (SitePolicy.decideNewWindow(pageUrl, uri, hasUserGesture)) {
            SitePolicy.NewWindowDecision.OPEN_IN_CURRENT_TAB -> handler.post { session.loadUri(uri) }
            SitePolicy.NewWindowDecision.BLOCK -> rememberBlockedPopup(uri)
        }
    }

    private fun rememberBlockedPopup(uri: String) {
        diagnostics.blockedPopups++
        blockedPopupUrl = uri
        blockedPopupAtMs = SystemClock.uptimeMillis()
        showToast(getString(R.string.popup_blocked, SitePolicy.hostOf(uri) ?: uri.take(40)))
    }

    private fun recentBlockedPopup(): String? =
        blockedPopupUrl?.takeIf { SystemClock.uptimeMillis() - blockedPopupAtMs < POPUP_RESCUE_WINDOW_MS }

    // ───────────────────────────── Thanh công cụ ─────────────────────────────

    private fun setupToolbar() {
        findViewById<View>(R.id.btn_back).setOnClickListener { currentSession?.goBack() }
        findViewById<View>(R.id.btn_forward).setOnClickListener { currentSession?.goForward() }
        findViewById<View>(R.id.btn_reload).setOnClickListener { currentSession?.reload() }
        findViewById<View>(R.id.btn_home).setOnClickListener {
            currentSession?.loadUri(homeUrl())
            enterPageMode()
        }
        findViewById<View>(R.id.btn_voice).setOnClickListener { startVoiceSearch() }
        btnBookmark.setOnClickListener { toggleBookmark() }
        btnTabs.setOnClickListener { showTabsMenu() }
        findViewById<View>(R.id.btn_menu).setOnClickListener { showMainMenu() }

        // Ô URL chỉ là nút: bấm OK mới mở hộp nhập (bàn phím không tự bật khi lướt qua thanh công cụ).
        urlBar.setOnClickListener {
            enterPageMode()
            showTextEntryDialog(forPage = false, prefill = displayUrl(currentUrl))
        }
    }

    private fun isToolbarMode() = toolbar.visibility == View.VISIBLE

    private fun enterToolbarMode() {
        topPushAccumPx = 0f
        toolbar.visibility = View.VISIBLE
        renderUrlBar(currentUrl)
        updateBookmarkButton()
        urlBar.requestFocus()
    }

    private fun enterPageMode() {
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(urlBar.windowToken, 0)
        toolbar.visibility = View.GONE
        geckoView.requestFocus()
        if (settings.navMode == NavMode.CURSOR) cursor.poke()
    }

    private fun startVoiceSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "vi-VN")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói từ khóa hoặc tên trang web")
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQ_VOICE)
            enterPageMode()
        } catch (_: Exception) {
            showToast("TV này không hỗ trợ tìm kiếm bằng giọng nói")
        }
    }

    // ───────────────────────────── Menu (bảng trượt bên phải) ─────────────────────────────

    /** Mở bảng: ẩn thanh công cụ và thông báo (bảng che bên phải, không cần cả hai cùng lúc). */
    private fun beforePanel() {
        if (isToolbarMode()) toolbar.visibility = View.GONE
        hideNextCard()
        handler.removeCallbacks(hideSnackbar)
        snackbar.visibility = View.GONE
    }

    private fun showMainMenu() {
        val url = currentUrl
        val isWebPage = LinkStore.isRecordable(url)
        beforePanel()
        // Hỏi uBO trạng thái chặn của trang trước khi dựng menu (trả lời trong vài ms).
        if (isWebPage && UblockBridge.isConnected) {
            UblockBridge.getSiteEnabled(url!!) { enabled -> buildMainMenu(url, enabled) }
        } else {
            buildMainMenu(url, null)
        }
    }

    private fun buildMainMenu(url: String?, adBlockEnabled: Boolean?) {
        val isWebPage = LinkStore.isRecordable(url)
        val host = if (isWebPage) SitePolicy.hostOf(url!!) else null
        panel.show("Phim275", host ?: "Trang chủ") {
            val page = mutableListOf<PanelItem>()
            recentBlockedPopup()?.let { popup ->
                page += PanelItem("Mở popup vừa bị chặn", R.drawable.ic_open, value = SitePolicy.hostOf(popup), accent = true) {
                    blockedPopupUrl = null
                    tabs.newTab(popup, settings.desktopModeDefault)
                }
            }
            if (isWebPage && videoPage == PlaybackCodec.key(url!!)) {
                page += PanelItem("Tùy chọn video", R.drawable.ic_tune, value = "Tốc độ · chất lượng") { showVideoOptions() }
            }
            if (isWebPage) {
                val marked = bookmarks.contains(url!!)
                page += PanelItem(if (marked) "Bỏ bookmark" else "Thêm bookmark", if (marked) R.drawable.ic_star else R.drawable.ic_star_border) {
                    toggleBookmark()
                }
                if (adBlockEnabled != null) {
                    page += PanelItem("Chặn quảng cáo trên trang", R.drawable.ic_shield, on = adBlockEnabled) {
                        setSiteAdBlock(url, !adBlockEnabled)
                    }
                }
                page += PanelItem("Ẩn một phần tử trên trang", R.drawable.ic_cut) { pickElement(zap = false) }
                page += PanelItem("Xóa tạm phần tử che màn hình", R.drawable.ic_clean) { pickElement(zap = true) }
                page += PanelItem("Báo trang lỗi / còn quảng cáo", R.drawable.ic_flag) { reportPage(url) }
            }
            currentTab?.let { tab ->
                page += PanelItem(
                    "Phiên bản trang",
                    if (tab.desktopMode) R.drawable.ic_desktop else R.drawable.ic_phone,
                    value = if (tab.desktopMode) "Desktop" else "Mobile",
                ) { tabs.setDesktopMode(tab, !tab.desktopMode) }
            }
            listOf(
                PanelSection("Trang này", page),
                PanelSection("Duyệt web", listOf(
                    PanelItem("Tìm kiếm / nhập địa chỉ", R.drawable.ic_search) { showTextEntryDialog(forPage = false) },
                    PanelItem("Nhập chữ vào ô trên trang", R.drawable.ic_keyboard) {
                        showTextEntryDialog(forPage = true)
                    },
                    PanelItem("Tab mới", R.drawable.ic_add) { tabs.newTab(homeUrl(), settings.desktopModeDefault) },
                    PanelItem("Tab đang mở", R.drawable.ic_tabs, value = "${tabs.tabs.size}") { showTabsMenu() },
                    PanelItem("Bookmark", R.drawable.ic_star_border, value = "${bookmarks.all().size}") { showBookmarksMenu() },
                    PanelItem("Lịch sử", R.drawable.ic_history) { showHistoryMenu() },
                )),
                PanelSection("Ứng dụng", listOf(
                    navModeItem(),
                    PanelItem("Cài đặt", R.drawable.ic_tune) { showSettingsMenu() },
                    PanelItem("Chẩn đoán hiệu năng", R.drawable.ic_chart, on = diagPanel.visibility == View.VISIBLE, keepOpen = true) {
                        toggleDiagnostics()
                        panel.refresh()
                    },
                    PanelItem("Kiểm tra bản cập nhật", R.drawable.ic_update, value = BuildConfig.VERSION_NAME) { checkForUpdates(silent = false) },
                )),
            )
        }
    }

    private fun navModeItem() = PanelItem(
        "Cách điều khiển",
        if (settings.navMode == NavMode.CURSOR) R.drawable.ic_mouse else R.drawable.ic_grid,
        value = if (settings.navMode == NavMode.CURSOR) "Con trỏ" else "Chuyển khối",
    ) {
        panel.choose("Điều khiển bằng remote", "Có thể đổi bất cứ lúc nào trong Menu", NavMode.entries, { settings.navMode }, { it.label }) {
            setNavMode(it)
        }
    }

    private fun showContextMenu(element: ContextElement) {
        val items = mutableListOf<PanelItem>()
        val link = element.linkUri
        if (link != null) items += linkItems(link, element.linkText ?: element.title)
        val src = element.srcUri
        if (src != null && element.type != ContextElement.TYPE_NONE) {
            val what = when (element.type) {
                ContextElement.TYPE_VIDEO -> "video"
                ContextElement.TYPE_AUDIO -> "âm thanh"
                else -> "ảnh"
            }
            items += PanelItem("Mở $what trong tab mới", R.drawable.ic_open) { tabs.newTab(src, settings.desktopModeDefault) }
        }
        if (items.isEmpty()) {
            showMainMenu()
            return
        }
        items += PanelItem("Menu trang…", R.drawable.ic_menu) { showMainMenu() }
        beforePanel()
        panel.show(element.linkText?.take(60) ?: "Liên kết", link?.let { SitePolicy.hostOf(it) ?: it.take(60) }) {
            listOf(PanelSection(null, items))
        }
    }

    private fun linkItems(link: String, title: String?) = listOf(
        PanelItem("Mở", R.drawable.ic_open) { currentSession?.loadUri(link) },
        PanelItem("Mở trong tab mới", R.drawable.ic_add) { tabs.newTab(link, settings.desktopModeDefault) },
        PanelItem("Thêm vào bookmark", R.drawable.ic_star_border) {
            bookmarks.add(link, title?.ifBlank { null })
            showToast("★ Đã thêm vào bookmark")
        },
    )

    private fun showTabsMenu() {
        beforePanel()
        panel.push("Tab đang mở", "Tối đa ${tabs.maxTabs} tab · ○ = đang ngủ để tiết kiệm RAM") {
            val list = tabs.tabs.map { tab ->
                val current = tab === currentTab
                PanelItem(
                    (if (!current && !tab.isLive) "○ " else "") + tab.label().take(70),
                    if (current) R.drawable.ic_check else 0,
                    value = if (current) "đang xem" else null,
                    accent = current,
                ) { showTabActions(tab) }
            }
            listOf(
                PanelSection(null, listOf(PanelItem("Tab mới", R.drawable.ic_add) { tabs.newTab(homeUrl(), settings.desktopModeDefault) })),
                PanelSection("${tabs.tabs.size} tab", list),
                PanelSection(
                    null,
                    if (tabs.tabs.size > 1) listOf(PanelItem("Đóng các tab khác", R.drawable.ic_close) { tabs.closeOthers() }) else emptyList(),
                ),
            )
        }
    }

    private fun showTabActions(tab: BrowserTab) {
        panel.menu(tab.label().take(60), tab.url?.let { SitePolicy.hostOf(it) }) {
            buildList {
                add(PanelItem("Chuyển tới tab này", R.drawable.ic_open) {
                    tabs.select(tab)
                    enterPageMode()
                })
                if (tabs.tabs.size > 1) {
                    add(PanelItem("Đóng tab", R.drawable.ic_close, keepOpen = true) {
                        tabs.close(tab)
                        panel.back()
                    })
                }
            }
        }
    }

    private fun toggleBookmark() {
        val url = currentUrl
        if (!LinkStore.isRecordable(url)) {
            showToast("Không thể bookmark trang này")
            return
        }
        if (bookmarks.contains(url!!)) {
            bookmarks.remove(url)
            showToast("Đã xóa khỏi bookmark")
        } else {
            bookmarks.add(url, currentTab?.title)
            showToast("★ Đã thêm vào bookmark — hiện trên Trang chủ")
        }
        updateBookmarkButton()
    }

    private fun showBookmarksMenu() {
        if (bookmarks.all().isEmpty()) {
            showToast("Chưa có bookmark. Mở Menu trên một trang và chọn \"Thêm bookmark\".")
            return
        }
        beforePanel()
        panel.push("Bookmark", "Hiện trên Trang chủ") {
            listOf(PanelSection(null, bookmarks.all().map { link ->
                PanelItem(link.title.take(60), R.drawable.ic_star, value = SitePolicy.hostOf(link.url)) {
                    panel.menu(link.title.take(60), SitePolicy.hostOf(link.url)) {
                        listOf(
                            PanelItem("Mở", R.drawable.ic_open) { currentSession?.loadUri(link.url) },
                            PanelItem("Mở trong tab mới", R.drawable.ic_add) { tabs.newTab(link.url, settings.desktopModeDefault) },
                            PanelItem("Xóa bookmark", R.drawable.ic_close, keepOpen = true) {
                                bookmarks.remove(link.url)
                                updateBookmarkButton()
                                if (bookmarks.all().isEmpty()) panel.hide() else panel.back()
                            },
                        )
                    }
                }
            }))
        }
    }

    private fun showHistoryMenu() {
        if (history.all().isEmpty()) {
            showToast("Lịch sử trống")
            return
        }
        beforePanel()
        panel.push("Lịch sử", "100 trang gần nhất") {
            val all = history.all()
            listOf(
                PanelSection(null, listOf(PanelItem("Xóa toàn bộ lịch sử", R.drawable.ic_clean) {
                    panel.confirm("Xóa lịch sử?", "Toàn bộ ${all.size} trang đã xem sẽ bị xóa.", "Xóa", R.drawable.ic_clean) {
                        history.clear()
                        playback.clear()
                        showToast("Đã xóa lịch sử")
                    }
                })),
                PanelSection("Gần đây", all.take(100).map { link ->
                    PanelItem(link.title.take(60), R.drawable.ic_history, value = SitePolicy.hostOf(link.url)) { currentSession?.loadUri(link.url) }
                }),
            )
        }
    }

    private fun showSettingsMenu() {
        beforePanel()
        panel.push("Cài đặt", "Phim275 ${BuildConfig.VERSION_NAME}") {
            listOf(
                PanelSection("Duyệt web", listOf(
                    PanelItem("Công cụ tìm kiếm", R.drawable.ic_search, value = settings.searchEngine.label) {
                        panel.choose("Công cụ tìm kiếm", null, SearchEngine.entries, { settings.searchEngine }, { it.label }) {
                            settings.searchEngine = it
                        }
                    },
                    navModeItem(),
                    PanelItem(
                        "Tab mới mở dạng",
                        if (settings.desktopModeDefault) R.drawable.ic_desktop else R.drawable.ic_phone,
                        value = if (settings.desktopModeDefault) "Desktop" else "Mobile",
                        keepOpen = true,
                    ) {
                        settings.desktopModeDefault = !settings.desktopModeDefault
                        panel.refresh()
                    },
                    PanelItem("Cỡ chữ trang web", R.drawable.ic_keyboard, value = "${(settings.fontScale * 100).toInt()}%") {
                        panel.choose(
                            "Cỡ chữ trang web", "Trang đang xem sẽ tải lại",
                            listOf(1.0f, 1.25f, 1.5f, 1.75f), { settings.fontScale }, { "${(it * 100).toInt()}%" },
                        ) {
                            settings.fontScale = it
                            BrowserRuntime.setFontScale(it)
                            currentSession?.reload()
                        }
                    },
                )),
                PanelSection("Xem phim", listOf(
                    PanelItem("Tự chuyển tập khi hết phim", R.drawable.ic_forward, on = settings.autoNextEpisode, keepOpen = true) {
                        settings.autoNextEpisode = !settings.autoNextEpisode
                        panel.refresh()
                    },
                )),
                PanelSection("Chặn quảng cáo", listOf(
                    PanelItem("DNS chặn quảng cáo (AdGuard)", R.drawable.ic_shield, on = settings.adBlockDns, keepOpen = true) {
                        settings.adBlockDns = !settings.adBlockDns
                        BrowserRuntime.setAdBlockDns(settings.adBlockDns)
                        panel.refresh()
                    },
                    PanelItem("Cập nhật bộ lọc ngay", R.drawable.ic_refresh) {
                        UblockBridge.updateFilters { ok ->
                            showToast(if (ok) "Đang tải bộ lọc mới trong nền…" else getString(R.string.ublock_loading))
                        }
                    },
                    PanelItem("Cài đặt nâng cao (uBlock Origin)", R.drawable.ic_tune) { openUblockPage("dashboard.html#3p-filters.html") },
                )),
                PanelSection("Hiệu năng", listOf(
                    PanelItem(
                        "Chế độ RAM thấp (box 1GB)", R.drawable.ic_chart,
                        value = settings.lowRamMode.label + if (BrowserRuntime.isLowRam) " · đang bật" else "",
                    ) {
                        panel.choose(
                            "Chế độ RAM thấp", "Tự động: bật khi TV có dưới 1,5GB RAM",
                            DeviceProfile.LowRamMode.entries, { settings.lowRamMode }, { it.label },
                        ) { mode ->
                            settings.lowRamMode = mode
                            val wanted = DeviceProfile.isLowRam(this)
                            UblockBridge.lowMemoryLists = wanted // bộ lọc gọn áp dụng ngay
                            if (wanted != BrowserRuntime.isLowRam) {
                                panel.confirm(
                                    "Khởi động lại Phim275?",
                                    "Cần mở lại app để áp dụng đầy đủ chế độ RAM thấp (bộ nhớ đệm, số tab).",
                                    "Mở lại ngay",
                                    R.drawable.ic_refresh,
                                ) { restartApp() }
                            }
                        }
                    },
                    PanelItem("Mở lại tab lần trước", R.drawable.ic_tabs, on = settings.restoreTabs, keepOpen = true) {
                        settings.restoreTabs = !settings.restoreTabs
                        panel.refresh()
                    },
                )),
                PanelSection("Cập nhật & dữ liệu", listOf(
                    PanelItem("Tự kiểm tra bản mới mỗi ngày", R.drawable.ic_update, on = settings.autoUpdateCheck, keepOpen = true) {
                        settings.autoUpdateCheck = !settings.autoUpdateCheck
                        panel.refresh()
                    },
                    PanelItem("Kiểm tra bản cập nhật", R.drawable.ic_update, value = BuildConfig.VERSION_NAME) { checkForUpdates(silent = false) },
                    PanelItem("Xóa cookie & bộ nhớ đệm", R.drawable.ic_clean) {
                        panel.confirm("Xóa dữ liệu duyệt web?", "Bạn sẽ bị đăng xuất khỏi các trang web.", "Xóa", R.drawable.ic_clean) {
                            clearBrowsingData()
                        }
                    },
                    PanelItem("Xem lại hướng dẫn", R.drawable.ic_info) { showOnboarding() },
                    PanelItem("Giới thiệu & giấy phép", R.drawable.ic_info) { tabs.newTab(ABOUT_URL, desktopMode = true) },
                )),
            )
        }
    }

    private fun setSiteAdBlock(url: String, enabled: Boolean) {
        UblockBridge.setSiteEnabled(url, enabled) { result ->
            if (result == null) {
                showToast(getString(R.string.ublock_loading))
            } else {
                showToast(if (result) "Đã BẬT chặn quảng cáo trên ${SitePolicy.hostOf(url)}" else "Đã TẮT chặn quảng cáo trên ${SitePolicy.hostOf(url)}")
                currentSession?.reload()
            }
        }
    }

    private fun pickElement(zap: Boolean) {
        UblockBridge.pickElement(zap) { ok ->
            if (!ok) {
                showToast(getString(R.string.ublock_loading))
            } else {
                showToast(
                    if (zap) "Di chuyển con trỏ tới phần tử cần xóa rồi bấm OK" else "Di chuyển con trỏ tới phần tử cần ẩn, bấm OK, rồi bấm \"Tạo\"",
                )
                applyNavMode(forceCursor = true)
                enterPageMode()
            }
        }
    }

    private fun reportPage(url: String) {
        val device = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
        val crash = CrashLog.summary(this) ?: ""
        val report = REPORT_URL +
            "?u=" + enc(url) +
            "&v=" + enc("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") +
            "&d=" + enc(device) +
            "&c=" + enc(crash)
        tabs.newTab(report, desktopMode = true)
    }

    /** Mở lại app (GeckoRuntime chỉ đọc cấu hình lúc khởi động). Tab được khôi phục nhờ SessionStore. */
    private fun restartApp() {
        saveSession()
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)
        getSystemService(android.app.AlarmManager::class.java)
            .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pending)
        finishAffinity()
        Runtime.getRuntime().exit(0)
    }

    private fun clearBrowsingData() {
        runtime.storageController.clearData(StorageController.ClearFlags.ALL).accept(
            { showToast("Đã xóa dữ liệu duyệt web") },
            { showToast("Xóa dữ liệu thất bại: ${it?.message}") },
        )
        history.clear()
        playback.clear()
    }

    // ───────────────────────────── Xem tiếp đúng chỗ ─────────────────────────────

    /** Trang (hoặc iframe trình phát của nó) báo vị trí video chính đang xem. */
    private fun onPlaybackPosition(page: String?, positionSec: Double, durationSec: Double, flush: Boolean, ended: Boolean) {
        // Không rõ trang nào lúc đang rời trang → bỏ qua (tab có thể đã sang trang mới; vị trí đã ghi ≤ 5 giây trước).
        if (page == null && flush) return
        val url = page ?: currentUrl
        if (!LinkStore.isRecordable(url)) return
        playback.record(url!!, positionSec, durationSec, flush)
        videoPage = PlaybackCodec.key(url)
        if (!flush) lastPlayingMs = SystemClock.uptimeMillis() // báo định kỳ chỉ khi video đang chạy
        updateNextCard(url, positionSec, durationSec, ended)
    }

    /** Video vừa bắt đầu phát: tua tới chỗ lần trước nếu cùng video và chưa xem hết. */
    private fun resumePositionFor(page: String?, durationSec: Double, startSec: Double): Double? {
        val url = page ?: currentUrl
        if (!LinkStore.isRecordable(url)) return null
        val host = Uri.parse(url).host.orEmpty()
        // Vừa đổi nguồn phát trong bảng Tùy chọn video: trình phát mới bắt đầu từ 0 → luôn tua về chỗ đang xem
        // (kể cả trang có tính năng xem tiếp riêng — tính năng đó thường không biết người xem vừa đổi server).
        if (SystemClock.uptimeMillis() < forceResumeUntilMs) {
            forceResumeUntilMs = 0
            val saved = playback.get(url!!)
            if (saved != null && saved.positionSec >= PlaybackCodec.MIN_RESUME_SEC && startSec < saved.positionSec - 5 &&
                !PlaybackCodec.isFinished(saved.positionSec, durationSec)
            ) {
                showToast("▶ Xem tiếp từ ${PlaybackCodec.formatTime(saved.positionSec)} trên nguồn mới")
                return saved.positionSec
            }
        }
        if (PlaybackCodec.siteResumedItself(startSec)) {
            playback.markSiteResumes(host) // trang tự xem tiếp được → từ nay nhường trang
            return null
        }
        val position = PlaybackCodec.resumePosition(playback.get(url!!), durationSec, startSec, playback.siteResumes(host)) ?: return null
        showToast("▶ Xem tiếp từ ${PlaybackCodec.formatTime(position)} · ⏪ tua lại")
        return position
    }

    // ───────────────────────────── Tùy chọn video ─────────────────────────────

    /** Trang (URL bỏ #) vừa báo vị trí video — menu chính hiện mục "Tùy chọn video". */
    private var videoPage: String? = null
    private var lastPlayingMs = 0L
    /** Trang vừa bị đóng giữa phim: null = không; true/false = lúc đó có đang toàn màn hình. */
    private var resumeAfterKill: Boolean? = null
    private var videoInfo: VideoInfo? = null
    private var videoSources: List<VideoSource> = emptyList()
    /** Vừa đổi nguồn phát: lần phát kế tiếp (trước mốc này) luôn tua về chỗ đang xem. */
    private var forceResumeUntilMs = 0L

    /**
     * Bảng tốc độ / chất lượng / nguồn phát của video đang xem: extension tvnav hỏi trình phát trong frame
     * có video, và trang chính về các nút server / bản dịch.
     */
    private fun showVideoOptions() {
        TvNavBridge.video("info") { infoJson ->
            TvNavBridge.video("sources") { sourcesJson ->
                val info = PlayerOptions.parse(infoJson)
                val sources = PlayerOptions.parseSources(sourcesJson)
                if (info == null && sources.isEmpty()) {
                    showToast("Không tìm thấy video trên trang này")
                    return@video
                }
                videoInfo = info
                videoSources = sources
                beforePanel()
                panel.show("Tùy chọn video", info?.let { engineName(it.engine) } ?: "Trình phát chưa chạy") {
                    listOf(PanelSection(null, videoOptionItems()))
                }
            }
        }
    }

    private fun engineName(engine: String) = when (engine) {
        "jwplayer" -> "Trình phát JW Player"
        "hls.js" -> "Trình phát HLS"
        else -> "Trình phát của trang"
    }

    private fun videoOptionItems(): List<PanelItem> {
        val items = mutableListOf<PanelItem>()
        if (videoSources.isNotEmpty()) {
            // Server / bản dịch của trang: đổi xong trình phát dựng lại → đóng bảng để xem.
            items += PanelItem("Nguồn phát / bản dịch", R.drawable.ic_open, value = PlayerOptions.sourcesSummary(videoSources)) {
                val sources = videoSources
                panel.choose(
                    "Nguồn phát / bản dịch", "Đổi server khi phim giật, tải chậm",
                    sources.indices.toList(), { sources.indexOfFirst { it.active } }, { sources[it].label },
                ) { index ->
                    forceResumeUntilMs = SystemClock.uptimeMillis() + FORCE_RESUME_WINDOW_MS
                    TvNavBridge.video("source", index) { json ->
                        PlayerOptions.parseSources(json).takeIf { it.isNotEmpty() }?.let { videoSources = it }
                    }
                    panel.hide()
                    showToast("Đang chuyển sang ${sources[index].label}…")
                }
            }
        }
        val info = videoInfo ?: return items
        items += listOf(
            PanelItem("Tốc độ phát", R.drawable.ic_forward, value = PlayerOptions.rateLabel(info.rate)) {
                panel.choose(
                    "Tốc độ phát", null, PlayerOptions.RATES,
                    { PlayerOptions.nearestRate(videoInfo?.rate ?: 1.0) }, PlayerOptions::rateLabel,
                ) { rate -> applyVideoOption("rate", rate) }
            },
        )
        items += if (info.canChooseLevel) {
            PanelItem("Chất lượng", R.drawable.ic_tune, value = info.qualitySummary) {
                val levels = videoInfo?.levels ?: info.levels
                panel.choose("Chất lượng", null, PlayerOptions.displayOrder(levels), { videoInfo?.level ?: -1 }, { levels[it] }) { index ->
                    applyVideoOption("level", index)
                }
            }
        } else {
            // Luồng chỉ có một độ phân giải, hoặc trình phát không công khai danh sách chất lượng.
            val note = if (info.levels.size == 1) "chỉ có 1 mức" else "trang không cho chọn"
            PanelItem("Chất lượng", R.drawable.ic_tune, value = "${info.qualitySummary} · $note", keepOpen = true) {}
        }
        return items
    }

    private fun applyVideoOption(cmd: String, value: Any) {
        TvNavBridge.video(cmd, value) { json ->
            PlayerOptions.parse(json)?.let { videoInfo = it }
            if (panel.isShowing) panel.refresh()
        }
    }

    /** Vài lần đầu vào toàn màn hình: nhắc cách mở tùy chọn video. */
    private fun hintVideoOptions() {
        val shown = settings.videoOptionsHints
        if (shown >= VIDEO_HINT_TIMES) return
        settings.videoOptionsHints = shown + 1
        showToast(if (isCursorMode()) "Menu: tốc độ, chất lượng video" else "▼ hoặc Menu: tốc độ, chất lượng video")
    }

    // ───────────────────────────── Tập tiếp theo ─────────────────────────────

    /** Trang xem phim báo tập kế tiếp (null = không có / không mở được). */
    private fun onNextEpisode(page: String?, label: String?) {
        val url = page ?: currentUrl ?: return
        val key = PlaybackCodec.key(url)
        if (label == null) {
            nextEpisodes.remove(key)
            if (nextCardPage == key) hideNextCard()
            if (SystemClock.uptimeMillis() - nextRequestedAtMs < NEXT_REQUEST_WINDOW_MS) {
                nextRequestedAtMs = 0
                showToast("Không tìm thấy tập tiếp theo trên trang này")
            }
            return
        }
        nextEpisodes.remove(key)
        nextEpisodes[key] = NextEpisodePolicy.displayLabel(label)
        while (nextEpisodes.size > MAX_NEXT_EPISODES) nextEpisodes.remove(nextEpisodes.keys.first())
        if (nextCardPage == key) nextCardTitle.text = nextEpisodes[key]
    }

    /** Mỗi lần trang báo vị trí video: tới đoạn cuối → hiện thẻ; hết phim → đếm ngược tự chuyển tập. */
    private fun updateNextCard(url: String, positionSec: Double, durationSec: Double, ended: Boolean) {
        val key = PlaybackCodec.key(url)
        val label = nextEpisodes[key] ?: return
        if (nextCardDismissedFor == key || currentUrl?.let { PlaybackCodec.key(it) } != key) return
        when {
            ended -> if (nextCountdownLeft == 0) showNextCard(key, label, countdown = settings.autoNextEpisode)
            NextEpisodePolicy.isCardDue(positionSec, durationSec) -> if (nextCardPage == null) showNextCard(key, label, countdown = false)
            nextCountdownLeft == 0 -> hideNextCard() // đã tua ngược khỏi đoạn cuối
        }
    }

    private fun showNextCard(key: String, label: String, countdown: Boolean) {
        if (panel.isShowing || onboarding != null) return
        nextCardPage = key
        nextCardTitle.text = label
        handler.removeCallbacks(nextCountdownTick)
        nextCountdownLeft = if (countdown) NextEpisodePolicy.AUTO_ADVANCE_SEC else 0
        nextCardCountdown.visibility = if (countdown) View.VISIBLE else View.GONE
        if (countdown) {
            nextCardCountdown.max = NextEpisodePolicy.AUTO_ADVANCE_SEC * 10
            nextCardCountdown.progress = nextCardCountdown.max
            handler.postDelayed(nextCountdownTick, NEXT_TICK_MS)
        }
        renderNextCardHint()
        nextCard.animate().cancel()
        if (nextCard.visibility != View.VISIBLE) {
            nextCard.alpha = 0f
            nextCard.translationX = dp(32f)
            nextCard.visibility = View.VISIBLE
        }
        nextCard.animate().alpha(1f).translationX(0f).setDuration(220).start()
    }

    private fun renderNextCardHint() {
        nextCardHint.text = if (nextCountdownLeft > 0) {
            "Tự chuyển sau $nextCountdownLeft giây · OK: xem ngay · Back: hủy"
        } else {
            "OK: xem ngay · Back: ẩn"
        }
    }

    /** Đếm ngược mỗi 100 ms (thanh chạy mượt), cập nhật chữ mỗi giây. */
    private var nextTicks = 0
    private val nextCountdownTick = object : Runnable {
        override fun run() {
            if (nextCardPage == null || nextCountdownLeft <= 0) return
            nextTicks++
            nextCardCountdown.progress = (nextCardCountdown.progress - 1).coerceAtLeast(0)
            if (nextTicks % 10 == 0) {
                nextCountdownLeft--
                if (nextCountdownLeft == 0) {
                    playNextEpisode()
                    return
                }
                renderNextCardHint()
            }
            handler.postDelayed(this, NEXT_TICK_MS)
        }
    }

    private fun hideNextCard() {
        handler.removeCallbacks(nextCountdownTick)
        nextCountdownLeft = 0
        nextTicks = 0
        nextCardOkDown = false
        if (nextCardPage == null) return
        nextCardPage = null
        nextCard.animate().cancel()
        nextCard.animate().alpha(0f).translationX(dp(32f)).setDuration(160).withEndAction {
            nextCard.visibility = View.GONE
        }.start()
    }

    /** Back trên thẻ: ẩn và không hiện lại cho tập này. */
    private fun dismissNextCard() {
        nextCardDismissedFor = nextCardPage
        hideNextCard()
    }

    /** Mở tập kế: extension tvnav nhận phím F10 và bấm vào ô tập kế tiếp trên trang. */
    private fun playNextEpisode() {
        val key = nextCardPage ?: return
        val label = nextEpisodes[key] ?: "tập tiếp theo"
        nextCardDismissedFor = key
        hideNextCard()
        nextRequestedAtMs = SystemClock.uptimeMillis()
        showToast("⏭ Đang mở $label…")
        sendKeyToPage(KeyEvent.KEYCODE_F10, 0)
    }

    // ───────────────────────────── Tải file ─────────────────────────────

    private fun startDownload(response: WebResponse) {
        try {
            response.body?.close()
        } catch (_: Exception) {
        }
        val uri = response.uri
        if (!uri.startsWith("http")) {
            showToast("Không hỗ trợ tải loại file này")
            return
        }
        fun header(name: String) = response.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        val fileName = URLUtil.guessFileName(uri, header("Content-Disposition"), header("Content-Type"))
        // Bấm tải APK Phim275 (từ phim275.vercel.app / GitHub) → dùng bộ tự cập nhật để cài thẳng,
        // vì TV thường không có trình quản lý file để mở APK trong thư mục Download.
        if (fileName.startsWith("phim275-") && fileName.endsWith(".apk")) {
            checkForUpdates(silent = false)
            return
        }
        try {
            val request = DownloadManager.Request(Uri.parse(uri))
                .setTitle(fileName)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            getSystemService(DownloadManager::class.java).enqueue(request)
            showToast("Đang tải về: $fileName (thư mục Download)")
        } catch (e: Exception) {
            showToast("Không tải được: ${e.message}")
        }
    }

    // ───────────────────────────── Tự cập nhật ─────────────────────────────

    private fun scheduleAutoUpdateCheck() {
        if (!settings.autoUpdateCheck) return
        if (System.currentTimeMillis() - settings.lastUpdateCheckMs < UpdateManager.AUTO_CHECK_INTERVAL_MS) return
        handler.postDelayed({ checkForUpdates(silent = true) }, AUTO_UPDATE_DELAY_MS)
    }

    private fun checkForUpdates(silent: Boolean) {
        if (!silent) showToast("Đang kiểm tra bản cập nhật…")
        updater.check { result ->
            settings.lastUpdateCheckMs = System.currentTimeMillis()
            when (result) {
                is UpdateManager.CheckResult.Available -> {
                    beforePanel()
                    panel.confirm(
                        "Có bản mới: Phim275 ${result.info.versionName}",
                        "Bạn đang dùng ${BuildConfig.VERSION_NAME}. Tải và cài bản mới ngay? (khoảng 90MB)",
                        "Cập nhật ngay",
                        R.drawable.ic_update,
                    ) { startUpdate(result.apk) }
                }
                UpdateManager.CheckResult.UpToDate -> if (!silent) showToast("Bạn đang dùng bản mới nhất (${BuildConfig.VERSION_NAME})")
                is UpdateManager.CheckResult.Failed -> if (!silent) showToast("Không kiểm tra được: ${result.message}")
            }
        }
    }

    private fun startUpdate(apk: UpdateInfo.Apk) {
        if (!ApkInstaller.ensurePermission(this)) {
            showToast("Hãy bật \"Cho phép cài ứng dụng\" cho Phim275, rồi vào Cài đặt → Kiểm tra bản cập nhật")
            return
        }
        val dialog = showProgress("Đang tải bản cập nhật", "0%")
        updater.download(apk, onProgress = { dialog.setMessage("$it%") }) { file, error ->
            dialog.dismiss()
            if (file == null) {
                showToast("Tải bản cập nhật thất bại: $error")
            } else {
                try {
                    ApkInstaller.install(this, file)
                } catch (e: Exception) {
                    showToast("Không cài được: ${e.message}")
                }
            }
        }
    }

    // ───────────────────────────── Chẩn đoán ─────────────────────────────

    private fun toggleDiagnostics() {
        if (diagPanel.visibility == View.VISIBLE) {
            diagPanel.visibility = View.GONE
        } else {
            diagPanel.visibility = View.VISIBLE
            handler.removeCallbacks(diagUpdater)
            handler.post(diagUpdater)
        }
    }

    private fun updateDiagnostics() {
        val blocked = UblockBridge.blockedCount.takeIf { it >= 0 }?.toString() ?: "—"
        val extra = listOf(
            "uBO chặn : $blocked yêu cầu",
            "Tab      : ${tabs.tabs.size}/${tabs.maxTabs} (đang trong RAM: ${tabs.tabs.count { it.isLive }})",
            "RAM thấp : ${if (BrowserRuntime.isLowRam) "BẬT (bộ lọc gọn, cache nhỏ)" else "tắt"}",
            "Crash    : ${CrashLog.summary(this) ?: "chưa có"}",
        )
        diagPanel.text = diagnostics.snapshot(ublockStatusText(), currentUrl, extra)
    }

    // ───────────────────────────── Phím remote ─────────────────────────────

    private fun setNavMode(mode: NavMode) {
        settings.navMode = mode
        applyNavMode()
        showToast(
            if (mode == NavMode.CURSOR) {
                "Con trỏ chuột: ◀▲▶▼ di chuyển, OK bấm"
            } else {
                "Chuyển khối: ◀▲▶▼ chọn khối (viền đỏ), OK bấm"
            },
        )
    }

    private fun applyNavMode(forceCursor: Boolean = false) {
        val useCursor = forceCursor || settings.navMode == NavMode.CURSOR
        cursorView.visibility = if (useCursor) View.VISIBLE else View.GONE
        if (useCursor) cursor.poke()
    }

    private fun isCursorMode() = cursorView.visibility == View.VISIBLE

    /** Con trỏ đang hiện trên màn hình (chưa tự mờ đi sau vài giây không dùng). */
    private fun isCursorShown() = isCursorMode() && cursorView.alpha > 0.05f

    /** Toàn màn hình thật (Fullscreen API) hoặc trang phóng to video bằng CSS (extension tvnav). */
    private fun isAnyFullscreen() = fullScreen || pseudoFullscreen

    private fun exitAnyFullscreen() {
        if (pseudoFullscreen) {
            sendKeyToPage(KeyEvent.KEYCODE_ESCAPE, 0) // tvnav bỏ phóng to CSS khi nhận Escape
            pseudoFullscreen = false
        }
        if (fullScreen) currentSession?.exitFullScreen()
    }

    /**
     * OK khi đang toàn màn hình: 1 lần = phát/dừng; 2 lần nhanh = thoát toàn màn hình
     * (lần bấm thứ hai đảo lại trạng thái phát của lần đầu cho đúng như trước).
     */
    private fun handleFullscreenOk() {
        val now = SystemClock.uptimeMillis()
        if (now - lastFullscreenOkMs < DOUBLE_PRESS_MS) {
            lastFullscreenOkMs = 0
            controlMedia(MediaKeys.Action.TOGGLE)
            exitAnyFullscreen()
        } else {
            lastFullscreenOkMs = now
            controlMedia(MediaKeys.Action.TOGGLE)
        }
    }

    // ── Tua: cộng dồn khi giữ phím, tua một lần khi buông (đỡ chạy script trong mọi frame ~20 lần/giây) ──
    private var seekAccumSec = 0
    private val flushSeek = Runnable {
        val delta = seekAccumSec
        seekAccumSec = 0
        if (delta != 0) applySeek(delta)
    }

    private fun accumulateSeek(forward: Boolean, repeatCount: Int) {
        val step = SeekPolicy.step(repeatCount)
        seekAccumSec += if (forward) step else -step
        if (repeatCount > 0) showToast((if (seekAccumSec >= 0) "⏩ " else "⏪ ") + SeekPolicy.deltaLabel(seekAccumSec))
        handler.removeCallbacks(flushSeek)
        handler.postDelayed(flushSeek, SEEK_FLUSH_MS)
    }

    private fun applySeek(deltaSec: Int) {
        val icon = if (deltaSec >= 0) "⏩ " else "⏪ "
        TvNavBridge.video("seekBy", deltaSec) { json ->
            val info = PlayerOptions.parse(json)
            if (info != null) {
                showToast(icon + SeekPolicy.positionLabel(info.time, info.duration))
                return@video
            }
            // Không tìm thấy video qua tvnav → cách cũ qua uBO (mọi frame).
            UblockBridge.media(if (deltaSec >= 0) MediaKeys.Action.FORWARD else MediaKeys.Action.REWIND, kotlin.math.abs(deltaSec)) { ok ->
                if (ok) showToast(icon + SeekPolicy.deltaLabel(deltaSec))
            }
        }
    }

    private fun seekToPercent(percent: Int) {
        TvNavBridge.video("seekTo", percent / 100.0) { json ->
            val info = PlayerOptions.parse(json)
            showToast(if (info != null) "⏩ $percent% · ${SeekPolicy.positionLabel(info.time, info.duration)}" else "Không tua được video trên trang này")
        }
    }

    private fun controlMedia(action: MediaKeys.Action) {
        UblockBridge.media(action) { ok ->
            if (!ok) {
                // Không tìm thấy video (hoặc uBO chưa sẵn sàng): thử phím Space như bàn phím thật.
                if (action == MediaKeys.Action.TOGGLE) sendKeyToPage(KeyEvent.KEYCODE_SPACE, 0)
                return@media
            }
            when (action) {
                MediaKeys.Action.FORWARD -> showToast("⏩ +${MediaKeys.SEEK_SECONDS} giây")
                MediaKeys.Action.REWIND -> showToast("⏪ −${MediaKeys.SEEK_SECONDS} giây")
                else -> Unit
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        // Hướng dẫn lần đầu: Back hoặc OK đều đóng.
        if (onboarding != null) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) hideOnboarding()
                return true
            }
            return super.dispatchKeyEvent(event)
        }
        // Bảng menu đang mở: Back = lùi một cấp, Menu = đóng; phím khác do bảng xử lý (▲▼ OK).
        if (panel.isShowing) {
            when (code) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) panel.back()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) panel.hide()
                    return true
                }
            }
            // Focus lạc ra trang web phía sau → phím đầu tiên chỉ kéo focus về bảng, không lọt xuống trang.
            if (event.action == KeyEvent.ACTION_DOWN && panel.reclaimFocus()) return true
            return super.dispatchKeyEvent(event)
        }
        if (isToolbarMode()) {
            when (code) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) enterPageMode()
                    return true
                }
                KeyEvent.KEYCODE_MENU -> {
                    if (event.action == KeyEvent.ACTION_UP) showMainMenu()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (event.action == KeyEvent.ACTION_DOWN) enterPageMode()
                    return true
                }
            }
            return super.dispatchKeyEvent(event)
        }

        // Thẻ "Tập tiếp theo": OK = xem ngay, Back = ẩn; phím khác vẫn điều khiển video như thường.
        if (nextCardPage != null) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
                -> {
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (event.repeatCount == 0) nextCardOkDown = true
                        return true
                    }
                    if (nextCardOkDown) { // chỉ nhận cú bấm bắt đầu khi thẻ đã hiện
                        nextCardOkDown = false
                        playNextEpisode()
                        return true
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_UP) dismissNextCard()
                    return true
                }
            }
        }

        // Phím media của remote (Play/Pause, tua) → điều khiển video trên trang.
        MediaKeys.forMediaKey(code)?.let { action ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (action) {
                    MediaKeys.Action.FORWARD -> accumulateSeek(true, event.repeatCount)
                    MediaKeys.Action.REWIND -> accumulateSeek(false, event.repeatCount)
                    else -> if (event.repeatCount == 0) controlMedia(action)
                }
            }
            return true
        }
        // Xem video toàn màn hình, con trỏ đang ẩn: ◀ ▶ tua 10 giây, OK tạm dừng/phát (▲▼ hiện con trỏ).
        if (isAnyFullscreen()) {
            // Menu (hoặc ▼ ở chế độ chuyển khối) → tốc độ, chất lượng.
            if (code == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) showVideoOptions()
                return true
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN && !isCursorMode()) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) showVideoOptions()
                return true
            }
            // Phím số: nhảy tới 0%–90% phim (như YouTube).
            if (code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) seekToPercent((code - KeyEvent.KEYCODE_0) * 10)
                return true
            }
            MediaKeys.forFullscreenKey(code, cursorVisible = isCursorShown())?.let { action ->
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when (action) {
                        MediaKeys.Action.FORWARD -> accumulateSeek(true, event.repeatCount)
                        MediaKeys.Action.REWIND -> accumulateSeek(false, event.repeatCount)
                        else -> if (event.repeatCount == 0) {
                            if (action == MediaKeys.Action.TOGGLE) handleFullscreenOk() else controlMedia(action)
                        }
                    }
                }
                return true
            }
        }

        if (isCursorMode()) {
            if (cursor.handleKey(event)) return true
        } else if (code in CursorController.DIRECTION_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN) moveSpatialFocus(code)
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
            -> {
                handleCenterKey(event)
                return true
            }
            KeyEvent.KEYCODE_BACK -> {
                handleBackKey(event)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (event.action == KeyEvent.ACTION_UP) enterToolbarMode()
                return true
            }
            KeyEvent.KEYCODE_SEARCH -> {
                if (event.action == KeyEvent.ACTION_UP) enterToolbarMode()
                return true
            }
            KeyEvent.KEYCODE_VOICE_ASSIST -> {
                if (event.action == KeyEvent.ACTION_UP) startVoiceSearch()
                return true
            }
            KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_CHANNEL_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) scrollPage(-PAGE_SCROLL_FRACTION)
                return true
            }
            KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) scrollPage(PAGE_SCROLL_FRACTION)
                return true
            }
        }
        if (isCursorMode()) cursor.poke()
        return super.dispatchKeyEvent(event)
    }

    /**
     * OK: chế độ con trỏ → chạm thật tại con trỏ (nhấn = chạm, giữ = chạm lâu → menu chuột phải của trang).
     * Chế độ chuyển khối → gửi Enter cho khối đang chọn; giữ OK → menu của khối (liên kết) hoặc menu chung.
     */
    private fun handleCenterKey(event: KeyEvent) {
        if (!isCursorMode()) {
            when {
                event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                    centerLongPressFired = false
                    handler.postDelayed(spatialLongPress, LONG_PRESS_MENU_MS)
                }
                event.action == KeyEvent.ACTION_UP -> {
                    handler.removeCallbacks(spatialLongPress)
                    if (!centerLongPressFired) sendKeyToPage(KeyEvent.KEYCODE_ENTER, 0)
                }
            }
            return
        }
        when {
            event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 -> {
                centerLongPressFired = false
                touchDownTime = SystemClock.uptimeMillis()
                dispatchTouch(touchDownTime, touchDownTime, MotionEvent.ACTION_DOWN, cursor.x, cursor.y)
                handler.postDelayed(centerLongPressFallback, LONG_PRESS_MENU_MS)
            }
            event.action == KeyEvent.ACTION_UP -> {
                handler.removeCallbacks(centerLongPressFallback)
                if (touchDownTime != 0L) {
                    // Nhấn quá nhanh (remote gửi DOWN/UP liền nhau) vẫn giữ tối thiểu một chút để trang nhận là cú chạm.
                    val held = SystemClock.uptimeMillis() - touchDownTime
                    val downTime = touchDownTime
                    val x = cursor.x
                    val y = cursor.y
                    touchDownTime = 0L
                    handler.postDelayed({
                        dispatchTouch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y)
                    }, (MIN_TAP_MS - held).coerceAtLeast(0))
                }
            }
        }
        cursor.poke()
    }

    /** Back: thoát toàn màn hình → lùi trang → nhấn 2 lần để thoát. Giữ Back: mở thanh địa chỉ. */
    private fun handleBackKey(event: KeyEvent) {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    backLongPressHandled = false
                } else if (!backLongPressHandled) {
                    backLongPressHandled = true
                    enterToolbarMode()
                }
            }
            KeyEvent.ACTION_UP -> {
                if (backLongPressHandled) return
                val now = SystemClock.uptimeMillis()
                val tab = currentTab
                when {
                    isAnyFullscreen() -> exitAnyFullscreen()
                    tab?.canGoBack == true -> currentSession?.goBack()
                    tab != null && tabs.tabs.size > 1 -> tabs.close(tab) // tab mở từ menu: Back ở trang đầu = đóng tab
                    now - lastBackPressMs < EXIT_CONFIRM_WINDOW_MS -> finish()
                    else -> {
                        lastBackPressMs = now
                        showToast(getString(R.string.press_back_again))
                    }
                }
            }
        }
    }

    /** Chế độ chuyển khối: gửi phím mũi tên vào trang, extension tvnav chọn khối kế tiếp. */
    private fun moveSpatialFocus(code: Int) {
        sendKeyToPage(code, 0)
    }

    /** Extension tvnav yêu cầu chạm vào giữa khối đang chọn (fx, fy ∈ [0,1]). */
    private fun tapAtFraction(fx: Float, fy: Float) {
        val x = fx * geckoView.width
        val y = fy * geckoView.height
        val downTime = SystemClock.uptimeMillis()
        dispatchTouch(downTime, downTime, MotionEvent.ACTION_DOWN, x, y)
        handler.postDelayed({ dispatchTouch(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y) }, MIN_TAP_MS)
    }

    private fun sendKeyToPage(keyCode: Int, meta: Int) {
        val now = SystemClock.uptimeMillis()
        geckoView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        geckoView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    // ───────────────────────────── Con trỏ ảo → trang web ─────────────────────────────

    private fun dispatchTouch(downTime: Long, eventTime: Long, action: Int, x: Float, y: Float) {
        if (x < 0 || y < 0) return
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        geckoView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    override fun onCursorMoved(x: Float, y: Float) {
        if (y > 2f) topPushAccumPx = 0f
        val now = SystemClock.uptimeMillis()
        if (now - lastHoverMs < HOVER_THROTTLE_MS) return
        lastHoverMs = now
        // Sự kiện rê chuột để menu thả xuống / tooltip của trang hoạt động.
        val ev = MotionEvent.obtain(now, now, MotionEvent.ACTION_HOVER_MOVE, x, y, 0)
        ev.source = InputDevice.SOURCE_MOUSE
        geckoView.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    override fun onEdgePush(dx: Float, dy: Float) {
        // Đẩy con trỏ lên mép trên khi trang đã ở đầu → mở thanh địa chỉ.
        if (dy < 0 && dx == 0f && pageScrollY <= 0) {
            topPushAccumPx += -dy
            if (topPushAccumPx > TOP_PUSH_TO_TOOLBAR_PX * cursorView.width / 1920f) enterToolbarMode()
            return
        }
        wheelScroll(cursor.x, cursor.y, dx, dy)
    }

    private fun scrollPage(fraction: Double) {
        val x = if (isCursorMode()) cursor.x else geckoView.width / 2f
        val y = if (isCursorMode()) cursor.y else geckoView.height / 2f
        wheelScroll(x, y, 0f, (geckoView.height * fraction).toFloat())
    }

    /**
     * Cuộn bằng sự kiện con lăn chuột tại vị trí con trỏ: Gecko cuộn đúng khung nằm dưới con trỏ
     * (danh sách tập phim, iframe…). dx/dy tính bằng pixel, dương = sang phải/xuống.
     */
    private fun wheelScroll(x: Float, y: Float, dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        val props = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x.coerceIn(1f, geckoView.width - 2f)
            this.y = y.coerceIn(1f, geckoView.height - 2f)
            // Quy ước Android: VSCROLL dương = cuộn lên; HSCROLL dương = cuộn sang phải.
            setAxisValue(MotionEvent.AXIS_VSCROLL, -dy / wheelFactorV)
            setAxisValue(MotionEvent.AXIS_HSCROLL, dx / wheelFactorH)
        }
        val now = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, arrayOf(props), arrayOf(coords),
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        geckoView.dispatchGenericMotionEvent(ev)
        ev.recycle()
    }

    private val wheelFactorV by lazy { ViewConfiguration.get(this).scaledVerticalScrollFactor }
    private val wheelFactorH by lazy { ViewConfiguration.get(this).scaledHorizontalScrollFactor }

    // ───────────────────────────── Tiện ích ─────────────────────────────

    /** Trang chủ nhận bookmark + công cụ tìm kiếm qua phần #hash (trang tĩnh trong APK). */
    private fun homeUrl(): String {
        val marks = JSONArray()
        bookmarks.all().take(HOME_BOOKMARKS).forEach { marks.put(JSONObject().put("u", it.url).put("t", it.title)) }
        // "Xem gần đây": các trang vừa xem (mới nhất trước) để bấm xem tiếp.
        val recent = JSONArray()
        history.all()
            .filter { it.title.isNotBlank() && it.title != it.url && !LinkStore.isErrorTitle(it.title) } // chưa có tiêu đề / trang lỗi
            .distinctBy { it.title } // trang phim + trang tập thường trùng tiêu đề
            .take(HOME_RECENT)
            .forEach { link ->
                val item = JSONObject().put("u", link.url).put("t", link.title)
                // Đang xem dở video trên trang này → thanh tiến độ + "Còn 23:10".
                playback.get(link.url)?.let { p ->
                    item.put("p", p.fraction).put("w", "Còn " + PlaybackCodec.formatTime(p.durationSec - p.positionSec))
                }
                recent.put(item)
            }
        val data = JSONObject()
            .put("s", settings.searchEngine.template)
            .put("n", settings.searchEngine.label)
            .put("b", marks)
            .put("r", recent)
        return HOME_URL + "#" + enc(data.toString())
    }

    private fun displayUrl(url: String?): String = when {
        url == null -> ""
        url.startsWith(HOME_URL) -> ""
        url.startsWith("data:") -> ""
        else -> url
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /** Thông báo ngắn ở cuối màn hình (thay Toast: Toast trên TV kèm icon app, lệch tông giao diện). */
    private fun showToast(text: String) {
        if (panel.isShowing) return // bảng đang mở đã thể hiện kết quả; tránh chữ đè lên bảng
        handler.removeCallbacks(hideSnackbar)
        snackbar.text = text
        if (snackbar.visibility != View.VISIBLE) {
            snackbar.alpha = 0f
            snackbar.translationY = dp(16f)
            snackbar.visibility = View.VISIBLE
        }
        snackbar.animate().alpha(1f).translationY(0f).setDuration(160).start()
        handler.postDelayed(hideSnackbar, (SNACKBAR_BASE_MS + text.length * SNACKBAR_PER_CHAR_MS).coerceAtMost(SNACKBAR_MAX_MS))
    }

    private val hideSnackbar = Runnable {
        snackbar.animate().alpha(0f).translationY(dp(16f)).setDuration(200).withEndAction {
            snackbar.visibility = View.GONE
        }.start()
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    /** Ô địa chỉ: 🔒 + tên miền đậm + đường dẫn mờ (đọc nhanh từ xa); trang chủ → icon tìm kiếm + gợi ý. */
    private fun renderUrlBar(url: String?) {
        val shown = displayUrl(url)
        val icon = when {
            shown.isEmpty() -> R.drawable.ic_search
            shown.startsWith("https://") -> R.drawable.ic_lock
            else -> R.drawable.ic_info
        }
        setStartIcon(urlBar, icon, if (icon == R.drawable.ic_lock) R.color.ok else R.color.text_dim)
        if (shown.isEmpty()) {
            urlBar.text = ""
            return
        }
        val bare = shown.removePrefix("https://").removePrefix("http://").removePrefix("www.")
        val hostEnd = bare.indexOf('/').let { if (it < 0) bare.length else it }
        urlBar.text = SpannableString(bare).apply {
            setSpan(ForegroundColorSpan(getColor(R.color.text)), 0, hostEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, hostEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun setStartIcon(view: TextView, icon: Int, tint: Int) {
        val size = dp(20f).toInt()
        val d = getDrawable(icon)?.mutate()?.apply {
            setBounds(0, 0, size, size)
            setTint(getColor(tint))
        }
        view.setCompoundDrawablesRelative(d, null, null, null)
    }

    // ───────────────────────────── Hướng dẫn lần đầu ─────────────────────────────

    private var onboarding: View? = null

    private fun showOnboarding() {
        if (onboarding != null) return
        beforePanel()
        val root = findViewById<ViewGroup>(R.id.root)
        val view = layoutInflater.inflate(R.layout.view_onboarding, root, false)
        val rows = view.findViewById<LinearLayout>(R.id.onboarding_rows)
        val tips = listOf(
            Triple(R.drawable.ic_grid, "◀ ▲ ▶ ▼", "Chuyển giữa các khối trên trang (viền đỏ)"),
            Triple(R.drawable.ic_check, "OK", "Bấm vào khối · trên video: bấm OK 2 lần để xem toàn màn hình"),
            Triple(R.drawable.ic_menu, "Menu / giữ Back", "Thanh địa chỉ, tab, bookmark, cài đặt"),
            Triple(R.drawable.ic_open, "Giữ OK", "Menu của khối: mở trong tab mới, thêm bookmark…"),
            Triple(R.drawable.ic_shield, "Tự động", "Chặn quảng cáo, popup và trang bắt tắt trình chặn"),
        )
        tips.forEach { (icon, key, desc) ->
            rows.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(7f).toInt(), 0, dp(7f).toInt())
                addView(ImageView(context).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(getColor(R.color.accent))
                }, LinearLayout.LayoutParams(dp(24f).toInt(), dp(24f).toInt()).apply { marginEnd = dp(16f).toInt() })
                addView(TextView(context).apply {
                    text = key
                    setTextColor(getColor(R.color.text))
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
                    paint.isFakeBoldText = true
                }, LinearLayout.LayoutParams(dp(160f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(context).apply {
                    text = desc
                    setTextColor(getColor(R.color.text_dim))
                    setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.text_body))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            })
        }
        val start = view.findViewById<View>(R.id.onboarding_start)
        start.setOnClickListener { hideOnboarding() }
        root.addView(view)
        onboarding = view
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(220).start()
        start.requestFocus()
    }

    private fun hideOnboarding() {
        val view = onboarding ?: return
        settings.onboardingDone = true
        onboarding = null
        view.animate().alpha(0f).setDuration(180).withEndAction {
            (view.parent as? ViewGroup)?.removeView(view)
        }.start()
        geckoView.requestFocus()
    }

    companion object {
        const val HOME_URL = "resource://android/assets/home/index.html"
        const val ABOUT_URL = "resource://android/assets/home/about.html"
        const val REPORT_URL = "https://phim275.vercel.app/report"
        private const val REQ_VOICE = 1001
        private const val IME_CHECK_MS = 700L
        private const val DOUBLE_PRESS_MS = 450L
        private const val BLOCK_MENU_TIMEOUT_MS = 600L
        private const val HOME_RECENT = 6
        private const val HOME_BOOKMARKS = 18
        private const val DIAG_REFRESH_MS = 2000L
        private const val MIN_TAP_MS = 40L
        private const val LONG_PRESS_MENU_MS = 900L
        private const val HOVER_THROTTLE_MS = 60L
        private const val POPUP_RESCUE_WINDOW_MS = 60_000L
        private const val EXIT_CONFIRM_WINDOW_MS = 2000L
        private const val AUTO_UPDATE_DELAY_MS = 15_000L
        private const val TOP_PUSH_TO_TOOLBAR_PX = 480f
        private const val PAGE_SCROLL_FRACTION = 0.8
        private const val SNACKBAR_BASE_MS = 2200L
        private const val SNACKBAR_PER_CHAR_MS = 35L
        private const val SNACKBAR_MAX_MS = 5000L
        private const val MAX_NEXT_EPISODES = 20
        private const val VIDEO_HINT_TIMES = 5
        private const val SEEK_FLUSH_MS = 300L
        private const val RESUME_AFTER_KILL_WINDOW_MS = 15_000L
        private const val FORCE_RESUME_WINDOW_MS = 30_000L
        private const val NEXT_TICK_MS = 100L
        private const val NEXT_REQUEST_WINDOW_MS = 4000L
    }
}

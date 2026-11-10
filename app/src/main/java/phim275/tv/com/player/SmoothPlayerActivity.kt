package phim275.tv.com.player

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.VideoSize
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import phim275.tv.com.R
import phim275.tv.com.data.PlayerOptions
import phim275.tv.com.data.SeekPolicy
import phim275.tv.com.data.SmoothResult
import phim275.tv.com.data.SmoothStream
import phim275.tv.com.data.SmoothSubtitle

/**
 * Trình phát mượt: phát luồng của trang (HLS / file video) bằng ExoPlayer trên SurfaceView.
 *
 * Gecko ghép từng khung video bằng GPU theo đồng hồ âm thanh nên phim 24 hình/giây trên màn 60Hz bị lệch nhịp
 * (đo trên máy chiếu H93A: 5–12% khoảng khung sai nhịp). ExoPlayer trả khung đúng theo lưới vsync và Android đưa
 * SurfaceView thẳng vào lớp video phần cứng → nhịp 2-3 đều tuyệt đối (0% sai nhịp), tốn ít CPU/GPU hơn.
 */
@OptIn(UnstableApi::class)
class SmoothPlayerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var player: ExoPlayer
    private lateinit var root: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var loading: ProgressBar
    private lateinit var osd: View
    private lateinit var title: TextView
    private lateinit var progress: ProgressBar
    private lateinit var time: TextView
    private lateinit var seekLabel: TextView
    private lateinit var menu: View
    private lateinit var menuTitle: TextView
    private lateinit var menuItems: LinearLayout
    private lateinit var toast: TextView
    private lateinit var subtitle: TextView

    private var started = false       // đã hiện khung hình đầu tiên
    private var retried = false       // đã thử nạp lại một lần sau lỗi mạng giữa chừng
    private var finishing = false
    private var seekDeltaSec = 0
    private var seekFromMs = -1L
    private var menuLevel: (() -> Unit)? = null // bảng con đang mở → Back quay về bảng chính

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_smooth_player)
        root = findViewById(R.id.player_root)
        surface = findViewById(R.id.player_surface)
        loading = findViewById(R.id.player_loading)
        osd = findViewById(R.id.player_osd)
        title = findViewById(R.id.player_title)
        progress = findViewById(R.id.player_progress)
        time = findViewById(R.id.player_time)
        seekLabel = findViewById(R.id.player_seek)
        menu = findViewById(R.id.player_menu)
        menuTitle = findViewById(R.id.player_menu_title)
        menuItems = findViewById(R.id.player_menu_items)
        toast = findViewById(R.id.player_toast)
        subtitle = findViewById(R.id.player_subtitle)

        val url = intent.dataString
        if (url == null) {
            finish()
            return
        }
        title.text = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val subtitles = pendingSubtitles
        pendingSubtitles = emptyList()
        player = buildPlayer()
        player.setVideoSurfaceView(surface)
        val item = MediaItem.Builder().setUri(Uri.parse(url))
            // Playlist nhiều trang không có đuôi .m3u8 (vd. https://embed…/eyJ0…) → báo rõ là HLS.
            .apply { if (intent.getBooleanExtra(EXTRA_HLS, false)) setMimeType(MimeTypes.APPLICATION_M3U8) }
            .setSubtitleConfigurations(subtitles.map(::subtitleConfig))
            .build()
        player.setMediaItem(item, (intent.getDoubleExtra(EXTRA_START_SEC, 0.0) * 1000).toLong())
        player.prepare()
        player.playWhenReady = true
        showOsd()
    }

    private fun buildPlayer(): ExoPlayer {
        val headers = buildMap {
            intent.getStringExtra(EXTRA_REFERER)?.let { put("Referer", it) }
            intent.getStringExtra(EXTRA_ORIGIN)?.let { put("Origin", it) }
        }
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(intent.getStringExtra(EXTRA_UA) ?: DEFAULT_UA)
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(NETWORK_TIMEOUT_MS)
            .setReadTimeoutMs(NETWORK_TIMEOUT_MS)
        // Máy chiếu ~1,6GB RAM: đệm vừa đủ (15–40 giây) thay vì mặc định tới 50 giây.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_500, 3_000)
            .build()
        // Bắt đầu ở chất lượng cao (wifi trong nhà) thay vì mức thấp rồi mới tăng dần.
        val bandwidth = DefaultBandwidthMeter.Builder(this).setInitialBitrateEstimate(INITIAL_BITRATE).build()
        return ExoPlayer.Builder(this, DefaultRenderersFactory(this).setEnableDecoderFallback(true))
            .setLoadControl(loadControl)
            .setBandwidthMeter(bandwidth)
            .setMediaSourceFactory(DefaultMediaSourceFactory(PlaylistCopyDataSource.Factory(takePlaylistCopies(), http)))
            .build().also { p ->
                p.addListener(playerListener)
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
                        Log.i(TAG, "Bỏ $droppedFrames khung trong ${elapsedMs}ms")
                    }

                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        Log.i(TAG, "Bộ giải mã: $decoderName")
                    }
                })
            }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            loading.visibility = if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
            if (state == Player.STATE_ENDED) finishWith(SmoothResult.ENDED)
            if (state == Player.STATE_READY) updateOsd()
        }

        override fun onRenderedFirstFrame() {
            if (!started) Log.i(TAG, "Bắt đầu phát ${player.videoFormat?.let { "${it.width}x${it.height} ${it.frameRate}fps" }}")
            started = true
            loading.visibility = View.GONE
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updateOsd()
            if (!isPlaying && player.playbackState == Player.STATE_READY) showOsd(sticky = true)
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) = fitVideo(videoSize)

        override fun onCues(cueGroup: CueGroup) {
            val text = cueGroup.cues.mapNotNull { it.text }.joinToString("\n")
            subtitle.text = text
            subtitle.visibility = if (text.isBlank()) View.GONE else View.VISIBLE
        }

        override fun onPlayerError(error: PlaybackException) {
            val failed = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.dataSpec?.uri
            Log.w(TAG, "Lỗi phát (${error.errorCodeName}): ${error.cause}${failed?.let { " · ${it.toString().take(160)}" }.orEmpty()}")
            if (started && !retried) {
                // Mạng chập chờn giữa phim: nạp lại một lần ở đúng chỗ đang xem.
                retried = true
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) player.seekToDefaultPosition()
                player.prepare()
                showToast("Mạng chập chờn · đang tải lại…")
                return
            }
            finishWith(SmoothResult.ERROR, error.errorCodeName)
        }
    }

    // ───────────────────────────── Hiển thị ─────────────────────────────

    /** Giữ đúng tỉ lệ khung hình (video 21:9, 4:3… không bị kéo giãn). */
    private fun fitVideo(size: VideoSize) {
        if (size.width == 0 || size.height == 0) return
        root.post {
            val ratio = size.width * size.pixelWidthHeightRatio / size.height
            var w = root.width
            var h = (w / ratio).toInt()
            if (h > root.height) {
                h = root.height
                w = (h * ratio).toInt()
            }
            surface.layoutParams = FrameLayout.LayoutParams(w, h, Gravity.CENTER)
        }
    }

    private val hideOsd = Runnable {
        osd.visibility = View.GONE
        subtitle.animate().translationY(0f).setDuration(150).start()
    }
    private val tick = object : Runnable {
        override fun run() {
            updateOsd()
            if (osd.visibility == View.VISIBLE) handler.postDelayed(this, TICK_MS)
        }
    }

    private fun showOsd(sticky: Boolean = false) {
        osd.visibility = View.VISIBLE
        subtitle.animate().translationY(-SUBTITLE_LIFT_DP * resources.displayMetrics.density).setDuration(150).start()
        handler.removeCallbacks(hideOsd)
        handler.removeCallbacks(tick)
        handler.post(tick)
        if (!sticky) handler.postDelayed(hideOsd, OSD_MS)
    }

    private fun updateOsd() {
        if (!::player.isInitialized) return
        val dur = durationSec()
        val pos = if (seekFromMs >= 0) seekTargetSec() else player.currentPosition / 1000.0
        val state = if (player.isPlaying || player.playWhenReady) "▶" else "❚❚"
        time.text = "$state  ${SeekPolicy.positionLabel(pos, dur)}"
        progress.progress = if (dur > 0) (pos / dur * progress.max).toInt() else 0
        progress.secondaryProgress = if (dur > 0) (player.bufferedPosition / 1000.0 / dur * progress.max).toInt() else 0
    }

    private val hideToast = Runnable { toast.visibility = View.GONE }

    private fun showToast(text: String) {
        toast.text = text
        toast.visibility = View.VISIBLE
        handler.removeCallbacks(hideToast)
        handler.postDelayed(hideToast, TOAST_MS)
    }

    private fun durationSec(): Double = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }?.div(1000.0) ?: 0.0

    // ───────────────────────────── Tua ─────────────────────────────

    private fun seekTargetSec(): Double {
        val dur = durationSec()
        val target = seekFromMs / 1000.0 + seekDeltaSec
        return target.coerceIn(0.0, if (dur > 0) dur - 1 else Double.MAX_VALUE)
    }

    private val applySeek = Runnable {
        if (seekFromMs < 0) return@Runnable
        player.seekTo((seekTargetSec() * 1000).toLong())
        seekFromMs = -1
        seekDeltaSec = 0
        handler.postDelayed({ seekLabel.visibility = View.GONE }, SEEK_LABEL_MS)
    }

    /** ◀▶: cộng dồn (giữ phím tua nhanh dần), dừng tay ~0,4 giây mới tua thật — giống tua trên trang. */
    private fun seekStep(forward: Boolean, repeatCount: Int) {
        if (seekFromMs < 0) seekFromMs = player.currentPosition
        val step = SeekPolicy.step(repeatCount)
        seekDeltaSec += if (forward) step else -step
        seekLabel.text = "${SeekPolicy.deltaLabel(seekDeltaSec)}   ${SeekPolicy.positionLabel(seekTargetSec(), durationSec())}"
        seekLabel.visibility = View.VISIBLE
        showOsd()
        handler.removeCallbacks(applySeek)
        handler.postDelayed(applySeek, SEEK_FLUSH_MS)
    }

    /** Phím số 0–9: nhảy tới 0%, 10%… 90% độ dài phim. */
    private fun seekToPercent(digit: Int) {
        val dur = durationSec()
        if (dur <= 0) return
        handler.removeCallbacks(applySeek)
        seekFromMs = -1
        seekDeltaSec = 0
        val target = dur * digit / 10.0
        player.seekTo((target * 1000).toLong())
        seekLabel.text = "${digit * 10}%   ${SeekPolicy.positionLabel(target, dur)}"
        seekLabel.visibility = View.VISIBLE
        handler.postDelayed({ seekLabel.visibility = View.GONE }, SEEK_LABEL_MS * 2)
        showOsd()
    }

    // ───────────────────────────── Bảng tùy chọn ─────────────────────────────

    private fun openMenu() {
        menuLevel = null
        menuTitle.text = "Tùy chọn video"
        menuItems.removeAllViews()
        val speed = player.playbackParameters.speed.toDouble()
        addRow("Tốc độ phát", PlayerOptions.rateLabel(speed)) { openSpeedMenu() }
        val tracks = videoTracks()
        val current = player.videoFormat?.height?.takeIf { it > 0 }?.let { "${it}p" }
        val auto = !player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
        val quality = when {
            tracks.size <= 1 -> (current ?: "—") + " · chỉ có 1 mức"
            auto -> "Tự động" + (current?.let { " ($it)" } ?: "")
            else -> current ?: "—"
        }
        addRow("Chất lượng", quality) { if (tracks.size > 1) openQualityMenu() }
        if (textTracks().isNotEmpty()) addRow("Phụ đề", currentSubtitleLabel() ?: "Tắt") { openSubtitleMenu() }
        addRow("Dùng trình phát của trang", "nếu phụ đề / âm thanh lỗi") { finishWith(SmoothResult.USE_WEB) }
        showMenu()
    }

    private fun openSpeedMenu() {
        menuLevel = ::openMenu
        menuTitle.text = "Tốc độ phát"
        menuItems.removeAllViews()
        val now = PlayerOptions.nearestRate(player.playbackParameters.speed.toDouble())
        PlayerOptions.RATES.forEach { rate ->
            addRow(PlayerOptions.rateLabel(rate), if (rate == now) "✓" else null, focus = rate == now) {
                player.playbackParameters = PlaybackParameters(rate.toFloat())
                hideMenu()
                showToast("Tốc độ: ${PlayerOptions.rateLabel(rate)}")
            }
        }
        showMenu()
    }

    private fun openQualityMenu() {
        menuLevel = ::openMenu
        menuTitle.text = "Chất lượng"
        menuItems.removeAllViews()
        val auto = !player.trackSelectionParameters.overrides.keys.any { it.type == C.TRACK_TYPE_VIDEO }
        addRow("Tự động", if (auto) "✓" else null, focus = auto) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .clearOverridesOfType(C.TRACK_TYPE_VIDEO).build()
            hideMenu()
            showToast("Chất lượng: tự động")
        }
        val playing = player.videoFormat
        videoTracks().sortedByDescending { it.second.height }.forEach { (override, format) ->
            val chosen = !auto && playing != null && playing.height == format.height
            addRow(qualityLabel(format), if (chosen) "✓" else null, focus = chosen) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setOverrideForType(override).build()
                hideMenu()
                showToast("Chất lượng: ${qualityLabel(format)}")
            }
        }
        showMenu()
    }

    private fun openSubtitleMenu() {
        menuLevel = ::openMenu
        menuTitle.text = "Phụ đề"
        menuItems.removeAllViews()
        val current = currentSubtitleLabel()
        addRow("Tắt", if (current == null) "✓" else null, focus = current == null) {
            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
            hideMenu()
            showToast("Đã tắt phụ đề")
        }
        textTracks().forEach { (override, format) ->
            val label = subtitleLabel(format)
            addRow(label, if (label == current) "✓" else null, focus = label == current) {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false).setOverrideForType(override).build()
                hideMenu()
                showToast("Phụ đề: $label")
            }
        }
        showMenu()
    }

    private fun textTracks(): List<Pair<TrackSelectionOverride, Format>> =
        player.currentTracks.groups.filter { it.type == C.TRACK_TYPE_TEXT }.flatMap { group ->
            (0 until group.length).filter { group.isTrackSupported(it) }
                .map { TrackSelectionOverride(group.mediaTrackGroup, it) to group.getTrackFormat(it) }
        }

    private fun currentSubtitleLabel(): String? =
        player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.isSelected }?.let { group ->
            (0 until group.length).firstOrNull { group.isTrackSelected(it) }?.let { subtitleLabel(group.getTrackFormat(it)) }
        }

    private fun subtitleLabel(f: Format) = f.label ?: f.language ?: "Phụ đề"

    /** Phụ đề trang đang bật → chọn sẵn (cờ DEFAULT); các phụ đề khác chọn được trong bảng ▼. */
    private fun subtitleConfig(sub: SmoothSubtitle): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(sub.url))
            .setMimeType(if (sub.isSrt) MimeTypes.APPLICATION_SUBRIP else MimeTypes.TEXT_VTT)
            .setLabel(sub.label)
            .setLanguage(sub.language)
            .setSelectionFlags(if (sub.on) C.SELECTION_FLAG_DEFAULT else 0)
            .build()

    private fun qualityLabel(f: Format) = if (f.height > 0) "${f.height}p" else "${f.bitrate / 1000} kbps"

    private fun videoTracks(): List<Pair<TrackSelectionOverride, Format>> {
        val group = player.currentTracks.groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO } ?: return emptyList()
        return (0 until group.length).filter { group.isTrackSupported(it) }
            .map { TrackSelectionOverride(group.mediaTrackGroup, it) to group.getTrackFormat(it) }
            .distinctBy { it.second.height }
    }

    private fun addRow(label: String, value: String?, focus: Boolean = false, onClick: () -> Unit) {
        val row = LayoutInflater.from(this).inflate(R.layout.row_smooth_menu, menuItems, false)
        row.findViewById<TextView>(R.id.row_label).text = label
        row.findViewById<TextView>(R.id.row_value).apply {
            text = value.orEmpty()
            visibility = if (value.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        row.setOnClickListener { onClick() }
        menuItems.addView(row)
        if (focus || menuItems.childCount == 1) row.post { row.requestFocus() }
    }

    private fun showMenu() {
        menu.visibility = View.VISIBLE
        showOsd(sticky = true)
    }

    private fun hideMenu() {
        menu.visibility = View.GONE
        menuLevel = null
        showOsd()
    }

    // ───────────────────────────── Phím ─────────────────────────────

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (menu.visibility == View.VISIBLE) {
            if (event.keyCode == KeyEvent.KEYCODE_BACK || event.keyCode == KeyEvent.KEYCODE_MENU) {
                if (event.action == KeyEvent.ACTION_UP) menuLevel?.invoke() ?: hideMenu()
                return true
            }
            return super.dispatchKeyEvent(event) // ▲▼ / OK trong bảng
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (event.keyCode != KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
            finishWith(SmoothResult.BACK)
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> if (event.repeatCount == 0) togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.play()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seekStep(true, event.repeatCount)
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> seekStep(false, event.repeatCount)
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_MENU -> if (event.repeatCount == 0) openMenu()
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> showOsd()
            KeyEvent.KEYCODE_MEDIA_NEXT -> finishWith(SmoothResult.ENDED)
            KeyEvent.KEYCODE_BACK -> Unit // thoát khi nhả phím (tránh lọt phím Back xuống trang)
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> seekToPercent(event.keyCode - KeyEvent.KEYCODE_0)
            else -> return super.dispatchKeyEvent(event)
        }
        return true
    }

    private fun togglePlay() {
        if (player.playbackState == Player.STATE_ENDED) return
        if (player.playWhenReady) player.pause() else player.play()
        showOsd(sticky = !player.playWhenReady)
    }

    // ───────────────────────────── Kết thúc ─────────────────────────────

    private fun finishWith(code: Int, error: String? = null) {
        if (finishing) return
        finishing = true
        handler.removeCallbacksAndMessages(null)
        val pos = if (seekFromMs >= 0) seekTargetSec() else player.currentPosition / 1000.0
        setResult(
            RESULT_OK,
            Intent().putExtra(EXTRA_RESULT, code).putExtra(EXTRA_POSITION_SEC, pos)
                .putExtra(EXTRA_DURATION_SEC, durationSec()).putExtra(EXTRA_ERROR, error),
        )
        player.pause()
        finish()
    }

    override fun onStop() {
        super.onStop()
        // Bấm Home / TV tắt màn hình: dừng phim, quay lại vẫn xem tiếp.
        if (!finishing && ::player.isInitialized) player.pause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Phim275Smooth"
        private const val EXTRA_HLS = "phim275.smooth.hls"
        private const val EXTRA_REFERER = "phim275.smooth.referer"
        private const val EXTRA_ORIGIN = "phim275.smooth.origin"
        private const val EXTRA_UA = "phim275.smooth.ua"
        private const val EXTRA_START_SEC = "phim275.smooth.start"
        private const val EXTRA_TITLE = "phim275.smooth.title"
        const val EXTRA_RESULT = "phim275.smooth.result"
        const val EXTRA_POSITION_SEC = "phim275.smooth.position"
        const val EXTRA_DURATION_SEC = "phim275.smooth.duration"
        const val EXTRA_ERROR = "phim275.smooth.error"

        private const val DEFAULT_UA = "Mozilla/5.0 (Android 14; Mobile; rv:159.0) Gecko/159.0 Firefox/159.0"
        private const val NETWORK_TIMEOUT_MS = 15_000
        private const val INITIAL_BITRATE = 6_000_000L
        private const val OSD_MS = 4_000L
        private const val TICK_MS = 500L
        private const val TOAST_MS = 2_500L
        private const val SEEK_FLUSH_MS = 400L
        private const val SEEK_LABEL_MS = 700L
        private const val SUBTITLE_LIFT_DP = 120f

        /** Playlist đã chép chuyển sang activity qua bộ nhớ (có thể vài trăm KB — quá lớn cho Intent). */
        private var pendingPlaylists: Map<String, String> = emptyMap()
        private var pendingSubtitles: List<SmoothSubtitle> = emptyList()

        private fun takePlaylistCopies(): MutableMap<String, ByteArray> {
            val copies = pendingPlaylists.mapValues { it.value.toByteArray() }.toMutableMap()
            pendingPlaylists = emptyMap()
            Log.i(TAG, "Playlist đã chép: ${copies.size}")
            return copies
        }

        fun intent(context: Context, stream: SmoothStream, title: String?, userAgent: String?): Intent =
            Intent(context, SmoothPlayerActivity::class.java).also {
                pendingPlaylists = stream.playlists
                pendingSubtitles = stream.subtitles
            }
                .setData(Uri.parse(stream.url))
                .putExtra(EXTRA_HLS, stream.isHls)
                .putExtra(EXTRA_REFERER, stream.referer)
                .putExtra(EXTRA_ORIGIN, stream.origin)
                .putExtra(EXTRA_UA, stream.userAgent ?: userAgent)
                .putExtra(EXTRA_START_SEC, stream.positionSec)
                .putExtra(EXTRA_TITLE, title)
    }
}

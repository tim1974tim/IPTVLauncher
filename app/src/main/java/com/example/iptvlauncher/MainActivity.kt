package com.example.iptvlauncher

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.BitmapFactory
import android.widget.ImageView
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var root: FrameLayout
    private lateinit var playerView: PlayerView
    private lateinit var browser: LinearLayout
    private lateinit var channelList: LinearLayout
    private lateinit var channelScroll: ScrollView
    private lateinit var programmeDetails: LinearLayout
    private lateinit var playbackBar: LinearLayout
    private val statusHandler = Handler(Looper.getMainLooper())
    private var okLongPressTriggered = false
    private val longOkRunnable = Runnable { if (!browserVisible && channels.isNotEmpty()) { okLongPressTriggered = true; showAppearanceSettings() } }
    private val palettes = listOf(
        intArrayOf(0xFF174A70.toInt(), 0xFF39D7FF.toInt(), 0xFF202A3A.toInt()),
        intArrayOf(0xFF245C3A.toInt(), 0xFF70E09A.toInt(), 0xFF1B3025.toInt()),
        intArrayOf(0xFF633A78.toInt(), 0xFFD49BFF.toInt(), 0xFF302039.toInt()),
        intArrayOf(0xFF76501F.toInt(), 0xFFFFC45C.toInt(), 0xFF382B1B.toInt()),
        intArrayOf(0xFF71343A.toInt(), 0xFFFF858D.toInt(), 0xFF382126.toInt()),
        intArrayOf(0xFF17656A.toInt(), 0xFF66E6E0.toInt(), 0xFF1B3335.toInt()),
        intArrayOf(0xFF3F4D86.toInt(), 0xFF9EADFF.toInt(), 0xFF252B45.toInt()),
        intArrayOf(0xFF66513B.toInt(), 0xFFE3C49A.toInt(), 0xFF332B23.toInt()),
        intArrayOf(0xFF4D5964.toInt(), 0xFFC5D5E3.toInt(), 0xFF28323B.toInt()),
        intArrayOf(0xFF7A3E66.toInt(), 0xFFFF9ACB.toInt(), 0xFF3B2533.toInt())
    )
    private val hideStatusRunnable = Runnable {
        if (!browserVisible && !isChannelBuffering && ::playbackBar.isInitialized) {
            playbackBar.visibility = View.GONE
        }
    }
    private lateinit var urlInput: EditText
    private lateinit var epgInput: EditText
    private var setupLoadButton: Button? = null
    private var setupFileButton: Button? = null
    private var setupFocusIndex = 0
    private var setupWrapper: FrameLayout? = null
    private var optionsWrapper: FrameLayout? = null
    private var pendingPlaylist: List<TvChannel> = emptyList()
    private var pendingRestoreIndex: Int = 0
    private lateinit var player: ExoPlayer
    private val channels = mutableListOf<TvChannel>()
    // Полный исходный плейлист хранится отдельно от отображаемых каналов, включая скрытые категории.
    private var allPlaylistChannels: List<TvChannel> = emptyList()
    private var epgProgrammes: List<EpgProgramme> = emptyList()
    private var epgLoading = false
    @Volatile private var epgRequestInProgress = false
    private var isChannelBuffering = false
    private var epgProgress = 0
    private var epgProgressText = ""
    private var epgById: Map<String, List<EpgProgramme>> = emptyMap()
    private var epgByName: Map<String, List<EpgProgramme>> = emptyMap()
    private var groups = listOf<String>()
    private var groupIndex = 0
    private var selectedChannel = 0
    private var browserVisible = false
    private var homeReturnPending = false
    private val prefs by lazy { getSharedPreferences("iptv", MODE_PRIVATE) }

    private val openFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            prefs.edit().putString("playlist_uri", uri.toString()).remove("playlist_url").apply()
            thread {
                val data = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                runOnUiThread { if (data != null) loadPlaylist(data) else toast("Не удалось открыть файл") }
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // HOME: запоминаем канал и останавливаем поток, чтобы он не продолжал играть на главном экране.
        if (::player.isInitialized && channels.isNotEmpty() && !isFinishing) {
            saveLastChannelIdentity()
            homeReturnPending = true
            player.stop()
        }
    }

    override fun onResume() {
        super.onResume()
        // При возвращении в приложение запускаем тот же канал, который играл до HOME.
        if (homeReturnPending && ::player.isInitialized && channels.isNotEmpty()) {
            homeReturnPending = false
            val index = resolveLastChannelIndex(channels, selectedChannel)
            playChannel(index)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Не давать Android TV включать системную заставку во время работы плеера.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility = 5894 or 1024 or 512
        // Увеличенные сетевые таймауты для IPTV-потоков, включая HLS.
        val streamDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(45_000)
            .setReadTimeoutMs(120_000)
            .setAllowCrossProtocolRedirects(true)
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(streamDataSourceFactory))
            .build()
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                isChannelBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                if (::playbackBar.isInitialized && channels.isNotEmpty() && !browserVisible) {
                    updatePlaybackBar()
                    if (isChannelBuffering) {
                        statusHandler.removeCallbacks(hideStatusRunnable)
                        playbackBar.visibility = View.VISIBLE
                    } else if (playbackState == androidx.media3.common.Player.STATE_READY) {
                        playbackBar.visibility = View.VISIBLE
                        statusHandler.removeCallbacks(hideStatusRunnable)
                        statusHandler.postDelayed(hideStatusRunnable, 3_000L)
                    }
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                isChannelBuffering = false
                runOnUiThread {
                    toast("Ошибка воспроизведения: ${error.errorCodeName}")
                    if (::playbackBar.isInitialized && !browserVisible) {
                        updatePlaybackBar()
                        statusHandler.removeCallbacks(hideStatusRunnable)
                        statusHandler.postDelayed(hideStatusRunnable, 3_000L)
                    }
                }
            }
        })
        root = FrameLayout(this).apply { setBackgroundColor(0xFF090B10.toInt()) }
        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
        playbackBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(4), dp(18), dp(6))
            setBackgroundColor(statusBackgroundColor())
            visibility = View.GONE
        }
        root.addView(playbackBar, FrameLayout.LayoutParams(-1, dp((176 * statusSizeScale()).toInt()), statusGravity()).apply {
            if (statusAtTop()) topMargin = dp(28) else bottomMargin = dp(28)
        })
        applyStatusAppearance()
        setContentView(root)

        val savedUrl = prefs.getString("playlist_url", null)
        val savedUri = prefs.getString("playlist_uri", null)
        when {
            savedUrl != null -> {
                val cache = java.io.File(filesDir, "playlist_cache.m3u")
                if (cache.exists() && cache.length() > 0L) {
                    thread(name = "playlist-cache") {
                        val parsed = runCatching { M3uParser.parse(cache.readText()) }.getOrDefault(emptyList())
                        runOnUiThread {
                            if (parsed.isNotEmpty()) {
                                allPlaylistChannels = parsed
                                val hidden = prefs.getStringSet("hidden_groups", emptySet()) ?: emptySet()
                                val visible = parsed.filter { it.group !in hidden }
                                applyPlaylist(visible.ifEmpty { parsed }, prefs.getInt("last_channel", 0))
                                val interval = prefs.getInt("playlist_interval_hours", 24).coerceIn(12, 48)
                                val last = prefs.getLong("playlist_updated_at", 0L)
                                if (System.currentTimeMillis() - last >= interval * 60L * 60L * 1000L) fetchPlaylist(savedUrl, selectedChannel, false)
                            } else fetchPlaylist(savedUrl, prefs.getInt("last_channel", -1))
                        }
                    }
                } else fetchPlaylist(savedUrl, prefs.getInt("last_channel", -1))
            }
            savedUri != null -> reopenSavedFile(savedUri)
            else -> showSetup()
        }
    }

    private fun showSetup() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(0xFF10141D.toInt())
        }
        panel.addView(label("IPTV Launcher", 30f))
        panel.addView(label("Добавьте M3U / M3U8 плейлист", 18f))
        urlInput = EditText(this).apply {
            hint = "https://example.com/playlist.m3u"
            setSingleLine(true)
            textSize = 16f
            setTextColor(-1)
            setHintTextColor(0xFF8993A4.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(64)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFF202A3A.toInt())
        }
        panel.addView(urlInput, LinearLayout.LayoutParams(dp(620), dp(64)).apply {
            bottomMargin = dp(8)
        })
        epgInput = EditText(this).apply {
            hint = "Ссылка на EPG XMLTV (.xml или .xml.gz)"
            setSingleLine(true)
            textSize = 16f
            setTextColor(-1)
            setHintTextColor(0xFF8993A4.toInt())
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(60)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setBackgroundColor(0xFF202A3A.toInt())
            setText(prefs.getString("epg_url", "") ?: "")
        }
        panel.addView(epgInput, LinearLayout.LayoutParams(dp(620), dp(64)).apply {
            bottomMargin = dp(12)
        })
        val loadButton = button("Далее — настройки плейлиста", focusable = true) {
            val url = urlInput.text.toString().trim()
            val epgUrl = epgInput.text.toString().trim()
            prefs.edit().putString("epg_url", epgUrl).apply()
            if (url.isNotEmpty()) {
                prefs.edit().putString("playlist_url", url).remove("playlist_uri").apply()
                fetchPlaylist(url, -1)
            } else {
                toast("Сначала введите адрес плейлиста")
                urlInput.requestFocus()
            }
        }
        val fileButton = button("Открыть файл M3U", focusable = true) {
            prefs.edit().putString("epg_url", epgInput.text.toString().trim()).apply()
            openFile.launch(arrayOf("*/*"))
        }
        setupLoadButton = loadButton
        setupFileButton = fileButton
        setupFocusIndex = 0
        urlInput.id = View.generateViewId()
        epgInput.id = View.generateViewId()
        loadButton.id = View.generateViewId()
        fileButton.id = View.generateViewId()
        urlInput.nextFocusDownId = epgInput.id
        epgInput.nextFocusUpId = urlInput.id
        epgInput.nextFocusDownId = loadButton.id
        loadButton.nextFocusUpId = epgInput.id
        loadButton.nextFocusDownId = fileButton.id
        fileButton.nextFocusUpId = loadButton.id
        panel.addView(loadButton)
        panel.addView(fileButton)
        val wrapper = FrameLayout(this)
        wrapper.addView(panel, FrameLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels - dp(48), dp(760)),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        setupWrapper = wrapper
        root.addView(wrapper, FrameLayout.LayoutParams(-1, -1))
        urlInput.post {
            urlInput.requestFocus()
            setupFocusIndex = 0
        }
    }

    private fun reopenSavedFile(uriText: String) {
        thread {
            try {
                val uri = android.net.Uri.parse(uriText)
                val data = contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                runOnUiThread {
                    if (!data.isNullOrBlank()) {
                        val parsed = M3uParser.parse(data)
                        allPlaylistChannels = parsed
                        val hidden = prefs.getStringSet("hidden_groups", emptySet()) ?: emptySet()
                        val visible = parsed.filter { it.group !in hidden }
                        if (visible.isNotEmpty()) applyPlaylist(visible, prefs.getInt("last_channel", 0))
                        else { toast("В плейлисте не найдены доступные каналы"); showSetup() }
                    } else {
                        toast("Не удалось восстановить плейлист. Выберите файл ещё раз.")
                        showSetup()
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    toast("Нет доступа к сохранённому файлу. Выберите плейлист ещё раз.")
                    prefs.edit().remove("playlist_uri").apply()
                    showSetup()
                }
            }
        }
    }

    private fun fetchPlaylist(url: String, restoreIndex: Int, showOptions: Boolean = true) {
        val parsedUrl = url.trim()
        if (!parsedUrl.startsWith("http://", true) && !parsedUrl.startsWith("https://", true)) {
            toast("Введите корректный адрес, начинающийся с http:// или https://")
            return
        }
        toast("Загрузка плейлиста…")
        thread(name = "playlist-download") {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(180, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val data = client.newCall(Request.Builder().url(parsedUrl)
                    .header("User-Agent", "IPTVLauncher/1.0").build()).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    val body = response.body ?: throw java.io.IOException("Пустой ответ сервера")
                    if (body.contentLength() > 30L * 1024L * 1024L) throw java.io.IOException("Плейлист больше 30 МБ")
                    body.string()
                }
                val parsed = M3uParser.parse(data)
                runCatching { java.io.File(filesDir, "playlist_cache.m3u").writeText(data) }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (parsed.isEmpty()) toast("В ответе сервера не найдены каналы M3U")
                    else {
                        allPlaylistChannels = parsed
                        prefs.edit().putString("playlist_url", parsedUrl).remove("playlist_uri").apply()
                        prefs.edit().putLong("playlist_updated_at", System.currentTimeMillis()).apply()
                        if (showOptions) showPlaylistOptions(parsed, restoreIndex) else {
                            val hidden = prefs.getStringSet("hidden_groups", emptySet()) ?: emptySet()
                            val visible = parsed.filter { it.group !in hidden }
                            applyPlaylist(visible.ifEmpty { parsed }, restoreIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) toast("Не удалось загрузить плейлист: ${e.message ?: "ошибка сети"}")
                }
            }
        }
    }

    private fun loadPlaylist(data: String) {
        thread(name = "playlist-parse") {
            val parsed = M3uParser.parse(data)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (parsed.isEmpty()) toast("В плейлисте не найдены каналы")
                else { allPlaylistChannels = parsed; showPlaylistOptions(parsed, 0) }
            }
        }
    }

    private fun showPlaylistOptions(parsed: List<TvChannel>, restoreIndex: Int) {
        pendingPlaylist = parsed
        allPlaylistChannels = parsed
        pendingRestoreIndex = restoreIndex
        setupWrapper?.let { root.removeView(it) }
        setupWrapper = null
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            setBackgroundColor(0xFF10141D.toInt())
        }
        panel.addView(label("Настройки плейлиста", 26f))
        panel.addView(label("Выберите категории, которые нужно показывать", 17f))
        val hidden = prefs.getStringSet("hidden_groups", emptySet())?.toMutableSet() ?: mutableSetOf()
        val categoryScroll = ScrollView(this).apply { isFillViewport = true }
        val categoryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parsed.map { it.group }.distinct().forEach { group ->
            val check = CheckBox(this).apply {
                text = group
                textSize = 18f
                setTextColor(0xFFFFFFFF.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF39D7FF.toInt())
                isChecked = group !in hidden
                setPadding(dp(8), dp(5), dp(8), dp(5))
            }
            check.tag = group
            check.isFocusable = true
            check.isFocusableInTouchMode = true
            check.minHeight = dp(52)
            categoryBox.addView(check)
        }
        categoryScroll.addView(categoryBox)
        panel.addView(categoryScroll, LinearLayout.LayoutParams(dp(620), 0, 1f))
        panel.addView(label("Автообновление плейлиста", 17f))
        val interval = Spinner(this).apply {
            isFocusable = true
            isFocusableInTouchMode = true
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Каждые 12 часов", "Каждые 24 часа", "Каждые 48 часов"))
            val saved = prefs.getInt("playlist_interval_hours", 24)
            setSelection(when (saved) { 12 -> 0; 48 -> 2; else -> 1 })
        }
        panel.addView(interval, LinearLayout.LayoutParams(dp(620), dp(56)))
        val start = button("Сохранить и запустить плеер", focusable = true) {
            val hiddenGroups = mutableSetOf<String>()
            for (i in 0 until categoryBox.childCount) {
                val cb = categoryBox.getChildAt(i) as CheckBox
                if (!cb.isChecked) hiddenGroups.add(cb.tag as String)
            }
            val filtered = pendingPlaylist.filter { it.group !in hiddenGroups }
            if (filtered.isEmpty()) {
                toast("Нельзя скрыть все категории — оставьте хотя бы одну")
                return@button
            }
            val hours = when (interval.selectedItemPosition) { 0 -> 12; 2 -> 48; else -> 24 }
            prefs.edit().putStringSet("hidden_groups", hiddenGroups)
                .putInt("playlist_interval_hours", hours)
                .putLong("playlist_updated_at", System.currentTimeMillis()).apply()
            optionsWrapper?.let { root.removeView(it) }
            optionsWrapper = null
            applyPlaylist(filtered, pendingRestoreIndex)
        }
        panel.addView(start)
        val wrapper = FrameLayout(this)
        wrapper.addView(panel, FrameLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels - dp(48), dp(760)),
            minOf(resources.displayMetrics.heightPixels - dp(48), dp(680)), Gravity.CENTER
        ))
        optionsWrapper = wrapper
        root.addView(wrapper, FrameLayout.LayoutParams(-1, -1))
        // На Android TV начинаем навигацию с первой категории, а не с кнопки запуска.
        categoryBox.post {
            val first = if (categoryBox.childCount > 0) categoryBox.getChildAt(0) else interval
            first.requestFocus()
        }
    }

    private fun applyPlaylist(parsed: List<TvChannel>, restoreIndex: Int, reloadEpg: Boolean = true) {
        if (parsed.isEmpty()) { toast("В плейлисте не найдены каналы"); return }
        setupWrapper?.let { root.removeView(it) }
        setupWrapper = null
        optionsWrapper?.let { root.removeView(it) }
        optionsWrapper = null
        setupLoadButton = null
        setupFileButton = null
        setupFocusIndex = 0
        channels.clear()
        channels.addAll(parsed)
        groups = channels.map { it.group }.distinct().ifEmpty { listOf("Без категории") }
        groupIndex = 0
        val index = resolveLastChannelIndex(channels, restoreIndex)
        selectedChannel = index
        playChannel(index)
        updatePlaybackBar()
        if (reloadEpg) loadEpgIfConfigured()
        toast("Загружено каналов: ${channels.size}")
    }

    private fun saveLastChannelIdentity() {
        val channel = channels.getOrNull(selectedChannel) ?: return
        prefs.edit()
            .putString("last_channel_url", channel.url)
            .putString("last_channel_tvg_id", channel.tvgId.orEmpty())
            .putString("last_channel_name", channel.name)
            .apply()
    }

    private fun resolveLastChannelIndex(list: List<TvChannel>, fallback: Int): Int {
        if (list.isEmpty()) return 0
        val savedId = prefs.getString("last_channel_tvg_id", null)?.takeIf { it.isNotBlank() }
        val savedUrl = prefs.getString("last_channel_url", null)?.takeIf { it.isNotBlank() }
        val savedName = prefs.getString("last_channel_name", null)?.takeIf { it.isNotBlank() }
        val byId = savedId?.let { id -> list.indexOfFirst { it.tvgId.equals(id, ignoreCase = true) } } ?: -1
        if (byId >= 0) return byId
        val byUrl = savedUrl?.let { url -> list.indexOfFirst { it.url == url } } ?: -1
        if (byUrl >= 0) return byUrl
        val byName = savedName?.let { name -> list.indexOfFirst { it.name.equals(name, ignoreCase = true) } } ?: -1
        if (byName >= 0) return byName
        return fallback.takeIf { it in list.indices } ?: 0
    }

    private fun playChannel(index: Int) {
        if (index !in channels.indices) return
        selectedChannel = index
        groupIndex = groups.indexOf(channels[index].group).coerceAtLeast(0)
        prefs.edit().putInt("last_channel", index).apply()
        saveLastChannelIdentity()
        isChannelBuffering = true
        statusHandler.removeCallbacks(hideStatusRunnable)
        playbackBar.visibility = View.VISIBLE
        player.setMediaItem(MediaItem.fromUri(channels[index].url))
        player.prepare()
        player.playWhenReady = true
        closeBrowser()
        updatePlaybackBar()
        showPlaybackBarForThreeSeconds()
    }

    private fun updatePlaybackBar() {
        if (channels.isEmpty()) return
        playbackBar.removeAllViews()
        val category = groups.getOrNull(groupIndex) ?: "Без категории"
        val inGroup = channels.indices.filter { channels[it].group == category }
        val position = inGroup.indexOf(selectedChannel).coerceAtLeast(0)
        val channel = channels.getOrNull(selectedChannel) ?: return

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        }
        val categoryColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        categoryColumn.addView(statusText(category, 28f * statusSizeScale()))
        categoryColumn.addView(statusText("Канал ${position + 1} из ${inGroup.size}", 24f * statusSizeScale()).apply {
            setTextColor(0xFFB8C0CC.toInt())
        })
        row.addView(categoryColumn, LinearLayout.LayoutParams(dp(250), -1))

        if (!channel.logo.isNullOrBlank()) {
            val logoView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                tag = channel.logo
            }
            row.addView(logoView, LinearLayout.LayoutParams(dp((104 * statusSizeScale()).toInt()), dp((104 * statusSizeScale()).toInt())).apply {
                rightMargin = dp(20)
            })
            val logoUrl = channel.logo!!
            thread(name = "channel-logo") {
                try {
                    val bitmap = OkHttpClient().newCall(Request.Builder().url(logoUrl).build()).execute().use { response ->
                        if (!response.isSuccessful) null else response.body?.byteStream()?.use { BitmapFactory.decodeStream(it) }
                    }
                    if (bitmap != null) runOnUiThread {
                        if (logoView.tag == logoUrl && !isFinishing && !isDestroyed) logoView.setImageBitmap(bitmap)
                    }
                } catch (_: Exception) { }
            }
        }

        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
        }
        details.addView(statusText(channel.name, 36f * statusSizeScale()).apply { maxLines = 1 })
        val programme = findCurrentAndNext(channel)?.first
        val programmeText = when {
            isChannelBuffering -> "Загрузка канала…"
            epgLoading -> "${epgProgressText.ifBlank { "Загрузка телепрограммы" }}"
            programme != null -> "${programme.title}  ${programme.timeRange()}"
            else -> "Телепрограмма недоступна"
        }
        details.addView(statusText(programmeText, 28f * statusSizeScale()).apply {
            setTextColor(0xFFD0D5DE.toInt())
            maxLines = 2
        })
        row.addView(details, LinearLayout.LayoutParams(0, dp((132 * statusSizeScale()).toInt()), 1f))
        playbackBar.addView(row, LinearLayout.LayoutParams(-1, 0, 1f))

        if (isChannelBuffering || epgLoading) {
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = epgProgress.coerceIn(0, 100)
                isIndeterminate = isChannelBuffering || epgProgress <= 0
            }
            playbackBar.addView(progress, LinearLayout.LayoutParams(-1, dp(10)).apply {
                topMargin = dp(4)
            })
        }
    }

    private fun showPlaybackBarForThreeSeconds() {
        statusHandler.removeCallbacks(hideStatusRunnable)
        if (!browserVisible && channels.isNotEmpty()) {
            playbackBar.visibility = View.VISIBLE
            if (!isChannelBuffering) statusHandler.postDelayed(hideStatusRunnable, 3_000L)
        }
    }

    /** Загружает сохранённый EPG сразу, а сеть использует только если кэш отсутствует,
     * адрес источника изменился или истёк выбранный интервал автообновления. */
    private fun loadEpgIfConfigured(force: Boolean = false) {
        val url = prefs.getString("epg_url", "")?.trim().orEmpty()
        if (url.isBlank()) {
            epgProgrammes = emptyList()
            epgById = emptyMap()
            epgByName = emptyMap()
            epgLoading = false
            updatePlaybackBar()
            return
        }
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            epgLoading = false
            toast("Ссылка EPG должна начинаться с http:// или https://")
            updatePlaybackBar()
            return
        }
        if (epgRequestInProgress) return
        epgRequestInProgress = true
        epgLoading = true
        epgProgress = 0
        epgProgressText = "Проверка сохранённой телепрограммы…"
        statusHandler.removeCallbacks(hideStatusRunnable)
        if (::playbackBar.isInitialized) playbackBar.visibility = View.VISIBLE
        updatePlaybackBar()

        thread(name = "epg-cache-check") {
            val cacheFile = java.io.File(filesDir, "epg_cache.xmltv")
            val cachedUrl = prefs.getString("epg_cached_url", null)
            val cacheExists = cacheFile.exists() && cacheFile.length() > 0L && cachedUrl == url
            val intervalHours = prefs.getInt("playlist_interval_hours", 24).coerceIn(12, 48)
            val lastUpdate = prefs.getLong("epg_updated_at", 0L)
            val intervalMs = intervalHours * 60L * 60L * 1000L
            val isFresh = cacheExists && lastUpdate > 0L &&
                System.currentTimeMillis() - lastUpdate < intervalMs

            // Сначала читаем локальную копию: телепрограмма доступна даже без интернета.
            if (cacheExists) {
                try {
                    // Быстрый индекс уже разобранного EPG. XML разбирается только при
                    // первом запуске после обновления или если бинарный кэш повреждён.
                    val parsedCache = java.io.File(filesDir, "epg_cache.parsed")
                    val cachedProgrammes = try {
                        readParsedEpgCache(parsedCache)
                    } catch (_: Exception) {
                        parseEpgCache(cacheFile).also { writeParsedEpgCache(parsedCache, it) }
                    }
                    val cachedById = cachedProgrammes.groupBy { it.channelId.lowercase(Locale.ROOT) }
                    val cachedByName = cachedProgrammes.flatMap { p ->
                        p.channelNames.map { normalizeEpgName(it) to p }
                    }.groupBy({ it.first }, { it.second })
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            epgProgrammes = cachedProgrammes
                            epgById = cachedById
                            epgByName = cachedByName
                            epgLoading = false
                            epgProgress = 100
                            epgProgressText = if (isFresh) "Сохранённая телепрограмма актуальна" else "Сохранённая телепрограмма; требуется обновление"
                            updatePlaybackBar()
                            refreshProgrammeDetails()
                        }
                    }
                } catch (_: Exception) {
                    // Повреждённый кэш не используем; ниже попробуем скачать новый.
                }
            }

            if (!force && isFresh) {
                epgRequestInProgress = false
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        epgLoading = false
                        showPlaybackBarForThreeSeconds()
                        refreshProgrammeDetails()
                    }
                }
                return@thread
            }

            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    epgLoading = true
                    epgProgress = 0
                    epgProgressText = if (cacheExists) "Обновление телепрограммы…" else "Загрузка телепрограммы…"
                    updatePlaybackBar()
                    refreshProgrammeDetails()
                }
            }

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(300, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(600, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val tempFile = java.io.File(filesDir, "epg_cache.tmp")
                client.newCall(Request.Builder().url(url)
                    .header("User-Agent", "IPTVLauncher/1.0").build()).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    val body = response.body ?: throw java.io.IOException("Пустой ответ EPG")
                    val contentLength = body.contentLength()
                    var bytes = 0L
                    body.byteStream().use { input ->
                        tempFile.outputStream().buffered().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                bytes += count
                                if (bytes > 64L * 1024L * 1024L) throw java.io.IOException("Файл EPG слишком большой (лимит 64 МБ)")
                                output.write(buffer, 0, count)
                                if (bytes % (512L * 1024L) < count) {
                                    val percent = if (contentLength > 0) ((bytes * 100L) / contentLength).toInt().coerceIn(0, 99) else 0
                                    runOnUiThread {
                                        if (!isFinishing && !isDestroyed && epgLoading) {
                                            epgProgress = percent
                                            epgProgressText = if (contentLength > 0) "Загрузка телепрограммы: $percent%" else "Загрузка телепрограммы: ${bytes / 1024} КБ"
                                            updatePlaybackBar()
                                            refreshProgrammeDetails()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        epgProgress = 0
                        epgProgressText = "Разбор телепрограммы…"
                        updatePlaybackBar()
                        refreshProgrammeDetails()
                    }
                }
                val programmes = parseEpgCache(tempFile)
                if (programmes.isEmpty()) throw java.io.IOException("В EPG не найдены передачи")
                // Сохраняем разобранные передачи отдельно: повторный запуск не требует
                // повторного разбора большого XMLTV-файла.
                writeParsedEpgCache(java.io.File(filesDir, "epg_cache.parsed"), programmes)
                if (!tempFile.renameTo(cacheFile)) {
                    tempFile.copyTo(cacheFile, overwrite = true)
                    tempFile.delete()
                }
                val byId = programmes.groupBy { it.channelId.lowercase(Locale.ROOT) }
                val byName = programmes.flatMap { p -> p.channelNames.map { normalizeEpgName(it) to p } }
                    .groupBy({ it.first }, { it.second })
                prefs.edit().putString("epg_cached_url", url)
                    .putLong("epg_updated_at", System.currentTimeMillis()).apply()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    epgProgrammes = programmes
                    epgById = byId
                    epgByName = byName
                    epgLoading = false
                    epgProgress = 100
                    epgProgressText = "Телепрограмма загружена и сохранена"
                    updatePlaybackBar()
                    refreshProgrammeDetails()
                    showPlaybackBarForThreeSeconds()
                    toast("EPG обновлён: ${programmes.size} передач")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    epgLoading = false
                    epgProgressText = if (cacheExists) "Не удалось обновить EPG; используется сохранённая копия" else "Ошибка загрузки телепрограммы"
                    updatePlaybackBar()
                    refreshProgrammeDetails()
                    showPlaybackBarForThreeSeconds()
                    toast(if (cacheExists) "Не удалось обновить EPG: ${e.message ?: "ошибка"}. Оставлена старая телепрограмма" else "Не удалось загрузить EPG: ${e.message ?: "ошибка"}")
                }
            } finally {
                epgRequestInProgress = false
            }
        }
    }

    /** Читает компактный бинарный кэш разобранного XMLTV без повторного XML-парсинга. */
    private fun readParsedEpgCache(file: java.io.File): List<EpgProgramme> {
        if (!file.exists() || file.length() < 12L) throw java.io.IOException("Нет быстрого кэша EPG")
        java.io.DataInputStream(java.io.BufferedInputStream(file.inputStream())).use { input ->
            if (input.readInt() != 0x45504743 || input.readInt() != 1) {
                throw java.io.IOException("Неизвестный формат кэша EPG")
            }
            val count = input.readInt()
            if (count !in 1..50000) throw java.io.IOException("Некорректный кэш EPG")
            val result = ArrayList<EpgProgramme>(count)
            repeat(count) {
                val channelId = readCacheString(input)
                val title = readCacheString(input)
                val description = readCacheString(input)
                val start = input.readLong()
                val end = input.readLong()
                val namesCount = input.readInt()
                if (namesCount !in 0..100) throw java.io.IOException("Некорректные названия каналов EPG")
                val names = ArrayList<String>(namesCount)
                repeat(namesCount) { names.add(readCacheString(input)) }
                if (channelId.isNotBlank() && title.isNotBlank() && start > 0L && end > start) {
                    result.add(EpgProgramme(channelId, names, title, description, start, end))
                }
            }
            if (result.isEmpty()) throw java.io.IOException("Пустой кэш EPG")
            return result
        }
    }

    /** Атомарно записывает индекс EPG, чтобы отключение питания не повредило рабочую копию. */
    private fun writeParsedEpgCache(file: java.io.File, programmes: List<EpgProgramme>) {
        val temp = java.io.File(file.parentFile, file.name + ".tmp")
        java.io.DataOutputStream(java.io.BufferedOutputStream(temp.outputStream())).use { output ->
            output.writeInt(0x45504743) // EPGC
            output.writeInt(1)
            output.writeInt(programmes.size.coerceAtMost(50000))
            programmes.take(50000).forEach { item ->
                writeCacheString(output, item.channelId)
                writeCacheString(output, item.title)
                writeCacheString(output, item.description)
                output.writeLong(item.startMillis)
                output.writeLong(item.endMillis)
                val names = item.channelNames.take(100)
                output.writeInt(names.size)
                names.forEach { writeCacheString(output, it) }
            }
        }
        if (file.exists() && !file.delete()) throw java.io.IOException("Не удалось заменить быстрый кэш EPG")
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    private fun writeCacheString(output: java.io.DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        if (bytes.size > 1_000_000) throw java.io.IOException("Слишком длинное поле EPG")
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readCacheString(input: java.io.DataInputStream): String {
        val size = input.readInt()
        if (size !in 0..1_000_000) throw java.io.IOException("Повреждённый кэш EPG")
        val bytes = ByteArray(size)
        input.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    /** XMLTV сохраняется в исходном виде; gzip распаковывается потоково при чтении кэша. */
    private fun parseEpgCache(file: java.io.File): List<EpgProgramme> {
        java.io.BufferedInputStream(file.inputStream()).use { source ->
            source.mark(2)
            val first = source.read()
            val second = source.read()
            source.reset()
            val stream: java.io.InputStream = if (first == 0x1f && second == 0x8b) {
                java.util.zip.GZIPInputStream(LimitedInputStream(source, 64L * 1024L * 1024L))
            } else source
            return LimitedInputStream(stream, 256L * 1024L * 1024L).use { EpgParser.parse(it) }
        }
    }

    private class LimitedInputStream(input: java.io.InputStream, private val maxBytes: Long) : java.io.FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            val value = super.read()
            if (value >= 0 && ++total > maxBytes) throw java.io.IOException(
                "Файл EPG слишком большой (лимит ${maxBytes / (1024 * 1024)} МБ)"
            )
            return value
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val allowed = minOf(length.toLong(), (maxBytes - total + 1L).coerceAtLeast(1L)).toInt()
            val count = super.read(buffer, offset, allowed)
            if (count > 0) {
                total += count
                if (total > maxBytes) throw java.io.IOException(
                    "Файл EPG слишком большой (лимит ${maxBytes / (1024 * 1024)} МБ)"
                )
            }
            return count
        }
    }

    private fun findCurrentAndNext(channel: TvChannel): Pair<EpgProgramme, EpgProgramme?>? {
        val now = System.currentTimeMillis()
        val candidates = if (!channel.tvgId.isNullOrBlank()) {
            epgById[channel.tvgId.lowercase(Locale.ROOT)].orEmpty()
        } else {
            epgByName[normalizeEpgName(channel.name)].orEmpty()
        }
        val current = candidates.firstOrNull { now >= it.startMillis && now < it.endMillis } ?: return null
        val next = candidates.firstOrNull { it.startMillis >= current.endMillis }
        return current to next
    }

    private fun normalizeEpgName(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("[^\\p{L}\\p{N}]"), "")

    private fun changeChannelInGroup(direction: Int) {
        if (channels.isEmpty()) return
        val category = groups.getOrNull(groupIndex) ?: return
        val indices = channels.indices.filter { channels[it].group == category }
        if (indices.isEmpty()) return
        val position = indices.indexOf(selectedChannel).let { if (it < 0) 0 else it }
        val nextPosition = (position + direction).coerceIn(0, indices.lastIndex)
        playChannel(indices[nextPosition])
    }

    private fun changeGroup(direction: Int) {
        if (groups.isEmpty()) return
        groupIndex = (groupIndex + direction).coerceIn(0, groups.lastIndex)
        selectFirstChannelInGroup()
        playChannel(selectedChannel)
    }

    private fun showBrowser() {
        if (channels.isEmpty() || browserVisible) return
        browserVisible = true
        statusHandler.removeCallbacks(hideStatusRunnable)
        playbackBar.visibility = View.GONE
        browser = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xF00B0E14.toInt())
        }

        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(channelList)
        }
        programmeDetails = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPadding(dp(28), dp(18), dp(18), dp(12))
            setBackgroundColor(0xFF111925.toInt())
        }
        // Список каналов занимает примерно половину прежней ширины,
        // освободившееся место отдаём описаниям текущей и следующей передач.
        browser.addView(channelScroll, LinearLayout.LayoutParams(0, -1, 0.24f).apply {
            rightMargin = dp(12)
        })
        val programmeScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(programmeDetails)
        }
        browser.addView(programmeScroll, LinearLayout.LayoutParams(0, -1, 0.76f))
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        refreshChannels()
        refreshProgrammeDetails()
    }

    private fun refreshChannels() {
        if (!browserVisible) return
        channelList.removeAllViews()
        channelList.addView(label("${groups.getOrNull(groupIndex) ?: "КАНАЛЫ"}  •  КАНАЛЫ", 20f))
        val indices = channels.indices.filter { channels[it].group == groups[groupIndex] }
        var selectedRow: View? = null
        indices.forEach { index ->
            val isSelected = index == selectedChannel
            val row = button((if (isSelected) "▶  " else "   ") + channels[index].name) {
                selectedChannel = index
                refreshChannels()
                refreshProgrammeDetails()
            }.apply {
                isAllCaps = false
                textSize = 17f
                minHeight = dp(62)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(18), dp(8), dp(14), dp(8))
                if (isSelected) {
                    setTextColor(0xFFFFFFFF.toInt())
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    backgroundTintList = null
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(currentPalette()[0])
                        setCornerRadius(dp(9).toFloat())
                        setStroke(dp(3), currentPalette()[1])
                    }
                    elevation = dp(4).toFloat()
                } else {
                    setTextColor(0xFFE0E7F0.toInt())
                    setTypeface(typeface, android.graphics.Typeface.NORMAL)
                    setBackgroundTintList(android.content.res.ColorStateList.valueOf(currentPalette()[2]))
                    elevation = 0f
                }
            }
            channelList.addView(row)
            if (index == selectedChannel) selectedRow = row
        }
        selectedRow?.let { row ->
            channelList.post {
                val target = (row.top - channelScroll.height / 2 + row.height / 2).coerceAtLeast(0)
                channelScroll.smoothScrollTo(0, target)
            }
        }
    }

    private fun refreshProgrammeDetails() {
        if (!browserVisible || !::programmeDetails.isInitialized) return
        programmeDetails.removeAllViews()
        val channel = channels.getOrNull(selectedChannel) ?: return
        programmeDetails.addView(label("ТЕЛЕПРОГРАММА", 16f))
        programmeDetails.addView(statusText(channel.name, 27f).apply {
            setPadding(0, dp(10), 0, dp(14))
        })
        val pair = findCurrentAndNext(channel)
        if (epgLoading) {
            programmeDetails.addView(statusText(epgProgressText.ifBlank { "Загрузка телепрограммы…" }, 20f))
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                progress = epgProgress.coerceIn(0, 100)
                isIndeterminate = epgProgress <= 0
            }
            programmeDetails.addView(progress, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(12) })
        } else if (pair != null) {
            val current = pair.first
            programmeDetails.addView(label("СЕЙЧАС  ${current.timeRange()}", 16f))
            programmeDetails.addView(statusText(current.title, 24f).apply {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, dp(6), 0, dp(10))
            })
            if (current.description.isNotBlank()) {
                programmeDetails.addView(statusText(current.description, 16f).apply {
                    setTextColor(0xFFD0D5DE.toInt())
                    setPadding(0, 0, 0, dp(12))
                })
            }
            pair.second?.let { next ->
                programmeDetails.addView(label("ДАЛЕЕ  ${next.timeRange()}", 16f))
                programmeDetails.addView(statusText(next.title, 20f).apply {
                    setPadding(0, dp(6), 0, dp(8))
                })
                if (next.description.isNotBlank()) programmeDetails.addView(statusText(next.description, 15f).apply {
                    setTextColor(0xFFB8C0CC.toInt())
                })
            }
        } else {
            programmeDetails.addView(statusText("Для этого канала сейчас нет данных телепрограммы.", 20f).apply {
                setTextColor(0xFFB8C0CC.toInt())
            })
        }
    }

    private fun closeBrowser() {
        if (browserVisible) root.removeView(browser)
        browserVisible = false
        updatePlaybackBar()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER) {
            if (!browserVisible && channels.isNotEmpty() && optionsWrapper == null) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    if (event.repeatCount == 0) { okLongPressTriggered = false; statusHandler.postDelayed(longOkRunnable, 3000L) }
                    return true
                }
                if (event.action == KeyEvent.ACTION_UP) {
                    statusHandler.removeCallbacks(longOkRunnable)
                    if (okLongPressTriggered) { okLongPressTriggered = false; return true }
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            // На экране первоначальной настройки явно переводим фокус между полем URL и кнопками.
            if (optionsWrapper != null) {
                // Отдельная навигация D-pad для экрана настроек плейлиста.
                // Фокус последовательно проходит категории, интервал обновления и кнопку запуска.
                if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_DPAD_UP) {
                    val current = currentFocus
                    val direction = if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) View.FOCUS_DOWN else View.FOCUS_UP
                    val next = current?.focusSearch(direction)
                    if (next != null && optionsWrapper?.let { next === it || isDescendantOf(next, it) } == true) {
                        next.requestFocus()
                        next.post {
                            val rect = android.graphics.Rect(0, 0, next.width, next.height)
                            next.requestRectangleOnScreen(rect, true)
                        }
                    } else if (current == null) {
                        optionsWrapper?.requestFocus()
                    }
                    return true
                }
                // Влево/вправо и OK передаём самим элементам (галочкам и Spinner).
                if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                    return super.dispatchKeyEvent(event)
                }
            }
            if (setupWrapper != null) {
                // На экране настройки вручную обрабатываем только стрелки.
                // OK/ENTER обязательно передаём активному EditText/Button,
                // иначе кнопка загрузки получает фокус, но не нажимается.
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        setupFocusIndex = (setupFocusIndex + 1).coerceAtMost(3)
                        when (setupFocusIndex) {
                            0 -> urlInput.requestFocus()
                            1 -> epgInput.requestFocus()
                            2 -> setupLoadButton?.requestFocus()
                            3 -> setupFileButton?.requestFocus()
                        }
                        return true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> {
                        setupFocusIndex = (setupFocusIndex - 1).coerceAtLeast(0)
                        when (setupFocusIndex) {
                            0 -> urlInput.requestFocus()
                            1 -> epgInput.requestFocus()
                            2 -> setupLoadButton?.requestFocus()
                            3 -> setupFileButton?.requestFocus()
                        }
                        return true
                    }
                    else -> return super.dispatchKeyEvent(event)
                }
            }
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE ||
                keyCode == KeyEvent.KEYCODE_BUTTON_B) {
                if (channels.isNotEmpty()) {
                    if (browserVisible) {
                        closeBrowser()
                        return true
                    }
                    if (prefs.getBoolean("back_opens_browser", true)) {
                        showBrowser()
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
            if (browserVisible) {
                val visible = channels.indices.filter { channels[it].group == groups[groupIndex] }
                if (visible.isNotEmpty()) {
                    val position = visible.indexOf(selectedChannel).coerceAtLeast(0)
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            selectedChannel = visible[(position - 1).coerceAtLeast(0)]
                            refreshChannels(); refreshProgrammeDetails(); return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            selectedChannel = visible[(position + 1).coerceAtMost(visible.lastIndex)]
                            refreshChannels(); refreshProgrammeDetails(); return true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            groupIndex = (groupIndex - 1).coerceAtLeast(0)
                            selectFirstChannelInGroup(); refreshChannels(); refreshProgrammeDetails(); return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            groupIndex = (groupIndex + 1).coerceAtMost(groups.lastIndex)
                            selectFirstChannelInGroup(); refreshChannels(); refreshProgrammeDetails(); return true
                        }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                            playChannel(selectedChannel); return true
                        }
                    }
                }
            } else {
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { changeChannelInGroup(-1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { changeChannelInGroup(1); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { changeGroup(-1); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { changeGroup(1); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun statusAtTop() = prefs.getBoolean("status_top", false)
    private fun statusSizeScale() = when (prefs.getString("status_size", "large")) { "medium" -> 0.8f; "small" -> 0.5f; else -> 1f }
    private fun statusAlpha() = prefs.getInt("status_alpha", 85).coerceIn(20, 100)
    private fun statusBackgroundColor(): Int { val a = (statusAlpha() * 255 / 100) shl 24; return a or 0x00000000 }
    private fun statusGravity() = if (statusAtTop()) Gravity.TOP else Gravity.BOTTOM
    private fun currentPalette() = palettes[prefs.getInt("channel_palette", 0).coerceIn(0, palettes.lastIndex)]

    private fun applyStatusAppearance() {
        if (!::playbackBar.isInitialized) return
        playbackBar.setBackgroundColor(statusBackgroundColor())
        val lp = playbackBar.layoutParams as? FrameLayout.LayoutParams ?: return
        lp.height = dp((176 * statusSizeScale()).toInt())
        lp.gravity = statusGravity()
        lp.topMargin = if (statusAtTop()) dp(28) else 0
        lp.bottomMargin = if (statusAtTop()) 0 else dp(28)
        playbackBar.layoutParams = lp
        if (channels.isNotEmpty()) updatePlaybackBar()
    }

    private fun showAppearanceSettings() {
        val accent = 0xFF58AFFF.toInt()
        val bg = 0xFF080B12.toInt()
        val card = 0xFF151B26.toInt()
        val muted = 0xFFAAB5C5.toInt()
        lateinit var dialog: android.app.AlertDialog

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            setBackgroundColor(bg)
        }

        // Левая часть — живая визуальная демонстрация выбранных настроек.
        val previewColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xFF101722.toInt())
                cornerRadius = dp(18).toFloat()
                setStroke(dp(1), 0xFF263448.toInt())
            }
        }
        previewColumn.addView(TextView(this).apply {
            text = "ПРЕДПРОСМОТР"
            textSize = 12f
            letterSpacing = 0.14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(muted)
        })
        previewColumn.addView(TextView(this).apply {
            text = "Так будет выглядеть эфир"
            textSize = 17f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(-1)
            setPadding(0, dp(5), 0, dp(12))
        })
        val previewStage = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                colors = intArrayOf(0xFF26384B.toInt(), 0xFF111923.toInt())
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                cornerRadius = dp(14).toFloat()
            }
        }
        val fakeScene = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = "▶"
                textSize = 46f
                setTextColor(0xFFE8F2FF.toInt())
            })
            addView(TextView(this@MainActivity).apply {
                text = "ПРЯМОЙ ЭФИР"
                textSize = 11f
                letterSpacing = 0.18f
                setTextColor(0xFFB8C8DA.toInt())
            })
        }
        previewStage.addView(fakeScene, FrameLayout.LayoutParams(-1, -1))
        val previewStatus = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(10), dp(7))
        }
        val previewText = TextView(this).apply {
            text = "●  ФИЛЬМЫ     1/24   Первый канал"
            textSize = 11f
            setTextColor(-1)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        previewStatus.addView(previewText, LinearLayout.LayoutParams(0, -2, 1f))
        previewStatus.addView(TextView(this).apply {
            text = "16:00"
            textSize = 10f
            setTextColor(-1)
        })
        val previewParams = FrameLayout.LayoutParams(-1, dp(54), Gravity.BOTTOM)
        previewStage.addView(previewStatus, previewParams)
        previewStage.minimumHeight = dp(190)
        previewColumn.addView(previewStage, LinearLayout.LayoutParams(-1, 0, 1f))
        val previewCaption = TextView(this).apply {
            text = "Изменения отображаются сразу"
            textSize = 12f
            setTextColor(muted)
            setPadding(0, dp(10), 0, 0)
        }
        previewColumn.addView(previewCaption)

        fun refreshPreview() {
            val colors = currentPalette()
            previewStatus.setBackgroundColor(statusBackgroundColor())
            previewStatus.alpha = statusAlpha() / 100f
            previewText.text = "●  ФИЛЬМЫ     1/24   Первый канал"
            previewText.setTextColor(0xFFFFFFFF.toInt())
            val h = (54 * statusSizeScale()).toInt().coerceAtLeast(26)
            val lp = previewStatus.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(-1, dp(h), if (statusAtTop()) Gravity.TOP else Gravity.BOTTOM)
            lp.width = -1
            lp.height = dp(h)
            lp.gravity = if (statusAtTop()) Gravity.TOP else Gravity.BOTTOM
            previewStatus.layoutParams = lp
            previewStatus.setPadding(dp(10), dp(if (h < 40) 3 else 7), dp(10), dp(if (h < 40) 3 else 7))
            previewStage.setBackgroundColor(0xFF172332.toInt())
            previewCaption.text = "Палитра ${prefs.getInt("channel_palette", 0) + 1}  ·  ${statusAlpha()}%  ·  " +
                (if (statusAtTop()) "сверху" else "снизу") + "  ·  " +
                when (prefs.getString("status_size", "large")) {
                    "medium" -> "80%"
                    "small" -> "50%"
                    else -> "100%"
                }
            // Акцент палитры виден на тонкой линии под демонстрацией.
            previewStatus.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(statusBackgroundColor())
                setStroke(dp(2), colors[1])
                cornerRadius = dp(7).toFloat()
            }
        }

        val rightColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(8), dp(8))
        }
        rightColumn.addView(TextView(this).apply {
            text = "Настройки просмотра"
            textSize = 25f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(-1)
        })
        rightColumn.addView(TextView(this).apply {
            text = "Настройте интерфейс под себя"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(3), 0, dp(12))
        })
        val settingsScroll = ScrollView(this).apply { isFillViewport = true }
        val settings = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        settingsScroll.addView(settings)
        rightColumn.addView(settingsScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        fun section(title: String) {
            settings.addView(TextView(this).apply {
                text = title
                textSize = 13f
                letterSpacing = 0.08f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(accent)
                setPadding(0, dp(14), 0, dp(7))
            })
        }
        fun settingButton(title: String, selected: Boolean = false, onClick: () -> Unit) {
            settings.addView(Button(this).apply {
                text = title
                isAllCaps = false
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), 0, dp(12), 0)
                minHeight = dp(44)
                isFocusable = true
                isFocusableInTouchMode = true
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFE5EAF2.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (selected) 0xFF203D5D.toInt() else card)
                    cornerRadius = dp(11).toFloat()
                    setStroke(dp(if (selected) 2 else 1), if (selected) accent else 0xFF293648.toInt())
                }
                setOnClickListener { onClick(); refreshPreview() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }

        section("ПОЛОЖЕНИЕ СТАТУС-БАРА")
        settingButton("↑  В верхней части", statusAtTop()) {
            prefs.edit().putBoolean("status_top", true).apply(); applyStatusAppearance()
        }
        settingButton("↓  В нижней части", !statusAtTop()) {
            prefs.edit().putBoolean("status_top", false).apply(); applyStatusAppearance()
        }

        section("РАЗМЕР СТАТУС-БАРА")
        settingButton("Большой  ·  100%", statusSizeScale() == 1f) {
            prefs.edit().putString("status_size", "large").apply(); applyStatusAppearance()
        }
        settingButton("Средний  ·  80%", statusSizeScale() == 0.8f) {
            prefs.edit().putString("status_size", "medium").apply(); applyStatusAppearance()
        }
        settingButton("Компактный  ·  50%", statusSizeScale() == 0.5f) {
            prefs.edit().putString("status_size", "small").apply(); applyStatusAppearance()
        }

        section("ПРОЗРАЧНОСТЬ  ·  ${statusAlpha()}%")
        val alphaValue = TextView(this).apply {
            text = "${statusAlpha()}%"
            textSize = 13f
            setTextColor(0xFFE5EAF2.toInt())
        }
        settings.addView(alphaValue)
        settings.addView(SeekBar(this).apply {
            max = 80
            progress = statusAlpha() - 20
            isFocusable = true
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val value = progress + 20
                        prefs.edit().putInt("status_alpha", value).apply()
                        alphaValue.text = "$value%"
                        applyStatusAppearance()
                        refreshPreview()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        section("ЦВЕТОВАЯ ПАЛИТРА")
        palettes.indices.forEach { i ->
            val colors = palettes[i]
            val paletteRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(5), dp(10), dp(5))
                isFocusable = true
                isFocusableInTouchMode = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (prefs.getInt("channel_palette", 0) == i) 0xFF203D5D.toInt() else card)
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(if (prefs.getInt("channel_palette", 0) == i) 2 else 1),
                        if (prefs.getInt("channel_palette", 0) == i) accent else 0xFF293648.toInt())
                }
                addView(TextView(this@MainActivity).apply {
                    text = "Вариант ${i + 1}"
                    textSize = 14f
                    setTextColor(-1)
                }, LinearLayout.LayoutParams(0, dp(40), 1f))
                addView(View(this@MainActivity).apply { setBackgroundColor(colors[0]) },
                    LinearLayout.LayoutParams(dp(32), dp(25)).apply { rightMargin = dp(5) })
                addView(View(this@MainActivity).apply { setBackgroundColor(colors[1]) },
                    LinearLayout.LayoutParams(dp(32), dp(25)))
                setOnClickListener {
                    prefs.edit().putInt("channel_palette", i).apply()
                    if (browserVisible) refreshChannels()
                    refreshPreview()
                    // Перестраиваем строки, чтобы подсветить выбранную палитру.
                    dialog.dismiss()
                    showAppearanceSettings()
                }
            }
            settings.addView(paletteRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }

        section("ПОВЕДЕНИЕ КНОПКИ НАЗАД")
        val backOption = CheckBox(this).apply {
            text = "Открывать список каналов кнопкой BACK"
            textSize = 14f
            setTextColor(0xFFE5EAF2.toInt())
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = prefs.getBoolean("back_opens_browser", true)
            minHeight = dp(48)
            isFocusable = true
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("back_opens_browser", checked).apply()
            }
        }
        settings.addView(backOption)

        section("ДОПОЛНИТЕЛЬНО")
        settingButton("Адреса плейлиста и EPG") {
            val url = EditText(this).apply { hint = "Адрес M3U"; setSingleLine(true); setText(prefs.getString("playlist_url", "") ?: "") }
            val epg = EditText(this).apply { hint = "Адрес EPG"; setSingleLine(true); setText(prefs.getString("epg_url", "") ?: "") }
            val fields = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(8), dp(16), dp(8))
                addView(url); addView(epg)
            }
            android.app.AlertDialog.Builder(this).setTitle("Адреса источников").setView(fields)
                .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                    val newUrl = url.text.toString().trim()
                    prefs.edit().putString("playlist_url", newUrl).putString("epg_url", epg.text.toString().trim()).apply()
                    if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) fetchPlaylist(newUrl, selectedChannel, true)
                    else toast("Адреса сохранены.")
                }.show()
        }
        settingButton("Обновить EPG вручную") {
            loadEpgIfConfigured(force = true)
            toast("Запущено ручное обновление EPG")
        }
        settingButton("Выбор категорий плейлиста") {
            val source = allPlaylistChannels.ifEmpty { channels.toList() }
            if (source.isNotEmpty()) showCategorySettings(source)
        }
        settingButton("Открыть настройки приставки") {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                .onFailure { toast("Не удалось открыть настройки приставки") }
        }

        shell.addView(previewColumn, LinearLayout.LayoutParams(0, -1, 0.43f).apply { rightMargin = dp(14) })
        shell.addView(rightColumn, LinearLayout.LayoutParams(0, -1, 0.57f))
        dialog = android.app.AlertDialog.Builder(this).setView(shell).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnKeyListener { _, keyCode, event ->
            keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP
        }
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.90f).toInt())
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(22).toFloat()
            setStroke(dp(1), 0xFF2B3A4D.toInt())
        })
        refreshPreview()
        settingsScroll.post { settingsScroll.scrollTo(0, 0) }
    }

    private fun showCategorySettings(source: List<TvChannel>) {
        val allGroups = source.map { it.group }.distinct()
        val hidden = (prefs.getStringSet("hidden_groups", emptySet()) ?: emptySet()).toMutableSet()
        // Include saved hidden categories even if the current visible channel list does not contain them.
        val groupsToShow = (allGroups + hidden).distinct()
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(12), dp(18), dp(12)) }
        panel.addView(label("Все категории плейлиста", 20f))
        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        groupsToShow.forEach { group ->
            val cb = CheckBox(this).apply {
                text = group; textSize = 18f; setTextColor(0xFFFFFFFF.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF39D7FF.toInt())
                isChecked = group !in hidden; isFocusable = true; isFocusableInTouchMode = true
                minHeight = dp(48); tag = group
            }
            list.addView(cb)
        }
        scroll.addView(list)
        panel.addView(scroll, LinearLayout.LayoutParams(dp(520), dp(420)))
        val dialog = android.app.AlertDialog.Builder(this).setTitle("Выбор категорий")
            .setView(panel)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val newHidden = groupsToShow.filter { group ->
                    val cb = (0 until list.childCount).map { list.getChildAt(it) as CheckBox }.first { it.tag == group }
                    !cb.isChecked
                }.toMutableSet()
                val filtered = source.filter { it.group !in newHidden }
                if (filtered.isEmpty()) { toast("Оставьте включённой хотя бы одну категорию"); return@setOnClickListener }
                prefs.edit().putStringSet("hidden_groups", newHidden).apply()
                dialog.dismiss()
                // Меняется только фильтр отображения. Ни плейлист, ни EPG не загружаются повторно.
                applyPlaylist(filtered, selectedChannel, reloadEpg = false)
            }
        }
        dialog.show()
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (channels.isNotEmpty() && browserVisible) {
            closeBrowser()
        } else if (channels.isNotEmpty() && prefs.getBoolean("back_opens_browser", true)) {
            showBrowser()
        } else {
            super.onBackPressed()
        }
    }

    private fun selectFirstChannelInGroup() {
        selectedChannel = channels.indexOfFirst { it.group == groups[groupIndex] }.coerceAtLeast(0)
    }

    private fun statusText(value: String, size: Float) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(0xFFFFFFFF.toInt())
        setPadding(0, dp(2), dp(4), dp(2))
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(-1); setPadding(12, 12, 12, 12)
    }

    private fun isDescendantOf(view: View, parent: android.view.ViewGroup): Boolean {
        var node: android.view.ViewParent? = view.parent
        while (node != null) {
            if (node === parent) return true
            node = node.parent
        }
        return false
    }

    private fun button(value: String, focusable: Boolean = false, action: () -> Unit) = Button(this).apply {
        text = value; isFocusable = focusable; isFocusableInTouchMode = focusable
        minHeight = dp(54); setTextColor(-1)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF202A3A.toInt()))
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        statusHandler.removeCallbacks(hideStatusRunnable)
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

}

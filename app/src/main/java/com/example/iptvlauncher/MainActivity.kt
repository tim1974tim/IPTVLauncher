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
    private lateinit var categoryScroll: ScrollView
    private lateinit var playbackBar: LinearLayout
    private val statusHandler = Handler(Looper.getMainLooper())
    private val hideStatusRunnable = Runnable { if (!browserVisible && ::playbackBar.isInitialized) playbackBar.visibility = View.GONE }
    private lateinit var urlInput: EditText
    private lateinit var epgInput: EditText
    private var setupLoadButton: Button? = null
    private var setupFileButton: Button? = null
    private var setupFocusIndex = 0
    private var setupWrapper: FrameLayout? = null
    private lateinit var player: ExoPlayer
    private val channels = mutableListOf<TvChannel>()
    private var epgProgrammes: List<EpgProgramme> = emptyList()
    private var epgLoading = false
    private var groups = listOf<String>()
    private var groupIndex = 0
    private var selectedChannel = 0
    private var browserVisible = false
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.systemUiVisibility = 5894 or 1024 or 512
        player = ExoPlayer.Builder(this).build()
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                runOnUiThread {
                    toast("Ошибка воспроизведения: ${error.errorCodeName}")
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
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setBackgroundColor(0xD9000000.toInt())
            visibility = View.GONE
        }
        root.addView(playbackBar, FrameLayout.LayoutParams(-1, dp(88), Gravity.BOTTOM))
        setContentView(root)

        val savedUrl = prefs.getString("playlist_url", null)
        val savedUri = prefs.getString("playlist_uri", null)
        when {
            savedUrl != null -> fetchPlaylist(savedUrl, prefs.getInt("last_channel", -1))
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
        val loadButton = button("Загрузить по URL", focusable = true) {
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
                        loadPlaylist(data)
                        val index = prefs.getInt("last_channel", 0).coerceIn(0, channels.lastIndex)
                        playChannel(index)
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

    private fun fetchPlaylist(url: String, restoreIndex: Int) {
        val parsedUrl = url.trim()
        if (!parsedUrl.startsWith("http://", true) && !parsedUrl.startsWith("https://", true)) {
            toast("Введите корректный адрес, начинающийся с http:// или https://")
            return
        }
        toast("Загрузка плейлиста…")
        thread(name = "playlist-download") {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(45, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val data = client.newCall(Request.Builder().url(parsedUrl)
                    .header("User-Agent", "IPTVLauncher/1.0").build()).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    val body = response.body ?: throw java.io.IOException("Пустой ответ сервера")
                    if (body.contentLength() > 30L * 1024L * 1024L) throw java.io.IOException("Плейлист больше 30 МБ")
                    body.string()
                }
                val parsed = M3uParser.parse(data)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (parsed.isEmpty()) toast("В ответе сервера не найдены каналы M3U")
                    else {
                        prefs.edit().putString("playlist_url", parsedUrl).remove("playlist_uri").apply()
                        applyPlaylist(parsed, restoreIndex)
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
                else applyPlaylist(parsed, 0)
            }
        }
    }

    private fun applyPlaylist(parsed: List<TvChannel>, restoreIndex: Int) {
        if (parsed.isEmpty()) { toast("В плейлисте не найдены каналы"); return }
        setupWrapper?.let { root.removeView(it) }
        setupWrapper = null
        setupLoadButton = null
        setupFileButton = null
        setupFocusIndex = 0
        channels.clear()
        channels.addAll(parsed)
        groups = channels.map { it.group }.distinct().ifEmpty { listOf("Без категории") }
        groupIndex = 0
        val index = restoreIndex.takeIf { it in channels.indices } ?: 0
        selectedChannel = index
        playChannel(index)
        updatePlaybackBar()
        loadEpgIfConfigured()
        toast("Загружено каналов: ${channels.size}")
    }

    private fun playChannel(index: Int) {
        if (index !in channels.indices) return
        selectedChannel = index
        groupIndex = groups.indexOf(channels[index].group).coerceAtLeast(0)
        prefs.edit().putInt("last_channel", index).apply()
        player.setMediaItem(MediaItem.fromUri(channels[index].url))
        player.prepare()
        player.playWhenReady = true
        closeBrowser()
        updatePlaybackBar()
        showPlaybackBarForFiveSeconds()
    }

    private fun updatePlaybackBar() {
        if (channels.isEmpty()) return
        playbackBar.removeAllViews()
        val category = groups.getOrNull(groupIndex) ?: "Без категории"
        val inGroup = channels.indices.filter { channels[it].group == category }
        val position = inGroup.indexOf(selectedChannel).coerceAtLeast(0)
        val channel = channels.getOrNull(selectedChannel) ?: return
        val programme = findCurrentAndNext(channel)?.first

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val categoryColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        categoryColumn.addView(label(category, 14f))
        categoryColumn.addView(label("Канал ${position + 1} из ${inGroup.size}", 12f).apply {
            setTextColor(0xFFB8C0CC.toInt())
        })
        row.addView(categoryColumn, LinearLayout.LayoutParams(dp(190), -1))

        if (!channel.logo.isNullOrBlank()) {
            val logoView = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                tag = channel.logo
            }
            row.addView(logoView, LinearLayout.LayoutParams(dp(52), dp(52)).apply {
                rightMargin = dp(12)
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
            gravity = Gravity.CENTER_VERTICAL
        }
        details.addView(label(channel.name, 18f))
        val programmeText = when {
            epgLoading -> "Загрузка телепрограммы…"
            programme != null -> "${programme.title}  ${programme.timeRange()}"
            else -> "Телепрограмма недоступна"
        }
        details.addView(label(programmeText, 14f).apply {
            setTextColor(0xFFD0D5DE.toInt())
        })
        row.addView(details, LinearLayout.LayoutParams(0, -1, 1f))
        playbackBar.addView(row, LinearLayout.LayoutParams(-1, -1))
    }

    private fun showPlaybackBarForFiveSeconds() {
        statusHandler.removeCallbacks(hideStatusRunnable)
        if (!browserVisible && channels.isNotEmpty()) {
            playbackBar.visibility = View.VISIBLE
            statusHandler.postDelayed(hideStatusRunnable, 5_000L)
        }
    }

    private fun loadEpgIfConfigured() {
        val url = prefs.getString("epg_url", "")?.trim().orEmpty()
        if (url.isBlank()) {
            epgProgrammes = emptyList()
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
        epgLoading = true
        updatePlaybackBar()
        thread(name = "epg-download") {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
                    .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                    .build()
                val programmes = client.newCall(Request.Builder().url(url)
                    .header("User-Agent", "IPTVLauncher/1.0").build()).execute().use { response ->
                    if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
                    val body = response.body ?: throw java.io.IOException("Пустой ответ EPG")
                    // Ограничиваем чтение до выделения памяти: некоторые XMLTV-файлы
                    // распаковываются в сотни мегабайт и могут завершить приложение на ТВ-боксе.
                    val rawBytes = body.byteStream().use { readLimited(it, 32L * 1024L * 1024L) }
                    val isGzip = url.substringBefore('?').endsWith(".gz", true) ||
                        response.header("Content-Encoding")?.contains("gzip", true) == true ||
                        (rawBytes.size >= 2 && rawBytes[0] == 0x1f.toByte() && rawBytes[1] == 0x8b.toByte())
                    val xmlBytes = if (isGzip) {
                        java.util.zip.GZIPInputStream(rawBytes.inputStream()).use {
                            readLimited(it, 48L * 1024L * 1024L)
                        }
                    } else rawBytes
                    EpgParser.parse(xmlBytes)
                }
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    epgProgrammes = programmes
                    epgLoading = false
                    updatePlaybackBar()
                    toast("EPG загружен: ${programmes.size} передач")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    epgLoading = false
                    updatePlaybackBar()
                    toast("Не удалось загрузить EPG: ${e.message ?: "ошибка"}")
                }
            }
        }
    }

    private fun readLimited(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maxBytes) throw java.io.IOException("Файл EPG слишком большой (лимит ${maxBytes / (1024 * 1024)} МБ)")
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun findCurrentAndNext(channel: TvChannel): Pair<EpgProgramme, EpgProgramme?>? {
        val now = System.currentTimeMillis()
        val candidates = epgProgrammes.asSequence().filter { programme ->
            val idMatch = !channel.tvgId.isNullOrBlank() && programme.channelId.equals(channel.tvgId, true)
            val nameMatch = programme.channelNames.any { normalizeEpgName(it) == normalizeEpgName(channel.name) }
            idMatch || nameMatch
        }.sortedBy { it.startMillis }.toList()
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
        playbackBar.visibility = View.GONE
        browser = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xF00B0E14.toInt())
        }

        val categories = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        categories.addView(label("КАТЕГОРИИ", 16f))
        groups.forEachIndexed { index, group ->
            categories.addView(button((if (index == groupIndex) "●  " else "   ") + group) {
                groupIndex = index
                selectFirstChannelInGroup()
                refreshChannels()
                refreshCategories()
            })
        }
        categoryScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(categories)
        }
        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        channelScroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(channelList)
        }
        browser.addView(categoryScroll, LinearLayout.LayoutParams(dp(260), 0, 1f))
        browser.addView(channelScroll, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        refreshChannels()
        refreshCategories()
    }

    private fun refreshCategories() {
        if (!browserVisible || !::categoryScroll.isInitialized) return
        val categories = categoryScroll.getChildAt(0) as? LinearLayout ?: return
        categories.removeAllViews()
        categories.addView(label("КАТЕГОРИИ", 16f))
        groups.forEachIndexed { index, group ->
            categories.addView(button((if (index == groupIndex) "●  " else "   ") + group) {
                groupIndex = index
                selectFirstChannelInGroup()
                refreshChannels()
                refreshCategories()
            })
        }
        categories.post {
            val selected = categories.getChildAt(groupIndex + 1) ?: return@post
            val target = (selected.top - categoryScroll.height / 2 + selected.height / 2).coerceAtLeast(0)
            categoryScroll.smoothScrollTo(0, target)
        }
    }

    private fun refreshChannels() {
        if (!browserVisible) return
        channelList.removeAllViews()
        channelList.addView(label("КАНАЛЫ  •  ${groups[groupIndex]}", 20f))
        val indices = channels.indices.filter { channels[it].group == groups[groupIndex] }
        var selectedRow: View? = null
        indices.forEach { index ->
            val isSelected = index == selectedChannel
            val row = button((if (isSelected) "▶  " else "   ") + channels[index].name) {
                playChannel(index)
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
                        setColor(0xFF174A70.toInt())
                        setCornerRadius(dp(9).toFloat())
                        setStroke(dp(3), 0xFF39D7FF.toInt())
                    }
                    elevation = dp(4).toFloat()
                } else {
                    setTextColor(0xFFE0E7F0.toInt())
                    setTypeface(typeface, android.graphics.Typeface.NORMAL)
                    setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF202A3A.toInt()))
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

    private fun closeBrowser() {
        if (browserVisible) root.removeView(browser)
        browserVisible = false
        updatePlaybackBar()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            val keyCode = event.keyCode
            // На экране первоначальной настройки явно переводим фокус между полем URL и кнопками.
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
                    if (browserVisible) closeBrowser() else showBrowser()
                    return true
                }
                return true
            }
            if (browserVisible) {
                val visible = channels.indices.filter { channels[it].group == groups[groupIndex] }
                if (visible.isNotEmpty()) {
                    val position = visible.indexOf(selectedChannel).coerceAtLeast(0)
                    when (keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            selectedChannel = visible[(position - 1).coerceAtLeast(0)]
                            refreshChannels(); return true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            selectedChannel = visible[(position + 1).coerceAtMost(visible.lastIndex)]
                            refreshChannels(); return true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            groupIndex = (groupIndex - 1).coerceAtLeast(0)
                            selectFirstChannelInGroup(); refreshChannels(); return true
                        }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            groupIndex = (groupIndex + 1).coerceAtMost(groups.lastIndex)
                            selectFirstChannelInGroup(); refreshChannels(); return true
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

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (channels.isNotEmpty()) {
            if (browserVisible) closeBrowser() else showBrowser()
        } else {
            super.onBackPressed()
        }
    }

    private fun selectFirstChannelInGroup() {
        selectedChannel = channels.indexOfFirst { it.group == groups[groupIndex] }.coerceAtLeast(0)
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(-1); setPadding(12, 12, 12, 12)
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

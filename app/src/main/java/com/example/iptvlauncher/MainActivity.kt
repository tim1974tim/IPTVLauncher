package com.example.iptvlauncher

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.*
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
    private val channelButtons = mutableListOf<Button>()
    private lateinit var urlInput: EditText
    private var setupWrapper: FrameLayout? = null
    private lateinit var player: ExoPlayer
    private val channels = mutableListOf<TvChannel>()
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
        root = FrameLayout(this).apply { setBackgroundColor(0xFF090B10.toInt()) }
        playerView = PlayerView(this).apply {
            player = this@MainActivity.player
            useController = true
            setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
        }
        root.addView(playerView, FrameLayout.LayoutParams(-1, -1))
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
        panel.addView(urlInput, LinearLayout.LayoutParams(dp(620), dp(68)).apply {
            bottomMargin = dp(12)
        })
        panel.addView(button("Загрузить по URL") {
            val url = urlInput.text.toString().trim()
            if (url.isNotEmpty()) {
                prefs.edit().putString("playlist_url", url).remove("playlist_uri").apply()
                fetchPlaylist(url, -1)
            }
        })
        panel.addView(button("Открыть файл M3U") { openFile.launch(arrayOf("*/*")) })
        val wrapper = FrameLayout(this)
        wrapper.addView(panel, FrameLayout.LayoutParams(
            minOf(resources.displayMetrics.widthPixels - dp(48), dp(760)),
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        ))
        setupWrapper = wrapper
        root.addView(wrapper, FrameLayout.LayoutParams(-1, -1))
        urlInput.requestFocus()
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
                    val maxBytes = 10L * 1024L * 1024L
                    if (body.contentLength() > maxBytes) throw java.io.IOException("Плейлист больше 10 МБ. Используйте более компактный список")
                    body.byteStream().use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(16 * 1024)
                        var total = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > maxBytes) throw java.io.IOException("Плейлист больше 10 МБ. Используйте более компактный список")
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                }
                val parsed = M3uParser.parse(data, maxChannels = 12000)
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
        channels.clear()
        channels.addAll(parsed)
        groups = channels.map { it.group }.distinct().ifEmpty { listOf("Без категории") }
        groupIndex = 0
        val index = restoreIndex.takeIf { it in channels.indices } ?: 0
        selectedChannel = index
        // Не запускаем видеопоток автоматически: сначала проверяем, что список
        // успешно загружен и отображается. Канал запускается выбором пользователя.
        showBrowser()
        toast("Загружено каналов: ${channels.size}. Выберите канал для просмотра")
    }

    private fun playChannel(index: Int) {
        if (index !in channels.indices) return
        selectedChannel = index
        prefs.edit().putInt("last_channel", index).apply()
        player.setMediaItem(MediaItem.fromUri(channels[index].url))
        player.prepare()
        player.playWhenReady = true
        closeBrowser()
    }

    private fun showBrowser() {
        if (channels.isEmpty()) return
        browserVisible = true
        browser = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(30, 24, 30, 24)
            setBackgroundColor(0xF00B0E14.toInt())
        }
        val categories = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        categories.addView(label("КАТЕГОРИИ", 16f))
        groups.forEachIndexed { index, group ->
            categories.addView(button((if (index == groupIndex) "●  " else "   ") + group) {
                groupIndex = index
                selectedChannel = channels.indexOfFirst { it.group == group }.coerceAtLeast(0)
                refreshChannels()
                channelList.post { channelButtons.firstOrNull()?.requestFocus() }
            })
        }
        channelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        browser.addView(categories, LinearLayout.LayoutParams(340, -1))
        browser.addView(channelList, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(browser, FrameLayout.LayoutParams(-1, -1))
        refreshChannels()
        // На ТВ сразу переводим фокус с первой категории на первый канал.
        channelList.post { channelButtons.firstOrNull()?.requestFocus() }
    }

    private fun refreshChannels() {
        if (!browserVisible) return
        channelList.removeAllViews()
        channelButtons.clear()
        channelList.addView(label("КАНАЛЫ  •  ${groups[groupIndex]}", 20f))
        val indices = channels.indices.filter { channels[it].group == groups[groupIndex] }
        indices.forEach { index ->
            val channelButton = button((if (index == selectedChannel) "▶  " else "   ") + channels[index].name) {
                playChannel(index)
            }
            channelButtons += channelButton
            channelList.addView(channelButton)
        }
    }

    private fun closeBrowser() {
        if (browserVisible) root.removeView(browser)
        browserVisible = false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (!browserVisible && keyCode == KeyEvent.KEYCODE_DPAD_CENTER && channels.isNotEmpty()) {
            showBrowser()
            return true
        }
        if (browserVisible) {
            val visible = channels.indices.filter { channels[it].group == groups[groupIndex] }
            val position = visible.indexOf(selectedChannel).coerceAtLeast(0)
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    groupIndex = (groupIndex - 1).coerceAtLeast(0)
                    selectedChannel = channels.indexOfFirst { it.group == groups[groupIndex] }.coerceAtLeast(0)
                    refreshChannels()
                    channelList.post { channelButtons.firstOrNull()?.requestFocus() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    groupIndex = (groupIndex + 1).coerceAtMost(groups.lastIndex)
                    selectedChannel = channels.indexOfFirst { it.group == groups[groupIndex] }.coerceAtLeast(0)
                    refreshChannels()
                    channelList.post { channelButtons.firstOrNull()?.requestFocus() }
                    return true
                }
                KeyEvent.KEYCODE_BACK -> { closeBrowser(); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun label(value: String, size: Float) = TextView(this).apply {
        text = value; textSize = size; setTextColor(-1); setPadding(12, 12, 12, 12)
    }

    private fun button(value: String, action: () -> Unit) = Button(this).apply {
        text = value; isFocusable = true; minHeight = dp(54); setTextColor(-1)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF202A3A.toInt()))
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(value: String) = Toast.makeText(this, value, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }
}

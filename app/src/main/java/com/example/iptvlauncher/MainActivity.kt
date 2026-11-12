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
import android.view.ViewGroup
import android.widget.*
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
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
    private val statusBarColors = listOf(
        0xFF000000.toInt(), // Чёрный — исходный цвет #000000
        0xFF30343B.toInt(), // Тёмно-серый
        0xFF123B66.toInt(), // Синий
        0xFF174B36.toInt(), // Зелёный
        0xFF43265F.toInt(), // Фиолетовый
        0xFF642638.toInt()  // Бордовый
    )
    private val statusBarColorNames = listOf("Чёрный", "Тёмно-серый", "Синий", "Зелёный", "Фиолетовый", "Бордовый")
    private val palettes = listOf(
        intArrayOf(0xFF252525.toInt(), 0xFFFFFFFF.toInt(), 0xFF101010.toInt()), // Чёрно-белая
        intArrayOf(0xFF145B91.toInt(), 0xFF45C7FF.toInt(), 0xFF17334B.toInt()), // Океан
        intArrayOf(0xFF176B4A.toInt(), 0xFF63E6A5.toInt(), 0xFF183D30.toInt()), // Изумруд
        intArrayOf(0xFF59318A.toInt(), 0xFFC49AFF.toInt(), 0xFF34204F.toInt()), // Аметист
        intArrayOf(0xFF9A5718.toInt(), 0xFFFFC45A.toInt(), 0xFF503018.toInt()), // Янтарь
        intArrayOf(0xFF922E50.toInt(), 0xFFFF789A.toInt(), 0xFF4A2033.toInt())  // Рубин
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
    private var pendingBackupJson: JSONObject? = null
    private var pendingBackupContent: String? = null
    private var restoreOfferDialog: android.app.AlertDialog? = null
    private var restoreOfferButton: Button? = null
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
    private var endedDialogShown = false
    private var endedRetryCount = 0
    private var endedRetryChannelIndex = -1
    private var epgProgress = 0
    private var epgProgressText = ""
    private var epgById: Map<String, List<EpgProgramme>> = emptyMap()
    private var epgByName: Map<String, List<EpgProgramme>> = emptyMap()
    private var groups = listOf<String>()
    private var channelIndicesByGroup: Map<String, List<Int>> = emptyMap()
    private var groupIndex = 0
    private var activePlaylistSlot = 1
    private var selectedChannel = 0
    private var browserVisible = false
    private var homeReturnPending = false
    private val prefs by lazy { getSharedPreferences("iptv", MODE_PRIVATE) }
    private val decoderNames = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val diagnosticLog = java.util.Collections.synchronizedList(mutableListOf<String>())
    private fun logEvent(message: String) {
        val line = "${java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(java.util.Date())}  $message"
        synchronized(diagnosticLog) {
            diagnosticLog.add(line)
            while (diagnosticLog.size > 500) diagnosticLog.removeAt(0)
            runCatching { prefs.edit().putString("diagnostic_log", diagnosticLog.joinToString("\n")).apply() }
        }
        android.util.Log.i("IPTVLauncher", message)
    }

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

    private val createBackupFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val content = pendingBackupContent
        pendingBackupContent = null
        if (uri != null && content != null) {
            thread(name = "settings-backup-write") {
                val result = runCatching {
                    contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { it.write(content) }
                        ?: throw java.io.IOException("Не удалось открыть файл для записи")
                }
                runOnUiThread {
                    if (result.isSuccess) toast("Резервная копия сохранена")
                    else toast("Не удалось сохранить копию: ${result.exceptionOrNull()?.message ?: "ошибка"}")
                }
            }
        }
    }

    private val openBackupFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            thread(name = "settings-backup-read") {
                val parsed = runCatching {
                    val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                        ?: throw java.io.IOException("Не удалось прочитать файл")
                    JSONObject(text)
                }
                runOnUiThread {
                    if (parsed.isSuccess) {
                        pendingBackupJson = parsed.getOrNull()
                        val offer = restoreOfferDialog
                        if (offer != null) {
                            restoreOfferButton?.isEnabled = true
                            restoreOfferButton?.alpha = 1f
                            toast("Резервная копия выбрана")
                        } else {
                            android.app.AlertDialog.Builder(this)
                                .setTitle("Восстановить настройки?")
                                .setMessage("Выбран файл резервной копии. Текущие настройки будут заменены данными из копии.")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Восстановить") { _, _ -> restoreBackup(pendingBackupJson) }
                                .show()
                        }
                    } else {
                        toast("Не удалось прочитать резервную копию: ${parsed.exceptionOrNull()?.message ?: "неверный JSON"}")
                    }
                }
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
        // После возврата из свернутого состояния восстанавливаем последний канал.
        if (::player.isInitialized && channels.isNotEmpty() && !browserVisible) {
            applyInterfaceOpacity()
            if (homeReturnPending) {
                homeReturnPending = false
                playChannel(resolveLastChannelIndex(channels, selectedChannel))
            }
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
        // Режим выбирается пользователем в меню декодера.
        // 0 auto; 1 hardware preferred; 2 hardware only; 3 software preferred;
        // 4 software only; 5 standard selector + Media3 decoder fallback.
        val decoderMode = prefs.getInt("video_decoder_mode", 0).coerceIn(0, 5)
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(decoderMode == 5)
        if (decoderMode in 1..4) {
            renderersFactory.setMediaCodecSelector(object : MediaCodecSelector {
                override fun getDecoderInfos(
                    mimeType: String,
                    requiresSecureDecoder: Boolean,
                    requiresTunnelingDecoder: Boolean
                ): List<MediaCodecInfo> {
                    val available = MediaCodecSelector.DEFAULT.getDecoderInfos(
                        mimeType, requiresSecureDecoder, requiresTunnelingDecoder
                    )
                    val hardware = available.filter { it.hardwareAccelerated }
                    val software = available.filter { it.softwareOnly }
                    val chosen = when (decoderMode) {
                        1 -> hardware + software
                        2 -> hardware
                        3 -> software + hardware
                        4 -> software
                        else -> available
                    }
                    decoderNames.addAll(chosen.map { it.name })
                    return chosen
                }
            })
        } else {
            // Записываем список декодеров, который Android предлагает в обычном режиме.
            runCatching {
                val selector = MediaCodecSelector.DEFAULT
                listOf("video/avc", "video/hevc", "video/mp4v-es", "video/x-vnd.on2.vp9")
                    .forEach { mime -> selector.getDecoderInfos(mime, false, false).forEach { decoderNames.add(it.name) } }
            }
        }
        player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(DefaultMediaSourceFactory(streamDataSourceFactory))
            .build()
        // Восстанавливаем журнал предыдущего запуска, чтобы его можно было открыть после перезапуска.
        prefs.getString("diagnostic_log", "")?.lineSequence()?.filter { it.isNotBlank() }?.toList()?.takeLast(500)?.let {
            synchronized(diagnosticLog) { diagnosticLog.addAll(it) }
        }
        player.addListener(object : androidx.media3.common.Player.Listener {
            override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                logEvent("Размер видео: ${videoSize.width}x${videoSize.height}; pixelRatio=${videoSize.pixelWidthHeightRatio}")
            }

            override fun onRenderedFirstFrame() {
                logEvent("Отображён первый видеокадр")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                isChannelBuffering = playbackState == androidx.media3.common.Player.STATE_BUFFERING
                val stateName = when (playbackState) {
                    androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                    androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                    androidx.media3.common.Player.STATE_READY -> "READY"
                    androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                    else -> playbackState.toString()
                }
                logEvent("Состояние плеера: $stateName; канал: ${channels.getOrNull(selectedChannel)?.name ?: "не выбран"}")
                if (playbackState == androidx.media3.common.Player.STATE_READY) {
                    endedRetryCount = 0
                    endedRetryChannelIndex = selectedChannel
                }
                if (playbackState == androidx.media3.common.Player.STATE_ENDED && !endedDialogShown && channels.isNotEmpty()) {
                    endedDialogShown = true
                    isChannelBuffering = false
                    runOnUiThread { handleStreamEnded() }
                }
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
                logEvent("ОШИБКА ExoPlayer: ${error.errorCodeName}; ${error.message ?: "без описания"}; cause=${error.cause?.javaClass?.simpleName}: ${error.cause?.message}")
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
        logEvent("Запуск приложения; устройство=${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE}; движок=ExoPlayer/Media3; режим декодера=$decoderMode")
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

        activePlaylistSlot = prefs.getInt("active_playlist_slot", 1).coerceIn(1, 2)
        val savedUrl = prefs.getString("playlist_${activePlaylistSlot}_url", null) ?: prefs.getString("playlist_url", null)
        val savedUri = prefs.getString("playlist_uri", null)
        val cacheFileAvailable = java.io.File(filesDir, "playlist_cache.m3u").let { it.exists() && it.length() > 0L }
        val restoredCache = cacheFileAvailable && (prefs.getBoolean("restored_from_backup", false) || prefs.getBoolean("restored_local_playlist", false))
        when {
            restoredCache -> {
                prefs.edit().remove("restored_from_backup").apply()
                val cache = java.io.File(filesDir, "playlist_cache.m3u")
                thread(name = "restored-playlist-cache") {
                    val parsed = runCatching { M3uParser.parse(cache.readText()) }.getOrDefault(emptyList())
                    runOnUiThread {
                        if (parsed.isNotEmpty()) {
                            allPlaylistChannels = parsed
                            val hidden = prefs.getStringSet("hidden_groups_${activePlaylistSlot}", if (activePlaylistSlot == 1) prefs.getStringSet("hidden_groups", emptySet()) else emptySet()) ?: emptySet()
                            val visible = parsed.filter { it.group !in hidden }
                            applyPlaylist(visible.ifEmpty { parsed }, prefs.getInt("last_channel", 0))
                            if (!savedUrl.isNullOrBlank()) fetchPlaylist(savedUrl, selectedChannel, false)
                        } else {
                            toast("Кэш плейлиста из резервной копии повреждён")
                            if (!savedUrl.isNullOrBlank()) fetchPlaylist(savedUrl, prefs.getInt("last_channel", -1)) else showSetup()
                        }
                    }
                }
            }
            savedUrl != null -> {
                val cache = java.io.File(filesDir, "playlist_cache.m3u")
                if (cache.exists() && cache.length() > 0L) {
                    thread(name = "playlist-cache") {
                        val parsed = runCatching { M3uParser.parse(cache.readText()) }.getOrDefault(emptyList())
                        runOnUiThread {
                            if (parsed.isNotEmpty()) {
                                allPlaylistChannels = parsed
                                val hidden = prefs.getStringSet("hidden_groups_${activePlaylistSlot}", if (activePlaylistSlot == 1) prefs.getStringSet("hidden_groups", emptySet()) else emptySet()) ?: emptySet()
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
            else -> {
                if (!prefs.getBoolean("first_launch_banner_shown", false)) {
                    prefs.edit().putBoolean("first_launch_banner_shown", true).apply()
                    showFirstLaunchBanner()
                } else showSetup()
            }
        }
    }

    private fun showFirstLaunchBanner() {
        val banner = FrameLayout(this).apply { setBackgroundColor(0xFF090B10.toInt()) }
        val title = TextView(this).apply {
            text = "IPTV Launcher"
            textSize = 38f
            setTextColor(0xFFFFFFFF.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }
        banner.addView(title, FrameLayout.LayoutParams(-1, -1))
        root.addView(banner, FrameLayout.LayoutParams(-1, -1))
        banner.alpha = 0f
        banner.animate().alpha(1f).setDuration(250).start()
        statusHandler.postDelayed({
            if (!isFinishing && !isDestroyed) {
                banner.animate().alpha(0f).setDuration(200).withEndAction {
                    root.removeView(banner)
                    showRestoreOffer()
                }.start()
            }
        }, 3_000L)
    }

    private fun showRestoreOffer() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(22), dp(28), dp(22))
            setBackgroundColor(0xFF101722.toInt())
        }
        panel.addView(label("Восстановление настроек", 25f))
        panel.addView(label("Если у вас есть резервная копия IPTV Launcher в формате JSON, выберите её для восстановления. Если копии нет, нажмите «Пропустить».", 16f).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFB8C4D4.toInt())
        })
        val searchButton = button("Поиск резервной копии вручную", focusable = true) {
            openBackupFile.launch(arrayOf("application/json", "text/json", "*/*"))
        }
        val restoreButton = button("Восстановить", focusable = true) {
            val backup = pendingBackupJson
            if (backup == null) toast("Сначала выберите файл резервной копии")
            else {
                restoreOfferDialog?.dismiss()
                restoreOfferDialog = null
                restoreBackup(backup)
            }
        }.apply {
            isEnabled = false
            alpha = 0.45f
        }
        val skipButton = button("Пропустить", focusable = true) {
            restoreOfferDialog?.dismiss()
            restoreOfferDialog = null
            pendingBackupJson = null
            showSetup()
        }
        restoreOfferButton = restoreButton
        panel.addView(searchButton, LinearLayout.LayoutParams(dp(620), dp(58)).apply { topMargin = dp(18); bottomMargin = dp(8) })
        panel.addView(restoreButton, LinearLayout.LayoutParams(dp(620), dp(58)).apply { bottomMargin = dp(8) })
        panel.addView(skipButton, LinearLayout.LayoutParams(dp(620), dp(58)))
        restoreOfferDialog = android.app.AlertDialog.Builder(this).setView(panel).setCancelable(false).create()
        restoreOfferDialog?.setOnDismissListener { restoreOfferDialog = null }
        restoreOfferDialog?.show()
        restoreOfferDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        restoreOfferDialog?.window?.setLayout(minOf(resources.displayMetrics.widthPixels - dp(48), dp(760)), -2)
        searchButton.requestFocus()
    }

    private fun buildBackupJson(): String {
        val root = JSONObject()
        root.put("format", "IPTVLauncherBackup")
        root.put("version", 1)
        root.put("createdAt", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(java.util.Date()))
        val values = JSONObject()
        prefs.all.forEach { (key, value) ->
            val item = JSONObject()
            when (value) {
                is String -> { item.put("type", "string"); item.put("value", value) }
                is Boolean -> { item.put("type", "boolean"); item.put("value", value) }
                is Int -> { item.put("type", "int"); item.put("value", value) }
                is Long -> { item.put("type", "long"); item.put("value", value) }
                is Float -> { item.put("type", "float"); item.put("value", value.toDouble()) }
                is Set<*> -> {
                    item.put("type", "stringSet")
                    val array = JSONArray()
                    value.filterIsInstance<String>().sorted().forEach { array.put(it) }
                    item.put("value", array)
                }
                else -> return@forEach
            }
            values.put(key, item)
        }
        root.put("preferences", values)
        val cache = java.io.File(filesDir, "playlist_cache.m3u")
        if (cache.exists() && cache.length() <= 30L * 1024L * 1024L) {
            root.put("playlistCacheBase64", Base64.encodeToString(cache.readBytes(), Base64.NO_WRAP))
        }
        return root.toString(2)
    }

    private fun createSettingsBackup() {
        try {
            pendingBackupContent = buildBackupJson()
            createBackupFile.launch("IPTVLauncher-backup-${java.text.SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(java.util.Date())}.json")
        } catch (e: Exception) {
            pendingBackupContent = null
            toast("Не удалось подготовить копию: ${e.message ?: "ошибка"}")
        }
    }

    private fun chooseBackupToRestore() {
        openBackupFile.launch(arrayOf("application/json", "text/json", "*/*"))
    }

    private fun restoreBackup(backup: JSONObject?) {
        if (backup == null) { toast("Сначала выберите резервную копию"); return }
        if (backup.optString("format") != "IPTVLauncherBackup" || backup.optInt("version", -1) != 1) {
            toast("Формат резервной копии не поддерживается")
            return
        }
        val values = backup.optJSONObject("preferences") ?: run {
            toast("В резервной копии отсутствуют настройки")
            return
        }
        try {
            val cacheEncoded = backup.optString("playlistCacheBase64", "")
            val restoredCacheBytes = if (cacheEncoded.isNotBlank()) Base64.decode(cacheEncoded, Base64.DEFAULT) else null
            if (restoredCacheBytes != null && restoredCacheBytes.size > 30 * 1024 * 1024) {
                throw IllegalArgumentException("Кэш плейлиста слишком большой")
            }
            val backupPrefs = backup.optJSONObject("preferences") ?: JSONObject()
            fun hasNonBlankPreference(key: String): Boolean =
                backupPrefs.optJSONObject(key)?.optString("value", "")?.isNotBlank() == true
            val hasPlaylistUrl = hasNonBlankPreference("playlist_url") ||
                hasNonBlankPreference("playlist_1_url") || hasNonBlankPreference("playlist_2_url")
            val editor = prefs.edit().clear()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = values.optJSONObject(key) ?: continue
                val value = item.opt("value")
                when (item.optString("type")) {
                    "string" -> if (value is String) editor.putString(key, value)
                    "boolean" -> editor.putBoolean(key, value as? Boolean ?: false)
                    "int" -> editor.putInt(key, (value as? Number)?.toInt() ?: 0)
                    "long" -> editor.putLong(key, (value as? Number)?.toLong() ?: 0L)
                    "float" -> editor.putFloat(key, (value as? Number)?.toFloat() ?: 0f)
                    "stringSet" -> {
                        val array = item.optJSONArray("value") ?: JSONArray()
                        val set = mutableSetOf<String>()
                        for (i in 0 until array.length()) if (!array.isNull(i)) set.add(array.optString(i))
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.putBoolean("restored_from_backup", true)
                .putBoolean("restored_local_playlist", restoredCacheBytes != null && !hasPlaylistUrl)
                .putBoolean("first_launch_banner_shown", true)
            if (restoredCacheBytes != null && !hasPlaylistUrl) editor.remove("playlist_uri")
            editor.apply()
            if (restoredCacheBytes != null) {
                java.io.File(filesDir, "playlist_cache.m3u").writeBytes(restoredCacheBytes)
            } else {
                java.io.File(filesDir, "playlist_cache.m3u").delete()
            }
            pendingBackupJson = null
            toast("Настройки восстановлены. Перезапуск приложения…")
            statusHandler.postDelayed({ if (!isFinishing) recreate() }, 700L)
        } catch (e: Exception) {
            toast("Ошибка восстановления: ${e.message ?: "неверные данные"}")
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
        val homeHint = label("Чтобы открыть главное меню приставки, нажмите и удерживайте кнопку OK на пульте 3 секунды.", 14f).apply {
            setTextColor(0xFF9EADC2.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(10), 0, dp(14))
        }
        panel.addView(homeHint)
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
                        val hidden = prefs.getStringSet("hidden_groups_${activePlaylistSlot}", if (activePlaylistSlot == 1) prefs.getStringSet("hidden_groups", emptySet()) else emptySet()) ?: emptySet()
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
                        prefs.edit().putString("playlist_url", parsedUrl).putString("playlist_${activePlaylistSlot}_url", parsedUrl).remove("playlist_uri").putBoolean("restored_local_playlist", false).apply()
                        prefs.edit().putLong("playlist_updated_at", System.currentTimeMillis()).apply()
                        if (showOptions) showPlaylistOptions(parsed, restoreIndex) else {
                            val hidden = prefs.getStringSet("hidden_groups_${activePlaylistSlot}", if (activePlaylistSlot == 1) prefs.getStringSet("hidden_groups", emptySet()) else emptySet()) ?: emptySet()
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
        runCatching { java.io.File(filesDir, "playlist_cache.m3u").writeText(data) }
        prefs.edit().putBoolean("restored_local_playlist", false).apply()
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
        val hidden = (prefs.getStringSet("hidden_groups_${activePlaylistSlot}", if (activePlaylistSlot == 1) prefs.getStringSet("hidden_groups", emptySet()) else emptySet()) ?: emptySet()).toMutableSet()
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
            prefs.edit().putStringSet("hidden_groups", hiddenGroups).putStringSet("hidden_groups_${activePlaylistSlot}", hiddenGroups)
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
        val currentUrls = channels.map { it.url }.toSet()
        val favs = favoriteChannels().map { it.url }.filter { it in currentUrls }.toSet()
        groups = (if (favs.isNotEmpty()) listOf("Избранное") else emptyList()) +
            channels.map { it.group }.distinct().filter { it != "Избранное" }
        if (prefs.getBoolean("only_favorites_${activePlaylistSlot}", false)) {
            channels.retainAll { it.url in favs }
            groups = if (channels.isNotEmpty()) listOf("Избранное") else emptyList()
        }
        if (groups.isEmpty()) groups = channels.map { it.group }.distinct().ifEmpty { listOf("Без категории") }
        rebuildChannelIndex()
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
        endedDialogShown = false
        endedRetryCount = 0
        endedRetryChannelIndex = index
        logEvent("Запрос воспроизведения канала: ${channels[index].name}")
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


    private fun handleStreamEnded() {
        if (isFinishing || channels.isEmpty()) return
        val index = selectedChannel
        val channelName = channels.getOrNull(index)?.name ?: "Текущий канал"
        if (endedRetryChannelIndex != index) {
            endedRetryChannelIndex = index
            endedRetryCount = 0
        }
        if (endedRetryCount < 2) {
            endedRetryCount++
            val attempt = endedRetryCount
            logEvent("Поток завершился (ENDED); автоматическая перезагрузка $attempt/2; канал=$channelName")
            statusHandler.postDelayed({
                if (!isFinishing && selectedChannel == index && channels.indices.contains(index)) {
                    logEvent("Автоматический повторный запуск $attempt/2; канал=$channelName")
                    endedDialogShown = false
                    player.stop()
                    player.clearMediaItems()
                    player.setMediaItem(MediaItem.fromUri(channels[index].url))
                    player.prepare()
                    player.playWhenReady = true
                }
            }, 1500L)
        } else {
            logEvent("Две автоматические попытки исчерпаны; требуется перезагрузить канал вручную; канал=$channelName")
            Toast.makeText(this, "Перезагрузите канал", Toast.LENGTH_LONG).show()
        }
    }

    private fun updatePlaybackBar() {
        if (channels.isEmpty()) return
        playbackBar.removeAllViews()
        val category = groups.getOrNull(groupIndex) ?: "Без категории"
        val inGroup = channelIndicesByGroup[category].orEmpty()
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
        categoryColumn.addView(statusText(category, 28f * statusSizeScale()).apply {
            setTextColor(currentPalette()[1])
        })
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
        details.addView(statusText(channel.name, 36f * statusSizeScale()).apply {
            maxLines = 1
            setTextColor(currentPalette()[0])
        })
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
            val intervalHours = epgIntervalHours(activePlaylistSlot)
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

    private fun rebuildChannelIndex() {
        val favorites = favoriteUrls()
        channelIndicesByGroup = groups.associateWith { category ->
            channels.indices.filter { index ->
                val channel = channels[index]
                if (category == "Избранное") channel.url in favorites else channel.group == category
            }
        }
    }

    private fun changeChannelInGroup(direction: Int) {
        if (channels.isEmpty()) return
        val category = groups.getOrNull(groupIndex) ?: return
        val indices = channelIndicesByGroup[category].orEmpty()
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
        applyInterfaceOpacity()
        refreshChannels()
        refreshProgrammeDetails()
    }

    private fun refreshChannels() {
        if (!browserVisible) return
        channelList.removeAllViews()
        channelList.addView(label("${groups.getOrNull(groupIndex) ?: "КАНАЛЫ"}  •  КАНАЛЫ", 20f))
        val indices = channelIndicesByGroup[groups[groupIndex]].orEmpty()
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
                        setColor(uiPanelColor(currentPalette()[0]))
                        setCornerRadius(dp(9).toFloat())
                        setStroke(dp(3), currentPalette()[1])
                    }
                    elevation = dp(4).toFloat()
                } else {
                    setTextColor(0xFFE0E7F0.toInt())
                    setTypeface(typeface, android.graphics.Typeface.NORMAL)
                    setBackgroundTintList(android.content.res.ColorStateList.valueOf(uiPanelColor(currentPalette()[2])))
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
                    if (!prefs.getBoolean("back_opens_browser", true)) {
                        updatePlaybackBar()
                        showPlaybackBarForThreeSeconds()
                    }
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
                    } else {
                        // При выключенной опции BACK не закрывает приложение:
                        // показываем статус-бар поверх эфира на 3 секунды.
                        updatePlaybackBar()
                        showPlaybackBarForThreeSeconds()
                        return true
                    }
                }
                return super.dispatchKeyEvent(event)
            }
            if (browserVisible) {
                val visible = channels.indices.filter { if (groups[groupIndex] == "Избранное") channels[it].url in favoriteUrls() else channels[it].group == groups[groupIndex] }
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
    private fun statusBackgroundColor(): Int {
        // Независимый цвет статус-бара: не зависит от общей палитры интерфейса.
        val base = statusBarColors[prefs.getInt("status_bar_color", 0).mod(statusBarColors.size)]
        val alpha = (statusAlpha() * 255f / 100f).toInt().coerceIn(0, 255)
        return (alpha shl 24) or (base and 0x00FFFFFF)
    }
    private fun statusGravity() = if (statusAtTop()) Gravity.TOP else Gravity.BOTTOM
    private fun uiPanelColor(color: Int): Int {
        val factor = 1f - prefs.getInt("ui_transparency", 55).coerceIn(0, 80) / 100f
        val alpha = (android.graphics.Color.alpha(color) * factor).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (alpha shl 24)
    }

    private fun applyInterfaceOpacity() {
        // Эти параметры относятся к экранному меню каналов, открываемому BACK,
        // и не должны менять системный статус-бар или окно воспроизведения.
        if (::browser.isInitialized) {
            browser.background = android.graphics.drawable.ColorDrawable(uiPanelColor(0xF00B0E14.toInt()))
        }
        if (::channelList.isInitialized) {
            channelList.setBackgroundColor(uiPanelColor(currentPalette()[2]))
        }
        if (::programmeDetails.isInitialized) {
            programmeDetails.setBackgroundColor(uiPanelColor(0xFF111925.toInt()))
        }
        if (browserVisible) {
            if (::channelList.isInitialized) refreshChannels()
            if (::programmeDetails.isInitialized) refreshProgrammeDetails()
        }
    }

    private fun epgIntervalHours(slot: Int) =
        prefs.getInt("playlist_${slot}_epg_interval_hours", 24).coerceIn(6, 72)
    private fun currentPalette(): IntArray =
        palettes[prefs.getInt("channel_palette", 0).mod(palettes.size)]

    private fun paletteName(index: Int): String = when (index.mod(palettes.size)) {
        0 -> "Чёрно-белая"
        1 -> "Океан"
        2 -> "Изумруд"
        3 -> "Аметист"
        4 -> "Янтарь"
        else -> "Рубин"
    }

    private fun favoriteKey() = "favorites_${activePlaylistSlot}"
    private fun favoriteUrls(): MutableSet<String> = (prefs.getStringSet(favoriteKey(), emptySet()) ?: emptySet()).toMutableSet()
    private fun favoriteChannels(): List<TvChannel> = allPlaylistChannels.filter { it.url in favoriteUrls() }

    private fun visiblePlaylistChannels(source: List<TvChannel>): List<TvChannel> {
        val hidden = prefs.getStringSet("hidden_groups_${activePlaylistSlot}", if (activePlaylistSlot == 1) prefs.getStringSet("hidden_groups", emptySet()) else emptySet()) ?: emptySet()
        val categoryFiltered = source.filter { it.group !in hidden }
        return if (prefs.getBoolean("only_favorites_${activePlaylistSlot}", false)) favoriteChannels()
        else (categoryFiltered + favoriteChannels().filter { fav -> categoryFiltered.none { it.url == fav.url } }).distinctBy { it.url }
    }

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

    private fun showDiagnosticLog() {
        val snapshot = synchronized(diagnosticLog) { diagnosticLog.toList() }
        val content = if (snapshot.isEmpty()) "Журнал пока пуст. Ошибки и события появятся во время воспроизведения." else snapshot.joinToString("\n")
        val textView = TextView(this).apply {
            text = content
            textSize = 13f
            setTextColor(0xFFE3E8EF.toInt())
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(this).apply {
            addView(textView)
            isFillViewport = true
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Журнал ошибок и событий (${snapshot.size})")
            .setView(scroll)
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Копировать") { _, _ ->
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("IPTVLauncher log", content))
                toast("Журнал скопирован")
            }
            .setNegativeButton("Очистить") { _, _ ->
                synchronized(diagnosticLog) { diagnosticLog.clear() }
                logEvent("Журнал очищен пользователем")
                toast("Журнал очищен")
            }
            .create()
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.82f).toInt(), (resources.displayMetrics.heightPixels * 0.78f).toInt())
    }

    private fun showDecoderSettings() {
        val options = arrayOf(
            "Автоматический — выбор Android (рекомендуется)",
            "Аппаратный — аппаратные декодеры в приоритете",
            "Только аппаратный — без программных декодеров",
            "Программный — программные декодеры в приоритете",
            "Только программный — без аппаратных декодеров",
            "Аппаратный с резервным — разрешить fallback Media3"
        )
        val descriptions = arrayOf(
            "Android сам выбирает подходящий декодер.",
            "Сначала предлагаются аппаратные декодеры; программные остаются запасным вариантом.",
            "Оставляются только декодеры, которые Android пометил как аппаратные. Если подходящего нет, канал может не запуститься.",
            "Сначала предлагаются программные декодеры; аппаратные остаются запасным вариантом.",
            "Оставляются только декодеры, которые Android пометил как программные. Если подходящего нет, канал может не запуститься.",
            "Используется обычный список Android и разрешается резервный декодер при ошибке инициализации. Этот режим не гарантирует восстановление зависшей картинки."
        )
        val current = prefs.getInt("video_decoder_mode", 0).coerceIn(0, 5)
        android.app.AlertDialog.Builder(this)
            .setTitle("Режим видеодекодера")
            .setSingleChoiceItems(options, current) { dialog, which ->
                prefs.edit().putInt("video_decoder_mode", which).apply()
                dialog.dismiss()
                android.app.AlertDialog.Builder(this)
                    .setTitle("Режим сохранён")
                    .setMessage(descriptions[which] + "\n\nДля применения полностью закройте IPTVLauncher и запустите его снова. Если канал не запускается, вернитесь в автоматический режим.")
                    .setPositiveButton("Понятно", null)
                    .show()
            }
            .setNeutralButton("Информация о декодерах") { _, _ -> showDecoderInfo() }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun showDecoderInfo() {
        val names = synchronized(decoderNames) { decoderNames.toList().sorted() }
        val device = "Устройство: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\nAndroid: ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        val list = if (names.isEmpty()) "Список ещё не получен. Перезапустите приложение после выбора режима." else names.joinToString("\n")
        android.app.AlertDialog.Builder(this)
            .setTitle("Информация о декодерах")
            .setMessage("$device\n\nДекодеры, обнаруженные Android:\n$list\n\nЭто список доступных декодеров, а не подтверждение того, какой из них сейчас обрабатывает канал.")
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun showAppearanceSettings() {
        val accent = currentPalette()[1]
        val bg = 0xFF080B12.toInt()
        val card = 0xFF151B26.toInt()
        val muted = 0xFFAAB5C5.toInt()
        lateinit var dialog: android.app.AlertDialog

        fun applyFocusStyle(view: View, selected: Boolean = false) {
            val normal = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (selected) currentPalette()[2] else 0xFF151B26.toInt())
                cornerRadius = dp(11).toFloat()
                setStroke(dp(if (selected) 2 else 1), if (selected) currentPalette()[1] else 0xFF293648.toInt())
            }
            val focused = android.graphics.drawable.GradientDrawable().apply {
                setColor(currentPalette()[0])
                cornerRadius = dp(11).toFloat()
                setStroke(dp(3), currentPalette()[1])
            }
            val states = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), focused)
                addState(intArrayOf(android.R.attr.state_pressed), focused)
                addState(intArrayOf(), normal)
            }
            view.background = states
            view.isFocusable = true
            view.isFocusableInTouchMode = true
            if (android.os.Build.VERSION.SDK_INT >= 21 && view is Button) {
                view.backgroundTintList = null
            }
            view.onFocusChangeListener = View.OnFocusChangeListener { v, hasFocus ->
                v.invalidate()
                if (v is TextView) v.setTextColor(if (hasFocus || selected) 0xFFFFFFFF.toInt() else 0xFFE5EAF2.toInt())
            }
        }

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
                colors = intArrayOf(currentPalette()[0], currentPalette()[2])
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                cornerRadius = dp(14).toFloat()
            }
        }
        val fakeScene = FrameLayout(this).apply {
            addView(android.widget.ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.channel_preview)
                scaleType = android.widget.ImageView.ScaleType.CENTER_CROP
                contentDescription = "Иллюстрация телестудии для предпросмотра прозрачности"
            }, FrameLayout.LayoutParams(-1, -1))
            addView(TextView(this@MainActivity).apply {
                text = "ПЕРВЫЙ\nКАНАЛ"
                textSize = 10f
                setTypeface(null, android.graphics.Typeface.BOLD)
                gravity = Gravity.CENTER
                setTextColor(0xFFFFFFFF.toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0xAA182536.toInt())
                    cornerRadius = dp(8).toFloat()
                }
                setPadding(dp(8), dp(5), dp(8), dp(5))
            }, FrameLayout.LayoutParams(dp(58), dp(48), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(10); rightMargin = dp(10)
            })
            addView(TextView(this@MainActivity).apply {
                text = "НОВОСТИ  ·  СЕЙЧАС В ЭФИРЕ"
                textSize = 10f
                setTextColor(0xFFEAF4FF.toInt())
                setPadding(dp(10), dp(5), dp(10), dp(5))
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x99000000.toInt())
                    cornerRadius = dp(6).toFloat()
                }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(10); leftMargin = dp(10)
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
            val opacity = statusAlpha()
            val base = statusBarColors[prefs.getInt("status_bar_color", 0).mod(statusBarColors.size)]
            val translucent = android.graphics.Color.argb(
                (opacity * 255 / 100).coerceIn(0, 255),
                android.graphics.Color.red(base),
                android.graphics.Color.green(base),
                android.graphics.Color.blue(base)
            )
            previewText.text = "●  ФИЛЬМЫ     1/24   Первый канал"
            previewText.setTextColor(0xFFFFFFFF.toInt())
            previewText.setCompoundDrawablePadding(dp(5))
            val h = (54 * statusSizeScale()).toInt().coerceAtLeast(26)
            val lp = previewStatus.layoutParams as? FrameLayout.LayoutParams
                ?: FrameLayout.LayoutParams(-1, dp(h), if (statusAtTop()) Gravity.TOP else Gravity.BOTTOM)
            lp.width = -1
            lp.height = dp(h)
            lp.gravity = if (statusAtTop()) Gravity.TOP else Gravity.BOTTOM
            previewStatus.layoutParams = lp
            previewStatus.setPadding(dp(10), dp(if (h < 40) 3 else 7), dp(10), dp(if (h < 40) 3 else 7))
            previewStage.background = android.graphics.drawable.GradientDrawable().apply {
                setColors(intArrayOf(colors[0], colors[2]))
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TL_BR
                cornerRadius = dp(14).toFloat()
            }
            previewCaption.text = "Схема: ${paletteName(prefs.getInt("channel_palette", 0))}  ·  " +
                "Статус-бар: ${statusBarColorNames[prefs.getInt("status_bar_color", 0).mod(statusBarColors.size)]}  ·  " +
                "Прозрачность ${statusAlpha()}%"
            // Цветовая схема видна сразу: фон сцены, статус-бар и акцентная рамка.
            previewStatus.background = android.graphics.drawable.GradientDrawable().apply {
                setColor(translucent)
                setStroke(dp(2), colors[1])
                cornerRadius = dp(7).toFloat()
            }
            // Не задаём alpha всей плашке: текст остаётся контрастным,
            // а меняется именно прозрачность её фона.
            previewStatus.alpha = 1f
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
                if (settings.childCount == 0) tag = "first_menu_action"
                setTextColor(if (selected) 0xFFFFFFFF.toInt() else 0xFFE5EAF2.toInt())
                applyFocusStyle(this, selected)
                setOnClickListener { onClick(); refreshPreview() }
            }, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
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
            text = "Непрозрачность фона: ${statusAlpha()}%"
            textSize = 13f
            setTextColor(0xFFE5EAF2.toInt())
        }
        settings.addView(alphaValue)
        settings.addView(SeekBar(this).apply {
            max = 80
            progress = statusAlpha() - 20
            isFocusable = true
            isFocusableInTouchMode = true
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFEAF4FF.toInt())
            setOnFocusChangeListener { v, focused ->
                v.scaleX = if (focused) 1.05f else 1f
                v.scaleY = if (focused) 1.18f else 1f
                v.alpha = if (focused) 1f else 0.78f
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val value = progress + 20
                        prefs.edit().putInt("status_alpha", value).apply()
                        alphaValue.text = "Непрозрачность фона: $value%"
                        applyStatusAppearance()
                        refreshPreview()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        section("ПРОЗРАЧНОСТЬ МЕНЮ КАНАЛОВ  ·  ${prefs.getInt("ui_transparency", 55).coerceIn(0, 80)}%")
        val menuTransparencyValue = TextView(this).apply {
            text = "Прозрачность фона меню: ${prefs.getInt("ui_transparency", 55).coerceIn(0, 80)}%"
            textSize = 13f
            setTextColor(0xFFE5EAF2.toInt())
        }
        settings.addView(menuTransparencyValue)
        settings.addView(SeekBar(this).apply {
            max = 80
            progress = prefs.getInt("ui_transparency", 55).coerceIn(0, 80)
            isFocusable = true
            isFocusableInTouchMode = true
            progressTintList = android.content.res.ColorStateList.valueOf(accent)
            thumbTintList = android.content.res.ColorStateList.valueOf(0xFFEAF4FF.toInt())
            setOnFocusChangeListener { v, focused ->
                v.scaleX = if (focused) 1.05f else 1f
                v.scaleY = if (focused) 1.18f else 1f
                v.alpha = if (focused) 1f else 0.78f
            }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        prefs.edit().putInt("ui_transparency", progress).apply()
                        menuTransparencyValue.text = "Прозрачность фона меню: ${progress}%"
                        applyInterfaceOpacity()
                    }
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        section("ЦВЕТ СТАТУС-БАРА")
        settingButton("Цвет статус-бара: ${statusBarColorNames[prefs.getInt("status_bar_color", 0).mod(statusBarColors.size)]}  ›") {
            val next = (prefs.getInt("status_bar_color", 0) + 1).mod(statusBarColors.size)
            prefs.edit().putInt("status_bar_color", next).apply()
            applyStatusAppearance()
            refreshPreview()
            (settings.getChildAt(settings.childCount - 1) as? Button)?.text =
                "Цвет статус-бара: ${statusBarColorNames[next]}  ›"
            toast("Цвет статус-бара: ${statusBarColorNames[next]}")
        }

        section("ЦВЕТОВАЯ СХЕМА")
        settingButton("Цветовая схема: ${paletteName(prefs.getInt("channel_palette", 0))}  ›") {
            val next = (prefs.getInt("channel_palette", 0) + 1).mod(palettes.size)
            prefs.edit().putInt("channel_palette", next).apply()
            refreshPreview()
            if (browserVisible) {
                refreshChannels()
                refreshProgrammeDetails()
                applyInterfaceOpacity()
            }
            if (::playbackBar.isInitialized && playbackBar.visibility == View.VISIBLE) updatePlaybackBar()
            toast("Цветовая схема: ${paletteName(next)}")
            // Обновляем подпись пункта, чтобы выбранная схема была видна сразу.
            (settings.getChildAt(settings.childCount - 1) as? Button)?.text =
                "Цветовая схема: ${paletteName(next)}  ›"
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
            isFocusableInTouchMode = true
            setPadding(dp(10), dp(4), dp(10), dp(4))
            applyFocusStyle(this)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("back_opens_browser", checked).apply()
            }
        }
        settings.addView(backOption)

        section("ПЛЕЙЛИСТЫ")
        settingButton("Активный плейлист: ${activePlaylistSlot}") {
            val choices = arrayOf("Плейлист 1", "Плейлист 2")
            android.app.AlertDialog.Builder(this).setTitle("Выберите активный плейлист")
                .setSingleChoiceItems(choices, activePlaylistSlot - 1) { d, which ->
                    activePlaylistSlot = which + 1
                    prefs.edit().putInt("active_playlist_slot", activePlaylistSlot).apply()
                    val url = prefs.getString("playlist_${activePlaylistSlot}_url", null)
                    d.dismiss()
                    if (!url.isNullOrBlank()) fetchPlaylist(url, 0, false)
                    else toast("Укажите адрес плейлиста ${activePlaylistSlot}")
                    dialog.dismiss()
                }.setNegativeButton("Отмена", null).show()
        }
        settingButton("Настройка плейлиста 1") { showPlaylistSourceEditor(1) }
        settingButton("Настройка плейлиста 2") { showPlaylistSourceEditor(2) }
        fun addEpgIntervalSetting(slot: Int) {
            val values = intArrayOf(6, 12, 24, 48, 72)
            val labels = arrayOf("Каждые 6 часов", "Каждые 12 часов", "Каждые 24 часа", "Каждые 48 часов", "Каждые 72 часа")
            settingButton("Интервал обновления EPG · плейлист $slot: ${epgIntervalHours(slot)} ч") {
                android.app.AlertDialog.Builder(this)
                    .setTitle("Обновление EPG · плейлист $slot")
                    .setSingleChoiceItems(labels, values.indexOf(epgIntervalHours(slot)).coerceAtLeast(0)) { d, which ->
                        prefs.edit().putInt("playlist_${slot}_epg_interval_hours", values[which]).apply()
                        d.dismiss()
                        dialog.dismiss()
                        showAppearanceSettings()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
        addEpgIntervalSetting(1)
        addEpgIntervalSetting(2)
        val onlyFav = CheckBox(this).apply {
            text = "Только избранные каналы"
            textSize = 14f; setTextColor(0xFFE5EAF2.toInt())
            buttonTintList = android.content.res.ColorStateList.valueOf(accent)
            isChecked = prefs.getBoolean("only_favorites_${activePlaylistSlot}", false)
            isFocusable = true; isFocusableInTouchMode = true; minHeight = dp(48)
            setOnCheckedChangeListener { _, checked ->
                val source = allPlaylistChannels.ifEmpty { channels.toList() }
                if (checked && favoriteChannels().isEmpty()) {
                    toast("Сначала добавьте каналы в избранное")
                    isChecked = false
                    return@setOnCheckedChangeListener
                }
                prefs.edit().putBoolean("only_favorites_${activePlaylistSlot}", checked).apply()
                val filtered = visiblePlaylistChannels(source)
                if (filtered.isNotEmpty()) applyPlaylist(filtered, selectedChannel, reloadEpg = false)
            }
        }
        settings.addView(onlyFav)

        section("РЕЗЕРВНОЕ КОПИРОВАНИЕ")
        settingButton("Создать резервную копию настроек (JSON)") { createSettingsBackup() }
        settingButton("Восстановить настройки из копии") { chooseBackupToRestore() }

        section("ДОПОЛНИТЕЛЬНО")
        settingButton("Обновить EPG вручную") {
            loadEpgIfConfigured(force = true)
            toast("Запущено ручное обновление EPG")
        }
        settingButton("Выбор категорий плейлиста") {
            val source = allPlaylistChannels.ifEmpty { channels.toList() }
            if (source.isNotEmpty()) showCategorySettings(source)
        }
        settingButton("Настройка избранного") { showFavoritesSettings() }
        settingButton("Режим видеодекодера") { showDecoderSettings() }
        settingButton("Журнал ошибок и событий") { showDiagnosticLog() }
        settingButton("Открыть настройки приставки") {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
                .onFailure { toast("Не удалось открыть настройки приставки") }
        }
        section("СПРАВКА")
        settingButton("Как управлять приложением") {
            android.app.AlertDialog.Builder(this)
                .setTitle("Управление с пульта")
                .setMessage(
                    "Во время просмотра:\n" +
                    "• Вверх / Вниз — переключение каналов.\n" +
                    "• Влево / Вправо — смена категории.\n" +
                    "• OK (удерживать 3 секунды) — настройки.\n" +
                    "• BACK — список каналов, если включена соответствующая галочка; если выключена — показать статус-бар на 3 секунды.\n\n" +
                    "В списке каналов:\n" +
                    "• Вверх / Вниз — выбор канала.\n" +
                    "• Влево / Вправо — категории.\n" +
                    "• OK — включить выбранный канал.\n" +
                    "• BACK — вернуться к просмотру.\n\n" +
                    "Избранное:\n" +
                    "• В меню настроек откройте «Настройка избранного».\n" +
                    "• Вверх / Вниз — выбор канала, OK — поставить или снять галочку.\n" +
                    "• Изменения сохраняются сразу, BACK — выход. Скрытые категории не показываются.\n\n" +
                    "Настройки приложения: удерживайте OK 3 секунды во время просмотра.\n" +
                    "Главное меню приставки: нажмите и удерживайте кнопку OK на пульте 3 секунды. При возвращении приложение пытается продолжить последний канал."
                )
                .setPositiveButton("Понятно", null)
                .show()
        }

        shell.addView(previewColumn, LinearLayout.LayoutParams(0, -1, 0.43f).apply { rightMargin = dp(14) })
        shell.addView(rightColumn, LinearLayout.LayoutParams(0, -1, 0.57f))
        dialog = android.app.AlertDialog.Builder(this).setView(shell).create()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setOnKeyListener { dlg, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                if (event.action == KeyEvent.ACTION_UP) {
                    dialog.dismiss()
                    window?.decorView?.requestFocus()
                }
                true
            } else {
                false
            }
        }
        dialog.setCancelable(true)
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
        dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.94f).toInt(),
            (resources.displayMetrics.heightPixels * 0.90f).toInt())
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
            setColor(bg); cornerRadius = dp(22).toFloat()
            setStroke(dp(1), 0xFF2B3A4D.toInt())
        })
        refreshPreview()
        settingsScroll.post {
            settingsScroll.scrollTo(0, 0)
            val firstAction = settings.findViewWithTag<View>("first_menu_action")
            firstAction?.isFocusable = true
            firstAction?.requestFocus()
        }
    }

    private fun showPlaylistSourceEditor(slot: Int) {
        val url = EditText(this).apply { hint = "Ссылка M3U / M3U8"; setSingleLine(true); setText(prefs.getString("playlist_${slot}_url", if (slot == 1) prefs.getString("playlist_url", "") else "")) }
        val epg = EditText(this).apply { hint = "Ссылка EPG XMLTV (необязательно)"; setSingleLine(true); setText(prefs.getString("playlist_${slot}_epg", "")) }
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)); addView(url); addView(epg) }
        android.app.AlertDialog.Builder(this).setTitle("Настройка плейлиста $slot").setView(fields)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                val address = url.text.toString().trim()
                prefs.edit().putString("playlist_${slot}_url", address).putString("playlist_${slot}_epg", epg.text.toString().trim()).apply()
                if (address.startsWith("http://") || address.startsWith("https://")) {
                    activePlaylistSlot = slot
                    prefs.edit().putInt("active_playlist_slot", slot).putString("playlist_url", address).putString("epg_url", epg.text.toString().trim()).apply()
                    fetchPlaylist(address, 0, false)
                } else toast("Адрес плейлиста сохранён")
            }.show()
    }

    private fun showFavoritesSettings() {
        val source = allPlaylistChannels.ifEmpty { channels.toList() }
        if (source.isEmpty()) { toast("Сначала загрузите плейлист"); return }

        val hiddenGroups = prefs.getStringSet(
            "hidden_groups_${activePlaylistSlot}",
            if (activePlaylistSlot == 1) prefs.getStringSet("hidden_groups", emptySet()) else emptySet()
        ) ?: emptySet()
        val visibleSource = source.filter { it.group !in hiddenGroups }
        if (visibleSource.isEmpty()) { toast("Нет каналов в отображаемых категориях"); return }

        val previousChannel = selectedChannel
        val dialog = android.app.Dialog(this)
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF090D15.toInt())
            setPadding(dp(18), dp(14), dp(18), dp(14))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF111925.toInt())
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        left.addView(TextView(this).apply {
            text = "ИЗБРАННОЕ  •  КАНАЛЫ"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(8), dp(8), dp(8), dp(14))
        })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            addView(list)
        }
        left.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val preview = FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(4), dp(8))
            addView(TextView(this@MainActivity).apply {
                text = "ПРЕДПРОСМОТР КАНАЛА"
                textSize = 20f
                setTextColor(0xFFFFFFFF.toInt())
                setPadding(dp(8), dp(8), dp(8), dp(14))
            })
            addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
            addView(TextView(this@MainActivity).apply {
                text = "OK — изменить избранное    BACK — возврат"
                textSize = 14f
                setTextColor(0xFFB8C4D4.toInt())
                setPadding(dp(8), dp(12), dp(8), dp(4))
            })
        }
        screen.addView(left, LinearLayout.LayoutParams(0, -1, 0.43f).apply { rightMargin = dp(10) })
        screen.addView(right, LinearLayout.LayoutParams(0, -1, 0.57f))

        // Переносим тот же PlayerView в правую часть, чтобы эфир продолжал отображаться без второго плеера.
        (playerView.parent as? android.view.ViewGroup)?.removeView(playerView)
        preview.addView(playerView, FrameLayout.LayoutParams(-1, -1))

        var firstRow: View? = null
        visibleSource.forEachIndexed { position, channel ->
            val checkBox = CheckBox(this).apply {
                text = channel.name
                textSize = 17f
                setTextColor(0xFFFFFFFF.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(currentPalette()[1])
                isChecked = channel.url in favoriteUrls()
                isFocusable = true
                isFocusableInTouchMode = true
                minHeight = dp(48)
                setPadding(dp(8), dp(2), dp(8), dp(2))
                id = View.generateViewId()
                tag = channel.url
                setOnFocusChangeListener { _, hasFocus ->
                    if (hasFocus) {
                        val index = channels.indexOfFirst { it.url == channel.url }
                        if (index >= 0 && index != selectedChannel) playChannel(index)
                    }
                }
            }
            checkBox.setOnCheckedChangeListener { _, checked ->
                val updated = favoriteUrls()
                if (checked) updated.add(channel.url) else updated.remove(channel.url)
                prefs.edit().putStringSet(favoriteKey(), updated).apply()
            }
            list.addView(checkBox)
            if (position == 0) firstRow = checkBox
        }

        dialog.setContentView(screen)
        dialog.setCanceledOnTouchOutside(false)
        dialog.setOnDismissListener {
            // Возвращаем PlayerView на основной экран; выбранный для предпросмотра канал остаётся текущим.
            (playerView.parent as? android.view.ViewGroup)?.removeView(playerView)
            root.addView(playerView, 0, FrameLayout.LayoutParams(-1, -1))
            val filtered = visiblePlaylistChannels(source)
            if (filtered.isNotEmpty()) {
                val current = channels.indexOfFirst { it.url == channels.getOrNull(selectedChannel)?.url }
                applyPlaylist(filtered, current.coerceIn(0, filtered.lastIndex), reloadEpg = false)
            } else if (prefs.getBoolean("only_favorites_${activePlaylistSlot}", false)) {
                toast("Избранное пусто — отключите фильтр избранного")
            }
            if (selectedChannel == previousChannel) updatePlaybackBar()
        }
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.show()
        dialog.window?.setLayout(-1, -1)
        firstRow?.requestFocus()
    }

    private fun showCategorySettings(source: List<TvChannel>) {
        val allGroups = source.map { it.group }.distinct()
        val hidden = (prefs.getStringSet(
            "hidden_groups_${activePlaylistSlot}",
            if (activePlaylistSlot == 1) prefs.getStringSet("hidden_groups", emptySet()) else emptySet()
        ) ?: emptySet()).toMutableSet()
        val groupsToShow = (allGroups + hidden).distinct()
        if (groupsToShow.isEmpty()) {
            toast("В плейлисте нет категорий")
            return
        }

        // Отдельное стандартное диалоговое окно поверх меню OK.
        // Категории прокручиваются в ScrollView, кнопки остаются внизу окна.
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(12))
            setBackgroundColor(0xFF10141D.toInt())
        }
        panel.addView(label("Выберите категории, которые нужно показывать", 17f))

        val categoryScroll = ScrollView(this).apply {
            isFillViewport = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
            isFocusable = false
        }
        val categoryBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val checks = linkedMapOf<String, CheckBox>()
        groupsToShow.forEach { group ->
            val check = CheckBox(this).apply {
                text = group
                textSize = 18f
                setTextColor(0xFFFFFFFF.toInt())
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF39D7FF.toInt())
                isChecked = group !in hidden
                setPadding(dp(8), dp(5), dp(8), dp(5))
                isFocusable = true
                isFocusableInTouchMode = true
                id = View.generateViewId()
                minHeight = dp(52)
            }
            checks[group] = check
            categoryBox.addView(check)
        }
        categoryScroll.addView(categoryBox)
        // Ограничиваем высоту списка по реальной высоте экрана,
        // чтобы заголовок и кнопки всегда оставались видимыми.
        val screenHeightDp = (resources.displayMetrics.heightPixels /
            resources.displayMetrics.density).toInt()
        val categoryListHeightDp = (screenHeightDp - 300).coerceIn(180, 440)
        panel.addView(categoryScroll, LinearLayout.LayoutParams(dp(620), dp(categoryListHeightDp)))

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(0, dp(12), 0, 0)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setTitle("Настройка категорий плейлиста")
            .setView(panel)
            .create()
        val cancel = button("Отмена", focusable = true) { dialog.dismiss() }
        val save = button("Сохранить", focusable = true) {
            val newHidden = checks.filterValues { !it.isChecked }.keys.toMutableSet()
            val filtered = source.filter { it.group !in newHidden }
            if (filtered.isEmpty()) {
                toast("Оставьте включённой хотя бы одну категорию")
                return@button
            }
            prefs.edit()
                .putStringSet("hidden_groups", newHidden)
                .putStringSet("hidden_groups_${activePlaylistSlot}", newHidden)
                .apply()
            dialog.dismiss()
            applyPlaylist(filtered, selectedChannel, reloadEpg = false)
        }
        actions.addView(cancel, LinearLayout.LayoutParams(0, dp(54), 1f).apply { marginEnd = dp(12) })
        actions.addView(save, LinearLayout.LayoutParams(0, dp(54), 1f))
        panel.addView(actions, LinearLayout.LayoutParams(dp(620), dp(66)))

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.window?.setLayout(dp(700), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
            categoryBox.post { checks.values.firstOrNull()?.requestFocus() ?: cancel.requestFocus() }
        }
        dialog.show()
        dialog.window?.setLayout(dp(700), android.view.WindowManager.LayoutParams.WRAP_CONTENT)
    }

    @Deprecated("Deprecated in Android")
    override fun onBackPressed() {
        if (channels.isNotEmpty() && browserVisible) {
            closeBrowser()
        } else if (channels.isNotEmpty() && prefs.getBoolean("back_opens_browser", true)) {
            showBrowser()
        } else if (channels.isNotEmpty()) {
            // При выключенной опции BACK оставляет эфир и показывает статус-бар 3 секунды.
            updatePlaybackBar()
            showPlaybackBarForThreeSeconds()
        } else {
            super.onBackPressed()
        }
    }

    private fun selectFirstChannelInGroup() {
        selectedChannel = channelIndicesByGroup[groups.getOrNull(groupIndex)].orEmpty().firstOrNull() ?: 0
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
        statusHandler.removeCallbacks(longOkRunnable)
        logEvent("Завершение приложения")
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

}

package com.example.iptvlauncher

import android.content.Context
import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Real libVLC playback backend. UI owner supplies the shared video container. */
class VlcPlaybackController(
    context: Context,
    private val onState: (State, String?) -> Unit
) {
    enum class State { OPENING, BUFFERING, PLAYING, PAUSED, STOPPED, ENDED, ERROR }

    private val appContext = context.applicationContext
    private val libVlc = LibVLC(appContext, arrayListOf("--network-caching=1500"))
    private val mediaPlayer = MediaPlayer(libVlc)
    private var videoLayout: VLCVideoLayout? = null
    private var released = false

    private val eventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Opening -> onState(State.OPENING, null)
            MediaPlayer.Event.Buffering -> onState(State.BUFFERING, "${event.buffering}%")
            MediaPlayer.Event.Playing -> onState(State.PLAYING, null)
            MediaPlayer.Event.Paused -> onState(State.PAUSED, null)
            MediaPlayer.Event.Stopped -> onState(State.STOPPED, null)
            MediaPlayer.Event.EndReached -> onState(State.ENDED, null)
            MediaPlayer.Event.EncounteredError -> onState(State.ERROR, "libVLC: EncounteredError")
        }
    }

    init {
        mediaPlayer.setEventListener(eventListener)
    }

    /** Creates/attaches VLC's own video surface inside the app's movable video host. */
    fun attachTo(host: FrameLayout) {
        check(!released) { "VLC controller already released" }
        detachFromHost()
        val layout = VLCVideoLayout(host.context)
        videoLayout = layout
        host.addView(layout, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        mediaPlayer.attachViews(layout, null, true, false)
    }

    fun play(url: String) {
        check(!released) { "VLC controller already released" }
        onState(State.OPENING, null)
        mediaPlayer.stop()
        val media = Media(libVlc, Uri.parse(url))
        try {
            mediaPlayer.media = media
            mediaPlayer.play()
        } finally {
            media.release()
        }
    }

    fun pause() { if (!released) mediaPlayer.pause() }
    fun resume() { if (!released) mediaPlayer.play() }

    fun stop() {
        if (!released) mediaPlayer.stop()
    }

    fun detachFromHost() {
        if (mediaPlayer.vlcVout.areViewsAttached()) mediaPlayer.detachViews()
        val old = videoLayout
        (old?.parent as? ViewGroup)?.removeView(old)
        videoLayout = null
    }

    fun release() {
        if (released) return
        released = true
        detachFromHost()
        mediaPlayer.setEventListener(null)
        mediaPlayer.release()
        libVlc.release()
    }
}

package com.branchcam.hook

import android.content.Context
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import de.robv.android.xposed.XposedBridge

/**
 * Feeds a video file into the app's original [Surface] using MediaPlayer.
 *
 * MediaPlayer can render decoded frames directly onto a Surface — the same
 * Camera2Hook redirects the camera output to a separate drained reader before
 * this player takes ownership of the app surface.
 *
 * If [videoUri] is empty we fall back to a solid-black placeholder rendered
 * via a background thread pushing dummy frames so the Surface stays "alive."
 */
class FrameInjector(
    private val context: Context,
    private val surface: Surface,
    private val videoUri: String
) {
    private val tag   = MainHook.TAG
    private var mp: MediaPlayer? = null
    private var fallbackThread: HandlerThread? = null
    private var fallbackHandler: Handler? = null

    fun start() {
        if (videoUri.isNotEmpty()) {
            startMediaPlayer()
        } else {
            startFallback()
        }
    }

    private fun startMediaPlayer() {
        val player = MediaPlayer()
        mp = player
        try {
            player.setSurface(surface)
            if (videoUri.startsWith("content://"))
                player.setDataSource(context, Uri.parse(videoUri))
            else
                player.setDataSource(videoUri)
            player.isLooping = true
            player.setOnErrorListener { _, what, extra ->
                XposedBridge.log("[$tag] MediaPlayer error: what=$what extra=$extra — falling back")
                startFallback()
                true
            }
            player.prepareAsync()
            player.setOnPreparedListener { it.start() }
        } catch (e: Throwable) {
            XposedBridge.log("[$tag] MediaPlayer setup failed: ${e.message} — falling back")
            player.release()
            mp = null
            startFallback()
        }
    }

    /**
     * Fallback: push a black frame repeatedly so the Surface keeps receiving
     * data and doesn't freeze the app's camera preview in a broken state.
     *
     * We use a Canvas lock to draw black frames at ~30 fps.
     */
    private fun startFallback() {
        val ht = HandlerThread("branchcam-fallback").also {
            it.start()
            fallbackThread = it
        }
        fallbackHandler = Handler(ht.looper).also { h ->
            val delayMs = 33L   // ~30 fps
            val runner = object : Runnable {
                override fun run() {
                    if (!surface.isValid) return
                    try {
                        val canvas = surface.lockCanvas(null)
                        canvas.drawARGB(255, 0, 0, 0)   // solid black
                        surface.unlockCanvasAndPost(canvas)
                    } catch (_: Throwable) {}
                    h.postDelayed(this, delayMs)
                }
            }
            h.post(runner)
        }
    }

    fun stop() {
        try { mp?.stop(); mp?.release() } catch (_: Throwable) {}
        mp = null
        fallbackHandler?.removeCallbacksAndMessages(null)
        try { fallbackThread?.quitSafely() } catch (_: Throwable) {}
    }
}

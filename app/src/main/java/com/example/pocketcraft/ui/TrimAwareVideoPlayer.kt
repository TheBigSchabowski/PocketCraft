package com.example.pocketcraft.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.pocketcraft.engine.ProjectEngine
import kotlin.math.abs

/**
 * Hält einen [MediaPlayer] auf einer [TextureView].
 *
 * TextureView statt VideoView, weil eine VideoView (SurfaceView) Compose-Transformationen wie
 * Zoom, Verschieben, Drehen und Blur ignoriert – mit TextureView wirken `zoom 2`, `all move 10%l` usw.
 * auch auf echte Videos.
 */
private class PreviewPlayerController(
    private val context: Context,
    private val uri: Uri,
    private val onPrepared: (durationMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) : TextureView.SurfaceTextureListener, PreviewPlaybackController {

    var player: MediaPlayer? = null
        private set
    override var isPrepared by mutableStateOf(false)
        private set

    override val isPlaying: Boolean
        get() = try {
            player?.isPlaying == true
        } catch (_: Exception) {
            false
        }

    private var textureView: TextureView? = null
    private var activeTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var videoWidth = 0
    private var videoHeight = 0
    private var currentSpeed = 1.0f

    fun attach(view: TextureView) {
        textureView = view
        view.surfaceTextureListener = this
        view.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyFitCenter() }
        val st = view.surfaceTexture
        if (view.isAvailable && st != null) {
            onSurfaceTextureAvailable(st, view.width, view.height)
        }
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
        releasePlayer()
        activeTexture = st
        val s = Surface(st)
        surface = s
        try {
            val mp = MediaPlayer()
            mp.setDataSource(context, uri)
            mp.setSurface(s)
            mp.isLooping = false
            mp.setOnVideoSizeChangedListener { _, w, h ->
                videoWidth = w
                videoHeight = h
                applyFitCenter()
            }
            mp.setOnPreparedListener {
                isPrepared = true
                onPrepared(it.duration.toLong())
            }
            mp.setOnErrorListener { _, what, extra ->
                isPrepared = false
                onError("Video kann nicht abgespielt werden (Fehler $what/$extra).")
                true
            }
            mp.prepareAsync()
            player = mp
        } catch (e: SecurityException) {
            onError("Kein Zugriff mehr auf das Video. Bitte über 📂 Video neu auswählen.")
        } catch (e: Exception) {
            onError("Video konnte nicht geöffnet werden: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = applyFitCenter()

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        // Compose kann kurzzeitig eine zweite TextureView anlegen; wird die alte zerstört,
        // darf das NICHT den Player der aktuellen View freigeben (sonst bleibt das Bild schwarz).
        if (st !== activeTexture) return true
        activeTexture = null
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit

    /** Video unverzerrt (mit Rand) in den Vorschau-Rahmen einpassen. */
    private fun applyFitCenter() {
        val view = textureView ?: return
        val vw = view.width.toFloat()
        val vh = view.height.toFloat()
        if (vw <= 0f || vh <= 0f || videoWidth <= 0 || videoHeight <= 0) return
        val scale = minOf(vw / videoWidth, vh / videoHeight)
        val sx = videoWidth * scale / vw
        val sy = videoHeight * scale / vh
        view.setTransform(Matrix().apply { setScale(sx, sy, vw / 2f, vh / 2f) })
    }

    override fun seekTo(ms: Long) {
        if (!isPrepared) return
        try {
            player?.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        } catch (_: Exception) {
        }
    }

    override fun start(speed: Float) {
        val mp = player ?: return
        if (!isPrepared) return
        try {
            mp.start()
            applySpeed(speed)
        } catch (_: Exception) {
        }
    }

    override fun applySpeed(speed: Float) {
        val mp = player ?: return
        if (!isPrepared || !mp.isPlaying || abs(speed - currentSpeed) < 0.01f) return
        try {
            mp.playbackParams = PlaybackParams().setSpeed(speed)
            currentSpeed = speed
        } catch (_: Exception) {
        }
    }

    override fun pause() {
        try {
            if (player?.isPlaying == true) player?.pause()
        } catch (_: Exception) {
        }
    }

    override val positionMs: Long
        get() = try {
            player?.currentPosition?.toLong() ?: 0L
        } catch (_: Exception) {
            0L
        }

    fun releasePlayer() {
        isPrepared = false
        currentSpeed = 1.0f
        try {
            player?.release()
        } catch (_: Exception) {
        }
        player = null
        surface?.release()
        surface = null
    }
}

/**
 * Echte Video-Vorschau auf Android. Die Trim-/Schnitt-Logik steckt in [TrimAwarePlaybackEffects].
 */
@Composable
fun TrimAwareVideoPlayer(
    engine: ProjectEngine,
    videoUri: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val controller = remember(videoUri) {
        PreviewPlayerController(
            context = context.applicationContext,
            uri = Uri.parse(videoUri),
            onPrepared = { dur -> engine.syncRealVideoDuration(dur) },
            onError = { msg ->
                engine.isPlaying = false
                engine.statusBanner = msg
            },
        )
    }

    DisposableEffect(controller) {
        onDispose { controller.releasePlayer() }
    }

    AndroidView(
        factory = { ctx -> TextureView(ctx).also { controller.attach(it) } },
        modifier = modifier,
    )

    TrimAwarePlaybackEffects(engine, controller)
}

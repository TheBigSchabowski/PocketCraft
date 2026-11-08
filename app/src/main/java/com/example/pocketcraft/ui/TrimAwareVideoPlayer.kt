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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.example.pocketcraft.engine.ProjectEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
) : TextureView.SurfaceTextureListener {

    var player: MediaPlayer? = null
        private set
    var isPrepared by mutableStateOf(false)
        private set

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

    fun seekTo(ms: Long) {
        if (!isPrepared) return
        try {
            player?.seekTo(ms, MediaPlayer.SEEK_CLOSEST)
        } catch (_: Exception) {
        }
    }

    fun start(speed: Float) {
        val mp = player ?: return
        if (!isPrepared) return
        try {
            mp.start()
            applySpeed(speed)
        } catch (_: Exception) {
        }
    }

    fun applySpeed(speed: Float) {
        val mp = player ?: return
        if (!isPrepared || !mp.isPlaying || abs(speed - currentSpeed) < 0.01f) return
        try {
            mp.playbackParams = PlaybackParams().setSpeed(speed)
            currentSpeed = speed
        } catch (_: Exception) {
        }
    }

    fun pause() {
        try {
            if (player?.isPlaying == true) player?.pause()
        } catch (_: Exception) {
        }
    }

    val positionMs: Long
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
 * Echte Video-Vorschau, die beim Abspielen nur den geschnittenen Teil zeigt:
 * - startet bei Trim-Start, springt am Trim-Ende zurück zum Trim-Start (Schleife),
 * - überspringt herausgeschnittene Bereiche (`delete 10-20`, `delete between cut1 cut2`),
 * - folgt dem Playhead beim Scrubben auf der Timeline (auch während des Abspielens),
 * - berücksichtigt die Abspielgeschwindigkeit.
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

    val prepared = controller.isPrepared

    // Abspielen: Video-Position treibt den Playhead, Trim-Grenzen & Löschbereiche werden erzwungen.
    LaunchedEffect(controller, prepared, engine.isPlaying) {
        if (!prepared) return@LaunchedEffect
        if (!engine.isPlaying) {
            controller.pause()
            controller.seekTo(engine.playheadMs)
            return@LaunchedEffect
        }

        fun loopStart(): Long = engine.project.resolvePlayablePosition(engine.project.trimStartMs)

        var target = engine.project.resolvePlayablePosition(engine.playheadMs)
        if (target >= engine.project.trimEndMs - 100L) target = loopStart()
        controller.seekTo(target)
        engine.playheadMs = target
        var lastReported = target
        var ignoreUntil = System.currentTimeMillis() + 400L
        controller.start(engine.project.playbackSpeed)

        while (isActive && engine.isPlaying) {
            delay(30L)
            val p = engine.project
            controller.applySpeed(p.playbackSpeed)

            // Nutzer hat während des Abspielens auf der Timeline gescrubbt -> dorthin springen
            if (abs(engine.playheadMs - lastReported) > 250L) {
                val jump = p.resolvePlayablePosition(engine.playheadMs)
                val dest = if (jump >= p.trimEndMs - 100L) loopStart() else jump
                controller.seekTo(dest)
                engine.playheadMs = dest
                lastReported = dest
                ignoreUntil = System.currentTimeMillis() + 400L
                continue
            }

            val pos = controller.positionMs
            // Direkt nach einem Sprung meldet der Player kurz noch die alte Position
            if (System.currentTimeMillis() < ignoreUntil && abs(pos - lastReported) > 600L) continue

            val removed = p.removedRangeAt(pos)
            val dest: Long? = when {
                pos >= p.trimEndMs - 30L -> loopStart()
                pos < p.trimStartMs - 300L -> loopStart()
                removed != null -> p.resolvePlayablePosition(removed.endMs).let {
                    if (it >= p.trimEndMs - 100L) loopStart() else it
                }
                else -> null
            }
            if (dest != null) {
                controller.seekTo(dest)
                if (controller.player?.isPlaying != true) controller.start(p.playbackSpeed)
                engine.playheadMs = dest
                lastReported = dest
                ignoreUntil = System.currentTimeMillis() + 400L
            } else {
                val clamped = pos.coerceIn(p.trimStartMs, p.trimEndMs)
                engine.playheadMs = clamped
                lastReported = clamped
            }
        }
    }

    // Pausiert: Scrubben auf der Timeline zeigt sofort das passende Bild.
    LaunchedEffect(controller, prepared, engine.playheadMs) {
        if (prepared && !engine.isPlaying) {
            controller.seekTo(engine.playheadMs)
        }
    }
}

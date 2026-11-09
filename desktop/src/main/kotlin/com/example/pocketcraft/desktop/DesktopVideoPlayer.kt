package com.example.pocketcraft.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import com.example.pocketcraft.engine.FilterMath
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.ui.PreviewPlaybackController
import com.example.pocketcraft.ui.TrimAwarePlaybackEffects
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import java.nio.ShortBuffer
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.abs

/** Ein dekodiertes Vorschaubild, das noch auf den UI-Thread wartet. */
private class DecodedFrame(val pixels: ByteArray, val width: Int, val height: Int, val stride: Int)

/**
 * Spielt eine Videodatei mit FFmpeg ab: Ein eigener Thread dekodiert Bild und Ton, die Oberfläche
 * holt sich jeweils das neueste Bild. Springen (Scrubben) ist bildgenau.
 */
class DesktopPreviewController(
    videoUri: String,
    private val onPrepared: (durationMs: Long) -> Unit,
    private val onError: (String) -> Unit,
) : PreviewPlaybackController {

    override var isPrepared by mutableStateOf(false)
        private set

    @Volatile
    override var isPlaying = false
        private set

    @Volatile
    override var positionMs = 0L
        private set

    @Volatile
    var info: VideoInfo? = null
        private set

    /** Neuestes Bild für die Oberfläche (wird von dort abgeholt). */
    private val pendingFrame = AtomicReference<DecodedFrame?>(null)
    private val pendingSeekMs = AtomicLong(-1L)

    @Volatile
    private var speed = 1.0f

    @Volatile
    private var closed = false

    @Volatile
    private var clockReset = true

    private val lock = Object()
    private val file = VideoSource.fileOf(videoUri)
    private val thread = Thread(::decodeLoop, "pocketcraft-preview").apply { isDaemon = true }

    init {
        thread.start()
    }

    fun takeFrame(): Image? {
        val f = pendingFrame.getAndSet(null) ?: return null
        return VideoSource.toSkiaImage(f.pixels, f.width, f.height, f.stride)
    }

    override fun seekTo(ms: Long) {
        pendingSeekMs.set(ms.coerceAtLeast(0L))
        wake()
    }

    override fun start(speed: Float) {
        this.speed = speed
        clockReset = true
        isPlaying = true
        wake()
    }

    override fun applySpeed(speed: Float) {
        if (abs(speed - this.speed) < 0.01f) return
        this.speed = speed
        clockReset = true
    }

    override fun pause() {
        isPlaying = false
        wake()
    }

    fun release() {
        closed = true
        isPlaying = false
        wake()
    }

    private fun wake() = synchronized(lock) { lock.notifyAll() }

    private fun idle(ms: Long) = synchronized(lock) {
        if (!closed) lock.wait(ms)
    }

    private fun decodeLoop() {
        var grabber: FFmpegFrameGrabber? = null
        var audio: SourceDataLine? = null
        try {
            val videoInfo = VideoSource.probe(file)
            info = videoInfo
            grabber = VideoSource.open(file, videoInfo, maxLongSide = 1280)
            audio = openAudioLine(videoInfo)
            // Erstes Bild sofort zeigen
            grabber.grabImage()?.let { publish(it) }
            isPrepared = true
            onPrepared(videoInfo.durationMs)

            var clockWallNs = 0L
            var clockMediaUs = 0L
            var audioActive = false

            while (!closed) {
                val seek = pendingSeekMs.getAndSet(-1L)
                if (seek >= 0L) {
                    val target = seek.coerceAtMost((videoInfo.durationMs - 40L).coerceAtLeast(0L))
                    grabber.setTimestamp(target * 1000L)
                    val frame = grabber.grabImage()
                    if (frame != null) publish(frame)
                    positionMs = target
                    audio?.flush()
                    clockReset = true
                    continue
                }
                if (!isPlaying) {
                    if (audioActive) {
                        audio?.stop()
                        audio?.flush()
                        audioActive = false
                    }
                    idle(60L)
                    continue
                }

                val frame = grabber.grab()
                if (frame == null) {
                    // Dateiende: Position ans Ende melden, die Vorschau-Logik springt dann zum Start zurück
                    positionMs = videoInfo.durationMs
                    isPlaying = false
                    continue
                }

                if (frame.image != null) {
                    val nowNs = System.nanoTime()
                    if (clockReset) {
                        clockWallNs = nowNs
                        clockMediaUs = frame.timestamp
                        clockReset = false
                    }
                    val dueNs = clockWallNs + ((frame.timestamp - clockMediaUs) * 1000.0 / speed).toLong()
                    var waitNs = dueNs - System.nanoTime()
                    if (waitNs < -250_000_000L) {
                        // Deutlich zu spät (langsamer Rechner): Uhr neu ansetzen statt Bilder zu stauen
                        clockReset = true
                    }
                    while (waitNs > 0L && isPlaying && pendingSeekMs.get() < 0L && !closed) {
                        Thread.sleep(minOf(waitNs / 1_000_000L, 15L).coerceAtLeast(1L))
                        waitNs = dueNs - System.nanoTime()
                    }
                    if (pendingSeekMs.get() < 0L) {
                        publish(frame)
                        positionMs = frame.timestamp / 1000L
                    }
                } else if (frame.samples != null && audio != null && abs(speed - 1f) < 0.01f) {
                    if (!audioActive) {
                        audio.start()
                        audioActive = true
                    }
                    writeAudio(audio, frame)
                } else if (audioActive && abs(speed - 1f) >= 0.01f) {
                    // Bei Zeitlupe/Zeitraffer bleibt die Vorschau stumm (der Export passt den Ton an)
                    audio?.stop()
                    audio?.flush()
                    audioActive = false
                }
            }
        } catch (e: Throwable) {
            isPrepared = false
            isPlaying = false
            if (!closed) onError("Video konnte nicht geöffnet werden: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            runCatching { audio?.close() }
            runCatching { grabber?.close() }
        }
    }

    private fun publish(frame: Frame) {
        pendingFrame.set(DecodedFrame(VideoSource.copyPixels(frame), frame.imageWidth, frame.imageHeight, frame.imageStride))
    }

    private fun openAudioLine(info: VideoInfo): SourceDataLine? {
        if (!info.hasAudio) return null
        return try {
            val format = AudioFormat(info.sampleRate.toFloat(), 16, 2, true, false)
            AudioSystem.getSourceDataLine(format).apply {
                // ~0,2 s Puffer: klein genug, damit Bild und Ton zusammenbleiben
                open(format, info.sampleRate * 4 / 5)
            }
        } catch (_: Exception) {
            null // kein Audiogerät (z.B. Server) -> stumme Vorschau
        }
    }

    private var audioBytes = ByteArray(0)

    private fun writeAudio(line: SourceDataLine, frame: Frame) {
        val samples = (frame.samples[0] as? ShortBuffer)?.duplicate() ?: return
        samples.position(0)
        val count = samples.remaining()
        if (audioBytes.size < count * 2) audioBytes = ByteArray(count * 2)
        for (i in 0 until count) {
            val s = samples.get().toInt()
            audioBytes[i * 2] = s.toByte()
            audioBytes[i * 2 + 1] = (s shr 8).toByte()
        }
        line.write(audioBytes, 0, count * 2 - (count * 2) % 4)
    }
}

/**
 * Echte Video-Vorschau auf dem Desktop: zeigt das aktuelle Bild unverzerrt eingepasst (inkl.
 * Handy-Drehung) und mit dem gewählten Farbfilter.
 */
@Composable
fun DesktopVideoPreview(engine: ProjectEngine, videoUri: String, modifier: Modifier) {
    val controller = remember(videoUri) {
        DesktopPreviewController(
            videoUri = videoUri,
            onPrepared = { dur -> engine.syncRealVideoDuration(dur) },
            onError = { msg ->
                engine.isPlaying = false
                engine.statusBanner = msg
            },
        )
    }
    var image by remember(controller) { mutableStateOf<Image?>(null) }
    var frameTick by remember(controller) { mutableIntStateOf(0) }

    DisposableEffect(controller) {
        onDispose {
            controller.release()
            image?.close()
            image = null
        }
    }

    // Einmal pro Bildschirm-Frame das neueste dekodierte Bild übernehmen (auf dem UI-Thread)
    LaunchedEffect(controller) {
        while (true) {
            withFrameNanos { }
            val next = controller.takeFrame() ?: continue
            val old = image
            image = next
            frameTick++
            old?.close()
        }
    }

    val filterMatrix = remember(engine.project.filter) { FilterMath.colorMatrix(engine.project.filter) }

    Canvas(modifier = modifier) {
        frameTick // Neuzeichnen bei jedem neuen Bild
        val img = image ?: return@Canvas
        val rotation = controller.info?.rotation ?: 0
        val shownW = if (rotation % 180 == 0) img.width else img.height
        val shownH = if (rotation % 180 == 0) img.height else img.width
        val scale = minOf(size.width / shownW, size.height / shownH)
        val drawW = img.width * scale
        val drawH = img.height * scale
        val paint = Paint().apply {
            if (filterMatrix != null) {
                // Skia erwartet die Offsets im Bereich 0–1
                val m = filterMatrix.copyOf()
                for (i in intArrayOf(4, 9, 14, 19)) m[i] = m[i] / 255f
                colorFilter = ColorFilter.makeMatrix(ColorMatrix(m))
            }
        }
        drawIntoCanvas { canvas ->
            val c = canvas.nativeCanvas
            c.save()
            c.translate(size.width / 2f, size.height / 2f)
            c.rotate(rotation.toFloat())
            c.drawImageRect(
                img,
                Rect.makeWH(img.width.toFloat(), img.height.toFloat()),
                Rect.makeXYWH(-drawW / 2f, -drawH / 2f, drawW, drawH),
                FilterMipmap(FilterMode.LINEAR, MipmapMode.NONE),
                paint,
                true,
            )
            c.restore()
        }
        paint.close()
    }

    TrimAwarePlaybackEffects(engine, controller)
}

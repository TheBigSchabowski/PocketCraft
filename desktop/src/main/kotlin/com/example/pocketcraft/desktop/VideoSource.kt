package com.example.pocketcraft.desktop

import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.bytedeco.javacv.FFmpegLogCallback
import org.bytedeco.javacv.Frame
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/** Eckdaten einer Videodatei. [rotation] ist die Drehung im Uhrzeigersinn, mit der das Bild gezeigt werden muss. */
data class VideoInfo(
    val width: Int,
    val height: Int,
    val rotation: Int,
    val durationMs: Long,
    val frameRate: Double,
    val hasAudio: Boolean,
    val sampleRate: Int,
) {
    /** Breite/Höhe so, wie das Video angezeigt wird (Hochkant-Handyvideos sind gedreht gespeichert). */
    val displayWidth: Int get() = if (rotation % 180 == 0) width else height
    val displayHeight: Int get() = if (rotation % 180 == 0) height else width
}

object VideoSource {

    init {
        // FFmpeg-Meldungen nicht ungefiltert auf die Konsole schreiben
        avutil.av_log_set_level(avutil.AV_LOG_ERROR)
        runCatching { FFmpegLogCallback.setLevel(avutil.AV_LOG_ERROR) }
    }

    /** Aus einer gespeicherten Projekt-Adresse (`/pfad/video.mp4` oder `file:/…`) wird eine Datei. */
    fun fileOf(videoUri: String): File =
        if (videoUri.startsWith("file:")) File(java.net.URI(videoUri)) else File(videoUri)

    fun probe(file: File): VideoInfo {
        if (!file.exists()) throw java.io.FileNotFoundException("Videodatei nicht gefunden: ${file.path}")
        FFmpegFrameGrabber(file).use { g ->
            g.start()
            if (!g.hasVideo()) throw IllegalArgumentException("Die Datei enthält kein Videobild.")
            val tagRotation = g.getVideoMetadata("rotate")?.toDoubleOrNull()
            // Anzeige-Matrix: -90 bedeutet "90° im Uhrzeigersinn drehen"
            val clockwise = tagRotation ?: -g.displayRotation
            val rotation = (((clockwise / 90.0).roundToInt() * 90) % 360 + 360) % 360
            return VideoInfo(
                width = g.imageWidth,
                height = g.imageHeight,
                rotation = rotation,
                durationMs = (g.lengthInTime / 1000L).coerceAtLeast(0L),
                frameRate = g.frameRate.takeIf { it.isFinite() && it in 1.0..240.0 } ?: 30.0,
                hasAudio = g.hasAudio(),
                sampleRate = g.sampleRate.takeIf { it > 0 } ?: 44_100,
            )
        }
    }

    /**
     * Öffnet einen Decoder, der Bilder als BGRA (höchstens [maxLongSide] Pixel an der langen Seite)
     * und Ton als 16-Bit-Stereo liefert.
     */
    fun open(file: File, info: VideoInfo, maxLongSide: Int): FFmpegFrameGrabber {
        val g = FFmpegFrameGrabber(file)
        val longSide = maxOf(info.width, info.height)
        if (longSide > maxLongSide) {
            val scale = maxLongSide.toDouble() / longSide
            g.imageWidth = ((info.width * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
            g.imageHeight = ((info.height * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
        }
        g.pixelFormat = avutil.AV_PIX_FMT_BGRA
        g.sampleFormat = avutil.AV_SAMPLE_FMT_S16
        if (info.hasAudio) g.audioChannels = 2
        g.start()
        return g
    }

    /** Kopiert die Pixel eines BGRA-Frames (inkl. Zeilen-Padding) in ein Array. */
    fun copyPixels(frame: Frame, reuse: ByteArray? = null): ByteArray {
        val buffer = (frame.image[0] as ByteBuffer).duplicate()
        buffer.position(0)
        val needed = frame.imageStride * frame.imageHeight
        val count = minOf(needed, buffer.remaining())
        val out = if (reuse != null && reuse.size == needed) reuse else ByteArray(needed)
        buffer.get(out, 0, count)
        return out
    }

    fun toSkiaImage(pixels: ByteArray, width: Int, height: Int, stride: Int): Image =
        Image.makeRaster(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE), pixels, stride)
}

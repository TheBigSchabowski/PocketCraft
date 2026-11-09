package com.example.pocketcraft.desktop

import com.example.pocketcraft.engine.ExportLayout
import com.example.pocketcraft.model.VideoProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.javacv.FFmpegFrameFilter
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.bytedeco.javacv.Frame
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Exportiert ein Projekt auf dem Desktop als MP4 (H.264 + AAC).
 *
 * Im Video landet genau das, was die Vorschau zeigt: nur der Trim-Bereich ohne herausgeschnittene
 * Stellen, Format, Zoom, Verschiebung, Drehung, Rand-Zuschnitt, Untertitel, Text-/Emoji-Overlays,
 * Blur, Farbfilter und Geschwindigkeit (der Ton wird dabei ohne Tonhöhenänderung angepasst).
 */
object DesktopVideoExporter {

    sealed class Outcome {
        data class Success(val file: File) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    /** Zielordner für Exporte: `~/Movies/PocketCraft` (macOS) bzw. `~/Videos/PocketCraft` (Linux). */
    fun defaultOutputFile(project: VideoProject): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        return File(AppDirs.exports.apply { mkdirs() }, ExportLayout.exportFileName(project.title, stamp))
    }

    suspend fun export(project: VideoProject, target: File, onProgress: (Int) -> Unit): Outcome {
        val videoUri = project.videoUri
            ?: return Outcome.Failure("Lade zuerst ein Video über 📂 Video – die Demo-Szene kann nicht als Video exportiert werden.")
        val segments = project.playableSegments()
        if (segments.isEmpty()) return Outcome.Failure("Nichts zu exportieren – der geschnittene Bereich ist leer.")

        return withContext(Dispatchers.Default) {
            val partial = File(target.parentFile, target.name + ".part")
            try {
                target.parentFile?.mkdirs()
                render(project, VideoSource.fileOf(videoUri), partial, segments, onProgress)
                if (target.exists()) target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
                onProgress(100)
                Outcome.Success(target)
            } catch (e: kotlinx.coroutines.CancellationException) {
                partial.delete()
                throw e
            } catch (e: Throwable) {
                partial.delete()
                Outcome.Failure("Export fehlgeschlagen: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun render(
        project: VideoProject,
        source: File,
        target: File,
        segments: List<Pair<Long, Long>>,
        onProgress: (Int) -> Unit,
    ) {
        val info = VideoSource.probe(source)
        val size = ExportLayout.outputSizeFor(project, minOf(info.width, info.height))
        val speed = project.playbackSpeed.toDouble().coerceIn(0.25, 3.0)
        val changeSpeed = abs(speed - 1.0) > 0.01
        val fps = info.frameRate.coerceIn(10.0, 60.0)
        val totalSourceUs = segments.sumOf { (s, e) -> e - s } * 1000L

        VideoSource.open(source, info, maxLongSide = maxOf(size.width, size.height, 1920)).use { grabber ->
            val audioChannels = if (info.hasAudio) 2 else 0
            val recorder = FFmpegFrameRecorder(target, size.width, size.height, audioChannels).apply {
                format = "mp4"
                frameRate = fps
                pixelFormat = avutil.AV_PIX_FMT_YUV420P
                gopSize = (fps * 2).toInt()
                videoBitrate = if (minOf(size.width, size.height) >= 1000) 10_000_000 else 5_000_000
                val h264 = listOf("libx264", "h264_videotoolbox", "libopenh264")
                    .firstOrNull { avcodec.avcodec_find_encoder_by_name(it) != null }
                if (h264 != null) {
                    videoCodec = avcodec.AV_CODEC_ID_H264
                    videoCodecName = h264
                } else {
                    videoCodec = avcodec.AV_CODEC_ID_MPEG4
                }
                if (audioChannels > 0) {
                    audioCodec = avcodec.AV_CODEC_ID_AAC
                    sampleRate = info.sampleRate
                    audioBitrate = 160_000
                }
                // Abspiel-Programme können das Video schon beim Laden starten
                setOption("movflags", "+faststart")
            }

            val tempo = if (changeSpeed && audioChannels > 0) {
                FFmpegFrameFilter(atempoChain(speed), audioChannels).apply {
                    sampleFormat = avutil.AV_SAMPLE_FMT_S16
                    sampleRate = info.sampleRate
                    start()
                }
            } else {
                null
            }

            val renderer = DesktopFrameRenderer(project, size, info.rotation)
            val outFrame = Frame(size.width, size.height, Frame.DEPTH_UBYTE, 4)
            var sourcePixels: ByteArray? = null
            var lastFrameNumber = -1L
            var doneSourceUs = 0L
            var lastPercent = -1

            try {
                recorder.start()
                for ((startMs, endMs) in segments) {
                    val startUs = startMs * 1000L
                    val endUs = endMs * 1000L
                    grabber.setTimestamp(startUs)
                    while (true) {
                        coroutineContext.ensureActive()
                        val frame = grabber.grab() ?: break
                        val ts = frame.timestamp

                        if (frame.image != null) {
                            if (ts >= endUs) break
                            if (ts < startUs - 1000L) continue
                            val outUs = ((doneSourceUs + (ts - startUs).coerceAtLeast(0L)) / speed).roundToLong()
                            val frameNumber = (outUs * fps / 1_000_000.0).roundToLong()
                            // Bei Zeitraffer fallen mehrere Quellbilder auf dasselbe Ausgabebild -> überspringen
                            if (frameNumber <= lastFrameNumber) continue
                            lastFrameNumber = frameNumber

                            val pixels = VideoSource.copyPixels(frame, sourcePixels).also { sourcePixels = it }
                            val rendered = VideoSource.toSkiaImage(pixels, frame.imageWidth, frame.imageHeight, frame.imageStride)
                                .use { renderer.render(it, ts / 1000L) }
                            writePixels(outFrame, rendered, size.width)
                            recorder.timestamp = (frameNumber * 1_000_000.0 / fps).roundToLong()
                            recorder.record(outFrame, avutil.AV_PIX_FMT_BGRA)

                            val percent = (((doneSourceUs + (ts - startUs)) * 100L) / totalSourceUs.coerceAtLeast(1L)).toInt().coerceIn(0, 99)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        } else if (frame.samples != null && audioChannels > 0) {
                            if (ts < startUs || ts >= endUs) continue
                            if (tempo != null) {
                                tempo.push(frame)
                                drain(tempo, recorder)
                            } else {
                                recorder.record(frame)
                            }
                        }
                    }
                    doneSourceUs += endUs - startUs
                }
                if (tempo != null) drain(tempo, recorder)
            } finally {
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
                runCatching { tempo?.close() }
                renderer.close()
                outFrame.close()
            }
        }
    }

    private fun drain(filter: FFmpegFrameFilter, recorder: FFmpegFrameRecorder) {
        while (true) {
            val out = filter.pullSamples() ?: break
            recorder.record(out)
        }
    }

    /**
     * `atempo` verträgt pro Stufe nur 0,5–2,0 – größere Änderungen werden hintereinandergeschaltet.
     * Die Werte stehen als Bruch (`3/2`) da: FFmpeg liest Kommazahlen je nach Systemsprache mit
     * Punkt oder Komma, ein Bruch funktioniert überall.
     */
    internal fun atempoChain(speed: Double): String {
        val parts = mutableListOf<String>()
        var rest = speed
        while (rest > 2.0) {
            parts.add("atempo=2")
            rest /= 2.0
        }
        while (rest < 0.5) {
            parts.add("atempo=1/2")
            rest /= 0.5
        }
        parts.add("atempo=${(rest * 1000).roundToLong()}/1000")
        return parts.joinToString(",")
    }

    private fun writePixels(frame: Frame, pixels: ByteArray, width: Int) {
        val buffer = frame.image[0] as ByteBuffer
        val rowBytes = width * 4
        buffer.position(0)
        if (frame.imageStride == rowBytes) {
            buffer.put(pixels, 0, minOf(pixels.size, buffer.remaining()))
        } else {
            for (row in 0 until frame.imageHeight) {
                buffer.position(row * frame.imageStride)
                buffer.put(pixels, row * rowBytes, rowBytes)
            }
        }
        buffer.position(0)
    }
}

package com.example.pocketcraft.engine

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.GaussianBlur
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.effect.RgbMatrix
import androidx.media3.effect.SpeedChangeEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.example.pocketcraft.model.BlurMode
import com.example.pocketcraft.model.FilterConfig
import com.example.pocketcraft.model.OverlayKind
import com.example.pocketcraft.model.VideoFilterPreset
import com.example.pocketcraft.model.VideoProject
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Exportiert ein Projekt als echtes MP4-Video (H.264 + AAC) in die Galerie (`Movies/PocketCraft`).
 *
 * Im Video landet genau das, was die Vorschau zeigt:
 * - nur der Trim-Bereich, herausgeschnittene Stellen (`delete 10-20`) fehlen,
 * - Format (9:16, 1:1, …), Zoom, Verschiebung (`move 10%l`), Drehung, Rand-Zuschnitt (`u20%`),
 * - Untertitel, Text- und Emoji-Overlays (eingebrannt), Blur, Farbfilter, Geschwindigkeit.
 */
private typealias OutputSize = ExportLayout.OutputSize

@OptIn(UnstableApi::class)
object VideoExporter {

    sealed class Outcome {
        data class Success(val displayPath: String, val uri: Uri?) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    fun outputSizeFor(project: VideoProject, sourceShortSide: Int?): OutputSize =
        ExportLayout.outputSizeFor(project, sourceShortSide)

    /**
     * Startet den Export. Muss auf dem Main-Thread aufgerufen werden (Transformer-Anforderung).
     * [onProgress] liefert 0–100.
     */
    suspend fun export(
        context: Context,
        project: VideoProject,
        onProgress: (Int) -> Unit,
    ): Outcome {
        val videoUri = project.videoUri
            ?: return Outcome.Failure("Lade zuerst ein Video über 📂 Video – die Demo-Szene kann nicht als Video exportiert werden.")
        val segments = project.playableSegments()
        if (segments.isEmpty()) return Outcome.Failure("Nichts zu exportieren – der geschnittene Bereich ist leer.")

        val sourceShortSide = withContext(Dispatchers.IO) { readSourceShortSide(context, Uri.parse(videoUri)) }
        val size = outputSizeFor(project, sourceShortSide)
        val tempFile = File(context.cacheDir, "pocketcraft_export.mp4").apply { delete() }

        val items = segments.map { (startMs, endMs) ->
            buildEditedItem(context, project, videoUri, startMs, endMs, size)
        }
        val composition = Composition.Builder(EditedMediaItemSequence.Builder(items).build()).build()

        val finished = CompletableDeferred<String?>()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished.complete(null)
                }

                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finished.complete(exportException.message ?: exportException.errorCodeName)
                }
            })
            .build()

        val exportError: String? = coroutineScope {
            try {
                transformer.start(composition, tempFile.absolutePath)
            } catch (e: Exception) {
                finished.complete(e.message ?: e.javaClass.simpleName)
            }
            val progressJob = launch {
                val holder = ProgressHolder()
                while (isActive && !finished.isCompleted) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress)
                    }
                    delay(300L)
                }
            }
            try {
                finished.await()
            } finally {
                progressJob.cancel()
                if (!finished.isCompleted) transformer.cancel()
            }
        }

        if (exportError != null) {
            tempFile.delete()
            return Outcome.Failure("Export fehlgeschlagen: $exportError")
        }
        onProgress(100)
        return withContext(Dispatchers.IO) { publishToGallery(context, tempFile, project.title) }
    }

    private fun buildEditedItem(
        context: Context,
        project: VideoProject,
        videoUri: String,
        startMs: Long,
        endMs: Long,
        size: OutputSize,
    ): EditedMediaItem {
        val mediaItem = MediaItem.Builder()
            .setUri(videoUri)
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(startMs)
                    .setEndPositionMs(endMs)
                    .build(),
            )
            .build()

        // Jeder Abschnitt bekommt eine eigene Uhr: Sie kalibriert sich am ersten Frame und rechnet
        // die Frame-Zeitstempel in Quellvideo-Millisekunden um (für Untertitel/Overlays/Spans).
        val clock = SegmentClock(startMs)
        val videoEffects = mutableListOf<Effect>()
        videoEffects.add(Presentation.createForWidthAndHeight(size.width, size.height, Presentation.LAYOUT_SCALE_TO_FIT))
        videoEffects.add(transformEffect(project, clock, size))
        videoEffects.addAll(colorEffects(project, clock))
        if (project.blur.mode == BlurMode.FULL_FRAME && project.blur.radiusPx > 0.5f) {
            val sigma = (project.blur.radiusPx * 0.65f * dpScale(size, project) / 2f).coerceIn(1f, 60f)
            videoEffects.add(GaussianBlur(sigma))
        }
        val renderer = ExportOverlayRenderer(context, project, size)
        videoEffects.add(
            OverlayEffect(
                ImmutableList.of<TextureOverlay>(
                    object : BitmapOverlay() {
                        override fun getBitmap(presentationTimeUs: Long): Bitmap =
                            renderer.render(clock.sourceMs(presentationTimeUs))
                    },
                ),
            ),
        )

        val audioProcessors = mutableListOf<AudioProcessor>()
        val hasAudioGainChange = abs(project.audioVolume - 1f) > 0.01f ||
            project.timedFormatSpans.any { it.audioVolumeStart != null }
        if (hasAudioGainChange) {
            audioProcessors.add(VolumeFadeAudioProcessor(project, startMs))
        }
        val speed = project.playbackSpeed
        if (abs(speed - 1f) > 0.01f) {
            // Zuletzt: verändert die Zeitstempel, alle Effekte davor sehen noch Quellzeit
            videoEffects.add(SpeedChangeEffect(speed))
            audioProcessors.add(SonicAudioProcessor().apply { setSpeed(speed) })
        }

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
    }

    /** Skaliert eine 3D-Achsendrehung (X/Y) als cos(Grad) für die affine Export-Matrix (nie 0). */
    internal fun axisScale(degrees: Float): Float {
        val c = kotlin.math.cos(Math.toRadians(degrees.toDouble())).toFloat()
        return if (kotlin.math.abs(c) < 0.01f) {
            if (c >= 0f) 0.01f else -0.01f
        } else {
            c
        }
    }

    /** Zoom, Verschiebung und 2D/3D-Drehung wie in der Vorschau (graphicsLayer), in NDC umgerechnet. */
    private fun transformEffect(project: VideoProject, clock: SegmentClock, size: OutputSize): MatrixTransformation {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        return MatrixTransformation { presentationTimeUs ->
            val t = project.transformAt(clock.sourceMs(presentationTimeUs))
            val sx = t.zoom * axisScale(t.rotationYDegrees)
            val sy = t.zoom * axisScale(t.rotationXDegrees)
            Matrix().apply {
                setScale(w / 2f, h / 2f) // NDC -> Pixel (zentriert, y nach oben)
                postScale(sx, sy)
                postRotate(-t.rotationDegrees) // im Uhrzeigersinn wie in der Vorschau
                postTranslate(t.panX * w, -t.panY * h)
                postScale(2f / w, 2f / h) // Pixel -> NDC
            }
        }
    }

    /** Dynamic per-frame color filter (supports both global filter and timed filter spans). */
    private fun colorEffects(project: VideoProject, clock: SegmentClock): List<Effect> {
        val hasTimedFilter = project.timedFormatSpans.any { it.filterPreset != null }
        if (FilterMath.colorMatrix(project.filter) == null && !hasTimedFilter) return emptyList()
        return listOf(
            RgbMatrix { presentationTimeUs, _ ->
                buildGlColorMatrix(project.filterAt(clock.sourceMs(presentationTimeUs)))
            },
        )
    }

    private fun buildGlColorMatrix(config: FilterConfig): FloatArray {
        val m = FilterMath.colorMatrix(config) ?: return floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f,
        )
        // FilterMath yields a 4x5 row-major matrix with translation in col 4 (0..255); convert to 4x4 column-major GL matrix
        val gl = FloatArray(16)
        for (row in 0 until 3) {
            for (col in 0 until 3) {
                gl[col * 4 + row] = m[row * 5 + col]
            }
            gl[3 * 4 + row] = m[row * 5 + 4] / 255f
        }
        gl[15] = 1f
        return gl
    }

    /** Pixel pro dp, damit Texte/Emojis im Video so groß wirken wie in der Vorschau (~391x421 dp Bühne). */
    internal fun dpScale(size: OutputSize, project: VideoProject): Float = ExportLayout.dpScale(size, project)

    private fun readSourceShortSide(context: Context, uri: Uri): Int? = try {
        MediaMetadataRetriever().run {
            setDataSource(context, uri)
            val w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            release()
            if (w != null && h != null) minOf(w, h) else null
        }
    } catch (_: Exception) {
        null
    }

    private fun publishToGallery(context: Context, tempFile: File, title: String): Outcome {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        val fileName = ExportLayout.exportFileName(title, stamp)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    // DCIM/Camera: erscheint in Google Fotos direkt oben in der Hauptansicht
                    // (eigene Ordner wie Movies/PocketCraft zeigt Fotos nur versteckt unter "Auf dem Gerät").
                    put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/Camera")
                    put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
                    ?: return Outcome.Failure("Galerie-Eintrag konnte nicht angelegt werden.")
                resolver.openOutputStream(uri)?.use { out -> tempFile.inputStream().use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                tempFile.delete()
                Outcome.Success("DCIM/Camera/$fileName", uri)
            } else {
                val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "PocketCraft").apply { mkdirs() }
                val target = File(dir, fileName)
                tempFile.copyTo(target, overwrite = true)
                tempFile.delete()
                Outcome.Success(target.absolutePath, Uri.fromFile(target))
            }
        } catch (e: Exception) {
            Outcome.Failure("Video gerendert, aber Speichern in der Galerie fehlgeschlagen: ${e.message}")
        }
    }
}

/**
 * Rechnet Frame-Zeitstempel eines exportierten Abschnitts in Quellvideo-Millisekunden um.
 * Kalibriert sich am kleinsten gesehenen Zeitstempel (= Abschnittsbeginn), unabhängig davon,
 * ob Media3 die Zeitstempel relativ zum Abschnitt oder zur Gesamtkomposition liefert.
 */
internal class SegmentClock(private val segmentStartMs: Long) {
    @Volatile
    private var baseUs: Long = Long.MAX_VALUE

    fun sourceMs(presentationTimeUs: Long): Long {
        if (presentationTimeUs < baseUs) baseUs = presentationTimeUs
        return segmentStartMs + (presentationTimeUs - baseUs) / 1000L
    }
}

/**
 * Zeichnet alle Ebenen über dem Video (Rand-Zuschnitt-Maske, Blur-Zonen, Text-/Emoji-Overlays,
 * Untertitel) in ein transparentes Bitmap in Ausgabegröße – mit denselben Positionen und Größen
 * wie die Vorschau. Unveränderte Frames liefern dasselbe Bitmap (kein erneutes Hochladen).
 */
internal class ExportOverlayRenderer(
    private val context: Context,
    private val project: VideoProject,
    private val size: ExportLayout.OutputSize,
) {
    private val w = size.width.toFloat()
    private val h = size.height.toFloat()
    private val dp = VideoExporter.dpScale(size, project)
    private val buffers = arrayOf(
        Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888),
        Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888),
    )
    private var current = 0
    private var lastKey: String? = null

    fun render(sourceMs: Long): Bitmap {
        val t = project.transformAt(sourceMs)
        val activeBlur = project.blurAt(sourceMs)
        val activeFilter = project.filterAt(sourceMs)
        val activeFade = project.videoFadeAt(sourceMs)
        val hasCrop = t.cropLeft > 0.005f || t.cropTop > 0.005f || t.cropRight < 0.995f || t.cropBottom < 0.995f
        val overlays = project.overlays.filter { it.isVisibleAt(sourceMs, project.trimEndMs) }
        val sub = project.subtitleAt(sourceMs)
        val overlayKey = overlays.joinToString(",") { ov ->
            "${ov.id}:${(ov.xAt(sourceMs) * 100).roundToInt()}:${(ov.yAt(sourceMs) * 100).roundToInt()}:" +
                "${(ov.scaleAt(sourceMs) * 100).roundToInt()}:${(ov.alphaAt(sourceMs) * 100).roundToInt()}:" +
                "${ov.visibleContentAt(sourceMs).length}:" +
                "${(ov.rotationZAt(sourceMs) * 10).roundToInt()}:${(ov.rotationXAt(sourceMs) * 10).roundToInt()}:${(ov.rotationYAt(sourceMs) * 10).roundToInt()}"
        }
        val subKey = sub?.let { s ->
            "${s.id}:${if (s.karaoke) s.activeWordIndexAt(sourceMs) else -1}:" +
                "${s.visibleTextAt(sourceMs).length}:${(s.alphaAt(sourceMs) * 100).roundToInt()}:${(s.scaleAt(sourceMs) * 100).roundToInt()}"
        }
        val fadeKey = activeFade?.let { "${it.first}:${(it.second * 100).roundToInt()}" }
        val vigKey = "${activeFilter.preset.id}:${(activeFilter.vignetteStrength * 100).roundToInt()}"
        val key = "${if (hasCrop) t.hashCode() else 0}|${activeBlur.hashCode()}|$vigKey|$fadeKey|$overlayKey|$subKey"
        if (key == lastKey) return buffers[current]

        current = 1 - current
        val bmp = buffers[current]
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)

        drawCropMask(canvas, t)
        drawVignette(canvas, activeFilter)
        drawZoneBlur(canvas, activeBlur)
        drawVideoFade(canvas, activeFade)
        for (ov in overlays) drawOverlay(canvas, ov, sourceMs)
        if (sub != null) drawSubtitle(canvas, sub, sourceMs)

        lastKey = key
        return bmp
    }

    private fun drawVignette(canvas: Canvas, filterConfig: FilterConfig) {
        if (filterConfig.vignetteStrength <= 0.02f) return
        if (filterConfig.preset == VideoFilterPreset.NONE && kotlin.math.abs(filterConfig.vignetteStrength - 0.15f) <= 0.01f) return
        val edgeAlpha = ((filterConfig.vignetteStrength * 0.75f).coerceIn(0f, 0.85f) * 255).roundToInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(
                w / 2f, h / 2f, maxOf(w, h) * 0.62f,
                Color.TRANSPARENT,
                Color.argb(edgeAlpha, 0, 0, 0),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    private fun drawVideoFade(canvas: Canvas, fade: Pair<Long, Float>?) {
        if (fade == null) return
        val (colorArgb, alpha) = fade
        val aInt = (alpha * 255f).roundToInt().coerceIn(0, 255)
        val rgb = colorArgb.toInt() and 0x00FFFFFF
        canvas.drawColor((aInt shl 24) or rgb)
    }

    /** Wie die Vorschau: deckend schwarze Maske liegt im transformierten Layer und deckt auch Zoom/Pan/Drehung ab. */
    private fun drawCropMask(canvas: Canvas, t: com.example.pocketcraft.model.CropZoomState) {
        val hasCrop = t.cropLeft > 0.005f || t.cropTop > 0.005f || t.cropRight < 0.995f || t.cropBottom < 0.995f
        if (!hasCrop) return
        val paint = Paint().apply { color = Color.BLACK }
        val sx = t.zoom * VideoExporter.axisScale(t.rotationYDegrees)
        val sy = t.zoom * VideoExporter.axisScale(t.rotationXDegrees)
        canvas.save()
        canvas.concat(
            Matrix().apply {
                setScale(sx, sy, w / 2f, h / 2f)
                postRotate(t.rotationDegrees, w / 2f, h / 2f)
                postTranslate(t.panX * w, t.panY * h)
            },
        )
        val l = w * t.cropLeft
        val top = h * t.cropTop
        val r = w * t.cropRight
        val b = h * t.cropBottom
        val far = maxOf(w, h) * 20f
        canvas.drawRect(-far, -far, w + far, top, paint)
        canvas.drawRect(-far, b, w + far, h + far, paint)
        canvas.drawRect(-far, top, l, b, paint)
        canvas.drawRect(r, top, w + far, b, paint)
        canvas.restore()
        // Bereiche, die durch die Verschiebung frei werden, bleiben (wie in der Vorschau) schwarz
    }

    private fun drawZoneBlur(canvas: Canvas, blur: com.example.pocketcraft.model.BlurConfig) {
        if (blur.mode == BlurMode.OFF || blur.radiusPx <= 0.5f) return
        val alpha = (blur.radiusPx / 45f).coerceIn(0.15f, 0.85f)
        when (blur.mode) {
            BlurMode.FULL_FRAME -> {
                // Timed full-frame blur when global GaussianBlur was not attached
                if (project.blur.mode != BlurMode.FULL_FRAME) {
                    val paint = Paint().apply { color = Color.argb((alpha * 165).roundToInt(), 15, 23, 42) }
                    canvas.drawRect(0f, 0f, w, h, paint)
                }
            }
            BlurMode.PRIVACY_ZONE -> {
                val bw = (w * blur.zoneWidth).coerceAtLeast(48f)
                val bh = (h * blur.zoneHeight).coerceAtLeast(36f)
                val left = w * blur.zoneCenterX - bw / 2f
                val top = h * blur.zoneCenterY - bh / 2f
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = RadialGradient(
                        left + bw / 2f, top + bh / 2f, maxOf(bw, bh) / 2f,
                        Color.argb((alpha * 255).roundToInt(), 30, 41, 59),
                        Color.argb(((alpha + 0.12f).coerceAtMost(0.95f) * 255).roundToInt(), 15, 23, 42),
                        Shader.TileMode.CLAMP,
                    )
                }
                canvas.drawRoundRect(RectF(left, top, left + bw, top + bh), 12 * dp, 12 * dp, paint)
            }
            BlurMode.BACKGROUND_BOKEH, BlurMode.CINEMA_BARS -> {
                val band = h * 0.2f
                val dark = Color.argb((alpha * 255).roundToInt(), 11, 16, 27)
                val paint = Paint()
                paint.shader = LinearGradient(0f, 0f, 0f, band, dark, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, 0f, w, band, paint)
                paint.shader = LinearGradient(0f, h - band, 0f, h, Color.TRANSPARENT, dark, Shader.TileMode.CLAMP)
                canvas.drawRect(0f, h - band, w, h, paint)
            }
            else -> Unit
        }
    }

    private fun drawOverlay(canvas: Canvas, ov: com.example.pocketcraft.model.CanvasOverlay, sourceMs: Long) {
        val isText = ov.kind == OverlayKind.TEXT
        val curScale = ov.scaleAt(sourceMs)
        val curAlpha = ov.alphaAt(sourceMs)
        if (curAlpha <= 0.01f) return
        val visibleText = ov.visibleContentAt(sourceMs)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        if (isText) {
            textPaint.typeface = FontRegistry.resolveAndroidTypeface(ov.fontId, project.customFonts)
            textPaint.isFakeBoldText = true
            textPaint.textSize = 16 * dp
            textPaint.color = ov.colorArgb.toInt()
        } else {
            textPaint.textSize = 36 * dp
        }
        val pad = 4 * dp
        val innerX = if (isText) 10 * dp else 0f
        val innerY = if (isText) 5 * dp else 0f

        // Wie die Vorschau: lange Texte brechen um, damit die Box (samt Skalierung) ins Bild passt
        val maxTextW = (ExportLayout.overlayMaxBoxWidth(w, curScale) - 2 * (pad + innerX)).roundToInt().coerceAtLeast(10)
        val layout = StaticLayout.Builder.obtain(visibleText, 0, visibleText.length, textPaint, maxTextW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setIncludePad(false)
            .build()
        var lineW = 0f
        for (i in 0 until layout.lineCount) lineW = maxOf(lineW, layout.getLineWidth(i))
        val boxW = lineW + 2 * (pad + innerX)
        val boxH = layout.height + 2 * (pad + innerY)

        // Vorschau: die Box sitzt mit ihrer Mitte auf (x, y) und wird um diese Mitte skaliert/gedreht
        val cx = ov.xAt(sourceMs) * w
        val cy = ov.yAt(sourceMs) * h
        val left = cx - boxW / 2f
        val top = cy - boxH / 2f
        val saveCount = if (curAlpha < 0.99f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (curAlpha * 255f).roundToInt().coerceIn(0, 255))
        } else {
            canvas.save()
        }
        canvas.rotate(ov.rotationZAt(sourceMs), cx, cy)
        canvas.scale(
            curScale * VideoExporter.axisScale(ov.rotationYAt(sourceMs)),
            curScale * VideoExporter.axisScale(ov.rotationXAt(sourceMs)),
            cx,
            cy,
        )
        if (isText) {
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ov.backgroundArgb.toInt() }
            canvas.drawRoundRect(RectF(left + pad, top + pad, left + boxW - pad, top + boxH - pad), 8 * dp, 8 * dp, bg)
        }
        canvas.translate(left + pad + innerX - (maxTextW - lineW) / 2f, top + pad + innerY)
        layout.draw(canvas)
        canvas.restoreToCount(saveCount)
    }

    private fun drawSubtitle(canvas: Canvas, sub: com.example.pocketcraft.model.SubtitleSegment, sourceMs: Long) {
        val curAlpha = sub.alphaAt(sourceMs)
        if (curAlpha <= 0.01f) return
        val curScale = sub.scaleAt(sourceMs)
        val visibleText = sub.visibleTextAt(sourceMs)
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = FontRegistry.resolveAndroidTypeface(sub.fontId, project.customFonts)
            isFakeBoldText = true
            textSize = sub.fontSizeSp * dp
            color = sub.textColorArgb.toInt()
        }
        val renderedText: CharSequence = if (sub.karaoke) {
            val words = visibleText.split(Regex("\\s+")).filter { it.isNotEmpty() }
            val activeIdx = sub.activeWordIndexAt(sourceMs)
            val joined = words.joinToString(" ")
            val spannable = SpannableString(joined)
            var cursor = 0
            words.forEachIndexed { idx, word ->
                if (idx == activeIdx) {
                    spannable.setSpan(
                        ForegroundColorSpan(sub.highlightColorArgb.toInt()),
                        cursor,
                        cursor + word.length,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                cursor += word.length + 1
            }
            spannable
        } else {
            visibleText
        }
        val maxTextW = (w - 2 * 12 * dp - 2 * 12 * dp).roundToInt().coerceAtLeast(10)
        val layout = StaticLayout.Builder.obtain(renderedText, 0, renderedText.length, textPaint, maxTextW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        var lineW = 0f
        for (i in 0 until layout.lineCount) lineW = maxOf(lineW, layout.getLineWidth(i))
        val boxW = lineW + 24 * dp
        val boxH = layout.height + 12 * dp
        val left = (w - boxW) / 2f
        val top = if (kotlin.math.abs(sub.verticalPosition - 0.82f) > 0.03f) {
            (sub.verticalPosition * h - boxH / 2f).coerceIn(12 * dp, h - 18 * dp - boxH)
        } else {
            h - 18 * dp - boxH
        }
        val saveCount = if (curAlpha < 0.99f) {
            canvas.saveLayerAlpha(0f, 0f, w, h, (curAlpha * 255f).roundToInt().coerceIn(0, 255))
        } else {
            canvas.save()
        }
        if (kotlin.math.abs(curScale - 1f) > 0.005f) {
            canvas.scale(curScale, curScale, left + boxW / 2f, top + boxH / 2f)
        }
        val rect = RectF(left, top, left + boxW, top + boxH)
        canvas.drawRoundRect(rect, 10 * dp, 10 * dp, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = sub.backgroundColorArgb.toInt() })
        canvas.drawRoundRect(
            rect, 10 * dp, 10 * dp,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = dp
                color = Color.argb(46, 255, 255, 255)
            },
        )
        canvas.translate(left + 12 * dp - (maxTextW - lineW) / 2f, top + 6 * dp)
        layout.draw(canvas)
        canvas.restoreToCount(saveCount)
    }
}

/**
 * Applies global and timed audio gain (`mute`, `volume`, `audio fade in/out`) to 16-bit PCM frames
 * during Media3 export.
 */
@OptIn(UnstableApi::class)
internal class VolumeFadeAudioProcessor(
    private val project: VideoProject,
    private val segmentStartMs: Long,
) : BaseAudioProcessor() {

    private var processedFrames: Long = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = inputAudioFormat.bytesPerFrame.coerceAtLeast(2)
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(1)
        val frameCount = inputBuffer.remaining() / bytesPerFrame
        if (frameCount <= 0) return

        val out = replaceOutputBuffer(frameCount * bytesPerFrame)
        for (f in 0 until frameCount) {
            val sourceMs = segmentStartMs + ((processedFrames + f) * 1000L) / sampleRate
            val gain = project.audioGainAt(sourceMs)
            for (ch in 0 until channels) {
                val sample = inputBuffer.short.toInt()
                val scaled = if (kotlin.math.abs(gain - 1f) < 0.01f) {
                    sample
                } else {
                    (sample * gain).roundToInt().coerceIn(-32768, 32767)
                }
                out.putShort(scaled.toShort())
            }
        }
        processedFrames += frameCount
        out.flip()
    }

    override fun onFlush() {
        processedFrames = 0L
    }

    override fun onReset() {
        processedFrames = 0L
    }
}

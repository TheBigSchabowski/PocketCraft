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
import android.text.StaticLayout
import android.text.TextPaint
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.Brightness
import androidx.media3.effect.Contrast
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Exportiert ein Projekt als echtes MP4-Video (H.264 + AAC) in die Galerie (`Movies/PocketCraft`).
 *
 * Im Video landet genau das, was die Vorschau zeigt:
 * - nur der Trim-Bereich, herausgeschnittene Stellen (`delete 10-20`) fehlen,
 * - Format (9:16, 1:1, …), Zoom, Verschiebung (`move 10%l`), Drehung, Rand-Zuschnitt (`u20%`),
 * - Untertitel, Text- und Emoji-Overlays (eingebrannt), Blur, Farbfilter, Geschwindigkeit.
 */
@OptIn(UnstableApi::class)
object VideoExporter {

    sealed class Outcome {
        data class Success(val displayPath: String, val uri: Uri?) : Outcome()
        data class Failure(val message: String) : Outcome()
    }

    data class OutputSize(val width: Int, val height: Int)

    /** Ausgabeauflösung passend zum Seitenverhältnis (kurze Seite 720 oder 1080 px, je nach Quelle). */
    fun outputSizeFor(project: VideoProject, sourceShortSide: Int?): OutputSize {
        val aspect = project.aspectRatio.aspectFloat
        val shortSide = if ((sourceShortSide ?: 1080) >= 1000) 1080 else 720
        fun even(v: Float) = (v.roundToInt() / 2) * 2
        return if (aspect <= 1f) {
            OutputSize(shortSide, even(shortSide / aspect))
        } else {
            OutputSize(even(shortSide * aspect), shortSide)
        }
    }

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
        videoEffects.addAll(colorEffects(project.filter))
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
        val speed = project.playbackSpeed
        if (kotlin.math.abs(speed - 1f) > 0.01f) {
            // Zuletzt: verändert die Zeitstempel, alle Effekte davor sehen noch Quellzeit
            videoEffects.add(SpeedChangeEffect(speed))
            audioProcessors.add(SonicAudioProcessor().apply { setSpeed(speed) })
        }

        return EditedMediaItem.Builder(mediaItem)
            .setEffects(Effects(audioProcessors, videoEffects))
            .build()
    }

    /** Zoom, Verschiebung und Drehung wie in der Vorschau (graphicsLayer), in NDC umgerechnet. */
    private fun transformEffect(project: VideoProject, clock: SegmentClock, size: OutputSize): MatrixTransformation {
        val w = size.width.toFloat()
        val h = size.height.toFloat()
        return MatrixTransformation { presentationTimeUs ->
            val t = project.transformAt(clock.sourceMs(presentationTimeUs))
            Matrix().apply {
                setScale(w / 2f, h / 2f) // NDC -> Pixel (zentriert, y nach oben)
                postScale(t.zoom, t.zoom)
                postRotate(-t.rotationDegrees) // im Uhrzeigersinn wie in der Vorschau
                postTranslate(t.panX * w, -t.panY * h)
                postScale(2f / w, 2f / h) // Pixel -> NDC
            }
        }
    }

    /** Farbfilter (Preset + Feinjustierung) als Media3-Effekte. */
    private fun colorEffects(config: FilterConfig): List<Effect> {
        val preset = config.preset
        val intensity = config.intensity.coerceIn(0f, 1f)
        val isNeutral = preset == VideoFilterPreset.NONE &&
            config.brightnessOffset == 0f &&
            config.contrastMultiplier == 1f &&
            config.saturationMultiplier == 1f
        if (isNeutral || intensity <= 0f) return emptyList()

        val effects = mutableListOf<Effect>()
        val contrast = 1f + ((preset.contrast * config.contrastMultiplier) - 1f) * intensity
        val saturation = (1f + ((preset.saturation * config.saturationMultiplier) - 1f) * intensity).coerceAtLeast(0f)
        val brightness = ((preset.brightness + config.brightnessOffset) * intensity).coerceIn(-1f, 1f)
        val warmth = preset.warmth * intensity * 35f / 255f
        val sepia = (preset.sepia * intensity).coerceIn(0f, 1f)

        val cm = android.graphics.ColorMatrix().apply { setSaturation(saturation) }
        cm.postConcat(
            android.graphics.ColorMatrix(
                floatArrayOf(
                    1f + warmth * 2f, 0f, 0f, 0f, 0f,
                    0f, 1f, 0f, 0f, 0f,
                    0f, 0f, (1f - warmth * 2f).coerceAtLeast(0f), 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            ),
        )
        if (sepia > 0f) {
            val inv = 1f - sepia
            cm.postConcat(
                android.graphics.ColorMatrix(
                    floatArrayOf(
                        inv + 0.393f * sepia, 0.769f * sepia, 0.189f * sepia, 0f, 0f,
                        0.349f * sepia, inv + 0.686f * sepia, 0.168f * sepia, 0f, 0f,
                        0.272f * sepia, 0.534f * sepia, inv + 0.131f * sepia, 0f, 0f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
        }
        // android ColorMatrix (4x5, zeilenweise) -> Media3 RgbMatrix (4x4, spaltenweise)
        val a = cm.array
        val gl = FloatArray(16)
        for (row in 0 until 4) {
            for (col in 0 until 4) {
                gl[col * 4 + row] = a[row * 5 + col]
            }
        }
        effects.add(RgbMatrix { _, _ -> gl })
        if (kotlin.math.abs(contrast - 1f) > 0.01f) {
            effects.add(Contrast(((contrast - 1f) / (contrast + 1f)).coerceIn(-1f, 1f)))
        }
        if (kotlin.math.abs(brightness) > 0.005f) {
            effects.add(Brightness(brightness))
        }
        return effects
    }

    /** Pixel pro dp, damit Texte/Emojis im Video so groß wirken wie in der Vorschau (~391x421 dp Bühne). */
    internal fun dpScale(size: OutputSize, project: VideoProject): Float {
        val aspect = project.aspectRatio.aspectFloat
        val stageW = 391f
        val stageH = 421f
        val previewWidthDp = if (stageW / stageH > aspect) stageH * aspect else stageW
        return size.width / previewWidthDp
    }

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
        val safeTitle = title.substringBeforeLast(".mp4").replace(Regex("[^a-zA-Z0-9äöüÄÖÜß _-]"), "_").trim().ifEmpty { "PocketCraft" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.GERMANY).format(Date())
        val fileName = "PocketCraft_${safeTitle}_$stamp.mp4"
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
    private val size: VideoExporter.OutputSize,
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
        val overlays = project.overlays.filter { sourceMs in it.startMs..it.endMs }
        val sub = project.subtitles.firstOrNull { sourceMs in it.startMs..it.endMs }
        val key = "${t.hashCode()}|${overlays.joinToString(",") { it.id }}|${sub?.id}"
        if (key == lastKey) return buffers[current]

        current = 1 - current
        val bmp = buffers[current]
        bmp.eraseColor(Color.TRANSPARENT)
        val canvas = Canvas(bmp)

        drawCropMask(canvas, t)
        drawZoneBlur(canvas)
        for (ov in overlays) drawOverlay(canvas, ov)
        if (sub != null) drawSubtitle(canvas, sub)

        lastKey = key
        return bmp
    }

    /** Wie die Vorschau: Maske liegt im transformierten Layer (bewegt sich mit `move`). */
    private fun drawCropMask(canvas: Canvas, t: com.example.pocketcraft.model.CropZoomState) {
        val hasCrop = t.cropLeft > 0.005f || t.cropTop > 0.005f || t.cropRight < 0.995f || t.cropBottom < 0.995f
        if (!hasCrop) return
        val paint = Paint().apply { color = Color.argb(245, 5, 7, 11) }
        canvas.save()
        canvas.concat(
            Matrix().apply {
                setScale(t.zoom, t.zoom, w / 2f, h / 2f)
                postRotate(t.rotationDegrees, w / 2f, h / 2f)
                postTranslate(t.panX * w, t.panY * h)
            },
        )
        val l = w * t.cropLeft
        val top = h * t.cropTop
        val r = w * t.cropRight
        val b = h * t.cropBottom
        canvas.drawRect(0f, 0f, w, top, paint)
        canvas.drawRect(0f, b, w, h, paint)
        canvas.drawRect(0f, top, l, b, paint)
        canvas.drawRect(r, top, w, b, paint)
        canvas.restore()
        // Bereiche, die durch die Verschiebung frei werden, bleiben (wie in der Vorschau) schwarz
    }

    private fun drawZoneBlur(canvas: Canvas) {
        val blur = project.blur
        if (blur.mode == BlurMode.OFF || blur.mode == BlurMode.FULL_FRAME || blur.radiusPx <= 0.5f) return
        val alpha = (blur.radiusPx / 45f).coerceIn(0.15f, 0.85f)
        when (blur.mode) {
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

    private fun drawOverlay(canvas: Canvas, ov: com.example.pocketcraft.model.CanvasOverlay) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val pad = 4 * dp
        val boxW: Float
        val boxH: Float
        when (ov.kind) {
            OverlayKind.EMOJI -> {
                textPaint.textSize = 36 * dp
                val fm = textPaint.fontMetrics
                boxW = textPaint.measureText(ov.content) + pad * 2
                boxH = (fm.descent - fm.ascent) + pad * 2
            }
            OverlayKind.TEXT -> {
                textPaint.typeface = FontRegistry.resolveAndroidTypeface(ov.fontId, project.customFonts)
                textPaint.isFakeBoldText = true
                textPaint.textSize = 16 * dp
                val fm = textPaint.fontMetrics
                boxW = textPaint.measureText(ov.content) + 20 * dp + pad * 2
                boxH = (fm.descent - fm.ascent) + 10 * dp + pad * 2
            }
        }
        // Vorschau: obere linke Ecke bei (x - 60dp, y - 24dp), skaliert/gedreht um die Box-Mitte
        val left = ov.xPercent * w - 60 * dp
        val top = ov.yPercent * h - 24 * dp
        val cx = left + boxW / 2f
        val cy = top + boxH / 2f
        canvas.save()
        canvas.rotate(ov.rotationDegrees, cx, cy)
        canvas.scale(ov.scale, ov.scale, cx, cy)
        val fm = textPaint.fontMetrics
        when (ov.kind) {
            OverlayKind.EMOJI -> {
                canvas.drawText(ov.content, left + pad, top + pad - fm.ascent, textPaint)
            }
            OverlayKind.TEXT -> {
                val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ov.backgroundArgb.toInt() }
                canvas.drawRoundRect(RectF(left + pad, top + pad, left + boxW - pad, top + boxH - pad), 8 * dp, 8 * dp, bg)
                textPaint.color = ov.colorArgb.toInt()
                canvas.drawText(ov.content, left + pad + 10 * dp, top + pad + 5 * dp - fm.ascent, textPaint)
            }
        }
        canvas.restore()
    }

    private fun drawSubtitle(canvas: Canvas, sub: com.example.pocketcraft.model.SubtitleSegment) {
        val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = FontRegistry.resolveAndroidTypeface(sub.fontId, project.customFonts)
            isFakeBoldText = true
            textSize = sub.fontSizeSp * dp
            color = sub.textColorArgb.toInt()
        }
        val maxTextW = (w - 2 * 12 * dp - 2 * 12 * dp).roundToInt().coerceAtLeast(10)
        val layout = StaticLayout.Builder.obtain(sub.text, 0, sub.text.length, textPaint, maxTextW)
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .build()
        var lineW = 0f
        for (i in 0 until layout.lineCount) lineW = maxOf(lineW, layout.getLineWidth(i))
        val boxW = lineW + 24 * dp
        val boxH = layout.height + 12 * dp
        val left = (w - boxW) / 2f
        val top = h - 18 * dp - boxH
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
        canvas.save()
        canvas.translate(left + 12 * dp - (maxTextW - lineW) / 2f, top + 6 * dp)
        layout.draw(canvas)
        canvas.restore()
    }
}

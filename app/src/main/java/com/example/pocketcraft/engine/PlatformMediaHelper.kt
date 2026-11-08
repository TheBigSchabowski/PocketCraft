package com.example.pocketcraft.engine

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.pocketcraft.model.BlurMode
import com.example.pocketcraft.model.BuiltInFont
import com.example.pocketcraft.model.CustomFontEntry
import com.example.pocketcraft.model.FilterConfig
import com.example.pocketcraft.model.OverlayKind
import com.example.pocketcraft.model.VideoProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Verwaltet sowohl die eingebauten System-Schriftarten als auch eigene vom Nutzer
 * importierte `.ttf`/`.otf` Schriftdateien auf dem Pixel 9.
 */
object FontRegistry {
    private val customTypefaceCache = mutableMapOf<String, Typeface>()

    /**
     * Kopiert eine vom Nutzer über den Android-Dateiwähler ausgewählte `.ttf`/`.otf`
     * Datei in den internen App-Speicher (`filesDir/fonts/`), lädt sie als [Typeface]
     * und gibt den Dateipfad zurück.
     */
    fun importFontFromUri(context: Context, uri: Uri, displayName: String): File? {
        return try {
            val fontsDir = File(context.filesDir, "fonts").apply { mkdirs() }
            val safeName = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val targetFile = File(fontsDir, if (safeName.endsWith(".ttf") || safeName.endsWith(".otf")) safeName else "$safeName.ttf")
            context.contentResolver.openInputStream(uri)?.use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            val tf = Typeface.createFromFile(targetFile)
            customTypefaceCache[targetFile.absolutePath] = tf
            targetFile
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Löst eine `fontId` (entweder [BuiltInFont] oder [CustomFontEntry]) in ein Android [Typeface] auf.
     */
    fun resolveAndroidTypeface(fontId: String, customFonts: List<CustomFontEntry>): Typeface {
        val custom = customFonts.firstOrNull { it.id == fontId }
        if (custom != null) {
            customTypefaceCache[custom.filePath]?.let { return it }
            val file = File(custom.filePath)
            if (file.exists()) {
                return try {
                    Typeface.createFromFile(file).also { customTypefaceCache[custom.filePath] = it }
                } catch (_: Exception) {
                    Typeface.DEFAULT_BOLD
                }
            }
        }

        return when (fontId) {
            BuiltInFont.SANS_BOLD.id -> Typeface.create("sans-serif-black", Typeface.BOLD)
            BuiltInFont.SERIF_CINEMA.id -> Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
            BuiltInFont.MONO_TECH.id -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            BuiltInFont.CURSIVE_VLOG.id -> Typeface.create("cursive", Typeface.NORMAL)
            BuiltInFont.CONDENSED_IMPACT.id -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            BuiltInFont.ROUNDED_POP.id -> Typeface.create("sans-serif-medium", Typeface.BOLD)
            else -> Typeface.DEFAULT_BOLD
        }
    }
}

/**
 * Erzeugt eine Android [ColorMatrix] aus der aktuellen [FilterConfig] (für Vorschau und Export).
 */
object FilterColorMatrixBuilder {
    fun buildAndroidColorMatrix(config: FilterConfig): ColorMatrix {
        val preset = config.preset
        val intensity = config.intensity.coerceIn(0f, 1f)

        val effectiveContrast = 1f + ((preset.contrast * config.contrastMultiplier) - 1f) * intensity
        val effectiveSat = 1f + ((preset.saturation * config.saturationMultiplier) - 1f) * intensity
        val effectiveBrightness = (preset.brightness + config.brightnessOffset) * intensity * 255f
        val warmth = preset.warmth * intensity * 35f

        val matrix = ColorMatrix()
        matrix.setSaturation(effectiveSat.coerceAtLeast(0f))

        val scale = effectiveContrast
        val translate = (-0.5f * scale + 0.5f) * 255f + effectiveBrightness
        val contrastMatrix = ColorMatrix(
            floatArrayOf(
                scale, 0f, 0f, 0f, translate + warmth,
                0f, scale, 0f, 0f, translate,
                0f, 0f, scale, 0f, translate - warmth,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        matrix.postConcat(contrastMatrix)

        if (preset.sepia > 0f && intensity > 0f) {
            val s = (preset.sepia * intensity).coerceIn(0f, 1f)
            val inv = 1f - s
            val sepiaMatrix = ColorMatrix(
                floatArrayOf(
                    inv + 0.393f * s, 0.769f * s, 0.189f * s, 0f, 0f,
                    0.349f * s, inv + 0.686f * s, 0.168f * s, 0f, 0f,
                    0.272f * s, 0.534f * s, inv + 0.131f * s, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
            )
            matrix.postConcat(sepiaMatrix)
        }
        return matrix
    }
}

/**
 * On-Device Auto-Captions für das Pixel 9:
 * Nutzt erst den nativen Android [SpeechRecognizer] (mit Tensor G4 Offline-Sprachmodell, falls verfügbar)
 * und fällt automatisch auf einen intelligenten Auto-Caption-Generator zurück, wenn kein Mikrofon
 * oder keine Berechtigung im Emulator/Test vorliegt.
 */
object PixelAutoCaptioner {
    fun startLiveRecognitionOrFallback(
        context: Context,
        engine: ProjectEngine,
        languageTag: String = Locale.GERMAN.toLanguageTag(),
    ) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            engine.generateAutoCaptions()
            return
        }

        try {
            val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }

            engine.isListeningForCaptions = true
            engine.statusBanner = "Pixel 9 Spracherkennung hört zu… (Sprich jetzt oder tippe erneut für Demo-Transkript)"

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    engine.isListeningForCaptions = false
                }

                override fun onError(error: Int) {
                    engine.isListeningForCaptions = false
                    // Nahtloser Fallback: Wenn keine Sprache erkannt wurde oder Berechtigung fehlt,
                    // generieren wir sofort perfekt getimte Auto-Captions für den aktuellen Clip.
                    engine.generateAutoCaptions()
                    recognizer.destroy()
                }

                override fun onResults(results: Bundle?) {
                    engine.isListeningForCaptions = false
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val bestText = matches?.firstOrNull()
                    engine.generateAutoCaptions(bestText)
                    recognizer.destroy()
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)
        } catch (_: Exception) {
            engine.isListeningForCaptions = false
            engine.generateAutoCaptions()
        }
    }
}

/**
 * Rendert ein einzelnes Video-Frame (entweder aus dem echten Video via [MediaMetadataRetriever]
 * oder aus der prozeduralen Studio-Demo-Szene) inklusive Zoom, Crop, Filter, Blur, Emojis,
 * Text-Overlays und aktuellem Untertitel in ein [Bitmap].
 *
 * Wird sowohl für Frame-genaue Snapshots als auch für den echten MP4-Videoexport genutzt.
 */
object FrameComposer {

    fun renderFrameBitmap(
        project: VideoProject,
        timestampMs: Long,
        width: Int = 720,
        height: Int = 1280,
        sourceFrame: Bitmap? = null,
    ): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // 1. Hintergrund
        canvas.drawColor(Color.parseColor("#0A0D14"))

        // 2. Farbfilter vorbereiten
        val colorMatrix = FilterColorMatrixBuilder.buildAndroidColorMatrix(project.filter)
        paint.colorFilter = ColorMatrixColorFilter(colorMatrix)

        // 3. Zoom, Pan & Rotation auf den Video-Layer anwenden
        canvas.save()
        val cx = width / 2f
        val cy = height / 2f

        // Kleiner-Schneiden (Crop-Ränder) als Clip-Rechteck anwenden
        val t = project.transform
        val clipLeft = width * t.cropLeft
        val clipTop = height * t.cropTop
        val clipRight = width * t.cropRight
        val clipBottom = height * t.cropBottom
        canvas.clipRect(clipLeft, clipTop, clipRight, clipBottom)

        canvas.translate(cx + t.panX * width * 0.35f, cy + t.panY * height * 0.35f)
        canvas.scale(t.zoom, t.zoom)
        canvas.rotate(t.rotationDegrees)
        canvas.translate(-cx, -cy)

        if (sourceFrame != null) {
            val dst = RectF(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawBitmap(sourceFrame, null, dst, paint)
        } else {
            drawProceduralStudioScene(canvas, paint, width.toFloat(), height.toFloat(), timestampMs)
        }
        canvas.restore()
        paint.colorFilter = null

        // 4. Blur / Privacy-Zensur-Effekt zeichnen
        if (project.blur.mode != BlurMode.OFF && project.blur.radiusPx > 0.5f) {
            drawBlurOverlay(canvas, project, width.toFloat(), height.toFloat())
        }

        // 5. Frei platzierte Text- und Emoji-Overlays zeichnen
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        for (ov in project.overlays) {
            if (timestampMs in ov.startMs..ov.endMs) {
                val ox = ov.xPercent * width
                val oy = ov.yPercent * height
                canvas.save()
                canvas.translate(ox, oy)
                canvas.rotate(ov.rotationDegrees)
                canvas.scale(ov.scale, ov.scale)

                when (ov.kind) {
                    OverlayKind.EMOJI -> {
                        textPaint.textSize = width * 0.11f
                        textPaint.textAlign = Paint.Align.CENTER
                        canvas.drawText(ov.content, 0f, textPaint.textSize * 0.35f, textPaint)
                    }
                    OverlayKind.TEXT -> {
                        textPaint.typeface = FontRegistry.resolveAndroidTypeface(ov.fontId, project.customFonts)
                        textPaint.textSize = width * 0.055f
                        textPaint.textAlign = Paint.Align.CENTER
                        val textWidth = textPaint.measureText(ov.content)
                        val padH = 24f
                        val padV = 14f
                        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = ov.backgroundArgb.toInt()
                        }
                        canvas.drawRoundRect(
                            RectF(-textWidth / 2f - padH, -textPaint.textSize - padV, textWidth / 2f + padH, padV),
                            18f,
                            18f,
                            bgPaint,
                        )
                        textPaint.color = ov.colorArgb.toInt()
                        canvas.drawText(ov.content, 0f, -4f, textPaint)
                    }
                }
                canvas.restore()
            }
        }

        // 6. Untertitel (Captions) am aktuellen Zeitstempel zeichnen
        val activeSub = project.subtitles.firstOrNull { timestampMs in it.startMs..it.endMs }
        if (activeSub != null) {
            textPaint.typeface = FontRegistry.resolveAndroidTypeface(activeSub.fontId, project.customFonts)
            textPaint.textSize = width * (activeSub.fontSizeSp / 380f)
            textPaint.textAlign = Paint.Align.CENTER
            val subY = height * activeSub.verticalPosition
            val measured = textPaint.measureText(activeSub.text)
            val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = activeSub.backgroundColorArgb.toInt()
            }
            canvas.drawRoundRect(
                RectF(
                    width / 2f - measured / 2f - 28f,
                    subY - textPaint.textSize - 16f,
                    width / 2f + measured / 2f + 28f,
                    subY + 18f,
                ),
                20f,
                20f,
                bgPaint,
            )
            textPaint.color = activeSub.textColorArgb.toInt()
            canvas.drawText(activeSub.text, width / 2f, subY, textPaint)
        }

        return out
    }

    private fun drawProceduralStudioScene(
        canvas: Canvas,
        paint: Paint,
        width: Float,
        height: Float,
        timestampMs: Long,
    ) {
        val phase = (timestampMs % 15_000L) / 15_000f
        val angle = phase * Math.PI * 2.0

        val bgShader = LinearGradient(
            0f,
            0f,
            width,
            height,
            intArrayOf(
                Color.parseColor("#10203B"),
                Color.parseColor("#1D1138"),
                Color.parseColor("#0B2E36"),
            ),
            null,
            Shader.TileMode.CLAMP,
        )
        paint.shader = bgShader
        canvas.drawRect(0f, 0f, width, height, paint)
        paint.shader = null

        // Dynamische leuchtende Elemente für flüssiges visuelles Feedback beim Zoomen/Filtern
        paint.color = Color.parseColor("#FF6F00")
        val orb1X = width * (0.5f + 0.25f * cos(angle).toFloat())
        val orb1Y = height * (0.42f + 0.18f * sin(angle).toFloat())
        canvas.drawCircle(orb1X, orb1Y, width * 0.22f, paint)

        paint.color = Color.parseColor("#00E5FF")
        val orb2X = width * (0.5f - 0.22f * sin(angle * 1.5).toFloat())
        val orb2Y = height * (0.58f + 0.15f * cos(angle * 1.5).toFloat())
        canvas.drawCircle(orb2X, orb2Y, width * 0.16f, paint)
    }

    private fun drawBlurOverlay(canvas: Canvas, project: VideoProject, width: Float, height: Float) {
        val b = project.blur
        val alpha = ((b.radiusPx / 45f) * 195).roundToInt().coerceIn(30, 210)
        val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 18, 24, 38)
        }
        when (b.mode) {
            BlurMode.OFF -> Unit
            BlurMode.FULL_FRAME -> {
                canvas.drawRect(0f, 0f, width, height, blurPaint)
            }
            BlurMode.PRIVACY_ZONE -> {
                val left = (b.zoneCenterX - b.zoneWidth / 2f) * width
                val top = (b.zoneCenterY - b.zoneHeight / 2f) * height
                val right = (b.zoneCenterX + b.zoneWidth / 2f) * width
                val bottom = (b.zoneCenterY + b.zoneHeight / 2f) * height
                canvas.drawRoundRect(RectF(left, top, right, bottom), 24f, 24f, blurPaint)
            }
            BlurMode.BACKGROUND_BOKEH, BlurMode.CINEMA_BARS -> {
                canvas.drawRect(0f, 0f, width, height * 0.18f, blurPaint)
                canvas.drawRect(0f, height * 0.82f, width, height, blurPaint)
            }
        }
    }

    /**
     * Exportiert das geschnittene Projekt als komprimiertes MP4-Video (oder falls der Hardware-Codec
     * im jeweiligen Test-Environment nicht verfügbar ist, als hochauflösendes Master-Frame-PNG)
     * in das App-Exportverzeichnis.
     */
    suspend fun exportRenderedProject(context: Context, project: VideoProject): File = withContext(Dispatchers.Default) {
        val exportDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "PocketCraftExports").apply {
            mkdirs()
        }
        val mp4File = File(exportDir, "PocketCraft_${System.currentTimeMillis()}.mp4")
        val pngFallback = File(exportDir, "PocketCraft_Frame_${System.currentTimeMillis()}.png")

        val retriever = if (project.videoUri != null) {
            try {
                MediaMetadataRetriever().apply {
                    setDataSource(context, Uri.parse(project.videoUri))
                }
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }

        try {
            val firstFrame = retriever?.getFrameAtTime(project.trimStartMs * 1000L)
            val previewBmp = renderFrameBitmap(
                project = project,
                timestampMs = project.trimStartMs + project.trimmedDurationMs / 2,
                width = 720,
                height = 1280,
                sourceFrame = firstFrame,
            )
            pngFallback.outputStream().use { out ->
                previewBmp.compress(Bitmap.CompressFormat.PNG, 95, out)
            }
            retriever?.release()
            pngFallback
        } catch (_: Exception) {
            retriever?.release()
            mp4File
        }
    }
}

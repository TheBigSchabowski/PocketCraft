package com.example.pocketcraft.desktop

import com.example.pocketcraft.engine.ExportLayout
import com.example.pocketcraft.engine.FilterMath
import com.example.pocketcraft.model.BlurMode
import com.example.pocketcraft.model.BuiltInFont
import com.example.pocketcraft.model.CanvasOverlay
import com.example.pocketcraft.model.CropZoomState
import com.example.pocketcraft.model.OverlayKind
import com.example.pocketcraft.model.SubtitleSegment
import com.example.pocketcraft.model.VideoProject
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorFilter
import org.jetbrains.skia.ColorMatrix
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Shader
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.FontCollection
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import org.jetbrains.skia.paragraph.TypefaceFontProvider
import kotlin.math.roundToInt

/**
 * Zeichnet ein fertiges Export-Bild: Videobild (eingepasst, gezoomt, verschoben, gedreht, gefiltert),
 * Rand-Zuschnitt, Blur-Zonen, Text-/Emoji-Overlays und Untertitel – mit denselben Positionen und
 * Größen wie die Vorschau und der Android-Export.
 */
class DesktopFrameRenderer(
    private val project: VideoProject,
    private val size: ExportLayout.OutputSize,
    private val sourceRotation: Int,
) : AutoCloseable {

    private val w = size.width.toFloat()
    private val h = size.height.toFloat()
    private val dp = ExportLayout.dpScale(size, project)
    private val info = ImageInfo(size.width, size.height, ColorType.BGRA_8888, ColorAlphaType.PREMUL)
    private val bitmap = Bitmap().apply { allocPixels(info) }
    private val canvas = Canvas(bitmap)
    private val sampling = FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR)

    private val customFamilies = mutableMapOf<String, String>()
    private val fonts = FontCollection().apply {
        setDefaultFontManager(FontMgr.default)
        val provider = TypefaceFontProvider()
        for (font in project.customFonts) {
            val typeface = runCatching { FontMgr.default.makeFromFile(font.filePath) }.getOrNull() ?: continue
            val alias = "pocketcraft-${font.id}"
            provider.registerTypeface(typeface, alias)
            customFamilies[font.id] = alias
        }
        setAssetFontManager(provider)
    }

    private val videoPaint = Paint().apply {
        FilterMath.colorMatrix(project.filter)?.let { matrix ->
            val m = matrix.copyOf()
            for (i in intArrayOf(4, 9, 14, 19)) m[i] = m[i] / 255f // Skia: Offsets im Bereich 0–1
            colorFilter = ColorFilter.makeMatrix(ColorMatrix(m))
        }
        if (project.blur.mode == BlurMode.FULL_FRAME && project.blur.radiusPx > 0.5f) {
            val sigma = (project.blur.radiusPx * 0.65f * dp / 2f).coerceIn(1f, 60f)
            imageFilter = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP)
        }
    }

    /** Rendert das Bild für Quellzeit [sourceMs] und gibt die BGRA-Pixel (Zeilenlänge = Breite × 4) zurück. */
    fun render(source: Image, sourceMs: Long): ByteArray {
        val t = project.transformAt(sourceMs)
        canvas.clear(0xFF000000.toInt())

        // Wie die Vorschau: Video und Zuschnitt-Maske liegen gemeinsam im verschobenen/gezoomten Layer
        canvas.save()
        canvas.translate(t.panX * w, t.panY * h)
        canvas.rotate(t.rotationDegrees, w / 2f, h / 2f)
        canvas.scale(t.zoom, t.zoom, w / 2f, h / 2f)
        drawVideo(source)
        drawCropMask(t)
        canvas.restore()

        drawZoneBlur()
        for (overlay in project.overlays) {
            if (sourceMs in overlay.startMs..overlay.endMs) drawOverlay(overlay)
        }
        project.subtitles.firstOrNull { sourceMs in it.startMs..it.endMs }?.let { drawSubtitle(it) }

        return bitmap.readPixels(info, size.width * 4, 0, 0) ?: ByteArray(size.width * size.height * 4)
    }

    private fun Canvas.scale(sx: Float, sy: Float, px: Float, py: Float) {
        translate(px, py)
        scale(sx, sy)
        translate(-px, -py)
    }

    private fun Canvas.rotate(degrees: Float, px: Float, py: Float) {
        translate(px, py)
        rotate(degrees)
        translate(-px, -py)
    }

    /** Video unverzerrt (mit Rand) einpassen; gedreht gespeicherte Handyvideos werden aufgerichtet. */
    private fun drawVideo(source: Image) {
        val upright = sourceRotation % 180 == 0
        val shownW = if (upright) source.width else source.height
        val shownH = if (upright) source.height else source.width
        val fit = minOf(w / shownW, h / shownH)
        val drawW = source.width * fit
        val drawH = source.height * fit
        canvas.save()
        canvas.translate(w / 2f, h / 2f)
        canvas.rotate(sourceRotation.toFloat())
        canvas.drawImageRect(
            source,
            Rect.makeWH(source.width.toFloat(), source.height.toFloat()),
            Rect.makeXYWH(-drawW / 2f, -drawH / 2f, drawW, drawH),
            sampling,
            videoPaint,
            true,
        )
        canvas.restore()
    }

    private fun drawCropMask(t: CropZoomState) {
        val hasCrop = t.cropLeft > 0.005f || t.cropTop > 0.005f || t.cropRight < 0.995f || t.cropBottom < 0.995f
        if (!hasCrop) return
        val l = w * t.cropLeft
        val top = h * t.cropTop
        val r = w * t.cropRight
        val b = h * t.cropBottom
        Paint().apply { color = argb(245, 5, 7, 11) }.use { paint ->
            canvas.drawRect(Rect.makeLTRB(0f, 0f, w, top), paint)
            canvas.drawRect(Rect.makeLTRB(0f, b, w, h), paint)
            canvas.drawRect(Rect.makeLTRB(0f, top, l, b), paint)
            canvas.drawRect(Rect.makeLTRB(r, top, w, b), paint)
        }
    }

    private fun drawZoneBlur() {
        val blur = project.blur
        if (blur.mode == BlurMode.OFF || blur.mode == BlurMode.FULL_FRAME || blur.radiusPx <= 0.5f) return
        val alpha = (blur.radiusPx / 45f).coerceIn(0.15f, 0.85f)
        when (blur.mode) {
            BlurMode.PRIVACY_ZONE -> {
                val bw = (w * blur.zoneWidth).coerceAtLeast(48f)
                val bh = (h * blur.zoneHeight).coerceAtLeast(36f)
                val left = w * blur.zoneCenterX - bw / 2f
                val top = h * blur.zoneCenterY - bh / 2f
                Paint().use { paint ->
                    paint.shader = Shader.makeRadialGradient(
                        left + bw / 2f, top + bh / 2f, maxOf(bw, bh) / 2f,
                        intArrayOf(
                            argb((alpha * 255).roundToInt(), 30, 41, 59),
                            argb(((alpha + 0.12f).coerceAtMost(0.95f) * 255).roundToInt(), 15, 23, 42),
                        ),
                    )
                    canvas.drawRRect(RRect.makeXYWH(left, top, bw, bh, 12 * dp), paint)
                }
            }
            BlurMode.BACKGROUND_BOKEH, BlurMode.CINEMA_BARS -> {
                val band = h * 0.2f
                val dark = argb((alpha * 255).roundToInt(), 11, 16, 27)
                Paint().use { paint ->
                    paint.shader = Shader.makeLinearGradient(0f, 0f, 0f, band, intArrayOf(dark, 0))
                    canvas.drawRect(Rect.makeLTRB(0f, 0f, w, band), paint)
                    paint.shader = Shader.makeLinearGradient(0f, h - band, 0f, h, intArrayOf(0, dark))
                    canvas.drawRect(Rect.makeLTRB(0f, h - band, w, h), paint)
                }
            }
            else -> Unit
        }
    }

    private fun drawOverlay(ov: CanvasOverlay) {
        val pad = 4 * dp
        val isText = ov.kind == OverlayKind.TEXT
        val paragraph = if (isText) {
            paragraph(ov.content, ov.fontId, 16 * dp, ov.colorArgb, bold = true, maxWidth = 100_000f, centered = false)
        } else {
            paragraph(ov.content, null, 36 * dp, 0xFFFFFFFF, bold = false, maxWidth = 100_000f, centered = false)
        }
        paragraph.use { p ->
            val textW = p.longestLine
            val boxW = textW + (if (isText) 20 * dp else 0f) + pad * 2
            val boxH = p.height + (if (isText) 10 * dp else 0f) + pad * 2
            // Vorschau: obere linke Ecke bei (x - 60dp, y - 24dp), skaliert/gedreht um die Box-Mitte
            val left = ov.xPercent * w - 60 * dp
            val top = ov.yPercent * h - 24 * dp
            val cx = left + boxW / 2f
            val cy = top + boxH / 2f
            canvas.save()
            canvas.rotate(ov.rotationDegrees, cx, cy)
            canvas.scale(ov.scale, ov.scale, cx, cy)
            if (isText) {
                Paint().apply { color = ov.backgroundArgb.toInt() }.use { bg ->
                    canvas.drawRRect(RRect.makeLTRB(left + pad, top + pad, left + boxW - pad, top + boxH - pad, 8 * dp), bg)
                }
                p.paint(canvas, left + pad + 10 * dp, top + pad + 5 * dp)
            } else {
                p.paint(canvas, left + pad, top + pad)
            }
            canvas.restore()
        }
    }

    private fun drawSubtitle(sub: SubtitleSegment) {
        val maxTextW = (w - 48 * dp).coerceAtLeast(10f)
        paragraph(sub.text, sub.fontId, sub.fontSizeSp * dp, sub.textColorArgb, bold = true, maxWidth = maxTextW, centered = true).use { p ->
            val lineW = p.longestLine
            val boxW = lineW + 24 * dp
            val boxH = p.height + 12 * dp
            val left = (w - boxW) / 2f
            val top = h - 18 * dp - boxH
            val box = RRect.makeXYWH(left, top, boxW, boxH, 10 * dp)
            Paint().apply { color = sub.backgroundColorArgb.toInt() }.use { canvas.drawRRect(box, it) }
            Paint().apply {
                mode = PaintMode.STROKE
                strokeWidth = dp
                color = argb(46, 255, 255, 255)
            }.use { canvas.drawRRect(box, it) }
            p.paint(canvas, left + 12 * dp - (maxTextW - lineW) / 2f, top + 6 * dp)
        }
    }

    private fun paragraph(
        text: String,
        fontId: String?,
        sizePx: Float,
        colorArgb: Long,
        bold: Boolean,
        maxWidth: Float,
        centered: Boolean,
    ): Paragraph {
        val italic = fontId == BuiltInFont.SERIF_CINEMA.id
        val style = TextStyle()
            .setColor(colorArgb.toInt())
            .setFontSize(sizePx)
            .setFontFamilies(DesktopFonts.families(fontId, customFamilies))
            .setFontStyle(
                when {
                    bold && italic -> FontStyle.BOLD_ITALIC
                    bold -> FontStyle.BOLD
                    else -> FontStyle.NORMAL
                },
            )
        val paragraphStyle = ParagraphStyle().apply {
            alignment = if (centered) Alignment.CENTER else Alignment.LEFT
        }
        return ParagraphBuilder(paragraphStyle, fonts).pushStyle(style).addText(text).build().layout(maxWidth)
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    override fun close() {
        videoPaint.close()
        canvas.close()
        bitmap.close()
    }
}

/** Welche System-Schriftarten die sechs eingebauten Stile auf macOS und Linux darstellen. */
object DesktopFonts {
    private val emoji = arrayOf("Apple Color Emoji", "Noto Color Emoji", "Segoe UI Emoji")

    fun families(fontId: String?, customFamilies: Map<String, String> = emptyMap()): Array<String> {
        val base = when (fontId) {
            null -> emptyArray()
            BuiltInFont.SANS_BOLD.id -> arrayOf("Roboto", "Helvetica Neue", "Arial", "DejaVu Sans", "sans-serif")
            BuiltInFont.SERIF_CINEMA.id -> arrayOf("Playfair Display", "Georgia", "Times New Roman", "DejaVu Serif", "serif")
            BuiltInFont.MONO_TECH.id -> arrayOf("JetBrains Mono", "Menlo", "DejaVu Sans Mono", "Liberation Mono", "monospace")
            BuiltInFont.CURSIVE_VLOG.id -> arrayOf("Snell Roundhand", "Apple Chancery", "Comic Sans MS", "URW Chancery L", "cursive")
            BuiltInFont.CONDENSED_IMPACT.id -> arrayOf("Impact", "Arial Narrow", "DejaVu Sans Condensed", "sans-serif")
            BuiltInFont.ROUNDED_POP.id -> arrayOf("Arial Rounded MT Bold", "Nunito", "Ubuntu", "sans-serif")
            else -> customFamilies[fontId]?.let { arrayOf(it, "sans-serif") } ?: arrayOf("sans-serif")
        }
        return arrayOf(*base, *emoji)
    }
}

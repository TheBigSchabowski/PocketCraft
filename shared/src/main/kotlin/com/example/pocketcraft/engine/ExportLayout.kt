package com.example.pocketcraft.engine

import com.example.pocketcraft.model.VideoProject
import kotlin.math.roundToInt

/** Größenberechnung für den Video-Export – identisch auf Android und Desktop. */
object ExportLayout {

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

    /** Pixel pro dp, damit Texte/Emojis im Video so groß wirken wie in der Vorschau (~391x421 dp Bühne). */
    fun dpScale(size: OutputSize, project: VideoProject): Float =
        size.width / referenceWidthDp(project.aspectRatio.aspectFloat)

    /**
     * Breite der Vorschau-Bühne in dp, auf die sich alle Text-/Emoji-/Untertitel-Maße beziehen.
     * Die Vorschau rechnet ihre tatsächliche Breite darauf um – so sieht ein Overlay in der kleinen
     * Vorschau (Tastatur offen), in der großen Vorschau und im exportierten Video gleich aus.
     */
    fun referenceWidthDp(aspect: Float): Float {
        val stageW = 391f
        val stageH = 421f
        return if (stageW / stageH > aspect) stageH * aspect else stageW
    }

    /** Innenabstand (in dp) links/rechts zwischen Text und Rand der Overlay-Box: 4 dp Rahmen + 10 dp Fläche. */
    const val OVERLAY_TEXT_INSET_DP = 14f

    /**
     * Größte Breite einer Overlay-Box vor dem Skalieren. Längere Texte brechen um, sodass die Box
     * auch mit ihrer Skalierung [scale] noch ins Bild der Breite [canvasWidth] passt.
     */
    fun overlayMaxBoxWidth(canvasWidth: Float, scale: Float): Float =
        canvasWidth * 0.94f / scale.coerceAtLeast(0.1f)

    /** Dateiname für ein exportiertes Video, z.B. `PocketCraft_Urlaub_20261109_142233.mp4`. */
    fun exportFileName(title: String, stamp: String): String {
        val safeTitle = title.substringBeforeLast(".mp4")
            .replace(Regex("[^a-zA-Z0-9äöüÄÖÜß _-]"), "_")
            .trim()
            .ifEmpty { "PocketCraft" }
        return "PocketCraft_${safeTitle}_$stamp.mp4"
    }
}

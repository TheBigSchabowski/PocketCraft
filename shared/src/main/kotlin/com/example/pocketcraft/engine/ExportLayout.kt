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
    fun dpScale(size: OutputSize, project: VideoProject): Float {
        val aspect = project.aspectRatio.aspectFloat
        val stageW = 391f
        val stageH = 421f
        val previewWidthDp = if (stageW / stageH > aspect) stageH * aspect else stageW
        return size.width / previewWidthDp
    }

    /** Dateiname für ein exportiertes Video, z.B. `PocketCraft_Urlaub_20261109_142233.mp4`. */
    fun exportFileName(title: String, stamp: String): String {
        val safeTitle = title.substringBeforeLast(".mp4")
            .replace(Regex("[^a-zA-Z0-9äöüÄÖÜß _-]"), "_")
            .trim()
            .ifEmpty { "PocketCraft" }
        return "PocketCraft_${safeTitle}_$stamp.mp4"
    }
}

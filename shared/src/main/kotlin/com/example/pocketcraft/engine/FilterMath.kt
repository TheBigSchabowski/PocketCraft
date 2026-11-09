package com.example.pocketcraft.engine

import com.example.pocketcraft.model.FilterConfig
import com.example.pocketcraft.model.VideoFilterPreset

/**
 * Farbfilter (Preset + Feinjustierung) als eine einzige 4x5-Farbmatrix (zeilenweise, Offsets in 0–255),
 * passend für Compose `ColorMatrix`. Rechnet wie der Android-Export: Sättigung → Wärme → Sepia →
 * Kontrast → Helligkeit.
 */
object FilterMath {

    /** `null`, wenn der Filter das Bild nicht verändert. */
    fun colorMatrix(config: FilterConfig): FloatArray? {
        val preset = config.preset
        val intensity = config.intensity.coerceIn(0f, 1f)
        val isNeutral = preset == VideoFilterPreset.NONE &&
            config.brightnessOffset == 0f &&
            config.contrastMultiplier == 1f &&
            config.saturationMultiplier == 1f
        if (isNeutral || intensity <= 0f) return null

        val contrast = 1f + ((preset.contrast * config.contrastMultiplier) - 1f) * intensity
        val saturation = (1f + ((preset.saturation * config.saturationMultiplier) - 1f) * intensity).coerceAtLeast(0f)
        val brightness = ((preset.brightness + config.brightnessOffset) * intensity).coerceIn(-1f, 1f)
        val warmth = preset.warmth * intensity * 35f / 255f
        val sepia = (preset.sepia * intensity).coerceIn(0f, 1f)

        val inv = 1f - saturation
        val r = 0.213f * inv
        val g = 0.715f * inv
        val b = 0.072f * inv
        var m = floatArrayOf(
            r + saturation, g, b, 0f, 0f,
            r, g + saturation, b, 0f, 0f,
            r, g, b + saturation, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
        m = concat(
            floatArrayOf(
                1f + warmth * 2f, 0f, 0f, 0f, 0f,
                0f, 1f, 0f, 0f, 0f,
                0f, 0f, (1f - warmth * 2f).coerceAtLeast(0f), 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
            m,
        )
        if (sepia > 0f) {
            val keep = 1f - sepia
            m = concat(
                floatArrayOf(
                    keep + 0.393f * sepia, 0.769f * sepia, 0.189f * sepia, 0f, 0f,
                    0.349f * sepia, keep + 0.686f * sepia, 0.168f * sepia, 0f, 0f,
                    0.272f * sepia, 0.534f * sepia, keep + 0.131f * sepia, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                ),
                m,
            )
        }
        // Kontrast um die Mitte (127,5) plus Helligkeit
        val offset = (0.5f - 0.5f * contrast + brightness) * 255f
        return concat(
            floatArrayOf(
                contrast, 0f, 0f, 0f, offset,
                0f, contrast, 0f, 0f, offset,
                0f, 0f, contrast, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
            m,
        )
    }

    /** Ergebnis wendet erst [first] an, danach [second] (beide 4x5, zeilenweise). */
    private fun concat(second: FloatArray, first: FloatArray): FloatArray {
        val out = FloatArray(20)
        for (row in 0 until 4) {
            for (col in 0 until 5) {
                var sum = 0f
                for (k in 0 until 4) sum += second[row * 5 + k] * first[k * 5 + col]
                if (col == 4) sum += second[row * 5 + 4]
                out[row * 5 + col] = sum
            }
        }
        return out
    }
}

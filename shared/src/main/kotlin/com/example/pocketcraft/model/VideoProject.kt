package com.example.pocketcraft.model

import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Unveränderliches Datenmodell eines kompletten Video-Schnittprojekts (inspiriert von storytold/filmcraft).
 *
 * Da jede Instanz von [VideoProject] komplett unveränderlich (immutable) ist, kann die History-Engine
 * jeden Bearbeitungsschritt verlustfrei als Snapshot auf dem Undo-/Redo-Stack ablegen. Dadurch sind
 * alle Aktionen – ob über das Touch-UI im unteren Drittel oder über die 2-Sekunden-Befehlskonsole –
 * jederzeit mit `undo` / `redo` umkehrbar.
 */
@Serializable
data class VideoProject(
    val title: String = "Pixel 9 Reel #1",
    val videoUri: String? = null,
    val sourceDurationMs: Long = 15_000L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = 15_000L,
    val playbackSpeed: Float = 1.0f,
    /** Global audio volume gain (0.0 = muted, 1.0 = normal, up to 2.0 = 200%). */
    val audioVolume: Float = 1.0f,
    /** Optional project tempo in beats per minute for beat-sync (`bpm 120`, `beats(...)`, `.pulse`). */
    val bpm: Float? = null,
    val aspectRatio: AspectRatioPreset = AspectRatioPreset.PORTRAIT_9_16,
    val transform: CropZoomState = CropZoomState(),
    val filter: FilterConfig = FilterConfig(),
    val blur: BlurConfig = BlurConfig(),
    val timedFormatSpans: List<TimedFormatSpan> = emptyList(),
    val cutMarkers: List<CutMarker> = emptyList(),
    /** Herausgeschnittene Bereiche mitten im Clip (`delete 10-20`, `delete between cut1 cut2`). */
    val removedRanges: List<RemovedRange> = emptyList(),
    val subtitles: List<SubtitleSegment> = defaultDemoSubtitles(),
    val overlays: List<CanvasOverlay> = defaultDemoOverlays(),
    val customFonts: List<CustomFontEntry> = emptyList(),
    /** PocketScript-Variablen dieses Projekts (`let x = 5`), als Text gespeichert. */
    val variables: Map<String, String> = emptyMap(),
    /** PocketScript-Funktionen dieses Projekts (`def name(a): …`), Name → Definition als Text. */
    val functions: Map<String, String> = emptyMap(),
    /** Named PocketScript modifier styles (`style hook = .gold.big.pop`) persisted per project. */
    val styles: Map<String, String> = emptyMap(),
    val activeFontId: String = BuiltInFont.SANS_BOLD.id,
    /**
     * Befehlsverlauf dieses Projekts (`show edits`): jede Konsolen-Eingabe, die das Projekt geändert hat,
     * in der Reihenfolge der Ausführung. Wird nur angehängt – `undo` nimmt hier nichts zurück.
     */
    val commandLog: List<String> = emptyList(),
) {
    /** Effektive Länge des geschnittenen Clips in Millisekunden. */
    val trimmedDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(500L)

    /** Effective playable duration computed by summing non-overlapping playable segments. */
    val playableDurationMs: Long
        get() = playableSegments().sumOf { (s, e) -> e - s }.coerceAtLeast(0L)

    /**
     * Selects spans active at [atMs] matching [predicate].
     * Uses half-open `[startMs, endMs)` intervals as primary match so back-to-back spans do not
     * collide on their shared boundary, falling back to `atMs == endMs` only when no span starts at [atMs].
     */
    private fun activeSpansAt(
        atMs: Long,
        predicate: (TimedFormatSpan) -> Boolean = { true },
    ): List<TimedFormatSpan> {
        val primary = timedFormatSpans.filter { predicate(it) && atMs >= it.startMs && atMs < it.endMs }
        if (primary.isNotEmpty()) return primary
        return timedFormatSpans.filter { predicate(it) && it.endMs >= it.startMs && atMs == it.endMs }
    }

    /**
     * Zum Zeitpunkt [atMs] gültiger Zoom/Pan/Zuschnitt/Drehung: globaler [transform] kombiniert mit
     * zeitgebundenen Kanten-Zuschnitten (`u20%`, `o20%`, `crop 20%`), Zoom- und Rotations-Animationen
     * sowie Shake/Pulse-Effekten aus [timedFormatSpans]. Wird von Vorschau UND Video-Export genutzt.
     */
    fun transformAt(atMs: Long): CropZoomState {
        var current = transform
        val activeSpans = activeSpansAt(atMs)
        for (span in activeSpans) {
            var left = span.cropLeft ?: current.cropLeft
            var top = span.cropTop ?: current.cropTop
            var right = span.cropRight ?: current.cropRight
            var bottom = span.cropBottom ?: current.cropBottom
            if (span.cropBottom != null && top >= bottom - 0.02f) {
                top = 0.0f
            }
            if (span.cropTop != null && bottom <= top + 0.02f) {
                bottom = 1.0f
            }
            if (span.cropRight != null && left >= right - 0.02f) {
                left = 0.0f
            }
            if (span.cropLeft != null && right <= left + 0.02f) {
                right = 1.0f
            }
            // Apply configured easing curve when interpolating animated span properties
            val spanZoom = interpolateSpanValue(
                span.zoom, span.zoomEnd, current.zoom, span.startMs, span.endMs, atMs, span.easing,
            ).coerceIn(0.1f, 5.0f)
            val spanPanX = interpolateSpanValue(
                span.panX, span.panXEnd, current.panX, span.startMs, span.endMs, atMs, span.easing,
            ).coerceIn(-1f, 1f)
            val spanPanY = interpolateSpanValue(
                span.panY, span.panYEnd, current.panY, span.startMs, span.endMs, atMs, span.easing,
            ).coerceIn(-1f, 1f)
            val spanRotZ = interpolateSpanValue(
                span.rotationDegrees, span.rotationEndDegrees, current.rotationDegrees, span.startMs, span.endMs, atMs, span.easing,
            )
            val spanRotX = interpolateSpanValue(
                span.rotationXDegrees, span.rotationXEndDegrees, current.rotationXDegrees, span.startMs, span.endMs, atMs, span.easing,
            )
            val spanRotY = interpolateSpanValue(
                span.rotationYDegrees, span.rotationYEndDegrees, current.rotationYDegrees, span.startMs, span.endMs, atMs, span.easing,
            )

            // Deterministic camera shake: multi-sine oscillation + slight zoom-in margin to hide edges
            val shakeAmp = span.shakeIntensity ?: 0f
            var finalZoom = spanZoom
            var finalPanX = spanPanX
            var finalPanY = spanPanY
            var finalRotZ = spanRotZ
            if (shakeAmp > 0.001f) {
                val sec = (atMs - span.startMs).coerceAtLeast(0L) / 1000.0
                val sx = (sin(sec * 37.0) * 0.6 + sin(sec * 73.0) * 0.4).toFloat()
                val sy = (cos(sec * 43.0) * 0.6 + sin(sec * 89.0) * 0.4).toFloat()
                val sr = (sin(sec * 53.0) * 0.7 + cos(sec * 97.0) * 0.3).toFloat()
                finalPanX = (finalPanX + sx * shakeAmp).coerceIn(-1f, 1f)
                finalPanY = (finalPanY + sy * shakeAmp).coerceIn(-1f, 1f)
                finalRotZ += sr * shakeAmp * 65f
                finalZoom = (finalZoom * (1f + shakeAmp * 1.6f)).coerceIn(0.1f, 5.0f)
            }

            // Deterministic beat-synced pulse zoom impulse
            val pBpm = span.pulseBpm
            if (pBpm != null && pBpm > 0f) {
                val beatMs = 60_000.0 / pBpm.toDouble()
                val phase = (((atMs - span.startMs).coerceAtLeast(0L) % beatMs) / beatMs).toFloat()
                val strength = (span.pulseStrength ?: 0.12f).coerceIn(0.01f, 0.5f)
                val impulse = (strength * exp(-phase * 5.0)).toFloat()
                finalZoom = (finalZoom * (1f + impulse)).coerceIn(0.1f, 5.0f)
            }

            current = current.copy(
                cropLeft = left.coerceIn(0f, 0.98f),
                cropTop = top.coerceIn(0f, 0.98f),
                cropRight = right.coerceIn((left + 0.02f).coerceAtMost(1f), 1f),
                cropBottom = bottom.coerceIn((top + 0.02f).coerceAtMost(1f), 1f),
                panX = finalPanX,
                panY = finalPanY,
                zoom = finalZoom,
                rotationDegrees = finalRotZ,
                rotationXDegrees = spanRotX,
                rotationYDegrees = spanRotY,
            )
        }
        return current
    }

    /** Eased interpolation of an optional animated span value in [spanStart]..[spanEnd]. */
    private fun interpolateSpanValue(
        start: Float?,
        end: Float?,
        fallback: Float,
        spanStart: Long,
        spanEnd: Long,
        atMs: Long,
        easing: EasingCurve = EasingCurve.LINEAR,
    ): Float = when {
        start != null && end != null -> {
            val duration = (spanEnd - spanStart).coerceAtLeast(1L)
            val rawFraction = ((atMs - spanStart).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            val eased = easing.apply(rawFraction)
            start + (end - start) * eased
        }
        start != null -> start
        else -> fallback
    }

    /** Zum Zeitpunkt [atMs] aktiver Farbfilter (globaler [filter] oder zeitgebundener Span-Filter). */
    fun filterAt(atMs: Long): FilterConfig {
        val span = activeSpansAt(atMs) { it.filterPreset != null }.lastOrNull()
        return if (span?.filterPreset != null) filter.copy(preset = span.filterPreset) else filter
    }

    /** Zum Zeitpunkt [atMs] aktive Unschärfe (globaler [blur] oder zeitgebundener Span-Blur). */
    fun blurAt(atMs: Long): BlurConfig {
        val span = activeSpansAt(atMs) { it.blurRadiusPx != null || it.blurMode != null }.lastOrNull()
            ?: return blur
        val radius = (span.blurRadiusPx ?: blur.radiusPx).coerceIn(0f, 45f)
        val mode = when {
            radius <= 0f -> BlurMode.OFF
            span.blurMode != null -> span.blurMode
            blur.mode == BlurMode.OFF -> BlurMode.BACKGROUND_BOKEH
            else -> blur.mode
        }
        return blur.copy(mode = mode, radiusPx = radius)
    }

    /** Active subtitle at [atMs] using half-open intervals `[startMs, endMs)` with end-frame fallback. */
    fun subtitleAt(atMs: Long): SubtitleSegment? =
        subtitles.lastOrNull { atMs >= it.startMs && atMs < it.endMs }
            ?: subtitles.lastOrNull { atMs == it.endMs }

    /**
     * Returns `(overlayColorArgb, alpha)` for any video fade (`fade in` / `fade out`) or `flash` pulse
     * active at [atMs], or `null` when transparent.
     */
    fun videoFadeAt(atMs: Long): Pair<Long, Float>? {
        val span = activeSpansAt(atMs) { it.fadeFromAlpha != null }.lastOrNull() ?: return null
        val fromA = span.fadeFromAlpha ?: return null
        val toA = span.fadeToAlpha ?: 0f
        val duration = (span.endMs - span.startMs).coerceAtLeast(1L)
        val rawFraction = ((atMs - span.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        val alpha = if (span.isFlashPulse) {
            // Sharp peak at 15% into the flash window followed by smooth decay to 0
            val peak = 0.15f
            val factor = if (rawFraction <= peak) rawFraction / peak else (1f - rawFraction) / (1f - peak)
            (fromA * factor.coerceIn(0f, 1f)).coerceIn(0f, 1f)
        } else {
            val eased = span.easing.apply(rawFraction)
            (fromA + (toA - fromA) * eased).coerceIn(0f, 1f)
        }
        return if (alpha > 0.005f) (span.fadeColorArgb ?: 0xFF000000L) to alpha else null
    }

    /** Returns the effective audio gain (`0.0f..2.0f`) at [atMs] combining global volume and timed audio spans. */
    fun audioGainAt(atMs: Long): Float {
        val span = activeSpansAt(atMs) { it.audioVolumeStart != null }.lastOrNull()
        if (span?.audioVolumeStart != null) {
            val startG = span.audioVolumeStart
            val endG = span.audioVolumeEnd ?: startG
            val duration = (span.endMs - span.startMs).coerceAtLeast(1L)
            val rawFraction = ((atMs - span.startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
            val eased = span.easing.apply(rawFraction)
            return (startG + (endG - startG) * eased).coerceIn(0f, 2f)
        }
        return audioVolume.coerceIn(0f, 2f)
    }

    /**
     * Die tatsächlich abgespielten/exportierten Abschnitte: Trim-Bereich ohne herausgeschnittene
     * Mittelstücke, als Liste von (Start, Ende) in Millisekunden.
     */
    fun playableSegments(): List<Pair<Long, Long>> {
        val result = mutableListOf<Pair<Long, Long>>()
        var cursor = trimStartMs
        for (r in removedRanges.sortedBy { it.startMs }) {
            if (r.endMs <= cursor) continue
            if (r.startMs >= trimEndMs) break
            if (r.startMs > cursor) result.add(cursor to minOf(r.startMs, trimEndMs))
            cursor = maxOf(cursor, r.endMs)
        }
        if (cursor < trimEndMs) result.add(cursor to trimEndMs)
        return result.filter { it.second - it.first >= 100L }
    }

    /** Liegt [ms] in einem herausgeschnittenen Bereich, gibt den Bereich zurück, sonst `null`. */
    fun removedRangeAt(ms: Long): RemovedRange? = removedRanges.firstOrNull { ms >= it.startMs && ms < it.endMs }

    /**
     * Korrigiert eine Abspielposition so, dass sie innerhalb von Trim-Start/-Ende liegt und
     * keine herausgeschnittenen Bereiche trifft (springt hinter den Schnitt).
     */
    fun resolvePlayablePosition(ms: Long): Long {
        var pos = ms.coerceIn(trimStartMs, trimEndMs)
        var guard = 0
        while (guard++ < 32) {
            val hit = removedRangeAt(pos) ?: break
            pos = hit.endMs
        }
        return if (pos >= trimEndMs) trimEndMs else pos
    }
}

/**
 * Easing curves for animated zoom, pan, rotation, fades, and overlay transitions.
 */
@Serializable
enum class EasingCurve(val id: String) {
    LINEAR("linear"),
    EASE_IN_OUT("ease"),
    EASE_IN("ease-in"),
    EASE_OUT("ease-out"),
    BOUNCE("bounce"),
    ELASTIC("elastic"),
    SPRING("spring");

    /** Maps linear progress [rawT] in `0..1` through the easing function. */
    fun apply(rawT: Float): Float {
        val t = rawT.coerceIn(0f, 1f)
        return when (this) {
            LINEAR -> t
            EASE_IN -> t * t * t
            EASE_OUT -> {
                val u = 1f - t
                1f - u * u * u
            }
            EASE_IN_OUT -> {
                if (t < 0.5f) 4f * t * t * t
                else 1f - (-2f * t + 2f).let { it * it * it } / 2f
            }
            BOUNCE -> {
                val n1 = 7.5625f
                val d1 = 2.75f
                when {
                    t < 1f / d1 -> n1 * t * t
                    t < 2f / d1 -> {
                        val u = t - 1.5f / d1
                        n1 * u * u + 0.75f
                    }
                    t < 2.5f / d1 -> {
                        val u = t - 2.25f / d1
                        n1 * u * u + 0.9375f
                    }
                    else -> {
                        val u = t - 2.625f / d1
                        n1 * u * u + 0.984375f
                    }
                }
            }
            ELASTIC -> {
                if (t <= 0f) 0f
                else if (t >= 1f) 1f
                else {
                    val c4 = (2.0 * PI) / 3.0
                    (2.0.pow(-10.0 * t) * sin((t * 10.0 - 0.75) * c4) + 1.0).toFloat()
                }
            }
            SPRING -> {
                // easeOutBack with classic overshoot constant c1 = 1.70158
                val c1 = 1.70158f
                val c3 = c1 + 1f
                val u = t - 1f
                1f + c3 * u * u * u + c1 * u * u
            }
        }
    }

    companion object {
        /** Resolves user-facing PocketScript easing aliases (`ease`, `smooth`, `bounce`, etc.). */
        fun fromToken(token: String): EasingCurve? = when (token.trim().lowercase()) {
            "linear" -> LINEAR
            "ease", "smooth", "ease-in-out", "easeinout" -> EASE_IN_OUT
            "ease-in", "easein" -> EASE_IN
            "ease-out", "easeout" -> EASE_OUT
            "bounce" -> BOUNCE
            "elastic" -> ELASTIC
            "spring", "overshoot" -> SPRING
            else -> null
        }
    }
}

/**
 * Entrance / presentation animations for subtitles, text overlays, and emojis.
 */
@Serializable
enum class OverlayAnimation(val id: String) {
    NONE("none"),
    FADE("fade"),
    POP("pop"),
    SLIDE_UP("slideup"),
    SLIDE_DOWN("slidedown"),
    TYPEWRITER("typewriter");
}

/**
 * Ein aus der Mitte des Videos herausgeschnittener Bereich (wird beim Abspielen übersprungen).
 */
@Serializable
data class RemovedRange(
    val startMs: Long,
    val endMs: Long,
)

/**
 * Seitenverhältnis-Presets für den Schnitt (insbesondere zum Kleiner-Schneiden / Formatwechsel).
 */
@Serializable
enum class AspectRatioPreset(val label: String, val ratioWidth: Float, val ratioHeight: Float, val cliAlias: String) {
    PORTRAIT_9_16("9:16 Reel", 9f, 16f, "9:16"),
    SQUARE_1_1("1:1 Quadrat", 1f, 1f, "1:1"),
    PORTRAIT_4_5("4:5 Feed", 4f, 5f, "4:5"),
    LANDSCAPE_16_9("16:9 Kino", 16f, 9f, "16:9"),
    FREE_CROP("Frei", 9f, 16f, "free");

    val aspectFloat: Float get() = ratioWidth / ratioHeight
}

/**
 * Zeitgebundene Auflösungs-/Seitenverhältnis-, Rand-Zuschnitt- (`u20%`, `o20%`, `r20%`, `l20%`)
 * und Zoom-Regel aus der PocketScript-Sprache,
 * z.B. `10-24,3 (8:15) 9 (16:18)`, `all (9:16)`, `all u20%` oder `90 u90%`.
 */
@Serializable
data class TimedFormatSpan(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val ratioWidth: Float = 9f,
    val ratioHeight: Float = 16f,
    val hasCustomRatio: Boolean = true,
    val cropLeft: Float? = null,
    val cropTop: Float? = null,
    val cropRight: Float? = null,
    val cropBottom: Float? = null,
    val panX: Float? = null,
    val panY: Float? = null,
    /** Optionales Ziel für animierte Kamerafahrt / Zoom-Zielpunkt (`panX` -> `panXEnd`, `panY` -> `panYEnd`). */
    val panXEnd: Float? = null,
    val panYEnd: Float? = null,
    val zoom: Float? = null,
    /** Optionales Ziel für animiertes Rein-/Rauszoomen (`zoom` -> `zoomEnd` über `startMs..endMs`). */
    val zoomEnd: Float? = null,
    /** 2D-Drehung in der Bildebene (Z-Achse, optional animiert bis [rotationEndDegrees]). */
    val rotationDegrees: Float? = null,
    val rotationEndDegrees: Float? = null,
    /** 3D-Drehung um die eigene waagerechte Achse (X-Achse, optional animiert bis [rotationXEndDegrees]). */
    val rotationXDegrees: Float? = null,
    val rotationXEndDegrees: Float? = null,
    /** 3D-Drehung um die eigene senkrechte Achse (Y-Achse, optional animiert bis [rotationYEndDegrees]). */
    val rotationYDegrees: Float? = null,
    val rotationYEndDegrees: Float? = null,
    val filterPreset: VideoFilterPreset? = null,
    val blurRadiusPx: Float? = null,
    val blurMode: BlurMode? = null,
    /** Easing curve applied to animated zoom/pan/rotation/fade/audio inside this span. */
    val easing: EasingCurve = EasingCurve.LINEAR,
    /** Video overlay fade/flash alpha animation (`0f..1f`). */
    val fadeFromAlpha: Float? = null,
    val fadeToAlpha: Float? = null,
    val fadeColorArgb: Long? = null,
    val isFlashPulse: Boolean = false,
    /** Deterministic camera shake amplitude (in normalized frame fraction, e.g. `0.035f`). */
    val shakeIntensity: Float? = null,
    /** Beat-synced zoom pulse BPM and strength. */
    val pulseBpm: Float? = null,
    val pulseStrength: Float? = null,
    /** Timed audio gain (`0f..2f`, animated from start to end). */
    val audioVolumeStart: Float? = null,
    val audioVolumeEnd: Float? = null,
) {
    val aspectFloat: Float
        get() = (ratioWidth / ratioHeight.coerceAtLeast(0.1f)).coerceIn(0.25f, 4.0f)

    val label: String
        get() = "${ratioWidth.toInt()}:${ratioHeight.toInt()}"

    /** `true`, falls der Span mindestens einen Effekt trägt (sonst kann er beim Zurücksetzen entfernt werden). */
    fun hasAnyEffect(): Boolean = hasCustomRatio ||
        cropLeft != null ||
        cropTop != null ||
        cropRight != null ||
        cropBottom != null ||
        panX != null ||
        panY != null ||
        panXEnd != null ||
        panYEnd != null ||
        zoom != null ||
        rotationDegrees != null ||
        rotationXDegrees != null ||
        rotationYDegrees != null ||
        filterPreset != null ||
        blurRadiusPx != null ||
        blurMode != null ||
        fadeFromAlpha != null ||
        shakeIntensity != null ||
        pulseBpm != null ||
        audioVolumeStart != null
}

/**
 * Benannter Schnittmarker auf der Timeline (z.B. `set cut 00:48` -> `cut1` bei 48.000 ms),
 * auf den sich Folgekommandos wie `delete after cut1` oder `delete before cut1` beziehen.
 */
@Serializable
data class CutMarker(
    val id: String,
    val timeMs: Long,
)

/**
 * Zustand für Zoom, Pan (Verschiebung), Rotation und freies Kleiner-Schneiden (Crop-Ränder).
 *
 * - [zoom] vergrößert oder verkleinert das Bild stufenlos (0.1x bis 5.0x; < 1.0x verkleinert zur Mitte hin),
 *   entweder per Pinch-to-Zoom direkt auf dem Video, per Slider oder per Befehl (`zoom 1.8`, `zoom in`, `zoom out`).
 * - [panX] / [panY] verschieben den Bildausschnitt normiert (-1.0 bis +1.0, z.B. `all move 10%l` -> `panY = +0.10f`).
 * - [cropLeft] (`l%`), [cropTop] (`o%`), [cropRight] (`1 - r%`), [cropBottom] (`1 - u%`) schneiden
 *   das Bild an jeder Kante prozentgenau kleiner (normierte Koordinaten 0.0 bis 1.0).
 *   Beispiel: `all u20%` setzt `cropBottom = 0.80f` (untere 20% weggeschnitten);
 *   `u90%` setzt `cropBottom = 0.10f` (untere 90% weg, nur noch ein 10%-Balken oben sichtbar).
 */
@Serializable
data class CropZoomState(
    val zoom: Float = 1.0f,
    val panX: Float = 0.0f,
    val panY: Float = 0.0f,
    val rotationDegrees: Float = 0.0f,
    val rotationXDegrees: Float = 0.0f,
    val rotationYDegrees: Float = 0.0f,
    val cropLeft: Float = 0.0f,
    val cropTop: Float = 0.0f,
    val cropRight: Float = 1.0f,
    val cropBottom: Float = 1.0f,
) {
    /** Kompakte Zusammenfassung aktiver Kanten-Cuts, Verschiebungen & Drehungen (oder leer, falls neutral). */
    fun activeEdgeCropSummary(): String {
        val parts = mutableListOf<String>()
        val uPct = ((1f - cropBottom) * 100f).roundToInt()
        val oPct = (cropTop * 100f).roundToInt()
        val lPct = (cropLeft * 100f).roundToInt()
        val rPct = ((1f - cropRight) * 100f).roundToInt()
        if (uPct > 0) parts.add("u${uPct}%")
        if (oPct > 0) parts.add("o${oPct}%")
        if (lPct > 0) parts.add("l${lPct}%")
        if (rPct > 0) parts.add("r${rPct}%")
        val moveYPct = (panY * 100f).roundToInt()
        val moveXPct = (panX * 100f).roundToInt()
        if (moveYPct > 0) parts.add("move ${moveYPct}%l")
        if (moveYPct < 0) parts.add("move ${-moveYPct}%u")
        if (moveXPct > 0) parts.add("move ${moveXPct}%r")
        if (moveXPct < 0) parts.add("move ${-moveXPct}%left")
        val rotZ = rotationDegrees.roundToInt()
        val rotY = rotationYDegrees.roundToInt()
        val rotX = rotationXDegrees.roundToInt()
        if (rotZ % 360 != 0) parts.add("rot ${rotZ}°")
        if (rotY % 360 != 0) parts.add("rotY ${rotY}°")
        if (rotX % 360 != 0) parts.add("rotX ${rotX}°")
        return parts.joinToString(" ")
    }
}

/**
 * Eingebaute Farbfilter + feinjustierbare Grading-Parameter.
 * Werden sowohl in der Live-Vorschau (über ColorMatrix) als auch beim MP4-Export pixelgenau angewendet.
 */
@Serializable
enum class VideoFilterPreset(
    val id: String,
    val displayName: String,
    val contrast: Float,
    val saturation: Float,
    val brightness: Float,
    val warmth: Float,
    val sepia: Float = 0f,
    val invert: Boolean = false,
) {
    NONE("none", "Original", 1.0f, 1.0f, 0f, 0f),
    CINEMATIC("cinematic", "Teal & Orange", 1.22f, 1.15f, -0.03f, 0.18f),
    CYBERPUNK("cyberpunk", "Pixel Cyber", 1.30f, 1.45f, 0.02f, -0.28f),
    BW_NOIR("bw", "Noir S/W", 1.35f, 0.0f, -0.04f, 0f),
    WARM_VINTAGE("vintage", "Retro 90s", 0.95f, 0.85f, 0.04f, 0.25f, sepia = 0.35f),
    VIVID_POP("vivid", "Knackig HDR", 1.18f, 1.40f, 0.03f, 0.05f),
    COOL_MORNING("arctic", "Eisblau", 1.10f, 0.90f, 0.04f, -0.32f),
    SEPIA_GOLD("sepia", "Sepia Gold", 1.08f, 0.70f, 0.02f, 0.30f, sepia = 0.75f);

    companion object {
        fun fromNameOrId(query: String): VideoFilterPreset? {
            val clean = query.trim().lowercase()
            return entries.firstOrNull {
                it.id.lowercase() == clean || it.displayName.lowercase().contains(clean)
            }
        }
    }
}

/**
 * Aktive Filtereinstellung inkl. Intensitätsregler und manueller Farbkorrektur.
 */
@Serializable
data class FilterConfig(
    val preset: VideoFilterPreset = VideoFilterPreset.NONE,
    val intensity: Float = 1.0f,
    val brightnessOffset: Float = 0.0f,
    val contrastMultiplier: Float = 1.0f,
    val saturationMultiplier: Float = 1.0f,
    val vignetteStrength: Float = 0.15f,
)

/**
 * Modi für den Unschärfe-Effekt (Blur).
 */
@Serializable
enum class BlurMode(val id: String, val label: String, val description: String) {
    OFF("off", "Aus", "Keine Unschärfe"),
    FULL_FRAME("full", "Gesamtes Bild", "Weichzeichner über das gesamte Video"),
    BACKGROUND_BOKEH("bokeh", "Porträt-Fokus", "Hintergrund unscharf, Zentrum bleibt gestochen scharf"),
    PRIVACY_ZONE("privacy", "Zensur-Box", "Verpixelt/blurrt einen frei verschiebbaren Bereich (z.B. Gesicht/Kennzeichen)"),
    CINEMA_BARS("tiltshift", "Tilt-Shift", "Oben und unten unscharf für Miniatur-Look")
}

/**
 * Konfiguration für den Blur-Effekt inkl. frei platzierbarer Zensur-/Fokus-Zone.
 */
@Serializable
data class BlurConfig(
    val mode: BlurMode = BlurMode.OFF,
    val radiusPx: Float = 14f,
    val zoneCenterX: Float = 0.5f,
    val zoneCenterY: Float = 0.45f,
    val zoneWidth: Float = 0.42f,
    val zoneHeight: Float = 0.28f,
)

/**
 * Einzelnes Untertitel-Segment auf der Timeline.
 * Unterstützt auch Wort-für-Wort-Hervorhebung (Karaoke-Style) wie bei modernen Reels/Shorts.
 */
@Serializable
data class SubtitleSegment(
    val id: String,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val fontId: String = BuiltInFont.SANS_BOLD.id,
    val textColorArgb: Long = 0xFFFFFFFF,
    val highlightColorArgb: Long = 0xFFFFD600,
    val backgroundColorArgb: Long = 0xB3000000,
    val verticalPosition: Float = 0.82f,
    val fontSizeSp: Float = 20f,
    val outlineStrength: Float = 3.5f,
    /** Enables true color word-by-word karaoke highlighting in preview and export. */
    val karaoke: Boolean = false,
    /** Optional entrance/reveal animation (`fade`, `pop`, `slideup`, `slidedown`, `typewriter`). */
    val animation: OverlayAnimation = OverlayAnimation.NONE,
) {
    /** Index of the active karaoke word at [atMs]. */
    fun activeWordIndexAt(atMs: Long): Int {
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return 0
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val progress = ((atMs - startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        return (progress * words.size).toInt().coerceIn(0, words.lastIndex)
    }

    /** Progressively reveals characters for [OverlayAnimation.TYPEWRITER] over the first 75% of duration. */
    fun visibleTextAt(atMs: Long): String {
        if (animation != OverlayAnimation.TYPEWRITER) return text
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val progress = ((atMs - startMs).toFloat() / (duration * 0.75f)).coerceIn(0f, 1f)
        val totalCp = text.codePointCount(0, text.length)
        val shownCp = (totalCp * progress).roundToInt().coerceIn(if (progress > 0f) 1 else 0, totalCp)
        return text.substring(0, text.offsetByCodePoints(0, shownCp))
    }

    /** Opacity factor (`0..1`) for `FADE` and `SLIDE_*` subtitle animations. */
    fun alphaAt(atMs: Long): Float {
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val elapsed = (atMs - startMs).coerceAtLeast(0L)
        val remaining = (endMs - atMs).coerceAtLeast(0L)
        val fadeMs = minOf(300L, duration / 3L).coerceAtLeast(1L)
        return when (animation) {
            OverlayAnimation.FADE -> minOf(
                (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f),
                (remaining.toFloat() / fadeMs).coerceIn(0f, 1f),
            )
            OverlayAnimation.SLIDE_UP, OverlayAnimation.SLIDE_DOWN ->
                (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    /** Scale multiplier (`0.2..1.15`) for [OverlayAnimation.POP] entrance over the first 320 ms. */
    fun scaleAt(atMs: Long): Float {
        if (animation != OverlayAnimation.POP) return 1f
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val popMs = minOf(320L, duration / 2L).coerceAtLeast(1L)
        val t = ((atMs - startMs).toFloat() / popMs.toFloat()).coerceIn(0f, 1f)
        return (0.25f + 0.75f * EasingCurve.SPRING.apply(t)).coerceAtLeast(0.1f)
    }
}

/**
 * Frei platzierbares Overlay auf dem Video-Canvas: entweder ein gestalteter Text-Layer
 * oder ein großer Emoji-/Sticker-Layer mit eigenem Zeitbereich, Skalierung und Drehung.
 */
@Serializable
enum class OverlayKind {
    TEXT,
    EMOJI
}

@Serializable
data class CanvasOverlay(
    val id: String,
    val kind: OverlayKind,
    val content: String,
    val xPercent: Float = 0.5f,
    val yPercent: Float = 0.35f,
    val xEndPercent: Float? = null,
    val yEndPercent: Float? = null,
    val scale: Float = 1.0f,
    val scaleEnd: Float? = null,
    val rotationDegrees: Float = 0f,
    val rotationEndDegrees: Float? = null,
    val rotationXDegrees: Float = 0f,
    val rotationXEndDegrees: Float? = null,
    val rotationYDegrees: Float = 0f,
    val rotationYEndDegrees: Float? = null,
    val fontId: String = BuiltInFont.SANS_BOLD.id,
    val colorArgb: Long = 0xFF00E5FF,
    val backgroundArgb: Long = 0x99000000,
    val startMs: Long = 0L,
    val endMs: Long = 15_000L,
    val easing: EasingCurve = EasingCurve.LINEAR,
    val animation: OverlayAnimation = OverlayAnimation.NONE,
    /** Beat-synced pulse BPM for this overlay (`pulse emoji1 120bpm`, `.pulse`). */
    val pulseBpm: Float? = null,
) {
    private fun easedFraction(atMs: Long): Float {
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val raw = ((atMs - startMs).toFloat() / duration.toFloat()).coerceIn(0f, 1f)
        return easing.apply(raw)
    }

    private fun interpolateValue(start: Float, end: Float?, atMs: Long): Float {
        if (end == null) return start
        return start + (end - start) * easedFraction(atMs)
    }

    /** Horizontal position (`0..1`) at [atMs], including animated `move` targets. */
    fun xAt(atMs: Long): Float = interpolateValue(xPercent, xEndPercent, atMs)

    /** Vertical position (`0..1`) at [atMs], including animated `move` targets and slide-in offsets. */
    fun yAt(atMs: Long): Float {
        val base = interpolateValue(yPercent, yEndPercent, atMs)
        if (animation != OverlayAnimation.SLIDE_UP && animation != OverlayAnimation.SLIDE_DOWN) return base
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val slideMs = minOf(380L, duration / 2L).coerceAtLeast(1L)
        val t = ((atMs - startMs).toFloat() / slideMs.toFloat()).coerceIn(0f, 1f)
        val offset = (1f - EasingCurve.EASE_OUT.apply(t)) * 0.10f
        return if (animation == OverlayAnimation.SLIDE_UP) base + offset else base - offset
    }

    /** Scale factor at [atMs], combining animated `size`, `POP` entrance, and optional `pulseBpm`. */
    fun scaleAt(atMs: Long): Float {
        var s = interpolateValue(scale, scaleEnd, atMs)
        if (animation == OverlayAnimation.POP) {
            val duration = (endMs - startMs).coerceAtLeast(1L)
            val popMs = minOf(350L, duration / 2L).coerceAtLeast(1L)
            val t = ((atMs - startMs).toFloat() / popMs.toFloat()).coerceIn(0f, 1f)
            s *= (0.20f + 0.80f * EasingCurve.SPRING.apply(t)).coerceAtLeast(0.1f)
        }
        val bpmVal = pulseBpm
        if (bpmVal != null && bpmVal > 0f) {
            val beatMs = 60_000.0 / bpmVal.toDouble()
            val phase = (((atMs - startMs).coerceAtLeast(0L) % beatMs) / beatMs).toFloat()
            s *= (1.0 + 0.14 * exp(-phase * 5.0)).toFloat()
        }
        return s.coerceAtLeast(0.05f)
    }

    /** Opacity factor (`0..1`) at [atMs] for `FADE` and `SLIDE_*` overlay animations. */
    fun alphaAt(atMs: Long): Float {
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val elapsed = (atMs - startMs).coerceAtLeast(0L)
        val remaining = (endMs - atMs).coerceAtLeast(0L)
        val fadeMs = minOf(350L, duration / 3L).coerceAtLeast(1L)
        return when (animation) {
            OverlayAnimation.FADE -> minOf(
                (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f),
                (remaining.toFloat() / fadeMs).coerceIn(0f, 1f),
            )
            OverlayAnimation.SLIDE_UP, OverlayAnimation.SLIDE_DOWN ->
                (elapsed.toFloat() / fadeMs).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    /** Progressively reveals code points over the first 75% of duration when [OverlayAnimation.TYPEWRITER] is active. */
    fun visibleContentAt(atMs: Long): String {
        if (animation != OverlayAnimation.TYPEWRITER) return content
        val duration = (endMs - startMs).coerceAtLeast(1L)
        val progress = ((atMs - startMs).toFloat() / (duration * 0.75f)).coerceIn(0f, 1f)
        val totalCp = content.codePointCount(0, content.length)
        val shownCp = (totalCp * progress).roundToInt().coerceIn(if (progress > 0f) 1 else 0, totalCp)
        return content.substring(0, content.offsetByCodePoints(0, shownCp))
    }

    /** Visibility check using half-open `[startMs, endMs)` intervals with end-of-clip inclusion. */
    fun isVisibleAt(atMs: Long, projectEndMs: Long): Boolean =
        atMs >= startMs && (atMs < endMs || (atMs == endMs && endMs >= projectEndMs))

    /** 2D-Rotation in der Bildebene (Z-Achse) zum Zeitpunkt [atMs]. */
    fun rotationZAt(atMs: Long): Float = interpolateValue(rotationDegrees, rotationEndDegrees, atMs)

    /** 3D-Rotation um die eigene waagerechte Achse (X-Achse) zum Zeitpunkt [atMs]. */
    fun rotationXAt(atMs: Long): Float = interpolateValue(rotationXDegrees, rotationXEndDegrees, atMs)

    /** 3D-Rotation um die eigene senkrechte Achse (Y-Achse) zum Zeitpunkt [atMs]. */
    fun rotationYAt(atMs: Long): Float = interpolateValue(rotationYDegrees, rotationYEndDegrees, atMs)
}

/**
 * Mitgelieferte System- und Design-Schriftarten, die sofort ohne Nachladen bereitstehen.
 */
@Serializable
enum class BuiltInFont(val id: String, val displayName: String, val sampleTag: String) {
    SANS_BOLD("sans_bold", "Roboto Black (Reels)", "MODERN"),
    SERIF_CINEMA("serif_cinema", "Playfair Serif (Kino)", "CLASSIC"),
    MONO_TECH("mono_tech", "JetBrains Mono (Code)", "CYBER"),
    CURSIVE_VLOG("cursive_vlog", "Casual Script (Vlog)", "HAND"),
    CONDENSED_IMPACT("condensed_impact", "Impact Punch (Meme)", "LOUD"),
    ROUNDED_POP("rounded_pop", "Rounded Pop (Clean)", "SOFT");
}

/**
 * Vom Nutzer zur Laufzeit hinzugefügte eigene Schriftart (.ttf / .otf Datei vom Pixel 9 Speicher).
 */
@Serializable
data class CustomFontEntry(
    val id: String,
    val displayName: String,
    val filePath: String,
)

/**
 * Kategorisierte Emoji-Bibliothek für schnelles Einfügen im Emoji-Tab.
 */
object EmojiCatalog {
    val categories: Map<String, List<String>> = linkedMapOf(
        "Hype & Feuer" to listOf("🔥", "🚀", "⚡", "💥", "✨", "👑", "💎", "🎯", "🏆", "💯"),
        "Reaktion" to listOf("😂", "🤯", "😍", "😎", "🥳", "😱", "🤔", "👀", "🫡", "🙌"),
        "Video & Cut" to listOf("🎬", "🎥", "✂️", "🎙️", "🎧", "🎵", "📢", "💬", "📌", "⬇️"),
        "Pixel & Tech" to listOf("📱", "🤖", "💻", "🕹️", "🔋", "🌐", "🔒", "🧪", "🪄", "🎨"),
    )

    val quickRow: List<String> = listOf("🔥", "😂", "🤯", "🚀", "✨", "👀", "🎬", "💯", "❤️", "⚡")
}

/**
 * Standard-Untertitel für den sofortigen Demo-Start, damit der Nutzer ohne langes Einrichten
 * Untertitel, Schriftarten, Filter und Timeline-Scrubbing live ausprobieren kann.
 */
fun defaultDemoSubtitles(): List<SubtitleSegment> = listOf(
    SubtitleSegment(
        id = "sub_1",
        startMs = 0L,
        endMs = 3_600L,
        text = "Willkommen in PocketCraft auf dem Pixel 9! 🎬",
    ),
    SubtitleSegment(
        id = "sub_2",
        startMs = 3_600L,
        endMs = 7_400L,
        text = "Pinch-to-Zoom im Bild oder schneide es direkt kleiner.",
    ),
    SubtitleSegment(
        id = "sub_3",
        startMs = 7_400L,
        endMs = 11_200L,
        text = "Halte unten 2 Sek. gedrückt für die FilmCraft-Konsole!",
    ),
    SubtitleSegment(
        id = "sub_4",
        startMs = 11_200L,
        endMs = 15_000L,
        text = "Tippe dort 'undo', 'zoom 1.5' oder 'filter cyberpunk' ein.",
    ),
)

/**
 * Standard-Overlays (Text + Emoji) für die interaktive Demo-Szene.
 */
fun defaultDemoOverlays(): List<CanvasOverlay> = listOf(
    CanvasOverlay(
        id = "ov_title",
        kind = OverlayKind.TEXT,
        content = "PIXEL 9 STUDIO",
        xPercent = 0.50f,
        yPercent = 0.14f,
        scale = 1.05f,
        colorArgb = 0xFF00E5FF,
        backgroundArgb = 0xB3090D16,
        startMs = 0L,
        endMs = 15_000L,
    ),
    CanvasOverlay(
        id = "ov_emoji_1",
        kind = OverlayKind.EMOJI,
        content = "🔥",
        xPercent = 0.82f,
        yPercent = 0.22f,
        scale = 1.25f,
        rotationDegrees = 12f,
        startMs = 0L,
        endMs = 15_000L,
    ),
)

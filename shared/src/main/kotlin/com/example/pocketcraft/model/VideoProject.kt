package com.example.pocketcraft.model

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

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
    val activeFontId: String = BuiltInFont.SANS_BOLD.id,
) {
    /** Effektive Länge des geschnittenen Clips in Millisekunden. */
    val trimmedDurationMs: Long
        get() = (trimEndMs - trimStartMs).coerceAtLeast(500L)

    /** Effektive Abspieldauer: Trim-Bereich minus aller herausgeschnittenen Mittelstücke. */
    val playableDurationMs: Long
        get() {
            var removed = 0L
            for (r in removedRanges) {
                val s = maxOf(r.startMs, trimStartMs)
                val e = minOf(r.endMs, trimEndMs)
                if (e > s) removed += e - s
            }
            return (trimmedDurationMs - removed).coerceAtLeast(0L)
        }

    /**
     * Zum Zeitpunkt [atMs] gültiger Zoom/Pan/Zuschnitt: globaler [transform] kombiniert mit
     * zeitgebundenen Kanten-Zuschnitten (`u20%`, `o20%`, …) aus [timedFormatSpans].
     * Wird von Vorschau UND Video-Export genutzt.
     */
    fun transformAt(atMs: Long): CropZoomState {
        var current = transform
        val activeSpans = timedFormatSpans.filter { atMs in it.startMs..it.endMs }
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
            current = current.copy(
                cropLeft = left.coerceIn(0f, 0.98f),
                cropTop = top.coerceIn(0f, 0.98f),
                cropRight = right.coerceIn((left + 0.02f).coerceAtMost(1f), 1f),
                cropBottom = bottom.coerceIn((top + 0.02f).coerceAtMost(1f), 1f),
                panX = (span.panX ?: current.panX).coerceIn(-1f, 1f),
                panY = (span.panY ?: current.panY).coerceIn(-1f, 1f),
                zoom = span.zoom ?: current.zoom,
            )
        }
        return current
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
    val zoom: Float? = null,
    val filterPreset: VideoFilterPreset? = null,
    val blurRadiusPx: Float? = null,
) {
    val aspectFloat: Float
        get() = (ratioWidth / ratioHeight.coerceAtLeast(0.1f)).coerceIn(0.25f, 4.0f)

    val label: String
        get() = "${ratioWidth.toInt()}:${ratioHeight.toInt()}"
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
 * - [zoom] vergrößert das Bild stufenlos (1.0x bis 5.0x), entweder per Pinch-to-Zoom direkt auf dem
 *   Video, per Slider oder per Befehl (`zoom 1.8`).
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
    val cropLeft: Float = 0.0f,
    val cropTop: Float = 0.0f,
    val cropRight: Float = 1.0f,
    val cropBottom: Float = 1.0f,
) {
    /** Kompakte Zusammenfassung aktiver Kanten-Cuts & Verschiebungen wie `u20% move 10%l` (oder leer, falls 0%). */
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
)

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
    val scale: Float = 1.0f,
    val rotationDegrees: Float = 0f,
    val fontId: String = BuiltInFont.SANS_BOLD.id,
    val colorArgb: Long = 0xFF00E5FF,
    val backgroundArgb: Long = 0x99000000,
    val startMs: Long = 0L,
    val endMs: Long = 15_000L,
)

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

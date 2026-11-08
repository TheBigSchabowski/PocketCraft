package com.example.pocketcraft.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.pocketcraft.engine.FontRegistry
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.model.BlurMode
import com.example.pocketcraft.model.BuiltInFont
import com.example.pocketcraft.model.CustomFontEntry
import com.example.pocketcraft.model.FilterConfig
import com.example.pocketcraft.model.OverlayKind
import com.example.pocketcraft.model.VideoFilterPreset
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Wandelt eine `fontId` in eine Compose [FontFamily] um.
 * Unterstützt sowohl die 6 eingebauten Stil-Schriftarten als auch eigene vom Nutzer
 * importierte `.ttf`/`.otf` Dateien vom Pixel 9 Speicher.
 */
@Composable
fun rememberComposeFontFamily(fontId: String, customFonts: List<CustomFontEntry>): FontFamily {
    return remember(fontId, customFonts) {
        val custom = customFonts.firstOrNull { it.id == fontId }
        if (custom != null) {
            val androidTypeface = FontRegistry.resolveAndroidTypeface(fontId, customFonts)
            FontFamily(androidTypeface)
        } else {
            when (fontId) {
                BuiltInFont.SANS_BOLD.id -> FontFamily.SansSerif
                BuiltInFont.SERIF_CINEMA.id -> FontFamily.Serif
                BuiltInFont.MONO_TECH.id -> FontFamily.Monospace
                BuiltInFont.CURSIVE_VLOG.id -> FontFamily.Cursive
                BuiltInFont.CONDENSED_IMPACT.id -> FontFamily.SansSerif
                BuiltInFont.ROUNDED_POP.id -> FontFamily.Default
                else -> FontFamily.SansSerif
            }
        }
    }
}

/**
 * Haupt-Vorschau-Bühne (nimmt im Hochformat die oberen 2/3 und im Querformat die linken 3/4 ein).
 *
 * Funktionen:
 * - Interaktives Pinch-to-Zoom und Zwei-Finger-Panning direkt auf dem Video.
 * - Live-Vorschau des kleiner geschnittenen Bildausschnitts (Crop-Ränder + Seitenverhältnis).
 * - Echtzeit-Farbfilter, Blur-Modi (inkl. verschiebbarer Privacy-Zensur-Box),
 * - Frei per Finger verschiebbare Text- und Emoji-Overlays sowie Live-Untertitel mit Karaoke-Hervorhebung.
 * - Kompakte Transport- und Timeline-Scrubber-Leiste direkt unter dem Video.
 */
@Composable
fun VideoStageArea(
    engine: ProjectEngine,
    onPickVideo: () -> Unit,
    onExportVideo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val project = engine.project

    // Playhead-Taktgeber während die Vorschau läuft
    LaunchedEffect(engine.isPlaying, project.playbackSpeed, project.trimStartMs, project.trimEndMs) {
        if (engine.isPlaying) {
            val stepMs = 33L
            while (engine.isPlaying) {
                delay(stepMs)
                val delta = (stepMs * project.playbackSpeed).toLong().coerceAtLeast(10L)
                val next = engine.playheadMs + delta
                engine.playheadMs = if (next >= project.trimEndMs) {
                    project.trimStartMs
                } else {
                    next
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF090B10))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // 1. Kompakte Top-Statusleiste (Projektname, Video laden, Undo/Redo-Schnellknopf, Export)
        StageTopBar(
            engine = engine,
            onPickVideo = onPickVideo,
            onExportVideo = onExportVideo,
        )

        // Optionales Status-Banner (z.B. für Auto-Captions oder Export-Bestätigung)
        engine.statusBanner?.let { msg ->
            Surface(
                color = Color(0xFF132A38),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clickable { engine.statusBanner = null },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        text = msg,
                        color = Color(0xFF80DEEA),
                        fontSize = 11.sp,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "✕",
                        color = Color(0xFF80DEEA),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }

        // 2. Interaktiver Video-Canvas mit Pinch-to-Zoom, Crop-Rahmen, Blur, Emojis & Untertiteln
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            InteractiveVideoCanvas(engine = engine)
        }

        // 3. Transport-Steuerung + Timeline-Scrubber mit Trim-Markierungen
        TimelineScrubberBar(engine = engine)
    }
}

@Composable
private fun StageTopBar(
    engine: ProjectEngine,
    onPickVideo: () -> Unit,
    onExportVideo: () -> Unit,
) {
    val project = engine.project
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Links: App-Badge + Format-Anzeige
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                color = Color(0xFF00E5FF).copy(alpha = 0.16f),
                shape = RoundedCornerShape(6.dp),
            ) {
                Text(
                    text = "POCKETCRAFT",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                )
            }
            Text(
                text = "${engine.currentFormatLabel()} · ${(project.transform.zoom * 10).roundToInt() / 10f}x",
                color = Color(0xFF9AA5B8),
                fontSize = 11.sp,
            )
        }

        // Rechts: Schnell-Aktionen (Undo, PDF-Anleitung, Video öffnen, Konsole, Export)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            TopPillButton(
                label = "↶ ${engine.undoCount}",
                enabled = engine.canUndo,
                onClick = { engine.undo() },
            )
            TopPillButton(
                label = "📄 PDF",
                enabled = true,
                onClick = { engine.isPdfManualOpen = true },
            )
            TopPillButton(
                label = "📂 Video",
                enabled = true,
                onClick = onPickVideo,
            )
            TopPillButton(
                label = "⌨️ Cmd",
                enabled = true,
                highlight = engine.isCommandConsoleOpen,
                onClick = { engine.isCommandConsoleOpen = !engine.isCommandConsoleOpen },
            )
            TopPillButton(
                label = "💾 Export",
                enabled = true,
                highlight = true,
                onClick = onExportVideo,
            )
        }
    }
}

@Composable
private fun TopPillButton(
    label: String,
    enabled: Boolean,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val bg = when {
        highlight -> Color(0xFF00E5FF)
        enabled -> Color(0xFF1A202C)
        else -> Color(0xFF12151E)
    }
    val fg = when {
        highlight -> Color(0xFF05080D)
        enabled -> Color(0xFFE2E8F0)
        else -> Color(0xFF4A5568)
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick),
    ) {
        Text(
            text = label,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun InteractiveVideoCanvas(engine: ProjectEngine) {
    val project = engine.project
    val transform = engine.currentTransform(engine.playheadMs)
    val blurCfg = project.blur

    var liveZoom by remember(transform.zoom) { mutableFloatStateOf(transform.zoom) }
    var livePanX by remember(transform.panX) { mutableFloatStateOf(transform.panX) }
    var livePanY by remember(transform.panY) { mutableFloatStateOf(transform.panY) }

    val activeAspect = engine.currentAspectFloat(engine.playheadMs)
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxHeight()
            .aspectRatio(activeAspect, matchHeightConstraintsFirst = true)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF05070B))
            .border(1.dp, Color(0xFF263248), RoundedCornerShape(14.dp))
            .clipToBounds()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoomChange, _ ->
                    if (zoomChange != 1f || pan != Offset.Zero) {
                        val updatedZoom = (liveZoom * zoomChange).coerceIn(1.0f, 5.0f)
                        val updatedPanX = (livePanX + pan.x / size.width.coerceAtLeast(1)).coerceIn(-1.0f, 1.0f)
                        val updatedPanY = (livePanY + pan.y / size.height.coerceAtLeast(1)).coerceIn(-1.0f, 1.0f)
                        liveZoom = updatedZoom
                        livePanX = updatedPanX
                        livePanY = updatedPanY
                        engine.setZoomAndPan(updatedZoom, updatedPanX, updatedPanY)
                    }
                }
            },
    ) {
        val canvasWidthPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val canvasHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val density = LocalDensity.current

        // Ganzbild-Blur falls aktiviert
        val fullBlurDp = if (blurCfg.mode == BlurMode.FULL_FRAME) {
            (blurCfg.radiusPx * 0.65f).dp
        } else {
            0.dp
        }

        // 1. Video-Layer (entweder echtes lokales Video vom Pixel 9 oder animierte 60fps Studio-Szene)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(fullBlurDp)
                .graphicsLayer {
                    scaleX = transform.zoom
                    scaleY = transform.zoom
                    translationX = transform.panX * canvasWidthPx * 0.35f
                    translationY = transform.panY * canvasHeightPx * 0.35f
                    rotationZ = transform.rotationDegrees
                },
        ) {
            if (project.videoUri != null) {
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(Uri.parse(project.videoUri))
                            setOnPreparedListener { mp ->
                                mp.isLooping = true
                                val dur = mp.duration.toLong()
                                if (dur > 0 && dur != project.sourceDurationMs) {
                                    engine.setVideoSource(project.videoUri, dur, project.title)
                                }
                            }
                        }
                    },
                    update = { view ->
                        if (engine.isPlaying && !view.isPlaying) {
                            view.seekTo(engine.playheadMs.toInt())
                            view.start()
                        } else if (!engine.isPlaying && view.isPlaying) {
                            view.pause()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ProceduralPreviewBackdrop(
                    playheadMs = engine.playheadMs,
                    filterConfig = project.filter,
                )
            }
        }

        // 2. Kleiner-Schneiden-Maske: schneidet die per `u20%`, `o20%`, `r20%`, `l20%`, `u90%`
        //    oder Crop-Slider abgetrennten Bildbereiche sauber weg.
        val hasCustomCrop = transform.cropLeft > 0.005f ||
            transform.cropTop > 0.005f ||
            transform.cropRight < 0.995f ||
            transform.cropBottom < 0.995f

        if (hasCustomCrop || engine.activeTab.id == "crop") {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val leftPx = size.width * transform.cropLeft
                val topPx = size.height * transform.cropTop
                val rightPx = size.width * transform.cropRight
                val bottomPx = size.height * transform.cropBottom
                val cropW = (rightPx - leftPx).coerceAtLeast(6f)
                val cropH = (bottomPx - topPx).coerceAtLeast(6f)

                // Abgeschnittene Außenbereiche komplett wegschneiden (Studio-Schwarz),
                // sodass z.B. bei `u90%` unten 90% verschwinden und oben nur der 10%-Balken bleibt.
                val maskColor = Color(0xFF05070B).copy(alpha = 0.96f)
                drawRect(maskColor, Offset(0f, 0f), Size(size.width, topPx))
                drawRect(maskColor, Offset(0f, bottomPx), Size(size.width, size.height - bottomPx))
                drawRect(maskColor, Offset(0f, topPx), Size(leftPx, cropH))
                drawRect(maskColor, Offset(rightPx, topPx), Size(size.width - rightPx, cropH))

                // Rahmen für den verbleibenden Bildausschnitt
                drawRect(
                    color = if (hasCustomCrop) Color(0xFF00E5FF) else Color(0xFFFFD600),
                    topLeft = Offset(leftPx, topPx),
                    size = Size(cropW, cropH),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
                if (!engine.isCommandConsoleOpen && engine.activeTab.id == "crop") {
                    val thirdW = cropW / 3f
                    val thirdH = cropH / 3f
                    val gridColor = Color.White.copy(alpha = 0.22f)
                    drawLine(gridColor, Offset(leftPx + thirdW, topPx), Offset(leftPx + thirdW, bottomPx), 1f)
                    drawLine(gridColor, Offset(leftPx + 2 * thirdW, topPx), Offset(leftPx + 2 * thirdW, bottomPx), 1f)
                    drawLine(gridColor, Offset(leftPx, topPx + thirdH), Offset(rightPx, topPx + thirdH), 1f)
                    drawLine(gridColor, Offset(leftPx, topPx + 2 * thirdH), Offset(rightPx, topPx + 2 * thirdH), 1f)
                }
            }
        }

        // 3. Selektive Blur-Modi (Bokeh, Tilt-Shift oder verschiebbare Privacy-Zensur-Box)
        if (blurCfg.mode != BlurMode.OFF && blurCfg.mode != BlurMode.FULL_FRAME && blurCfg.radiusPx > 0.5f) {
            val blurAlpha = (blurCfg.radiusPx / 45f).coerceIn(0.15f, 0.85f)
            when (blurCfg.mode) {
                BlurMode.PRIVACY_ZONE -> {
                    val boxW = (canvasWidthPx * blurCfg.zoneWidth).coerceAtLeast(48f)
                    val boxH = (canvasHeightPx * blurCfg.zoneHeight).coerceAtLeast(36f)
                    val boxLeft = (canvasWidthPx * blurCfg.zoneCenterX - boxW / 2f)
                    val boxTop = (canvasHeightPx * blurCfg.zoneCenterY - boxH / 2f)

                    Box(
                        modifier = Modifier
                            .offset { IntOffset(boxLeft.roundToInt(), boxTop.roundToInt()) }
                            .size(with(density) { boxW.toDp() }, with(density) { boxH.toDp() })
                            .clip(RoundedCornerShape(12.dp))
                            .background(
                                Brush.radialGradient(
                                    listOf(
                                        Color(0xFF1E293B).copy(alpha = blurAlpha),
                                        Color(0xFF0F172A).copy(alpha = (blurAlpha + 0.12f).coerceAtMost(0.95f)),
                                    ),
                                ),
                            )
                            .border(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                            .pointerInput(blurCfg) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val nx = (blurCfg.zoneCenterX + dragAmount.x / canvasWidthPx).coerceIn(0.1f, 0.9f)
                                    val ny = (blurCfg.zoneCenterY + dragAmount.y / canvasHeightPx).coerceIn(0.1f, 0.9f)
                                    engine.setBlur(centerX = nx, centerY = ny)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = "🔒 BLUR (${blurCfg.radiusPx.roundToInt()}px)",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                BlurMode.BACKGROUND_BOKEH, BlurMode.CINEMA_BARS -> {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val bandH = size.height * 0.20f
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color(0xFF0B101B).copy(alpha = blurAlpha), Color.Transparent),
                                startY = 0f,
                                endY = bandH,
                            ),
                            topLeft = Offset.Zero,
                            size = Size(size.width, bandH),
                        )
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.Transparent, Color(0xFF0B101B).copy(alpha = blurAlpha)),
                                startY = size.height - bandH,
                                endY = size.height,
                            ),
                            topLeft = Offset(0f, size.height - bandH),
                            size = Size(size.width, bandH),
                        )
                    }
                }

                BlurMode.OFF, BlurMode.FULL_FRAME -> Unit
            }
        }

        // 4. Frei verschiebbare Text- & Emoji-Overlays auf dem Video
        project.overlays.forEach { overlay ->
            if (engine.playheadMs in overlay.startMs..overlay.endMs) {
                val isSelected = engine.selectedOverlayId == overlay.id
                val fontFamily = rememberComposeFontFamily(overlay.fontId, project.customFonts)
                val xPx = overlay.xPercent * canvasWidthPx
                val yPx = overlay.yPercent * canvasHeightPx

                Box(
                    modifier = Modifier
                        .offset {
                            IntOffset(
                                (xPx - 60.dp.toPx()).roundToInt(),
                                (yPx - 24.dp.toPx()).roundToInt(),
                            )
                        }
                        .graphicsLayer {
                            scaleX = overlay.scale
                            scaleY = overlay.scale
                            rotationZ = overlay.rotationDegrees
                        }
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isSelected) {
                                Modifier.border(1.5.dp, Color(0xFFFFD600), RoundedCornerShape(10.dp))
                            } else {
                                Modifier
                            },
                        )
                        .clickable { engine.selectedOverlayId = overlay.id }
                        .pointerInput(overlay.id, overlay.xPercent, overlay.yPercent) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                engine.selectedOverlayId = overlay.id
                                val nextX = (overlay.xPercent + dragAmount.x / canvasWidthPx).coerceIn(0.08f, 0.92f)
                                val nextY = (overlay.yPercent + dragAmount.y / canvasHeightPx).coerceIn(0.08f, 0.92f)
                                engine.moveOverlay(overlay.id, nextX, nextY)
                            }
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    when (overlay.kind) {
                        OverlayKind.EMOJI -> {
                            Text(
                                text = overlay.content,
                                fontSize = 36.sp,
                            )
                        }
                        OverlayKind.TEXT -> {
                            Surface(
                                color = Color(overlay.backgroundArgb),
                                shape = RoundedCornerShape(8.dp),
                            ) {
                                Text(
                                    text = overlay.content,
                                    color = Color(overlay.colorArgb),
                                    fontFamily = fontFamily,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontStyle = if (overlay.fontId == BuiltInFont.SERIF_CINEMA.id) FontStyle.Italic else FontStyle.Normal,
                                    fontSize = 16.sp,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        // 5. Aktive Untertitel (Captions) mit Wort-für-Wort Karaoke-Highlight
        val activeSub = project.subtitles.firstOrNull { engine.playheadMs in it.startMs..it.endMs }
        if (activeSub != null) {
            val subFont = rememberComposeFontFamily(activeSub.fontId, project.customFonts)
            val progress = ((engine.playheadMs - activeSub.startMs).toFloat() /
                (activeSub.endMs - activeSub.startMs).coerceAtLeast(1L)).coerceIn(0f, 1f)
            val words = activeSub.text.split(" ")
            val activeWordIdx = (progress * words.size).toInt().coerceIn(0, (words.size - 1).coerceAtLeast(0))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 12.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    color = Color(activeSub.backgroundColorArgb),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(10.dp)),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = words.mapIndexed { idx, w ->
                                if (idx == activeWordIdx && engine.isPlaying) "[$w]" else w
                            }.joinToString(" "),
                            color = Color(activeSub.textColorArgb),
                            fontFamily = subFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = activeSub.fontSizeSp.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Prozedurale 60fps Studio-Szene mit Live-Farbfilterung (ColorMatrix), damit der Nutzer
 * sofort beim Start Zoomen, Kleiner-Schneiden, Filter, Blur und Untertitel live sieht,
 * noch bevor er ein eigenes MP4 aus der Galerie wählt.
 */
@Composable
private fun ProceduralPreviewBackdrop(
    playheadMs: Long,
    filterConfig: FilterConfig,
) {
    val preset = filterConfig.preset
    val intensity = filterConfig.intensity

    Canvas(modifier = Modifier.fillMaxSize()) {
        val phase = (playheadMs % 15_000L) / 15_000f
        val angle = phase * Math.PI * 2.0

        // Basisfarben je nach gewähltem Filter-Preset
        val (topColor, midColor, bottomColor) = when (preset) {
            VideoFilterPreset.NONE -> Triple(Color(0xFF102542), Color(0xFF1E153A), Color(0xFF0B3842))
            VideoFilterPreset.CINEMATIC -> Triple(Color(0xFF0A2E3D), Color(0xFF2B1D16), Color(0xFF4A2511))
            VideoFilterPreset.CYBERPUNK -> Triple(Color(0xFF2A0845), Color(0xFF6441A5), Color(0xFF00D2FF))
            VideoFilterPreset.BW_NOIR -> Triple(Color(0xFF141414), Color(0xFF2E2E2E), Color(0xFF090909))
            VideoFilterPreset.WARM_VINTAGE -> Triple(Color(0xFF3E2723), Color(0xFF4E342E), Color(0xFF271C19))
            VideoFilterPreset.VIVID_POP -> Triple(Color(0xFF0D47A1), Color(0xFF880E4F), Color(0xFF00ACC1))
            VideoFilterPreset.COOL_MORNING -> Triple(Color(0xFF0F2027), Color(0xFF203A43), Color(0xFF2C5364))
            VideoFilterPreset.SEPIA_GOLD -> Triple(Color(0xFF3B2F2F), Color(0xFF5C4033), Color(0xFF2C221E))
        }

        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(topColor, midColor, bottomColor),
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            ),
        )

        // Bewegte Lichtkugeln (zeigen sofort Pinch-to-Zoom, Crop und Blur-Wirkung)
        val orb1X = size.width * (0.5f + 0.25f * cos(angle).toFloat())
        val orb1Y = size.height * (0.42f + 0.18f * sin(angle).toFloat())
        val orb1Color = if (preset == VideoFilterPreset.BW_NOIR) {
            Color(0xFFBDBDBD)
        } else {
            Color(0xFFFF6D00).copy(alpha = 0.55f + 0.25f * intensity)
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(orb1Color, Color.Transparent),
                center = Offset(orb1X, orb1Y),
                radius = size.minDimension * 0.42f,
            ),
            center = Offset(orb1X, orb1Y),
            radius = size.minDimension * 0.42f,
        )

        val orb2X = size.width * (0.5f - 0.22f * sin(angle * 1.5).toFloat())
        val orb2Y = size.height * (0.60f + 0.16f * cos(angle * 1.5).toFloat())
        val orb2Color = if (preset == VideoFilterPreset.BW_NOIR) {
            Color(0xFF757575)
        } else {
            Color(0xFF00E5FF).copy(alpha = 0.50f + 0.25f * intensity)
        }
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(orb2Color, Color.Transparent),
                center = Offset(orb2X, orb2Y),
                radius = size.minDimension * 0.36f,
            ),
            center = Offset(orb2X, orb2Y),
            radius = size.minDimension * 0.36f,
        )

        // Subtiles Film-Framing-Kreuz in der Bildmitte
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(
            color = Color.White.copy(alpha = 0.14f),
            center = Offset(cx, cy),
            radius = size.minDimension * 0.18f,
            style = Stroke(
                width = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 12f)),
            ),
        )

        // Vignette falls eingestellt
        if (filterConfig.vignetteStrength > 0.02f) {
            drawRect(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = (filterConfig.vignetteStrength * 0.75f).coerceIn(0f, 0.85f)),
                    ),
                    center = Offset(cx, cy),
                    radius = size.maxDimension * 0.62f,
                ),
            )
        }
    }
}

/**
 * Kompakte Timeline- und Transport-Leiste unter dem Video.
 */
@Composable
private fun TimelineScrubberBar(engine: ProjectEngine) {
    val project = engine.project
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF121621), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Play / Pause Button
        Surface(
            color = if (engine.isPlaying) Color(0xFFFFB300) else Color(0xFF00E5FF),
            shape = CircleShape,
            modifier = Modifier
                .size(30.dp)
                .clickable { engine.isPlaying = !engine.isPlaying },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = if (engine.isPlaying) "⏸" else "▶",
                    color = Color(0xFF06090F),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Black,
                )
            }
        }

        // Zeitcode-Anzeige
        Text(
            text = "${ProjectEngine.formatSec(engine.playheadMs)} / ${ProjectEngine.formatSec(project.trimEndMs)}",
            color = Color(0xFFE2E8F0),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )

        // Visueller Mini-Timeline-Balken (klick- und ziehbar)
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .height(22.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF090C12))
                .pointerInput(project.sourceDurationMs) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        val frac = (change.position.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        engine.playheadMs = (frac * project.sourceDurationMs).toLong()
                    }
                }
                .clickable {
                    val mid = (project.trimStartMs + project.trimEndMs) / 2L
                    engine.playheadMs = mid
                },
        ) {
            val total = project.sourceDurationMs.coerceAtLeast(1L).toFloat()
            val startFrac = (project.trimStartMs / total).coerceIn(0f, 1f)
            val endFrac = (project.trimEndMs / total).coerceIn(0f, 1f)
            val playFrac = (engine.playheadMs / total).coerceIn(0f, 1f)

            Canvas(modifier = Modifier.fillMaxSize()) {
                val startX = size.width * startFrac
                val endX = size.width * endFrac
                val playX = size.width * playFrac

                // Aktiver Trim-Bereich
                drawRoundRect(
                    color = Color(0xFF00E5FF).copy(alpha = 0.24f),
                    topLeft = Offset(startX, 2f),
                    size = Size((endX - startX).coerceAtLeast(4f), size.height - 4f),
                    cornerRadius = CornerRadius(6f, 6f),
                )

                // Gelbe Trim-Klammern links & rechts
                drawLine(Color(0xFFFFB300), Offset(startX, 0f), Offset(startX, size.height), strokeWidth = 4f)
                drawLine(Color(0xFFFFB300), Offset(endX, 0f), Offset(endX, size.height), strokeWidth = 4f)

                // Untertitel-Blöcke als kleine violette Marker auf der Spur
                project.subtitles.forEach { sub ->
                    val sx = (sub.startMs / total) * size.width
                    val ex = (sub.endMs / total) * size.width
                    drawLine(
                        color = Color(0xFFB388FF),
                        start = Offset(sx, size.height - 3f),
                        end = Offset(ex, size.height - 3f),
                        strokeWidth = 3f,
                    )
                }

                // Zeitgebundene Format-/Auflösungs-Spans als grüne Balken oben auf der Spur
                project.timedFormatSpans.forEach { span ->
                    val sx = (span.startMs / total) * size.width
                    val ex = (span.endMs / total) * size.width
                    drawLine(
                        color = Color(0xFF00E676),
                        start = Offset(sx, 3f),
                        end = Offset(ex, 3f),
                        strokeWidth = 3.5f,
                    )
                }

                // Weißer Playhead
                drawLine(Color.White, Offset(playX, 0f), Offset(playX, size.height), strokeWidth = 3f)
            }
        }
    }
}

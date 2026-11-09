package com.example.pocketcraft.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pocketcraft.engine.EditorToolTab
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.model.AspectRatioPreset
import com.example.pocketcraft.model.BlurMode
import com.example.pocketcraft.model.BuiltInFont
import com.example.pocketcraft.model.EmojiCatalog
import com.example.pocketcraft.model.OverlayKind
import com.example.pocketcraft.model.VideoFilterPreset
import com.example.pocketcraft.platform.LocalPlatformHooks
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Das Bearbeitungs-Dock:
 * - Nimmt im **Hochformat (Portrait)** exakt das **untere 1/3** des Bildschirms ein.
 * - Nimmt im **Querformat (Landscape)** exakt das **rechte 1/4** des Bildschirms ein.
 * - Enthält alle 6 Hauptwerkzeuge (Kürzen, Zoom & Schnitt, Untertitel/Auto-Captions,
 *   Text & Fonts inkl. eigenem Font-Import, Emojis, Filter & Blur).
 * - Besitzt die **2-Sekunden-Langdruck-Erkennung** (2000ms Hold), die von überall im Dock
 *   oder über die untere Hold-Leiste direkt auf die FilmCraft-Befehlsseite umschaltet.
 */
@Composable
fun ToolsPanelArea(
    engine: ProjectEngine,
    isLandscape: Boolean,
    onPickCustomFont: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var holdProgress by remember { mutableFloatStateOf(0f) }
    val animatedProgress by animateFloatAsState(targetValue = holdProgress, label = "holdProgress")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF121622))
            .border(
                width = 1.dp,
                color = Color(0xFF232C40),
                shape = if (isLandscape) {
                    RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                } else {
                    RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                },
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        // Nach 2 Sek. Gedrückthalten ersetzt das mehrzeilige PocketScript-Code-Fenster
        // den gesamten normalen Bearbeitungs-Screen hier im unteren 1/3 (bzw. rechten 1/4)!
        if (engine.isCommandConsoleOpen) {
            PocketScriptCodePanel(
                engine = engine,
                onClose = { engine.isCommandConsoleOpen = false },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 4.dp),
            )
        } else {
            // 1. Tab-Auswahlleiste für die 6 Werkzeuge
            ToolTabSelectorRow(engine = engine)

            // 2. Scrollbarer Werkzeug-Inhalt des aktiven Tabs
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (engine.activeTab) {
                    EditorToolTab.CROP_ZOOM -> CropAndZoomToolView(engine)
                    EditorToolTab.TRIM -> TrimAndCutToolView(engine)
                    EditorToolTab.CAPTIONS -> CaptionsAndAutoSpeechToolView(engine)
                    EditorToolTab.TEXT_FONTS -> TextAndFontManagerToolView(engine, onPickCustomFont)
                    EditorToolTab.EMOJIS -> EmojiStickerToolView(engine)
                    EditorToolTab.BLUR_FILTER -> FilterAndBlurToolView(engine)
                }
            }
        }

        // 3. 2-Sekunden-Hold-Sensorleiste am unteren Rand zum Umschalten zwischen Schnitt-UI und Code-Screen
        TwoSecondCommandTriggerBar(
            progress = animatedProgress,
            isConsoleOpen = engine.isCommandConsoleOpen,
            onHoldProgressChange = { holdProgress = it },
            onOpenCommandPage = {
                holdProgress = 0f
                engine.isCommandConsoleOpen = !engine.isCommandConsoleOpen
            },
            scope = scope,
        )
    }
}

@Composable
private fun ToolTabSelectorRow(engine: ProjectEngine) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EditorToolTab.entries.forEach { tab ->
            val selected = engine.activeTab == tab
            Surface(
                color = if (selected) Color(0xFF00E5FF) else Color(0xFF1B2234),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.clickable { engine.activeTab = tab },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(text = tab.iconSymbol, fontSize = 12.sp)
                    Text(
                        text = tab.label,
                        color = if (selected) Color(0xFF060A10) else Color(0xFFD0D8E8),
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * Spezielle Sensorleiste am unteren Rand des Funktionsbereichs:
 * Wenn der Nutzer hier (oder auf den Button) **2 Sekunden (2000ms) gedrückt hält**, füllt sich
 * der Ladebalken sichtbar auf und öffnet sofort die Befehlsseite, auf der man `undo` und alle
 * anderen FilmCraft-Befehle eintippen kann.
 */
@Composable
private fun TwoSecondCommandTriggerBar(
    progress: Float,
    isConsoleOpen: Boolean,
    onHoldProgressChange: (Float) -> Unit,
    onOpenCommandPage: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF0B0E17))
            .border(1.dp, if (isConsoleOpen) Color(0xFF00E5FF) else Color(0xFF28344E), RoundedCornerShape(10.dp))
            .semantics {
                contentDescription = "2 Sek. halten für Befehlsseite"
            }
            .pointerInput(isConsoleOpen) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var triggered = false
                    val tickerJob: Job = scope.launch {
                        val steps = 20
                        for (i in 1..steps) {
                            delay(100L) // 20 * 100ms = exakt 2000ms (2 Sekunden)
                            onHoldProgressChange(i / steps.toFloat())
                        }
                        triggered = true
                        onOpenCommandPage()
                    }
                    waitForUpOrCancellation()
                    tickerJob.cancel()
                    if (!triggered) {
                        onHoldProgressChange(0f)
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Visueller Fortschrittsbalken während der 2 Sekunden
        if (progress > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .align(Alignment.CenterStart)
                    .background(Color(0xFF00E5FF).copy(alpha = 0.35f)),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = when {
                    progress > 0f -> "⚡ Halten für Befehlszeile… ${(progress * 2.0f * 10).roundToInt() / 10f}s / 2.0s"
                    isConsoleOpen -> "⚡ PocketScript-Zeile aktiv (2 Sek. halten für normale Tabs)"
                    else -> "⌨️ 2 Sek. gedrückt halten → PocketScript-Befehlszeile"
                },
                color = if (progress > 0f || isConsoleOpen) Color(0xFF00E5FF) else Color(0xFF94A3B8),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
            )

            Text(
                text = if (isConsoleOpen) "Tabs ▸" else "Code ▸",
                color = Color(0xFFFFB300),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { onOpenCommandPage() }
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
    }
}

// ============================================================================
// TAB 1: Zoom & Bild kleiner schneiden (Crop)
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CropAndZoomToolView(engine: ProjectEngine) {
    val t = engine.project.transform
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Seitenverhältnis-Presets (zum schnellen Kleiner-Schneiden)
        Text(
            text = "Seitenverhältnis & Bild kleiner schneiden",
            color = Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AspectRatioPreset.entries.forEach { preset ->
                val active = engine.project.aspectRatio == preset
                SmallActionChip(
                    label = preset.label,
                    active = active,
                    onClick = { engine.setAspectRatio(preset) },
                )
            }
            SmallActionChip(
                label = "🔄 +90°",
                active = false,
                onClick = { engine.setRotation(t.rotationDegrees + 90f) },
            )
            SmallActionChip(
                label = "Reset Zoom",
                active = false,
                onClick = {
                    engine.setZoomAndPan(1.0f, 0f, 0f)
                    engine.setCropMargins(0f, 0f, 1f, 1f)
                },
            )
        }

        // Stufenloser Zoom-Regler (1.0x bis 5.0x)
        CompactSliderRow(
            label = "Zoom (${(t.zoom * 100).roundToInt()}%)",
            value = t.zoom,
            valueRange = 1.0f..5.0f,
            onValueChange = { engine.setZoomAndPan(zoom = it) },
        )

        // Bildränder kleiner schneiden (Crop Inset Regler)
        val currentInset = ((t.cropLeft + t.cropTop) * 50f).roundToInt()
        CompactSliderRow(
            label = "Ränder wegschneiden ($currentInset%)",
            value = t.cropLeft,
            valueRange = 0.0f..0.38f,
            onValueChange = { margin ->
                engine.setCropMargins(
                    left = margin,
                    top = margin,
                    right = 1f - margin,
                    bottom = 1f - margin,
                )
            },
        )

        // Horizontale / Vertikale Bildverschiebung (Pan)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(modifier = Modifier.weight(1f)) {
                CompactSliderRow(
                    label = "Pan X (${(t.panX * 100).roundToInt()}%)",
                    value = t.panX,
                    valueRange = -1f..1f,
                    onValueChange = { engine.setZoomAndPan(panX = it) },
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                CompactSliderRow(
                    label = "Pan Y (${(t.panY * 100).roundToInt()}%)",
                    value = t.panY,
                    valueRange = -1f..1f,
                    onValueChange = { engine.setZoomAndPan(panY = it) },
                )
            }
        }
    }
}

// ============================================================================
// TAB 2: Video kürzen (Trim, Split & Speed)
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrimAndCutToolView(engine: ProjectEngine) {
    val p = engine.project
    val totalSec = (p.sourceDurationMs / 1000f).coerceAtLeast(1f)
    val startSec = p.trimStartMs / 1000f
    val endSec = p.trimEndMs / 1000f

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Clip kürzen: ${ProjectEngine.formatSec(p.trimStartMs)} – ${ProjectEngine.formatSec(p.trimEndMs)} (Dauer: ${ProjectEngine.formatSec(p.trimmedDurationMs)})",
                color = Color(0xFFE2E8F0),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            SmallActionChip(
                label = "✂️ Am Playhead teilen",
                active = true,
                onClick = { engine.splitAtPlayhead() },
            )
        }

        CompactSliderRow(
            label = "Start (${ProjectEngine.formatSec(p.trimStartMs)})",
            value = startSec,
            valueRange = 0f..(endSec - 0.5f).coerceAtLeast(0.5f),
            onValueChange = { newStart ->
                engine.setTrimRange((newStart * 1000).toLong(), p.trimEndMs)
            },
        )

        CompactSliderRow(
            label = "Ende (${ProjectEngine.formatSec(p.trimEndMs)})",
            value = endSec,
            valueRange = (startSec + 0.5f)..totalSec,
            onValueChange = { newEnd ->
                engine.setTrimRange(p.trimStartMs, (newEnd * 1000).toLong())
            },
        )

        Text(
            text = "Abspielgeschwindigkeit (${p.playbackSpeed}x)",
            color = Color(0xFF94A3B8),
            fontSize = 11.sp,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                SmallActionChip(
                    label = "${spd}x",
                    active = p.playbackSpeed == spd,
                    onClick = { engine.setPlaybackSpeed(spd) },
                )
            }
        }
    }
}

// ============================================================================
// TAB 3: Untertitel & Pixel 9 On-Device Auto-Captions
// ============================================================================
@Composable
private fun CaptionsAndAutoSpeechToolView(engine: ProjectEngine) {
    val platform = LocalPlatformHooks.current
    var draftCaption by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Auto-Captions Haupt-Button für das Pixel 9
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = {
                    platform.startAutoCaptions(engine)
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (engine.isListeningForCaptions) Color(0xFFFF5252) else Color(0xFF00E5FF),
                    contentColor = Color(0xFF05080F),
                ),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(
                    text = when {
                        engine.isListeningForCaptions -> "🎙️ Höre zu… (Pixel 9)"
                        platform.isMobile -> "✨ Auto-Captions (Pixel 9 KI)"
                        else -> "✨ Auto-Captions"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }

            SmallActionChip(
                label = "⚡ Demo-Transkript",
                active = false,
                onClick = { engine.generateAutoCaptions() },
            )
        }

        // Manuelles Hinzufügen eines Untertitels am aktuellen Playhead
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = draftCaption,
                onValueChange = { draftCaption = it },
                placeholder = { Text("Neuer Untertitel bei ${ProjectEngine.formatSec(engine.playheadMs)}…", fontSize = 11.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0xFF2D3748),
                ),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (draftCaption.isNotBlank()) {
                        engine.addOrUpdateSubtitle(draftCaption)
                        draftCaption = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300), contentColor = Color.Black),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("+ Sub", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        // Liste aller Untertitel-Segmente (antippen springt auf der Timeline hin)
        engine.project.subtitles.forEach { sub ->
            Surface(
                color = Color(0xFF1A2130),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { engine.playheadMs = sub.startMs },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${ProjectEngine.formatSec(sub.startMs)} - ${ProjectEngine.formatSec(sub.endMs)}",
                            color = Color(0xFF00E5FF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = sub.text,
                            color = Color.White,
                            fontSize = 12.sp,
                        )
                    }
                    Text(
                        text = "🗑️",
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clickable { engine.removeSubtitle(sub.id) }
                            .padding(4.dp),
                    )
                }
            }
        }
    }
}

// ============================================================================
// TAB 4: Text-Overlays & Schriftarten (inkl. eigene .TTF / .OTF Fonts laden)
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextAndFontManagerToolView(
    engine: ProjectEngine,
    onPickCustomFont: () -> Unit,
) {
    var textInput by remember { mutableStateOf("") }
    val p = engine.project

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Text-Overlay Eingabe
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = textInput,
                onValueChange = { textInput = it },
                placeholder = { Text("Text ins Video einfügen…", fontSize = 11.sp) },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Color(0xFF00E5FF),
                    unfocusedBorderColor = Color(0xFF2D3748),
                ),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    if (textInput.isNotBlank()) {
                        engine.addTextOverlay(textInput)
                        textInput = ""
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF), contentColor = Color.Black),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text("+ Text", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp)
            }
        }

        // Schriftarten-Katalog + Eigene Schriftart (.ttf / .otf) vom Pixel 9 importieren
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Schriftarten (${BuiltInFont.entries.size + p.customFonts.size} verfügbar)",
                color = Color(0xFFE2E8F0),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
            SmallActionChip(
                label = "➕ Eigene .TTF / .OTF laden",
                active = true,
                onClick = onPickCustomFont,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // Vom Nutzer importierte eigene Schriftarten zuerst anzeigen
            p.customFonts.forEach { customFont ->
                SmallActionChip(
                    label = "★ ${customFont.displayName}",
                    active = p.activeFontId == customFont.id,
                    onClick = { engine.selectFont(customFont.id) },
                )
            }
            // Mitgelieferte Design-Schriftarten
            BuiltInFont.entries.forEach { font ->
                SmallActionChip(
                    label = font.displayName,
                    active = p.activeFontId == font.id,
                    onClick = { engine.selectFont(font.id) },
                )
            }
        }

        // Falls ein Overlay ausgewählt ist: Größe & Löschen anbieten
        val selectedOverlay = p.overlays.firstOrNull { it.id == engine.selectedOverlayId }
        if (selectedOverlay != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    CompactSliderRow(
                        label = "Größe '${selectedOverlay.content}' (${(selectedOverlay.scale * 100).roundToInt()}%)",
                        value = selectedOverlay.scale,
                        valueRange = 0.5f..3.0f,
                        onValueChange = { engine.scaleOverlay(selectedOverlay.id, it) },
                    )
                }
                SmallActionChip(
                    label = "🗑️ Löschen",
                    active = false,
                    onClick = { engine.removeOverlay(selectedOverlay.id) },
                )
            }
        }
    }
}

// ============================================================================
// TAB 5: Emojis & Sticker hinzufügen
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmojiStickerToolView(engine: ProjectEngine) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "Tippe auf ein Emoji, um es frei verschiebbar ins Video zu setzen:",
            color = Color(0xFFE2E8F0),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Bold,
        )

        EmojiCatalog.categories.forEach { (categoryName, emojis) ->
            Text(
                text = categoryName,
                color = Color(0xFF00E5FF),
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                emojis.forEach { emoji ->
                    Surface(
                        color = Color(0xFF1C2333),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.clickable { engine.addEmojiOverlay(emoji) },
                    ) {
                        Text(
                            text = emoji,
                            fontSize = 20.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// TAB 6: Filter & Blur (Unschärfe / Zensur-Box / Bokeh)
// ============================================================================
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterAndBlurToolView(engine: ProjectEngine) {
    val filter = engine.project.filter
    val blur = engine.project.blur

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 1. Farbfilter-Presets
        Text(
            text = "Farbfilter (Look & Grading)",
            color = Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            VideoFilterPreset.entries.forEach { preset ->
                SmallActionChip(
                    label = preset.displayName,
                    active = filter.preset == preset,
                    onClick = { engine.setFilterPreset(preset) },
                )
            }
        }

        CompactSliderRow(
            label = "Filter-Stärke (${(filter.intensity * 100).roundToInt()}%)",
            value = filter.intensity,
            valueRange = 0f..1f,
            onValueChange = { engine.updateFilterAdjustments(intensity = it) },
        )

        // 2. Blur / Unschärfe-Sektion
        Text(
            text = "Blur & Unschärfe-Modus",
            color = Color(0xFFE2E8F0),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BlurMode.entries.forEach { mode ->
                SmallActionChip(
                    label = mode.label,
                    active = blur.mode == mode,
                    onClick = { engine.setBlur(mode = mode) },
                )
            }
        }

        if (blur.mode != BlurMode.OFF) {
            CompactSliderRow(
                label = "Blur-Radius (${blur.radiusPx.roundToInt()} px)",
                value = blur.radiusPx,
                valueRange = 2f..45f,
                onValueChange = { engine.setBlur(radiusPx = it) },
            )
        }
    }
}

// ============================================================================
// Wiederverwendbare kompakte UI-Bausteine
// ============================================================================
@Composable
fun SmallActionChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        color = if (active) Color(0xFF00E5FF).copy(alpha = 0.22f) else Color(0xFF1C2333),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier
            .border(
                width = 1.dp,
                color = if (active) Color(0xFF00E5FF) else Color(0xFF2B354B),
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = if (active) Color(0xFF00E5FF) else Color(0xFFD5DEEC),
            fontSize = 11.sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun CompactSliderRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Color(0xFF94A3B8),
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Medium,
        )
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF00E5FF),
                activeTrackColor = Color(0xFF00E5FF),
                inactiveTrackColor = Color(0xFF263044),
            ),
            modifier = Modifier.height(26.dp),
        )
    }
}

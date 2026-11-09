package com.example.pocketcraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.platform.LocalPlatformHooks
import com.example.pocketcraft.platform.PlatformHooks

private val PocketCraftDarkPalette = darkColorScheme(
    primary = Color(0xFF00E5FF),
    secondary = Color(0xFFFFB300),
    background = Color(0xFF090B10),
    surface = Color(0xFF121622),
    onPrimary = Color(0xFF04080E),
    onSecondary = Color(0xFF04080E),
    onBackground = Color(0xFFE2E8F0),
    onSurface = Color(0xFFE2E8F0),
)

/**
 * Haupt-Composable der PocketCraft Videoschnitt-App.
 *
 * Setzt die vom Nutzer gewünschte adaptive Bildschirm-Aufteilung pixelgenau um:
 * - **Hochkant (Portrait)**:
 *   - Obere **2/3** (`weight(2f)`): Video-Vorschau, Pinch-to-Zoom, Crop-Rahmen, Overlays & Timeline.
 *   - Unteres **1/3** (`weight(1f)`): Bearbeitungs-Menü + 2-Sekunden-Hold-Trigger für die Befehlsseite.
 * - **Querformat (Landscape)**:
 *   - Linke **3/4** (`weight(3f)`): Video-Vorschau, Pinch-to-Zoom, Crop-Rahmen, Overlays & Timeline.
 *   - Rechtes **1/4** (`weight(1f)`): Bearbeitungs-Menü + 2-Sekunden-Hold-Trigger für die Befehlsseite.
 */
@Composable
fun PocketCraftApp(
    engine: ProjectEngine,
    platform: PlatformHooks,
    onPickVideo: () -> Unit = {},
    onPickCustomFont: () -> Unit = {},
    onExportVideo: () -> Unit = {},
) {
    // Automatisches Speichern ein paar Sekunden nach der letzten Änderung
    LaunchedEffect(engine.project) {
        kotlinx.coroutines.delay(4_000L)
        engine.autosave()
    }

    CompositionLocalProvider(LocalPlatformHooks provides platform) {
    MaterialTheme(colorScheme = PocketCraftDarkPalette) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090B10))
                .windowInsetsPadding(WindowInsets.safeDrawing),
        ) {
            // Breiter als hoch (Handy quer, Desktop-Fenster) -> Werkzeuge rechts statt unten
            val isLandscape = maxWidth > maxHeight
            if (isLandscape) {
                // Querformat: Links 3/4 Video-Bühne, Rechts 1/4 Funktions-Menü (inkl. Inline-Befehlszeile)
                Row(modifier = Modifier.fillMaxSize()) {
                    VideoStageArea(
                        engine = engine,
                        onPickVideo = onPickVideo,
                        onExportVideo = onExportVideo,
                        modifier = Modifier
                            .weight(3f)
                            .fillMaxHeight(),
                    )
                    ToolsPanelArea(
                        engine = engine,
                        isLandscape = true,
                        onPickCustomFont = onPickCustomFont,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                // Hochkant (Standard): Oben 2/3 Video-Bühne, Unten 1/3 Funktions-Menü (inkl. Inline-Befehlszeile)
                Column(modifier = Modifier.fillMaxSize()) {
                    VideoStageArea(
                        engine = engine,
                        onPickVideo = onPickVideo,
                        onExportVideo = onExportVideo,
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxWidth(),
                    )
                    ToolsPanelArea(
                        engine = engine,
                        isLandscape = false,
                        onPickCustomFont = onPickCustomFont,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    )
                }
            }

            if (engine.isProjectsDialogOpen) {
                ProjectsDialog(
                    engine = engine,
                    onClose = { engine.isProjectsDialogOpen = false },
                )
            }

            // Optionaler PDF-Anleitungs-Viewer & Generator (wird über "📄 PDF" oder Befehl "pdf" geöffnet)
            if (engine.isPdfManualOpen) {
                PocketScriptPdfManualDialog(
                    engine = engine,
                    onClose = { engine.isPdfManualOpen = false },
                )
            }
        }
    }
    }
}

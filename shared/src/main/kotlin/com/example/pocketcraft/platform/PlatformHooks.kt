package com.example.pocketcraft.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.model.CustomFontEntry
import java.io.File

/**
 * Alles, was sich zwischen Android und Desktop (macOS / Linux) unterscheidet.
 *
 * Der gemeinsame Code (Engine, PocketScript, Oberfläche) kennt nur diese Schnittstelle;
 * jede Plattform liefert ihre eigene Umsetzung (Videoplayer, Zwischenablage, PDF, Schriftarten).
 */
interface PlatformHooks {

    /** `true` auf dem Handy (Touch-Texte, Mikrofon), `false` auf macOS / Linux. */
    val isMobile: Boolean

    fun copyToClipboard(text: String)

    fun readClipboardText(): String?

    /** Schreibt die PocketScript-Anleitung als PDF-Datei und gibt sie zurück. */
    fun saveManualPdf(): File

    /** Startet die Spracherkennung für Auto-Captions (oder direkt den Fallback-Generator). */
    fun startAutoCaptions(engine: ProjectEngine)

    /** Lädt eine vom Nutzer importierte Schriftdatei; `null`, falls sie nicht lesbar ist. */
    fun customFontFamily(entry: CustomFontEntry): FontFamily?

    /** Echte Video-Vorschau, die Trim-Grenzen und herausgeschnittene Bereiche einhält. */
    @Composable
    fun VideoPreview(engine: ProjectEngine, videoUri: String, modifier: Modifier)
}

val LocalPlatformHooks = staticCompositionLocalOf<PlatformHooks> {
    error("PlatformHooks fehlen – PocketCraftApp(platform = …) muss sie bereitstellen.")
}

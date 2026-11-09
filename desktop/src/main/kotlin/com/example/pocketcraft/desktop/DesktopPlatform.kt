package com.example.pocketcraft.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import com.example.pocketcraft.engine.PocketScriptPdfManualGenerator
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.model.CustomFontEntry
import com.example.pocketcraft.platform.PlatformHooks
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.io.File

/** Speicherorte der Desktop-App nach den Gepflogenheiten von macOS bzw. Linux (XDG). */
object AppDirs {
    private val home = File(System.getProperty("user.home"))
    private val isMac = System.getProperty("os.name").lowercase().contains("mac")

    /** Projekte, importierte Schriftarten, Anleitung. Mit `POCKETCRAFT_HOME` lässt sich der Ort ändern. */
    val data: File = when {
        !System.getenv("POCKETCRAFT_HOME").isNullOrBlank() -> File(System.getenv("POCKETCRAFT_HOME"))
        isMac -> File(home, "Library/Application Support/PocketCraft")
        else -> File(System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: File(home, ".local/share").path, "PocketCraft")
    }

    /** Fertige Videos. */
    val exports: File = File(home, if (isMac) "Movies/PocketCraft" else "Videos/PocketCraft")
}

/** Desktop-Umsetzung (macOS / Linux) der plattformabhängigen Funktionen. */
class DesktopPlatformHooks : PlatformHooks {

    override val isMobile: Boolean = false

    private val fontCache = mutableMapOf<String, FontFamily?>()

    override fun copyToClipboard(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    override fun readClipboardText(): String? = runCatching {
        Toolkit.getDefaultToolkit().systemClipboard.getData(DataFlavor.stringFlavor) as? String
    }.getOrNull()

    override fun saveManualPdf(): File {
        val file = File(File(AppDirs.data, "Manual").apply { mkdirs() }, "PocketScript_Anleitung.pdf")
        SimplePdfWriter.writeManual(file, PocketScriptPdfManualGenerator.sections)
        return file
    }

    override fun startAutoCaptions(engine: ProjectEngine) {
        // Keine Spracherkennung auf dem Desktop: Untertitel gleichmäßig über den Clip verteilen
        engine.generateAutoCaptions()
    }

    override fun customFontFamily(entry: CustomFontEntry): FontFamily? = fontCache.getOrPut(entry.filePath) {
        val file = File(entry.filePath)
        if (!file.exists()) null else runCatching { FontFamily(Font(file = file)) }.getOrNull()
    }

    @Composable
    override fun VideoPreview(engine: ProjectEngine, videoUri: String, modifier: Modifier) {
        DesktopVideoPreview(engine = engine, videoUri = videoUri, modifier = modifier)
    }
}

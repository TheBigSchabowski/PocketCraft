package com.example.pocketcraft.desktop

import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.engine.ProjectStore
import com.example.pocketcraft.ui.PocketCraftApp
import kotlinx.coroutines.launch
import org.jetbrains.skia.Image
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlin.system.exitProcess

/**
 * Einstiegspunkt der PocketCraft Desktop-App (macOS / Linux).
 *
 * Ohne Argumente startet das Fenster, `PocketCraft video.mp4` öffnet es direkt mit diesem Video.
 * Mit `--video … --script … --out …` wird ein PocketScript ohne Fenster auf ein Video angewendet
 * und exportiert (siehe [CommandLine]).
 */
fun main(args: Array<String>) {
    val videoToOpen = args.singleOrNull()?.takeIf { !it.startsWith("-") }?.let(::File)
    if (args.isNotEmpty() && videoToOpen == null) exitProcess(CommandLine.run(args))

    val engine = ProjectEngine()
    engine.projectStore = ProjectStore(File(AppDirs.data, "projects"))
    if (videoToOpen != null) {
        loadVideo(engine, videoToOpen)
    } else {
        engine.restoreLastSession()
    }
    val platform = DesktopPlatformHooks()

    application {
        val windowState = rememberWindowState(size = DpSize(1280.dp, 820.dp))
        val icon = remember {
            runCatching {
                val bytes = object {}.javaClass.getResourceAsStream("/icon.png")!!.use { it.readBytes() }
                BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
            }.getOrNull()
        }
        var shortcuts: (Key) -> Boolean = { false }

        Window(
            onCloseRequest = {
                engine.isPlaying = false
                engine.autosave()
                exitApplication()
            },
            title = "PocketCraft",
            state = windowState,
            icon = icon,
            onKeyEvent = { event ->
                // Strg/Cmd + S, O, E – nur wenn kein Textfeld die Taste schon verarbeitet hat
                event.type == KeyEventType.KeyDown && (event.isCtrlPressed || event.isMetaPressed) && shortcuts(event.key)
            },
        ) {
            window.minimumSize = Dimension(720, 520)
            val scope = rememberCoroutineScope()

            val pickVideo: () -> Unit = {
                chooseFile(window, "Video auswählen", listOf("mp4", "mov", "m4v", "mkv", "webm", "avi"))?.let { loadVideo(engine, it) }
            }

            val pickFont: () -> Unit = {
                chooseFile(window, "Schriftart auswählen (.ttf / .otf)", listOf("ttf", "otf"))?.let { file ->
                    try {
                        val copy = File(File(AppDirs.data, "fonts").apply { mkdirs() }, file.name.replace(Regex("[^a-zA-Z0-9._-]"), "_"))
                        file.copyTo(copy, overwrite = true)
                        val entry = engine.registerCustomFont(file.nameWithoutExtension, copy.absolutePath)
                        engine.statusBanner = "Schriftart '${entry.displayName}' hinzugefügt & aktiviert!"
                    } catch (e: Exception) {
                        engine.statusBanner = "Schriftdatei konnte nicht gelesen werden (.ttf / .otf erwartet)."
                    }
                }
            }

            val exportVideo: () -> Unit = exportAction@{
                if (engine.exportProgress != null) {
                    engine.statusBanner = "Export läuft bereits (${engine.exportProgress}%)…"
                    return@exportAction
                }
                if (engine.project.videoUri == null) {
                    engine.statusBanner = "Lade zuerst ein Video über 📂 Video – dann kannst du es exportieren."
                    return@exportAction
                }
                engine.isPlaying = false
                engine.autosave()
                engine.exportProgress = 0
                engine.statusBanner = "Video wird exportiert…"
                val project = engine.project
                scope.launch {
                    val outcome = try {
                        DesktopVideoExporter.export(project, DesktopVideoExporter.defaultOutputFile(project)) { pct ->
                            engine.exportProgress = pct
                        }
                    } finally {
                        engine.exportProgress = null
                    }
                    when (outcome) {
                        is DesktopVideoExporter.Outcome.Success -> {
                            engine.statusBanner = "✓ Video gespeichert: ${outcome.file.path}"
                            engine.bannerAction = ProjectEngine.BannerAction("▶ Ansehen") {
                                runCatching { Desktop.getDesktop().open(outcome.file) }
                                    .onFailure { engine.statusBanner = "Das Video liegt in ${outcome.file.parent}" }
                            }
                        }
                        is DesktopVideoExporter.Outcome.Failure -> engine.statusBanner = outcome.message
                    }
                }
            }
            engine.onExportRequested = exportVideo

            shortcuts = { key ->
                when (key) {
                    Key.S -> {
                        engine.statusBanner = engine.saveCurrentProject()
                        true
                    }
                    Key.O -> {
                        pickVideo()
                        true
                    }
                    Key.E -> {
                        exportVideo()
                        true
                    }
                    else -> false
                }
            }

            PocketCraftApp(
                engine = engine,
                platform = platform,
                onPickVideo = pickVideo,
                onPickCustomFont = pickFont,
                onExportVideo = exportVideo,
            )
        }
    }
}

private fun loadVideo(engine: ProjectEngine, file: File) {
    try {
        val info = VideoSource.probe(file)
        engine.setVideoSource(file.absolutePath, info.durationMs, file.name)
        engine.statusBanner = "Video geladen: ${file.name} (${ProjectEngine.formatSec(info.durationMs)})"
    } catch (e: Exception) {
        engine.statusBanner = "Video konnte nicht gelesen werden: ${e.message ?: e.javaClass.simpleName}"
    }
}

/** Nativer Datei-Öffnen-Dialog des Betriebssystems; `null`, wenn abgebrochen wurde. */
private fun chooseFile(owner: Frame, title: String, extensions: List<String>): File? {
    val dialog = FileDialog(owner, title, FileDialog.LOAD)
    dialog.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(".$it") } }
    dialog.isVisible = true
    val name = dialog.file ?: return null
    return File(dialog.directory, name)
}

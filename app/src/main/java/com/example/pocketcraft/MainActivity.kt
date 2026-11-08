package com.example.pocketcraft

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.example.pocketcraft.engine.FontRegistry
import com.example.pocketcraft.engine.FrameComposer
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.ui.PocketCraftApp
import kotlinx.coroutines.launch

/**
 * Einstiegspunkt der PocketCraft Videoschnitt-App auf Android (optimiert für Pixel 9).
 *
 * Hält die [ProjectEngine] über Konfigurationswechsel (Drehen zwischen Hochformat 2/3:1/3
 * und Querformat 3/4:1/4) hinweg am Leben und bindet die System-Dateiwähler für:
 * - Eigene Videos aus der Galerie
 * - Eigene Schriftarten (.ttf / .otf)
 * - Frame-/Video-Export in den Gerätespeicher
 * an.
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** Prozessweite Engine-Instanz, damit bei Rotation und in Tests sofort darauf zugegriffen werden kann. */
        val sharedEngine: ProjectEngine = ProjectEngine()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val darkBars = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = darkBars, navigationBarStyle = darkBars)

        setContent {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()

            // Dateiwähler für echtes Video vom Pixel 9
            val videoPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent(),
            ) { uri: Uri? ->
                if (uri != null) {
                    val duration = resolveVideoDurationMs(uri) ?: 15_000L
                    val name = resolveDisplayName(uri) ?: "Pixel 9 Clip"
                    sharedEngine.setVideoSource(uri.toString(), duration, name)
                    sharedEngine.statusBanner = "Video geladen: $name (${ProjectEngine.formatSec(duration)})"
                }
            }

            // Dateiwähler für eigene Schriftarten (.ttf / .otf)
            val fontPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.OpenDocument(),
            ) { uri: Uri? ->
                if (uri != null) {
                    val rawName = resolveDisplayName(uri) ?: "CustomFont.ttf"
                    val cleanTitle = rawName.substringBeforeLast(".")
                    val copied = FontRegistry.importFontFromUri(context, uri, rawName)
                    if (copied != null) {
                        val entry = sharedEngine.registerCustomFont(cleanTitle, copied.absolutePath)
                        sharedEngine.statusBanner = "Schriftart '${entry.displayName}' hinzugefügt & aktiviert!"
                    } else {
                        sharedEngine.statusBanner = "Schriftdatei konnte nicht gelesen werden (.ttf / .otf erwartet)."
                    }
                }
            }

            PocketCraftApp(
                engine = sharedEngine,
                onPickVideo = {
                    videoPicker.launch("video/*")
                },
                onPickCustomFont = {
                    val mimeTypes = arrayOf("font/*", "application/x-font-ttf", "application/x-font-otf")
                    fontPicker.launch(mimeTypes)
                },
                onExportVideo = {
                    scope.launch {
                        sharedEngine.statusBanner = "Rendert Frame & Effekte…"
                        val outFile = FrameComposer.exportRenderedProject(context, sharedEngine.project)
                        sharedEngine.statusBanner = "Export gespeichert: ${outFile.name}"
                    }
                },
            )
        }
    }

    private fun resolveVideoDurationMs(uri: Uri): Long? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, uri)
            val durStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durStr?.toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveDisplayName(uri: Uri): String? {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (_: Exception) {
            null
        }
    }
}

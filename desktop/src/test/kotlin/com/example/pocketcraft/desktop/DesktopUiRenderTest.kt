package com.example.pocketcraft.desktop

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.ui.PocketCraftApp
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Rendert die komplette Oberfläche ohne Fenster in ein Bild – prüft, dass sie auf dem Desktop aufbaut. */
class DesktopUiRenderTest {

    private fun render(engine: ProjectEngine, name: String, settleMs: Long = 0L): File {
        val scene = ImageComposeScene(width = 1280, height = 820, density = Density(1f)) {
            PocketCraftApp(engine = engine, platform = DesktopPlatformHooks())
        }
        try {
            var image = scene.render(0L)
            val start = System.nanoTime()
            // Mehrere Frames, damit Effekte (und ggf. der Video-Decoder) Zeit zum Arbeiten haben
            while ((System.nanoTime() - start) / 1_000_000L < settleMs) {
                Thread.sleep(40L)
                image = scene.render(System.nanoTime() - start)
            }
            image = scene.render(System.nanoTime() - start + 16_000_000L)
            val out = File("build/ui-renders").apply { mkdirs() }.resolve("$name.png")
            out.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            return out
        } finally {
            scene.close()
        }
    }

    @Test
    fun demoProjectRendersInLandscapeLayout() {
        val engine = ProjectEngine()
        engine.executeCommand("for t in 0..8 step 4: \$t-{\$t + 3} set emoji: 🎬")
        val file = render(engine, "demo")
        assertTrue(file.length() > 20_000, "Bild ist verdächtig klein: ${file.length()} Bytes")
    }

    @Test
    fun realVideoShowsInPreview() {
        val video = System.getenv("POCKETCRAFT_TEST_VIDEO")?.let(::File)?.takeIf { it.isFile } ?: return
        val engine = ProjectEngine()
        engine.setVideoSource(video.absolutePath, VideoSource.probe(video).durationMs, video.name)
        engine.executeCommand("clear all; 0-5 set caption: Desktop-Vorschau.gold; all set filter: bw; pdf")
        engine.isPdfManualOpen = false
        engine.playheadMs = 2_000L
        val file = render(engine, "video", settleMs = 2_500L)
        assertTrue(file.length() > 20_000)
    }
}

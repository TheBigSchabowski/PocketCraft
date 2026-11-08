package com.example.pocketcraft

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.model.AspectRatioPreset
import com.example.pocketcraft.model.BlurMode
import com.example.pocketcraft.model.VideoFilterPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Verifiziert sowohl die FilmCraft-inspirierte [ProjectEngine] (inkl. Undo/Redo, Zoom, Crop,
 * Untertitel, Auto-Captions, eigene Schriftarten, Emojis, Filter & Blur) als auch die echte
 * Android [MainActivity] inkl. 2-Sekunden-Langdruck auf die Befehlsseite.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class PocketCraftTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun resetSharedEngine() {
        MainActivity.sharedEngine.executeCommand("reset")
        MainActivity.sharedEngine.isCommandConsoleOpen = false
    }

    @Test
    fun commandEngineSupportsZoomCropTrimFilterBlurCaptionsAndUndoRedo() {
        val engine = ProjectEngine()

        // 1. Zoom & Kleiner-Schneiden (Crop)
        engine.executeCommand("zoom 2.4")
        assertEquals(2.4f, engine.project.transform.zoom, 0.01f)

        engine.executeCommand("crop 1:1")
        assertEquals(AspectRatioPreset.SQUARE_1_1, engine.project.aspectRatio)

        engine.executeCommand("crop 20%")
        assertEquals(0.20f, engine.project.transform.cropLeft, 0.01f)

        // 2. Kürzen (Trim)
        engine.executeCommand("trim 2.0 11.5")
        assertEquals(2_000L, engine.project.trimStartMs)
        assertEquals(11_500L, engine.project.trimEndMs)

        // 3. Filter & Blur
        engine.executeCommand("filter cyberpunk 0.8")
        assertEquals(VideoFilterPreset.CYBERPUNK, engine.project.filter.preset)

        engine.executeCommand("blur privacy 28")
        assertEquals(BlurMode.PRIVACY_ZONE, engine.project.blur.mode)
        assertEquals(28f, engine.project.blur.radiusPx, 0.1f)

        // 4. Text, Emoji & Auto-Captions
        val initialOverlays = engine.project.overlays.size
        engine.executeCommand("text Pixel 9 Pro Cut")
        engine.executeCommand("emoji 🚀")
        assertEquals(initialOverlays + 2, engine.project.overlays.size)

        engine.executeCommand("autocaption Das ist ein automatischer Untertitel Test auf dem Pixel 9")
        assertTrue(engine.project.subtitles.isNotEmpty())

        // 5. Eigene Schriftart hinzufügen
        val customFont = engine.registerCustomFont("BangersRegular", "/tmp/BangersRegular.ttf")
        assertEquals(customFont.id, engine.project.activeFontId)

        // 6. Undo & Redo prüfen
        val undoMsg = engine.executeCommand("undo")
        assertTrue(undoMsg.contains("Rückgängig"))
        val redoMsg = engine.executeCommand("redo")
        assertTrue(redoMsg.contains("Wiederhergestellt"))
        assertEquals(customFont.id, engine.project.activeFontId)

        // 7. PocketScript-Sprache prüfen:
        //    a) `10-24,3 (8:15) 9 (16:18)`
        engine.executeCommand("10-24,3 (8:15) 9 (16:18)")
        assertEquals(2, engine.project.timedFormatSpans.size)
        assertEquals(10_000L, engine.project.timedFormatSpans[0].startMs)
        assertEquals(24_300L, engine.project.timedFormatSpans[0].endMs)
        assertEquals(8f, engine.project.timedFormatSpans[0].ratioWidth, 0.01f)
        assertEquals(15f, engine.project.timedFormatSpans[0].ratioHeight, 0.01f)
        assertEquals(9_000L, engine.project.timedFormatSpans[1].startMs)
        assertEquals(16f, engine.project.timedFormatSpans[1].ratioWidth, 0.01f)
        assertEquals(18f, engine.project.timedFormatSpans[1].ratioHeight, 0.01f)

        //    b) `all (9:16)`
        engine.executeCommand("all (9:16)")
        assertEquals(AspectRatioPreset.PORTRAIT_9_16, engine.project.aspectRatio)

        //    c) Mehrzeiliges Skript mit `\n` (Enter = nächste Zeile) ausführen:
        val multiLineScript = """
            all (1:1)
            8-10 set caption: Pinker Honig.red
            2-5 set text: Kapitel Eins.cyan.serif
        """.trimIndent()
        engine.executeCommand(multiLineScript)
        assertEquals(AspectRatioPreset.SQUARE_1_1, engine.project.aspectRatio)
        val honeySub = engine.project.subtitles.first { it.text == "Pinker Honig" }
        assertEquals(8_000L, honeySub.startMs)
        assertEquals(10_000L, honeySub.endMs)
        assertEquals(0xFFFF3B30L, honeySub.textColorArgb)
        assertTrue(engine.project.overlays.any { it.content == "Kapitel Eins" })

        //    d) Prozentuale Kanten-Zuschnitte (`all u20%`, `o20%`, `r20%`, `l20%`, `u40%`, `90 u90%`):
        engine.executeCommand("reset")
        engine.executeCommand("all u20%")
        assertEquals(0.80f, engine.project.transform.cropBottom, 0.01f)

        engine.executeCommand("all o20% r20% l20%")
        assertEquals(0.20f, engine.project.transform.cropTop, 0.01f)
        assertEquals(0.80f, engine.project.transform.cropRight, 0.01f)
        assertEquals(0.20f, engine.project.transform.cropLeft, 0.01f)

        engine.executeCommand("all u40%")
        assertEquals(0.60f, engine.project.transform.cropBottom, 0.01f)

        // `90 u90%` schneidet unten 90% weg, sodass oben nur noch ein 10%-Balken bleibt (cropBottom = 0.10f)
        engine.executeCommand("90 u90%")
        assertEquals(0.10f, engine.currentTransform().cropBottom, 0.01f)
        assertEquals(0.0f, engine.currentTransform().cropTop, 0.01f)

        //    e) `if` und `while` Bedingungen (`if caption red: u20%`, `if caption red: (9:16)`, `while caption red: u40%`):
        engine.executeCommand("reset")
        engine.executeCommand("all (1:1)")
        engine.executeCommand("8-10 set caption: Pinker Honig.red")
        engine.executeCommand("if caption red: u20%")
        assertEquals(0.80f, engine.currentTransform(9_000L).cropBottom, 0.01f)

        engine.executeCommand("if caption red: (9:16)")
        assertEquals(AspectRatioPreset.PORTRAIT_9_16, engine.project.aspectRatio)

        engine.executeCommand("while caption red: u40%")
        assertEquals(0.60f, engine.currentTransform(9_000L).cropBottom, 0.01f)

        val beforeEmojis = engine.project.overlays.size
        engine.executeCommand("while 0-12 step 4: set emoji: 🔥")
        assertEquals(beforeEmojis + 3, engine.project.overlays.size)
    }

    @Test
    fun launchesActivityAndTwoSecondHoldReplacesBottomThirdWithMultilineCodeEditor() {
        // App startet mit oberer Vorschau und unterem 1/3 Funktionsbereich
        rule.onNodeWithText("POCKETCRAFT").assertExists()
        rule.onNodeWithText("Zoom & Schnitt").assertExists()

        // Wir führen eine Änderung aus (z.B. Wechsel auf 1:1 Quadrat)
        rule.onNodeWithText("1:1 Quadrat").performClick()
        assertEquals(AspectRatioPreset.SQUARE_1_1, MainActivity.sharedEngine.project.aspectRatio)

        // Jetzt 2 Sekunden (2100ms) unten auf die Sensorleiste drücken → Code-Editor ersetzt das untere 1/3!
        rule.onNodeWithContentDescription("2 Sek. halten für Befehlsseite").performTouchInput {
            down(centerLeft)
        }
        rule.mainClock.advanceTimeBy(2_200L)
        rule.onRoot().performTouchInput {
            up()
        }
        rule.waitForIdle()
        assertTrue(MainActivity.sharedEngine.isCommandConsoleOpen)

        // Das normale Schnitt-Menü ("Zoom & Schnitt") ist jetzt durch den mehrzeiligen Code-Editor ersetzt,
        // während das Video oben ("POCKETCRAFT") voll sichtbar bleibt!
        rule.onNodeWithText("POCKETCRAFT").assertExists()
        rule.onNodeWithText("Zoom & Schnitt").assertDoesNotExist()
        rule.onNodeWithText("⚡ POCKETSCRIPT").assertExists()

        // Mehrzeiligen Code (mit Enter / '\n') eingeben – er wird erst beim Tippen auf "Ausführen" ausgeführt!
        MainActivity.sharedEngine.scriptEditorText = "undo\n8-10 set caption: Pinker Honig.red"
        rule.waitForIdle()
        // Vor dem Klick auf "Ausführen" ist noch nichts passiert:
        assertEquals(AspectRatioPreset.SQUARE_1_1, MainActivity.sharedEngine.project.aspectRatio)

        // Jetzt auf "Ausführen" tippen:
        rule.onNodeWithText("Ausführen").performClick()
        rule.waitForIdle()

        // Beide Zeilen wurden ausgeführt: Format wieder 9:16 + roter Untertitel "Pinker Honig"
        assertEquals(AspectRatioPreset.PORTRAIT_9_16, MainActivity.sharedEngine.project.aspectRatio)
        assertTrue(MainActivity.sharedEngine.project.subtitles.any { it.text == "Pinker Honig" && it.textColorArgb == 0xFFFF3B30L })

        // PDF-Handbuch öffnen und prüfen, dass Antippen eines Befehls ihn in die Zwischenablage kopiert
        MainActivity.sharedEngine.isPdfManualOpen = true
        rule.waitForIdle()
        rule.onNodeWithText("all u20%").performClick()
        rule.waitForIdle()
        val cm = rule.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals("all u20%", cm.primaryClip?.getItemAt(0)?.text?.toString())

        MainActivity.sharedEngine.isPdfManualOpen = false
        MainActivity.sharedEngine.isCommandConsoleOpen = false
        MainActivity.sharedEngine.scriptEditorText = ""
        rule.waitForIdle()
    }
}

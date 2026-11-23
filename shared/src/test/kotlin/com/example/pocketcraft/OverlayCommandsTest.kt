package com.example.pocketcraft

import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.model.CanvasOverlay
import com.example.pocketcraft.model.OverlayKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vorhandene Texte/Emojis per Befehl verschieben, bearbeiten, in der Größe ändern und löschen. */
class OverlayCommandsTest {

    private val longText = "Lasst uns einen schönen Abend starten (☞ ͡° ͜ʖ ͡°)☞"

    private fun engine() = ProjectEngine().apply {
        executeCommand("clear all")
        executeCommand("0-60")
        executeCommand("50-53 set text: $longText.red")
    }

    private fun ProjectEngine.texts(): List<CanvasOverlay> =
        project.overlays.filter { it.kind == OverlayKind.TEXT }.sortedBy { it.startMs }

    private fun ProjectEngine.lastWasError() = commandHistory.last().isError

    @Test
    fun moveTextRelativeAbsoluteAndNamed() {
        val e = engine()
        assertEquals(0.5f, e.texts()[0].xPercent, 0.001f)
        assertEquals(0.32f, e.texts()[0].yPercent, 0.001f)

        e.executeCommand("move text1 10%u")
        assertEquals(0.22f, e.texts()[0].yPercent, 0.001f)
        e.executeCommand("move text1 10%l")
        assertEquals(0.32f, e.texts()[0].yPercent, 0.001f)
        e.executeCommand("move text1 10%r")
        assertEquals(0.60f, e.texts()[0].xPercent, 0.001f)
        e.executeCommand("move text1 20%left")
        assertEquals(0.40f, e.texts()[0].xPercent, 0.001f)

        e.executeCommand("move text1 up 5")
        assertEquals(0.27f, e.texts()[0].yPercent, 0.001f)
        e.executeCommand("Move text1 runter 5%")
        assertEquals(0.32f, e.texts()[0].yPercent, 0.001f)
        e.executeCommand("move text1 links 10 hoch 2")
        assertEquals(0.30f, e.texts()[0].xPercent, 0.001f)
        assertEquals(0.30f, e.texts()[0].yPercent, 0.001f)

        e.executeCommand("move text1 50 20")
        assertEquals(0.5f, e.texts()[0].xPercent, 0.001f)
        assertEquals(0.2f, e.texts()[0].yPercent, 0.001f)
        e.executeCommand("move text1 to x30 y70")
        assertEquals(0.3f, e.texts()[0].xPercent, 0.001f)
        assertEquals(0.7f, e.texts()[0].yPercent, 0.001f)

        e.executeCommand("move text1 bottom")
        assertEquals(0.84f, e.texts()[0].yPercent, 0.001f)
        assertEquals(0.3f, e.texts()[0].xPercent, 0.001f)
        e.executeCommand("move text1 center")
        assertEquals(0.5f, e.texts()[0].xPercent, 0.001f)
        assertEquals(0.5f, e.texts()[0].yPercent, 0.001f)
        e.executeCommand("move text1 top")
        assertEquals(0.16f, e.texts()[0].yPercent, 0.001f)
        assertFalse(e.lastWasError())

        // Rand: bleibt im Bild
        e.executeCommand("move text1 95%u")
        assertEquals(0.05f, e.texts()[0].yPercent, 0.001f)
    }

    @Test
    fun moveAddressesTextsByNumberContentSelectionAndGroup() {
        val e = engine()
        e.executeCommand("10-20 set text: Intro.gold")
        e.executeCommand("10-20 set emoji: 🔥")
        assertEquals("Intro", e.texts()[0].content) // text1 = frühester Start
        assertEquals(longText, e.texts()[1].content)

        e.executeCommand("move text2 10%u")
        assertEquals(0.22f, e.texts()[1].yPercent, 0.001f)
        assertEquals(0.32f, e.texts()[0].yPercent, 0.001f)
        // Nach dem Befehl ist das Element ausgewählt und sichtbar
        assertEquals(e.texts()[1].id, e.selectedOverlayId)
        assertEquals(50_000L, e.playheadMs)

        e.executeCommand("move text 1 10%l")
        assertEquals(0.42f, e.texts()[0].yPercent, 0.001f)

        e.executeCommand("move text Abend 60 40")
        assertEquals(0.6f, e.texts()[1].xPercent, 0.001f)
        assertEquals(0.4f, e.texts()[1].yPercent, 0.001f)

        // Ohne Nummer: der ausgewählte Text
        e.selectedOverlayId = e.texts()[0].id
        e.executeCommand("move text 50 50")
        assertEquals(0.5f, e.texts()[0].yPercent, 0.001f)
        assertEquals(0.4f, e.texts()[1].yPercent, 0.001f)

        e.executeCommand("move texts top")
        assertTrue(e.texts().all { it.yPercent == 0.16f })

        e.executeCommand("move emoji1 80 15")
        val emoji = e.project.overlays.first { it.kind == OverlayKind.EMOJI }
        assertEquals(0.8f, emoji.xPercent, 0.001f)
        assertEquals(0.15f, emoji.yPercent, 0.001f)

        // Fehler ändern nichts
        val before = e.project
        e.executeCommand("move text9 10%u")
        assertTrue(e.lastWasError())
        e.executeCommand("move text1 irgendwohin")
        assertTrue(e.lastWasError())
        assertEquals(before, e.project)

        // Ein Befehl = ein Undo-Schritt (der Befehlsverlauf merkt sich ihn trotzdem)
        e.executeCommand("move text1 10%l")
        e.undo()
        assertEquals(before.overlays, e.project.overlays)
        assertEquals(before.commandLog + "move text1 10%l", e.project.commandLog)
    }

    @Test
    fun plainMoveStillPansTheVideo() {
        val e = engine()
        e.executeCommand("move 0.2 -0.1")
        assertEquals(0.2f, e.project.transform.panX, 0.001f)
        e.executeCommand("all move 10%l")
        assertEquals(0.10f, e.project.transform.panY, 0.001f)
        assertEquals(0.32f, e.texts()[0].yPercent, 0.001f)
    }

    @Test
    fun editChangesContentAndStyle() {
        val e = engine()
        val original = e.texts()[0]

        e.executeCommand("edit text1: Schönen Abend!")
        assertEquals("Schönen Abend!", e.texts()[0].content)
        assertEquals(original.colorArgb, e.texts()[0].colorArgb)
        assertEquals(original.id, e.texts()[0].id)
        assertEquals(original.startMs, e.texts()[0].startMs)

        e.executeCommand("edit text1 Ohne Doppelpunkt: 20:15 Uhr")
        assertEquals("Ohne Doppelpunkt: 20:15 Uhr", e.texts()[0].content)

        e.executeCommand("edit text1: Gold & groß.gold.big.bottom")
        assertEquals("Gold & groß", e.texts()[0].content)
        assertEquals(0xFFFFB300L, e.texts()[0].colorArgb)
        assertEquals(1.45f, e.texts()[0].scale, 0.001f)
        assertEquals(0.84f, e.texts()[0].yPercent, 0.001f)

        // Nur Stil
        e.executeCommand("edit text1: .red.serif")
        assertEquals("Gold & groß", e.texts()[0].content)
        assertEquals(original.colorArgb, e.texts()[0].colorArgb)
        assertEquals("serif_cinema", e.texts()[0].fontId)

        e.executeCommand("edit text groß: Per Inhalt gefunden")
        assertEquals("Per Inhalt gefunden", e.texts()[0].content)
        assertFalse(e.lastWasError())

        e.executeCommand("edit text1:")
        assertTrue(e.lastWasError())
        e.executeCommand("edit Hallo")
        assertTrue(e.lastWasError())
        assertEquals("Per Inhalt gefunden", e.texts()[0].content)

        // Dialog im Video
        e.updateOverlayContent(e.texts()[0].id, "  Aus dem Dialog  ")
        assertEquals("Aus dem Dialog", e.texts()[0].content)
        e.undo()
        assertEquals("Per Inhalt gefunden", e.texts()[0].content)
    }

    @Test
    fun sizeChangesScale() {
        val e = engine()
        e.executeCommand("size text1 150")
        assertEquals(1.5f, e.texts()[0].scale, 0.001f)
        e.executeCommand("size text1 +20")
        assertEquals(1.7f, e.texts()[0].scale, 0.001f)
        e.executeCommand("size text1 -50%")
        assertEquals(1.2f, e.texts()[0].scale, 0.001f)
        e.executeCommand("size text1 0,8")
        assertEquals(0.8f, e.texts()[0].scale, 0.001f)
        e.executeCommand("size text1 big")
        assertEquals(1.45f, e.texts()[0].scale, 0.001f)
        e.executeCommand("size text1 900")
        assertEquals(3.5f, e.texts()[0].scale, 0.001f)
        e.executeCommand("size text1")
        assertTrue(e.lastWasError())
    }

    @Test
    fun listShowsNumbersAndPositions() {
        val e = engine()
        val list = e.executeCommand("texts")
        assertTrue(list.startsWith("text1: \"$longText\" (50.0s–53.0s, x50% y32%"), list)
        assertEquals("Keine Emojis vorhanden.", e.executeCommand("emojis"))
    }

    @Test
    fun clearAllTextsInEveryCommonSpelling() {
        for (command in listOf(
            "clear all texts", "Clear all texts.", "clear all text", "clear texts", "clear all texte",
            "clear all text overlays", "delete all texts", "clear all the texts", "clear text1",
        )) {
            val e = engine()
            e.executeCommand("0-5 set caption: Bleibt")
            e.executeCommand("10-20 set emoji: 🔥")
            e.editingOverlayId = e.texts()[0].id
            e.executeCommand(command)
            assertFalse(e.lastWasError(), command)
            assertEquals(0, e.texts().size, command)
            assertEquals(1, e.project.subtitles.size, command)
            assertEquals(1, e.project.overlays.size, command)
            assertNull(e.editingOverlayId, command)
        }
        val e = engine()
        e.executeCommand("10-20 set text: Zwei")
        assertEquals("Alle 2 Texte gelöscht.", e.executeCommand("clear all texts"))
    }

    @Test
    fun commandsWorkInsideScripts() {
        val e = engine()
        e.executeCommand("let hoch = 12\nmove text1 {\$hoch}%u\nsize text1 130\nedit text1: Fertig!.gold")
        val t = e.texts()[0]
        assertEquals(0.20f, t.yPercent, 0.001f)
        assertEquals(1.3f, t.scale, 0.001f)
        assertEquals("Fertig!", t.content)
    }

    @Test
    fun rotateSpinAndFlipWorkOnOverlaysAndDotModifiers() {
        val e = engine()
        e.executeCommand("0-4 set emoji: 🔥.huge.spinaxis")
        val emoji = e.project.overlays.first { it.kind == OverlayKind.EMOJI }
        assertEquals(0f, emoji.rotationYAt(0L), 0.01f)
        assertEquals(180f, emoji.rotationYAt(2_000L), 0.01f)
        assertEquals(360f, emoji.rotationYAt(4_000L), 0.01f)

        e.executeCommand("rotate text1 15")
        assertEquals(15f, e.texts()[0].rotationDegrees, 0.01f)
        e.executeCommand("rotate text1 +10")
        assertEquals(25f, e.texts()[0].rotationDegrees, 0.01f)
        e.executeCommand("rotate text1 y 0 -> 360")
        assertEquals(0f, e.texts()[0].rotationYAt(50_000L), 0.01f)
        assertEquals(180f, e.texts()[0].rotationYAt(51_500L), 0.01f)
        assertEquals(360f, e.texts()[0].rotationYAt(53_000L), 0.01f)

        e.executeCommand("spin emoji1 2x")
        assertEquals(360f, e.project.overlays.first { it.kind == OverlayKind.EMOJI }.rotationZAt(2_000L), 0.01f)
        e.executeCommand("flip emoji1")
        assertEquals(180f, e.project.overlays.first { it.kind == OverlayKind.EMOJI }.rotationYDegrees, 0.01f)
        e.executeCommand("edit text1: Neu.gold.spin.rot15")
        // `.rot15` setzt static 15° (wird zuletzt/zuerst von rechts geparst, `.spin` setzt 0->360° danach)
        e.executeCommand("edit text1: .spinaxis")
        assertEquals(360f, e.texts()[0].rotationYEndDegrees ?: 0f, 0.01f)
        assertFalse(e.lastWasError())
    }
}

package com.example.pocketcraft

import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.engine.ProjectStore
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `show edits`: der Befehlsverlauf gehört zum Projekt und wird mit ihm gespeichert. */
class CommandLogTest {

    private fun ProjectEngine.log() = project.commandLog

    @Test
    fun recordsEveryCommandThatChangedTheProject() {
        val e = ProjectEngine()
        assertEquals("Noch keine Befehle in diesem Projekt aufgezeichnet.", e.executeCommand("show edits"))
        assertFalse(e.isEditsDialogOpen)

        e.executeCommand("clear all")
        e.executeCommand("zoom 1,5")
        e.executeCommand("0-5 set text: Hallo.gold")
        e.executeCommand("move text1 10%u")
        // Anzeige-Befehle, Fehler und wirkungslose Befehle gehören nicht in den Verlauf
        e.executeCommand("texts")
        e.executeCommand("help")
        e.executeCommand("gibtsnicht 5")
        e.executeCommand("move text9 10%u")
        e.executeCommand("zoom 1,5")
        assertEquals(listOf("clear all", "zoom 1,5", "0-5 set text: Hallo.gold", "move text1 10%u"), e.log())

        // Ein Skript ist EIN Eintrag, so wie es eingegeben wurde
        e.executeCommand("let x = 2\nzoom \$x\nall u20%")
        assertEquals("let x = 2\nzoom \$x\nall u20%", e.log().last())
        assertEquals(5, e.log().size)

        val shown = e.executeCommand("show edits")
        assertTrue(shown.startsWith("5 Befehle in diesem Projekt: 1. clear all · 2. zoom 1,5 · "), shown)
        assertTrue(shown.endsWith("5. let x = 2; zoom \$x; all u20%"), shown)
        assertTrue(e.isEditsDialogOpen)
        assertEquals(5, e.log().size) // show edits selbst wird nicht aufgezeichnet
        assertEquals(shown, e.executeCommand("edits"))
    }

    @Test
    fun undoRedoAndResetKeepTheLog() {
        val e = ProjectEngine()
        e.executeCommand("zoom 2")
        e.executeCommand("undo")
        assertEquals(1.0f, e.project.transform.zoom, 0.001f)
        assertEquals(listOf("zoom 2", "undo"), e.log())
        e.executeCommand("redo")
        assertEquals(2.0f, e.project.transform.zoom, 0.001f)
        assertEquals(listOf("zoom 2", "undo", "redo"), e.log())

        // Undo-/Redo-Knöpfe und Gesten sind keine Befehle, lassen den Verlauf aber stehen
        e.undo()
        e.setZoomAndPan(zoom = 3f)
        assertEquals(listOf("zoom 2", "undo", "redo"), e.log())

        e.executeCommand("reset")
        assertEquals(1.0f, e.project.transform.zoom, 0.001f)
        assertEquals(listOf("zoom 2", "undo", "redo", "reset"), e.log())
        assertFalse(e.hasUnsavedChanges && e.undoCount == 0)
    }

    @Test
    fun clearEditsEmptiesOnlyTheLog() {
        val e = ProjectEngine()
        e.executeCommand("zoom 2")
        e.executeCommand("filter bw")
        e.executeCommand("show edits")
        assertEquals("Befehlsverlauf geleert (2 Einträge) – das Video bleibt unverändert.", e.executeCommand("clear edits"))
        assertEquals(emptyList(), e.log())
        assertFalse(e.isEditsDialogOpen)
        assertEquals(2.0f, e.project.transform.zoom, 0.001f)
        assertEquals("Der Befehlsverlauf ist schon leer.", e.executeCommand("clear all edits"))
        e.executeCommand("zoom 3")
        assertEquals(listOf("zoom 3"), e.log())
    }

    @Test
    fun logIsSavedPerProject() {
        val dir = Files.createTempDirectory("pc_log").toFile()
        try {
            val e = ProjectEngine().apply { projectStore = ProjectStore(dir) }
            e.executeCommand("zoom 2")
            e.executeCommand("save Erstes")
            assertEquals(listOf("zoom 2", "save Erstes"), e.log()) // umbenennen ändert das Projekt
            assertFalse(e.hasUnsavedChanges) // der Verlaufs-Eintrag macht das Projekt nicht wieder "ungespeichert"

            e.executeCommand("new Zweites")
            assertEquals(emptyList(), e.log()) // `new` landet weder im alten noch im neuen Projekt
            e.executeCommand("filter bw")
            e.executeCommand("save")
            assertEquals(listOf("filter bw"), e.log())

            e.executeCommand("open Erstes")
            assertEquals(listOf("zoom 2", "save Erstes"), e.log())

            // Neustart: der Verlauf ist wieder da
            val restarted = ProjectEngine().apply { projectStore = ProjectStore(dir) }
            restarted.restoreLastSession()
            assertEquals(listOf("zoom 2", "save Erstes"), restarted.log())
            restarted.executeCommand("open Zweites")
            assertEquals(listOf("filter bw"), restarted.log())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun logKeepsOnlyTheNewestEntries() {
        val e = ProjectEngine()
        repeat(ProjectEngine.MAX_COMMAND_LOG + 20) { i -> e.executeCommand("zoom ${1 + (i % 2)}") }
        assertEquals(ProjectEngine.MAX_COMMAND_LOG, e.log().size)
    }
}

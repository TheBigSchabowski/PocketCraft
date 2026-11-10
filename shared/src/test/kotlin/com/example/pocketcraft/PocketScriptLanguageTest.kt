package com.example.pocketcraft

import com.example.pocketcraft.engine.FilterMath
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.model.AspectRatioPreset
import com.example.pocketcraft.model.FilterConfig
import com.example.pocketcraft.model.OverlayKind
import com.example.pocketcraft.model.VideoFilterPreset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Variablen, Rechnen, Schleifen, Bedingungen und Funktionen in PocketScript. */
class PocketScriptLanguageTest {

    private fun engine() = ProjectEngine().apply { executeCommand("clear all") }

    private fun ProjectEngine.emojiStarts() =
        project.overlays.filter { it.kind == OverlayKind.EMOJI }.map { it.startMs }.sorted()

    @Test
    fun variablesAreInterpolatedAndPersistBetweenCommands() {
        val e = engine()
        assertEquals("x = 2.5", e.executeCommand("let x = 2,5"))
        e.executeCommand("zoom \$x")
        assertEquals(2.5f, e.project.transform.zoom, 0.001f)

        e.executeCommand("x += 1")
        e.executeCommand("zoom {\$x - 2}")
        assertEquals(1.5f, e.project.transform.zoom, 0.001f)

        assertTrue(e.executeCommand("vars").contains("x = 3.5"))
        e.executeCommand("unset x")
        assertFalse(e.scriptEnv.variables.containsKey("x"))
    }

    @Test
    fun arithmeticComparisonsAndBuiltins() {
        val e = engine()
        assertEquals("14", e.executeCommand("print 2 + 3 * 4"))
        assertEquals("20", e.executeCommand("print (2 + 3) * 4"))
        assertEquals("2.5", e.executeCommand("print 5 / 2"))
        assertEquals("1", e.executeCommand("print 7 % 3"))
        assertEquals("5", e.executeCommand("print max(1, 5)"))
        assertEquals("48", e.executeCommand("print 00:48"))
        assertEquals("true", e.executeCommand("print 3 > 2 and not (1 == 2)"))
        assertEquals("Teil 3", e.executeCommand("print \"Teil \" + 3"))
        assertEquals("15", e.executeCommand("print \$duration"))
        assertEquals("Dauer: 15 s, 0 Untertitel", e.executeCommand("print Dauer: \$duration s, \$captions Untertitel"))
        assertTrue(e.executeCommand("print 1 / 0").startsWith("⚠"))
        assertTrue(e.commandHistory.last().isError)
    }

    @Test
    fun forLoopPlacesEmojisAtComputedTimes() {
        val e = engine()
        e.executeCommand("for t in 0..12 step 4: \$t-{\$t + 2} set emoji: 🔥")
        assertEquals(listOf(0L, 4_000L, 8_000L, 12_000L), e.emojiStarts())
        assertEquals(2_000L, e.project.overlays.first().endMs)
    }

    @Test
    fun blockProgramWithIfElseWhileAndRepeat() {
        val e = engine()
        e.executeCommand(
            """
            # Zähler hochzählen, bis er 3 erreicht
            let n = 0
            while ${'$'}n < 3
              n += 1
              if ${'$'}n == 2
                {${'$'}n}-{${'$'}n + 1} set caption: Zwei.red
              else
                emoji ⭐
              end
            end
            repeat 2: rotate 90
            """.trimIndent(),
        )
        assertEquals(3.0, e.scriptEnv.variables["n"])
        assertEquals(1, e.project.subtitles.size)
        assertEquals("Zwei", e.project.subtitles.single().text)
        assertEquals(2_000L, e.project.subtitles.single().startMs)
        assertEquals(2, e.project.overlays.count { it.kind == OverlayKind.EMOJI })
        assertEquals(180f, e.project.transform.rotationDegrees, 0.01f)
    }

    @Test
    fun semicolonProgramOnOneLineWorksInCommandBar() {
        val e = engine()
        e.executeCommand("if \$captions == 0; caption Erster; else; caption Zweiter; end")
        assertEquals(listOf("Erster"), e.project.subtitles.map { it.text })
        e.executeCommand("if \$captions == 0; caption Erster; else; caption Zweiter; end")
        assertEquals(setOf("Erster", "Zweiter"), e.project.subtitles.map { it.text }.toSet())
    }

    @Test
    fun functionsWithParametersAndFormulas() {
        val e = engine()
        e.executeCommand("def titel(text, farbe): 0-3 set text: \$text.\$farbe.top")
        e.executeCommand("titel(Mein Urlaub, gold)")
        val overlay = e.project.overlays.single()
        assertEquals("Mein Urlaub", overlay.content)
        assertEquals(0xFFFFB300L, overlay.colorArgb)
        assertEquals(0.16f, overlay.yPercent, 0.001f)

        e.executeCommand("def haelfte(x) = \$x / 2")
        e.executeCommand("zoom {haelfte(5)}")
        assertEquals(2.5f, e.project.transform.zoom, 0.001f)

        e.executeCommand("def intro(n)\n  all (1:1)\n  repeat \$n: emoji 🎬\nend\nintro(2)")
        assertEquals(AspectRatioPreset.SQUARE_1_1, e.project.aspectRatio)
        assertEquals(2, e.project.overlays.count { it.kind == OverlayKind.EMOJI })
        // Parameter sind lokal und bleiben nicht als globale Variable zurück
        assertNull(e.scriptEnv.variables["n"])
        assertTrue(e.executeCommand("titel(nur eins)").startsWith("⚠"))
    }

    @Test
    fun forLoopOverCaptionsAndLists() {
        val e = engine()
        e.executeCommand("1-3 set caption: Eins; 5-8 set caption: Zwei")
        e.executeCommand("for c in captions: \$c_start-\$c_end set emoji: 👀")
        assertEquals(listOf(1_000L, 5_000L), e.emojiStarts())

        e.executeCommand("for farbe in [red, gold]: text Hallo.\$farbe")
        val texts = e.project.overlays.filter { it.kind == OverlayKind.TEXT }
        // `text Hallo.red` legt den Text wörtlich an – wichtig ist hier, dass beide Listenwerte ankamen
        assertEquals(listOf("Hallo.red", "Hallo.gold"), texts.map { it.content })
    }

    @Test
    fun endlessLoopIsStoppedWithAMessage() {
        val e = engine()
        val result = e.executeCommand("let i = 0; while \$i < 1; print x; end")
        assertTrue(result.contains("abgebrochen") || result.contains("Abbruch"), result)
        assertTrue(e.commandHistory.last().isError)
    }

    @Test
    fun captionConditionOnlyAppliesWhileTheCaptionIsVisible() {
        val e = engine()
        e.executeCommand("0-2 set caption: Hallo.red")
        e.executeCommand("if caption red: u20%")
        assertEquals(0.80f, e.currentTransform(1_000L).cropBottom, 0.01f)
        assertEquals(1.0f, e.currentTransform(5_000L).cropBottom, 0.01f)
        assertEquals(1.0f, e.project.transform.cropBottom, 0.01f)

        // Auch bei mehreren Treffern bleibt der Rest des Videos unberührt
        val many = engine()
        many.executeCommand("0-2 set caption: Eins.red; 6-8 set caption: Zwei.red; if caption red: u20%")
        assertEquals(0.80f, many.currentTransform(7_000L).cropBottom, 0.01f)
        assertEquals(1.0f, many.currentTransform(4_000L).cropBottom, 0.01f)
    }

    @Test
    fun conditionAcceptsSeveralCommands() {
        for (script in listOf(
            "if caption red: u20%, move 10%u, zoom 1,5",
            "if caption red: u20% & move 10%u & zoom 1,5",
            "if caption red: u20% und move 10%u und zoom 1,5",
            "if caption red: u20% move 10%u && zoom 1,5",
            "if caption red\nu20%\nmove 10%u\nzoom 1,5\nend",
        )) {
            val e = engine()
            e.executeCommand("0-2 set caption: Hallo.red")
            e.executeCommand(script)
            val inside = e.currentTransform(1_000L)
            assertEquals(0.80f, inside.cropBottom, 0.01f, script)
            assertEquals(-0.10f, inside.panY, 0.01f, script)
            assertEquals(1.5f, inside.zoom, 0.01f, script)
            val outside = e.currentTransform(5_000L)
            assertEquals(1.0f, outside.cropBottom, 0.01f, script)
            assertEquals(0f, outside.panY, 0.01f, script)
            assertEquals(1.0f, outside.zoom, 0.01f, script)
        }

        // Freier Text darf Kommas und "und" enthalten – dort trennt nur &&
        val text = engine()
        text.executeCommand("0-2 set caption: Hallo.red")
        text.executeCommand("if caption red: set text: Du, ich und er && u20%")
        assertTrue(text.project.overlays.any { it.content == "Du, ich und er" && it.startMs == 0L && it.endMs == 2_000L })
        assertEquals(0.80f, text.currentTransform(1_000L).cropBottom, 0.01f)
    }

    @Test
    fun demoCommandBuildsAShowcaseOfEveryFeature() {
        val short = engine()
        short.executeCommand("demo")
        assertTrue(short.commandHistory.last().isError, "15-Sekunden-Projekt ist zu kurz für die Demo")

        val e = ProjectEngine()
        e.setVideoSource("/tmp/beispiel.mp4", 163_000L, "beispiel.mp4")
        val result = e.executeCommand("demo")
        assertFalse(e.commandHistory.last().isError, result)
        assertEquals(72_000L, e.project.playableDurationMs)
        assertEquals(14, e.project.subtitles.size)
        assertEquals(13, e.project.overlays.count { it.kind == OverlayKind.TEXT })
        // Zoom und Zuschnitt gelten nur in ihrem Kapitel
        assertEquals(1.8f, e.currentTransform(33_000L).zoom, 0.01f)
        assertEquals(0.80f, e.currentTransform(39_000L).cropBottom, 0.01f)
        assertEquals(1.0f, e.currentTransform(3_000L).zoom, 0.01f)
        assertEquals(1.0f, e.currentTransform(3_000L).cropBottom, 0.01f)
        assertEquals(1.0f, e.project.transform.cropBottom, 0.01f)
        // Schnell hintereinander angelegte Elemente brauchen trotzdem unterschiedliche IDs
        val ids = e.project.subtitles.map { it.id } + e.project.overlays.map { it.id } + e.project.timedFormatSpans.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun cropWithTimeRangeOnlyAppliesInsideThatRange() {
        val e = engine()
        e.executeCommand("0-2 u20%")
        e.executeCommand("4-6 move 10%u")
        assertEquals(0.80f, e.currentTransform(1_000L).cropBottom, 0.01f)
        assertEquals(1.0f, e.currentTransform(3_000L).cropBottom, 0.01f)
        assertEquals(-0.10f, e.currentTransform(5_000L).panY, 0.01f)
        assertEquals(0f, e.currentTransform(1_000L).panY, 0.01f)
        assertEquals(1.0f, e.project.transform.cropBottom, 0.01f)
        assertEquals(0f, e.project.transform.panY, 0.01f)

        // Ohne Zeitangabe oder mit all gilt es weiterhin fürs ganze Video
        e.executeCommand("all u30%")
        assertEquals(0.70f, e.project.transform.cropBottom, 0.01f)
        e.executeCommand("o10%")
        assertEquals(0.10f, e.project.transform.cropTop, 0.01f)
    }

    @Test
    fun projectVariablesBelongToTheirProjectAndGlobalsToAll() {
        val dir = java.nio.file.Files.createTempDirectory("pocketcraft-vars").toFile()
        try {
            val e = ProjectEngine().apply { projectStore = com.example.pocketcraft.engine.ProjectStore(dir) }
            e.executeCommand("let tempo = 2,5")
            e.executeCommand("global favoriten = [🚀, ⭐, 🎉]")
            e.executeCommand("global zaehler = 1")
            e.executeCommand("save Erstes")
            val firstId = e.currentProjectId!!

            // Neues Projekt: Projekt-Variable ist weg, globale sind da
            e.executeCommand("new Zweites")
            assertTrue(e.executeCommand("print \$tempo").contains("\$tempo"))
            assertEquals("1", e.executeCommand("print \$zaehler"))
            e.executeCommand("clear all")
            e.executeCommand("let t = 0; for x in \$favoriten; \$t-{\$t + 1} set emoji: \$x; t += 1; end")
            assertEquals(listOf("🚀", "⭐", "🎉"), e.project.overlays.filter { it.kind == OverlayKind.EMOJI }.map { it.content })

            // Ohne let ändert eine Zuweisung die globale Variable, mit let überdeckt sie sie nur im Projekt
            e.executeCommand("zaehler += 1")
            e.executeCommand("let favoriten = nur hier")
            e.executeCommand("save")

            // Zurück im ersten Projekt gelten wieder dessen Variablen
            e.openProject(firstId)
            assertEquals("2.5", e.executeCommand("print \$tempo"))
            assertEquals("2", e.executeCommand("print \$zaehler"))
            assertEquals("[🚀, ⭐, 🎉]", e.executeCommand("print \$favoriten"))

            // Nach einem Neustart sind Projekt- und globale Variablen noch da
            val restarted = ProjectEngine().apply { projectStore = com.example.pocketcraft.engine.ProjectStore(dir) }
            restarted.restoreLastSession()
            assertEquals("2.5", restarted.executeCommand("print \$tempo"))
            assertEquals("2", restarted.executeCommand("print \$zaehler"))
            restarted.executeCommand("zoom \$tempo")
            assertEquals(2.5f, restarted.project.transform.zoom, 0.001f)

            assertTrue(restarted.executeCommand("vars").contains("global zaehler = 2"))
            restarted.executeCommand("unset global zaehler")
            restarted.executeCommand("clear vars")
            val again = ProjectEngine().apply { projectStore = com.example.pocketcraft.engine.ProjectStore(dir) }
            assertTrue(again.executeCommand("print \$zaehler").contains("\$zaehler"))
            assertEquals("[🚀, ⭐, 🎉]", again.executeCommand("print \$favoriten"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun functionsAreSavedPerProjectAndGlobally() {
        val dir = java.nio.file.Files.createTempDirectory("pocketcraft-funcs").toFile()
        try {
            val e = ProjectEngine().apply { projectStore = com.example.pocketcraft.engine.ProjectStore(dir) }
            e.executeCommand("def haelfte(x) = \$x / 2")
            e.executeCommand("def marke(t)\n\$t-{\$t + 1} set emoji: ⭐\n{\$t + 1}-{\$t + 2} set emoji: 🎉\nend")
            e.executeCommand("global def titel(text, farbe): 0-3 set text: \$text.\$farbe.big.top")
            e.executeCommand("global def doppelt(x) = \$x * 2")
            e.executeCommand("save Erstes")
            val firstId = e.currentProjectId!!

            // Anderes Projekt: nur die globalen Funktionen sind da
            e.executeCommand("new Zweites")
            e.executeCommand("clear all")
            assertEquals("8", e.executeCommand("print {doppelt(4)}"))
            e.executeCommand("titel(Hallo, gold)")
            assertTrue(e.project.overlays.any { it.content == "Hallo" })
            assertTrue(e.executeCommand("print {haelfte(4)}").contains("haelfte"))
            e.executeCommand("save")

            // Nach einem Neustart: Projekt-Funktionen (Formel und Block) und globale sind wieder da
            val restarted = ProjectEngine().apply { projectStore = com.example.pocketcraft.engine.ProjectStore(dir) }
            restarted.openProject(firstId)
            restarted.executeCommand("clear all")
            assertEquals("2", restarted.executeCommand("print {haelfte(4)}"))
            restarted.executeCommand("marke(3)")
            assertEquals(listOf(3_000L, 4_000L), restarted.emojiStarts())
            assertEquals("8", restarted.executeCommand("print {doppelt(4)}"))
            val listing = restarted.executeCommand("vars")
            assertTrue(listing.contains("def haelfte(x)") && listing.contains("global def titel(text, farbe)"), listing)

            restarted.executeCommand("unset global doppelt")
            val again = ProjectEngine().apply { projectStore = com.example.pocketcraft.engine.ProjectStore(dir) }
            assertTrue(again.executeCommand("print {doppelt(4)}").contains("doppelt"))
            again.executeCommand("clear all")
            again.executeCommand("titel(Noch da, cyan)")
            assertTrue(again.project.overlays.any { it.content == "Noch da" })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun classicCommandsAndRulesStillBehaveAsBefore() {
        val e = engine()
        // Regel vor dem passenden Untertitel: wird wie bisher nach den Setup-Zeilen ausgewertet
        val result = e.executeCommand("if caption red: u20%\n2-6 set caption: Rot.red")
        assertTrue(result.startsWith("2 Zeilen ausgeführt"), result)
        assertTrue(e.project.timedFormatSpans.any { it.cropBottom != null })

        val loop = engine()
        loop.executeCommand("while 0-12 step 4: set emoji: 🔥")
        assertEquals(3, loop.project.overlays.count { it.kind == OverlayKind.EMOJI })

        val plain = engine()
        plain.executeCommand("text Preis: 5\$ {Angebot}")
        assertEquals("Preis: 5\$ {Angebot}", plain.project.overlays.single().content)
        plain.executeCommand("all u20%; all move 10%l")
        assertEquals(0.8f, plain.project.transform.cropBottom, 0.001f)
        assertEquals(0.1f, plain.project.transform.panY, 0.001f)
    }

    @Test
    fun filterMatrixIsNeutralWithoutFilterAndDesaturatesForNoir() {
        assertNull(FilterMath.colorMatrix(FilterConfig()))
        val noir = assertNotNull(FilterMath.colorMatrix(FilterConfig(preset = VideoFilterPreset.BW_NOIR)))
        // Schwarz-Weiß: alle drei Farbkanäle werden aus derselben Mischung berechnet
        for (col in 0 until 3) {
            assertEquals(noir[col], noir[5 + col], 0.0001f)
            assertEquals(noir[col], noir[10 + col], 0.0001f)
        }
    }
}

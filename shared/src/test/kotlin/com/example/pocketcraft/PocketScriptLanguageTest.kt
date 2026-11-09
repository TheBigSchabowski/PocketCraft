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

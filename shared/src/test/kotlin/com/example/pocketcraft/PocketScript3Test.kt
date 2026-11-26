package com.example.pocketcraft

import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.engine.ProjectStore
import com.example.pocketcraft.model.OverlayAnimation
import com.example.pocketcraft.model.OverlayKind
import com.example.pocketcraft.model.VideoFilterPreset
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies all bugfixes (math safety, span/step limits, inline comments, scoping, undo sync,
 * parser edge cases, boundary exclusivity, cut validation, auto-caption clamping) and
 * PocketScript 3.0 features (easing, fade/flash, shake/kenburns/pulse, karaoke, smart ranges,
 * reusable styles, hex colors, audio fade/mute/volume, break/continue/return, lists & indexing).
 */
class PocketScript3Test {

    private fun engine() = ProjectEngine().apply { executeCommand("clear all") }

    @Test
    fun mathNanAndOverflowNeverCrashAndReturnError() {
        val e = engine()
        val r1 = e.executeCommand("print sqrt(-1)")
        assertTrue(r1.startsWith("⚠"), r1)
        assertTrue(e.commandHistory.last().isError)

        val r2 = e.executeCommand("print 10 ^ 500")
        assertTrue(r2.startsWith("⚠"), r2)
        assertTrue(e.commandHistory.last().isError)
    }

    @Test
    fun spansAndWhileStepsAreNotSilentlyTruncated() {
        val e = engine()
        e.executeCommand("0-100")
        for (i in 0 until 30) {
            e.executeCommand("${i * 2}-${i * 2 + 1} zoom 1,5")
        }
        assertEquals(30, e.project.timedFormatSpans.size)

        val w = engine()
        w.executeCommand("while 0-20 step 0,5: set emoji: 🔥")
        assertEquals(40, w.project.overlays.count { it.kind == OverlayKind.EMOJI })
    }

    @Test
    fun inlineCommentsAndSemicolonsInQuotesWorkCleanly() {
        val e = engine()
        val res = e.executeCommand(
            """
            # Leading comment; zoom 4
            all (9:16)  # Format setzen
            0-4 caption: "Hallo; Welt!" # inline comment
            0-4 text: Angebot #1.#FF5733
            """.trimIndent(),
        )
        assertFalse(e.commandHistory.last().isError, res)
        assertEquals("Hallo; Welt!", e.project.subtitles.single().text)
        val txt = e.project.overlays.single { it.kind == OverlayKind.TEXT }
        assertEquals("Angebot #1", txt.content)
        assertEquals(0xFFFF5733L, txt.colorArgb)
        assertEquals(1.0f, e.project.transform.zoom, 0.01f)
    }

    @Test
    fun missingCutMarkerReturnsErrorInsteadOfFallingBack() {
        val e = engine()
        e.executeCommand("0-30")
        e.executeCommand("set cut1 00:10")
        val beforeTrimEnd = e.project.trimEndMs
        val res = e.executeCommand("delete after cut2")
        assertTrue(res.startsWith("⚠"), res)
        assertTrue(e.commandHistory.last().isError)
        assertEquals(beforeTrimEnd, e.project.trimEndMs)
    }

    @Test
    fun singleAxisRotationDoesNotEraseOtherAxesTimedRotations() {
        val e = engine()
        e.executeCommand("0-4 rotate y 0 -> 360")
        e.executeCommand("rotate 90")
        assertEquals(90f, e.project.transform.rotationDegrees, 0.01f)
        assertEquals(180f, e.currentTransform(2_000L).rotationYDegrees, 0.01f)
    }

    @Test
    fun shortCircuitOperatorsAndRandomBoundsWork() {
        val e = engine()
        e.executeCommand("let x = 0")
        val res = e.executeCommand("if \$x != 0 and 10 / \$x > 2\n  print ok\nelse\n  print safe\nend")
        assertFalse(e.commandHistory.last().isError, res)
        assertEquals("safe", res)

        for (i in 0 until 20) {
            val v = e.executeCommand("print random(10, 5)").toDouble()
            assertTrue(v in 5.0..10.0, "Got $v outside 5..10")
        }
    }

    @Test
    fun scopingUndoSyncAndIfElseWithLegacyConditions() {
        val e = engine()
        e.executeCommand("let x = 10")
        e.executeCommand("def shadow(a)\n  let x = 99\n  return \$x + \$a\nend")
        assertEquals("104", e.executeCommand("print {shadow(5)}"))
        // Project variable `x` was NOT clobbered by `let x = 99` inside `shadow`
        assertEquals("10", e.executeCommand("print \$x"))

        // Loop variables do not leak into project variables
        e.executeCommand("for t in 1..3: print \$t")
        assertNull(e.scriptEnv.variables["t"])

        // `if caption red ... else ... end` runs `else` when no red caption exists
        val elseRes = e.executeCommand("if caption red\n  let ran = yes\nelse\n  print no-red\nend")
        assertEquals("no-red", elseRes)
        assertNull(e.scriptEnv.variables["ran"])

        // Timed filter condition works with `if filter bw`
        val f = engine()
        f.executeCommand("2-6 filter bw")
        f.executeCommand("if filter bw: zoom 1,5")
        assertEquals(1.5f, f.currentTransform(4_000L).zoom, 0.01f)
        assertEquals(1.0f, f.currentTransform(1_000L).zoom, 0.01f)

        // Undo syncs scriptEnv variables back
        val u = engine()
        u.executeCommand("let tempo = 2")
        u.executeCommand("zoom \$tempo")
        assertEquals(2.0f, u.project.transform.zoom, 0.01f)
        u.executeCommand("undo")
        assertEquals(1.0f, u.project.transform.zoom, 0.01f)
    }

    @Test
    fun parserEdgeCasesMoveRunterZoomAtTopSizePlus5AndBoundaries() {
        val e = engine()
        e.executeCommand("move 10% runter")
        assertEquals(0.10f, e.project.transform.panY, 0.01f)

        // Two segments where first ends with `at top` must not fuse `top` with `4-8`
        val z = engine()
        val zRes = z.executeCommand("0-4 zoom in at top 4-8 zoom out")
        assertFalse(z.commandHistory.last().isError, zRes)
        assertEquals(1.8f, z.currentTransform(3_999L).zoom, 0.02f)
        assertEquals(1.4f, z.currentTransform(6_000L).zoom, 0.02f)

        // `size text1 +5` means +5% (0.05), not +500%
        val s = engine()
        s.executeCommand("0-5 set text: Hi")
        val before = s.project.overlays.single().scale
        s.executeCommand("size text1 +5")
        assertEquals(before + 0.05f, s.project.overlays.single().scale, 0.005f)

        // Back-to-back subtitles switch cleanly at boundary 3000ms
        val sub = engine()
        sub.executeCommand("0-3 caption: A; 3-6 caption: B")
        assertEquals("A", sub.project.subtitleAt(2_999L)?.text)
        assertEquals("B", sub.project.subtitleAt(3_000L)?.text)
    }

    @Test
    fun realVideoStripsDemoPlaceholdersClampsCutsAndAutoCaptions() {
        val e = ProjectEngine()
        assertTrue(e.project.subtitles.isNotEmpty())
        assertTrue(e.project.overlays.isNotEmpty())

        e.setVideoSource("/tmp/real.mp4", 30_000L, "real.mp4")
        assertTrue(e.project.subtitles.isEmpty())
        assertTrue(e.project.overlays.isEmpty())

        // `set cut` past real video duration must fail instead of pushing playhead past end
        val cutErr = e.executeCommand("set cut 00:48")
        assertTrue(cutErr.startsWith("⚠"), cutErr)
        assertTrue(e.playheadMs <= 30_000L)

        // Short clip auto-captions must have valid startMs < endMs
        e.setTrimRange(0L, 2_000L)
        e.generateAutoCaptions("Eins zwei drei vier fünf sechs sieben acht neun zehn elf zwölf")
        assertTrue(e.project.subtitles.isNotEmpty())
        assertTrue(e.project.subtitles.all { it.startMs < it.endMs && it.endMs <= 2_000L })

        // `reset` on real video keeps demo placeholders empty
        e.executeCommand("reset")
        assertTrue(e.project.subtitles.isEmpty())
        assertTrue(e.project.overlays.isEmpty())
    }

    @Test
    fun easingCurvesVideoFadeFlashShakeKenBurnsAndPulse() {
        val e = engine()
        e.executeCommand("0-4 zoom 0 -> 1 ease-out")
        // With cubic ease-out at t=0.5: 1 - 0.5^3 = 0.875 -> 0.1 + 0.9 * 0.875 = 0.8875 (much larger than linear 0.55)
        assertTrue(e.currentTransform(2_000L).zoom > 0.85f)

        e.executeCommand("0-2 fade in")
        val fadeStart = e.project.videoFadeAt(0L)
        val fadeEnd = e.project.videoFadeAt(1_999L)
        assertNotNull(fadeStart)
        assertTrue(fadeStart.second > 0.95f)
        assertTrue((fadeEnd?.second ?: 0f) < 0.05f)

        e.executeCommand("4-4,4 flash gold")
        val flashPeak = e.project.videoFadeAt(4_060L)
        assertNotNull(flashPeak)
        assertEquals(0xFFFFB300L, flashPeak.first)
        assertTrue(flashPeak.second > 0.5f)

        e.executeCommand("5-7 shake strong")
        val shaken = e.currentTransform(5_300L)
        assertTrue(shaken.zoom > 1.05f)

        e.executeCommand("8-12 kenburns right")
        val kbMid = e.currentTransform(10_000L)
        assertTrue(kbMid.zoom > 1.05f)
        assertEquals(0.0f, kbMid.panX, 0.02f)

        e.executeCommand("bpm 120")
        assertEquals(120f, e.project.bpm)
        e.executeCommand("12-15 pulse 120bpm")
        assertTrue(e.currentTransform(12_010L).zoom > 1.05f)
    }

    @Test
    fun animatedOverlaysKaraokeStylesAndSmartTimeRanges() {
        val dir = Files.createTempDirectory("pc_styles").toFile()
        try {
            val e = ProjectEngine().apply { projectStore = ProjectStore(dir) }
            e.executeCommand("clear all")
            e.executeCommand("0-20")
            e.executeCommand("global style hook = .gold.big.pop")
            e.executeCommand("0-4 text: START!.@hook")
            val txt = e.project.overlays.single { it.kind == OverlayKind.TEXT }
            assertEquals(0xFFFFB300L, txt.colorArgb)
            assertEquals(1.45f, txt.scale, 0.01f)
            assertEquals(OverlayAnimation.POP, txt.animation)
            // Pop entrance starts small at t=0 and reaches full scale after 350ms
            assertTrue(txt.scaleAt(0L) < 0.5f)
            assertEquals(1.45f, txt.scaleAt(500L), 0.02f)

            // Animated move & size on overlay
            e.executeCommand("move text1 20 80 -> 80 20 ease")
            assertEquals(0.50f, txt.copy(
                xPercent = e.project.overlays.single().xPercent,
                xEndPercent = e.project.overlays.single().xEndPercent,
                easing = e.project.overlays.single().easing,
            ).xAt(2_000L), 0.02f)
            e.executeCommand("0-4 emoji: 🚀.typewriter")
            e.executeCommand("size emoji1 50 -> 200 bounce")
            val em = e.project.overlays.single { it.kind == OverlayKind.EMOJI }
            assertEquals(0.5f, em.scale, 0.01f)
            assertEquals(2.0f, em.scaleEnd ?: 0f, 0.01f)

            // Karaoke subtitle + sequential `+3` + `last 5` + `end-2..end` + `0-50%`
            e.executeCommand("4-8 caption: Eins Zwei Drei Vier.white.karaoke.hlgold")
            val kSub = e.project.subtitles.single()
            assertTrue(kSub.karaoke)
            assertEquals(0xFFFFB300L, kSub.highlightColorArgb)
            assertEquals(0, kSub.activeWordIndexAt(4_100L))
            assertEquals(2, kSub.activeWordIndexAt(6_100L))

            e.executeCommand("+3 caption: Nahtlos")
            val nextSub = e.project.subtitles.last()
            assertEquals(8_000L, nextSub.startMs)
            assertEquals(11_000L, nextSub.endMs)

            e.executeCommand("last 5 filter bw")
            assertEquals(VideoFilterPreset.BW_NOIR, e.currentFilter(17_000L).preset)
            assertEquals(VideoFilterPreset.NONE, e.currentFilter(10_000L).preset)

            e.executeCommand("0-50% crop 10%")
            assertEquals(0.10f, e.currentTransform(5_000L).cropLeft, 0.01f)
            assertEquals(0.0f, e.currentTransform(12_000L).cropLeft, 0.01f)

            // Global style persists across projects and restarts
            val e2 = ProjectEngine().apply { projectStore = ProjectStore(dir) }
            e2.executeCommand("clear all")
            e2.executeCommand("0-2 text: Neu.@hook")
            assertEquals(0xFFFFB300L, e2.project.overlays.single().colorArgb)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun audioCommandsAndProgrammingPowerFeatures() {
        val e = engine()
        e.executeCommand("0-20")
        e.executeCommand("volume 150%")
        assertEquals(1.5f, e.project.audioVolume, 0.01f)
        e.executeCommand("2-5 mute")
        assertEquals(0.0f, e.project.audioGainAt(3_000L), 0.01f)
        assertEquals(1.5f, e.project.audioGainAt(6_000L), 0.01f)

        e.executeCommand("0-2 audio fade in")
        assertEquals(0.0f, e.project.audioGainAt(0L), 0.01f)
        assertTrue(e.project.audioGainAt(1_000L) in 0.4f..1.1f)
        assertEquals(1.5f, e.project.audioGainAt(1_999L), 0.05f)

        // break, continue, multi-line def with return, lists + indexing, string interpolation, contains
        val progRes = e.executeCommand(
            """
            let farben = [rot, gold, cyan]
            let picked = "${'$'}{farben[1]}"
            def sumUntil(limit)
              let total = 0
              for i in 1..10
                if ${'$'}i == 2: continue
                if ${'$'}i > ${'$'}limit: break
                total += ${'$'}i
              end
              return ${'$'}total
            end
            let s = sumUntil(4)
            if ${'$'}title contains "Pixel"
              print "OK: ${'$'}picked / ${'$'}s / len=${'$'}{len(${'$'}farben)} / ${'$'}{timecode(93.5)} / ${'$'}{clamp(12, 0, 5)}"
            end
            """.trimIndent(),
        )
        assertFalse(e.commandHistory.last().isError, progRes)
        assertEquals("OK: gold / 8 / len=3 / 01:33.5 / 5", e.lastScriptOutput.last().text)
        assertTrue(progRes.contains("OK: gold / 8 / len=3 / 01:33.5 / 5"), progRes)

        // beats() loop
        val b = engine()
        b.executeCommand("bpm 120")
        b.executeCommand("for t in beats(0..1.5)\n  ${'$'}t-{${'$'}t + 0.1} flash\nend")
        assertEquals(4, b.project.timedFormatSpans.count { it.isFlashPulse })
    }
}

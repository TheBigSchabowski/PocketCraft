package com.example.pocketcraft.engine

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import androidx.core.content.FileProvider
import com.example.pocketcraft.model.AspectRatioPreset
import com.example.pocketcraft.model.BlurConfig
import com.example.pocketcraft.model.BlurMode
import com.example.pocketcraft.model.BuiltInFont
import com.example.pocketcraft.model.CanvasOverlay
import com.example.pocketcraft.model.CutMarker
import com.example.pocketcraft.model.OverlayKind
import com.example.pocketcraft.model.SubtitleSegment
import com.example.pocketcraft.model.TimedFormatSpan
import com.example.pocketcraft.model.VideoFilterPreset
import java.io.File
import kotlin.math.roundToInt

/**
 * Parser und Interpreter für **PocketScript** – die kompakte Videoschnitt-Programmiersprache
 * für die Ein-Zeilen-Befehlsleiste.
 *
 * Unterstützte Syntax-Konstrukte:
 * 1. **Zeitbereiche + Seitenverhältnis / Auflösung in Klammern** (auch mehrere hintereinander!):
 *    - `10-24,3 (8:15) 9 (16:18)` → von 10s bis 24,3s Format 8:15, ab 9s Format 16:18
 *    - `all (9:16)` oder `all (1080:1920)` → für das gesamte Video Format 9:16 setzen
 * 2. **Zeitgesteuerte `set`-Befehle mit `.modifier`-Kette** (Farbe, Schriftart, Größe, Position):
 *    - `8-10 set caption: Pinker Honig.red` → Untertitel "Pinker Honig" von 8s bis 10s in Rot
 *    - `2-6,5 set text: Kapitel Eins.pink.serif.big` → Text-Overlay von 2s bis 6,5s in Pink & Serif
 *    - `4-8 set emoji: 🔥.big` → Emoji 🔥 von 4s bis 8s
 *    - `0-5 set zoom: 2,2` → Zoom auf 2.2x
 *    - `3-9 set blur: 25.privacy` oder `all set filter: cyberpunk`
 * 3. **Schnell-Kommandos**:
 *    - `undo`, `redo`, `reset`, `pdf` / `manual` / `anleitung`
 */
object PocketScriptParser {

    /**
     * Farbtabelle für `.farbe` Suffixe (Deutsch & Englisch + Hex).
     */
    private val colorMap: Map<String, Long> = mapOf(
        "red" to 0xFFFF3B30L,
        "rot" to 0xFFFF3B30L,
        "pink" to 0xFFFF2D95L,
        "rosa" to 0xFFFF80ABL,
        "blue" to 0xFF2979FFL,
        "blau" to 0xFF2979FFL,
        "cyan" to 0xFF00E5FFL,
        "türkis" to 0xFF00E5FFL,
        "tuerkis" to 0xFF00E5FFL,
        "green" to 0xFF00E676L,
        "grün" to 0xFF00E676L,
        "gruen" to 0xFF00E676L,
        "yellow" to 0xFFFFEA00L,
        "gelb" to 0xFFFFEA00L,
        "gold" to 0xFFFFB300L,
        "orange" to 0xFFFF6D00L,
        "purple" to 0xFFD500F9L,
        "lila" to 0xFFD500F9L,
        "violett" to 0xFFB388FFL,
        "white" to 0xFFFFFFFFL,
        "weiss" to 0xFFFFFFFFL,
        "weiß" to 0xFFFFFFFFL,
        "black" to 0xFF111111L,
        "schwarz" to 0xFF111111L,
    )

    data class ParsedTimeRange(
        val startMs: Long,
        val endMs: Long,
        val isAll: Boolean = false,
    )

    data class StyledPayload(
        val cleanText: String,
        val colorArgb: Long?,
        val bgColorArgb: Long?,
        val fontId: String?,
        val fontSizeSp: Float?,
        val scale: Float?,
        val yPercent: Float?,
    )

    // Regex für `if <Bedingung>: <Befehl>` und `while <Bedingung>: <Befehl>`
    // Beispiel: `if caption red: u20%`, `if caption red: (9:16)`, `while caption red: u20%`,
    //           `while 0-12 step 4: set emoji: 🔥`
    private val ifWhileRegex = Regex(
        pattern = """^(if|wenn|while|solange)\s+((?:\([^)]*\)|[^:])+?)\s*:\s*(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für `set cut 00:48`, `set cut1 00:48`, `cut at 00:48`, `cut 00:48`, `set cut: 00:48`
    private val cutMarkerRegex = Regex(
        pattern = """^(?:all\s+)?(?:set\s+)?cut(\d+)?(?:\s+at|\s*:)?\s+(\d+(?::\d+)+|\d+(?:[.,]\d+)?(?:s|sek)?)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für `delete after cut1`, `delete behind cut`, `delete before cut1`, `delete after 00:48`
    private val deleteCutRegex = Regex(
        pattern = """^(?:delete|del|remove|lösche|loesche|cut)\s+(after|behind|nach|hinter|before|in\s+front\s+of|vor|front|between|zwischen)\s+(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für `delete 10-20` / `delete 00:10-00:20` (Mittelstück herausschneiden)
    private val deleteRangeRegex = Regex(
        pattern = """^(?:delete|del|remove|lösche|loesche)\s+(\d+(?::\d+)*(?:[.,]\d+)?)\s*-\s*(\d+(?::\d+)*(?:[.,]\d+)?)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für `<Zeitbereich> set <ziel>: <inhalt>`
    // Beispiel: `8-10 set caption: Pinker Honig.red` oder `all set filter: cyberpunk`
    private val setStatementRegex = Regex(
        pattern = """^(all|\d+(?::\d+)+|\d+(?:[.,]\d+)?(?:\s*-\s*(?:\d+(?::\d+)+|\d+(?:[.,]\d+)?))?)?\s*(?:set\s+)?(caption|sub|untertitel|text|title|titel|emoji|sticker|zoom|blur|filter|fx|speed|crop|cut|trim)\s*:\s*(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Token-Regexes für Format-Klammern `(8:15)`, Richtungs-Zuschnitte `u20%`, `o20%`, `r20%`, `l20%`
    // sowie prozentuale Verschiebungen `move 10%l` (10% lower/runter) und `move 10%u` (10% up/hoch).
    private val timeTokenRegex = Regex(
        pattern = """^(all|alles|\*|(?:\d+(?::\d+)+|\d+(?:[.,]\d+)?)(?:-(?:\d+(?::\d+)+|\d+(?:[.,]\d+)?))?)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val ratioTokenRegex = Regex(
        pattern = """^\((\d+(?:[.,]\d+)?)[:/x](\d+(?:[.,]\d+)?)\)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val edgeCropTokenRegex = Regex(
        pattern = """^(u|unten|o|oben|r|rechts|l|links)(\d+(?:[.,]\d+)?)%?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val moveSuffixTokenRegex = Regex(
        pattern = """^(\d+(?:[.,]\d+)?)%?(lower|low|down|unten|d|l|up|upper|oben|u|o|right|rechts|r|left|links)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val movePrefixTokenRegex = Regex(
        pattern = """^(lower|low|down|unten|d|up|upper|oben|right|rechts|left|links|u|o|r|l)(\d+(?:[.,]\d+)?)%?$""",
        option = RegexOption.IGNORE_CASE,
    )

    private data class FormatAndCropSegment(
        var timeToken: String = "all",
        var ratioW: Float? = null,
        var ratioH: Float? = null,
        var cutBottomPct: Float? = null, // u20% -> untere 20% wegschneiden
        var cutTopPct: Float? = null,    // o20% -> obere 20% wegschneiden
        var cutRightPct: Float? = null,  // r20% -> rechte 20% wegschneiden
        var cutLeftPct: Float? = null,   // l20% -> linke 20% wegschneiden
        var moveXPct: Float? = null,     // +10% = rechts, -10% = links
        var moveYPct: Float? = null,     // +10% = 10%l (lower/runter), -10% = 10%u (up/hoch)
    ) {
        val hasModifiers: Boolean
            get() = ratioW != null ||
                cutBottomPct != null ||
                cutTopPct != null ||
                cutRightPct != null ||
                cutLeftPct != null ||
                moveXPct != null ||
                moveYPct != null
    }

    /**
     * Prüft, ob die Eingabe ein PocketScript-Programm ist, führt es auf [engine] aus
     * und gibt `null` zurück, falls es stattdessen ein klassischer Einzelbefehl (`undo`, `zoom 1.5` etc.) ist.
     */
    fun tryExecuteScript(rawInput: String, engine: ProjectEngine): String? {
        var input = rawInput.trim()
        if (input.isEmpty()) return null

        // Unterstützt auch nummerierte Aufzählungen in einer oder mehreren Zeilen wie:
        // `1. all u20% 2. all move 10%l 3. set cut 00:48 4. delete after cut1`
        if (Regex("""^\d+\.\s+[a-zA-Z(]""").containsMatchIn(input)) {
            val numberedSteps = input
                .split(Regex("""(?:^|\s+)\d+\.\s+(?=[a-zA-Z(])"""))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (numberedSteps.size > 1) {
                input = numberedSteps.joinToString("\n")
            } else if (numberedSteps.size == 1) {
                input = numberedSteps.first()
            }
        }

        // Mehrzeilige Programme (durch Zeilenumbruch `\n` oder `;` getrennt) Zeile für Zeile ausführen.
        // Falls eine `if`/`while`-Zeile vor einer `set caption:`-Zeile steht, werden erst die
        // Setup-Zeilen und direkt danach die `if`/`while`-Regeln ausgewertet.
        if (input.contains("\n") || input.contains(";")) {
            val lines = input.split(Regex("[\r\n;]+"))
                .map { it.trim().replace(Regex("""^\d+\.\s+"""), "") }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
            if (lines.isEmpty()) return "Keine ausführbaren Zeilen."
            if (lines.size > 1) {
                val (condLines, normalLines) = lines.partition { ifWhileRegex.matches(it) }
                val ordered = normalLines + condLines
                val results = ordered.map { line -> engine.executeCommand(line) }
                return "${lines.size} Zeilen ausgeführt: " + results.joinToString(" · ")
            } else {
                return engine.executeCommand(lines.first())
            }
        }

        // 0. Prüfen auf `if <Bedingung>: <Befehl>` oder `while <Bedingung>: <Befehl>`
        //    z.B. `if caption red: u20%`, `if caption red: (9:16)`, `while caption red: u20%`
        val ifWhileMatch = ifWhileRegex.matchEntire(input)
        if (ifWhileMatch != null) {
            val keyword = ifWhileMatch.groupValues[1].lowercase()
            val conditionRaw = ifWhileMatch.groupValues[2].trim()
            val actionBody = ifWhileMatch.groupValues[3].trim()
            return executeIfOrWhileStatement(keyword, conditionRaw, actionBody, engine)
        }

        // 0b. Prüfen auf `set cut 00:48` / `set cut1 00:48` / `cut at 00:48`
        val cutMatch = cutMarkerRegex.matchEntire(input)
        if (cutMatch != null) {
            val cutNum = cutMatch.groupValues[1]
            val timeToken = cutMatch.groupValues[2]
            return executeCutMarkerStatement(cutNum, timeToken, engine)
        }

        // 0c. Prüfen auf `delete after cut1` / `delete behind cut` / `delete before cut1`
        val delMatch = deleteCutRegex.matchEntire(input)
        if (delMatch != null) {
            val dir = delMatch.groupValues[1].lowercase()
            val target = delMatch.groupValues[2].trim()
            return executeDeleteCutStatement(dir, target, engine)
        }

        // 0d. Prüfen auf `delete 10-20` (Bereich mitten aus dem Video herausschneiden)
        val delRangeMatch = deleteRangeRegex.matchEntire(input)
        if (delRangeMatch != null) {
            val startMs = parseSecondsToMs(delRangeMatch.groupValues[1])
            val endMs = parseSecondsToMs(delRangeMatch.groupValues[2])
            return executeRemoveRange(startMs, endMs, engine)
        }

        // 1. Prüfen auf `<Zeitbereich> set <Ziel>: <Wert.modifier>`
        val setMatch = setStatementRegex.matchEntire(input)
        if (setMatch != null) {
            val rangeToken = setMatch.groupValues[1].ifBlank { "all" }
            val targetKind = setMatch.groupValues[2].lowercase()
            val rawPayload = setMatch.groupValues[3].trim()
            return executeSetStatement(rangeToken, targetKind, rawPayload, engine)
        }

        // 2. Prüfen auf Format-/Auflösungs-Ausdrücke (`10-24,3 (8:15) 9 (16:18)`, `all (9:16)`),
        //    prozentuale Kanten-Zuschnitte (`all u20%`, `o20%`, `r20%`, `l20%`, `90 u90%`)
        //    UND prozentuale Bildverschiebungen (`all move 10%l`, `all move 10%u`, `u20% move 10%l`)
        val formatOrCropResult = tryParseAndExecuteFormatAndCrop(input, engine)
        if (formatOrCropResult != null) {
            return formatOrCropResult
        }

        // 3. Prüfen auf direkten Zeitbereichs-Trim wie `10-24,3` allein
        val pureRangeRegex = Regex("""^(\d+(?:[.,]\d+)?)\s*-\s*(\d+(?:[.,]\d+)?)$""")
        val pureMatch = pureRangeRegex.matchEntire(input)
        if (pureMatch != null) {
            val startMs = parseSecondsToMs(pureMatch.groupValues[1])
            val endMs = parseSecondsToMs(pureMatch.groupValues[2])
            if (endMs > startMs) {
                if (endMs > engine.project.sourceDurationMs) {
                    engine.commitChange("Dauer erweitert & gekürzt (${ProjectEngine.formatSec(startMs)}-${ProjectEngine.formatSec(endMs)})") { p ->
                        p.copy(
                            sourceDurationMs = endMs,
                            trimStartMs = startMs,
                            trimEndMs = endMs,
                        )
                    }
                } else {
                    engine.setTrimRange(startMs, endMs)
                }
                return "Zeitbereich ${ProjectEngine.formatSec(startMs)}–${ProjectEngine.formatSec(endMs)} aktiv gesetzt."
            }
        }

        return null
    }

    /**
     * Führt bedingte und wiederholte PocketScript-Anweisungen (`if` / `while`) aus.
     *
     * Unterstützte Formen:
     * 1. `if caption red: u20%` oder `if caption red: (9:16)`
     *    → Sucht alle Untertitel mit der Farbe `.red` (oder dem Text `"red"`), wendet für deren
     *      Zeitfenster den Befehl dahinter (`u20%`, `(9:16)`, `zoom 1,8` etc.) an und springt dorthin.
     *      Falls noch kein passender Farb-Untertitel existiert, wird der aktuelle Untertitel am
     *      Playhead entsprechend eingefärbt und der Befehl direkt angewendet.
     * 2. `while caption red: u20%` / `while caption: (9:16)` / `while text cyan: u30%` / `while emoji 🔥: (1:1)`
     *    → Wendet den Befehl für die gesamte Dauer aller passenden Untertitel / Text- / Emoji-Overlays an.
     * 3. `while 0-12 step 4: set emoji: 🔥` (oder `while step 3: ...`)
     *    → Schleife über die Timeline in festen Sekunden-Intervallen (`step` / `every` / `alle`).
     */
    private fun executeIfOrWhileStatement(
        keyword: String,
        conditionRaw: String,
        actionBody: String,
        engine: ProjectEngine,
    ): String {
        val cond = conditionRaw.trim()
        val condLower = cond.lowercase()
        val labelPrefix = if (keyword.startsWith("w") && keyword != "wenn") "While" else "If"

        // A) Zeitintervall-Schleife: `while 0-12 step 4: ...` oder `while step 3: ...`
        val stepLoopRegex = Regex(
            pattern = """^(?:(all|\d+(?:[.,]\d+)?(?:\s*-\s*\d+(?:[.,]\d+)?)?)\s+)?(?:step|every|alle|schritt)\s+(\d+(?:[.,]\d+)?)s?$""",
            option = RegexOption.IGNORE_CASE,
        )
        val stepMatch = stepLoopRegex.matchEntire(cond)
        if (stepMatch != null) {
            val rangeTok = stepMatch.groupValues[1].ifBlank { "all" }
            val range = parseTimeRange(rangeTok, engine)
            val stepMs = parseSecondsToMs(stepMatch.groupValues[2]).coerceAtLeast(1_000L)
            var t = range.startMs
            var iterations = 0
            val lastResults = mutableListOf<String>()
            while (t < range.endMs && iterations < 12) {
                val wEnd = (t + stepMs).coerceAtMost(range.endMs)
                lastResults.add(executeScopedBody(t, wEnd, actionBody, engine))
                t += stepMs
                iterations++
            }
            return "$labelPrefix-Schleife ($iterations Schritte à ${ProjectEngine.formatSec(stepMs)}): ${lastResults.lastOrNull() ?: actionBody}"
        }

        // B) `if/while caption <qualifier>: <action>`
        val captionCondRegex = Regex(
            pattern = """^(?:caption|sub|untertitel)(?:\s+(.+))?$""",
            option = RegexOption.IGNORE_CASE,
        )
        val capMatch = captionCondRegex.matchEntire(cond)
        if (capMatch != null) {
            val qualifier = capMatch.groupValues[1].trim().removePrefix(".").removeSurrounding("\"").lowercase()
            val targetColor = colorMap[qualifier]

            var matchedSubs = engine.project.subtitles.filter { sub ->
                when {
                    qualifier.isEmpty() -> true
                    targetColor != null -> sub.textColorArgb == targetColor ||
                        sub.highlightColorArgb == targetColor ||
                        sub.text.lowercase().contains(qualifier)
                    qualifier in listOf("big", "gross", "xl", "large", "huge", "xxl") -> sub.fontSizeSp >= 24f
                    qualifier in listOf("small", "klein", "sm") -> sub.fontSizeSp <= 16f
                    else -> sub.text.lowercase().contains(qualifier) || sub.fontId.lowercase().contains(qualifier)
                }
            }

            // Falls der Nutzer z.B. `if caption red: u20%` direkt auf dem Startprojekt ausführt,
            // in dem noch kein Untertitel rot ist, färben wir den aktiven Untertitel am Playhead
            // in dieser Farbe ein, damit die Bedingung sofort sichtbar greift.
            if (matchedSubs.isEmpty() && targetColor != null && engine.project.subtitles.isNotEmpty()) {
                val activeOrFirst = engine.project.subtitles.firstOrNull {
                    engine.playheadMs in it.startMs..it.endMs
                } ?: engine.project.subtitles.first()
                engine.commitChange("Untertitel '${activeOrFirst.text}' auf .$qualifier gesetzt") { p ->
                    p.copy(
                        subtitles = p.subtitles.map {
                            if (it.id == activeOrFirst.id) {
                                it.copy(textColorArgb = targetColor, highlightColorArgb = targetColor)
                            } else {
                                it
                            }
                        },
                    )
                }
                matchedSubs = engine.project.subtitles.filter { it.id == activeOrFirst.id }
            }

            if (matchedSubs.isEmpty()) {
                return "$labelPrefix ($cond): Kein passender Untertitel gefunden."
            }

            // Für alle gefundenen Untertitel-Zeitfenster den Zielbefehl ausführen
            val combinedRangeExpr = buildCombinedScopedCommand(
                ranges = matchedSubs.map { it.startMs to it.endMs },
                actionBody = actionBody,
            )
            val execResult = if (combinedRangeExpr != null) {
                // Wenn `if` genutzt wurde oder nur 1 Treffer vorliegt, setzen wir zusätzlich den globalen
                // Zustand, damit der Effekt auch außerhalb des engen Zeitfensters sofort sichtbar ist.
                val res = tryParseAndExecuteFormatAndCrop(combinedRangeExpr, engine)
                    ?: engine.executeCommand(actionBody)
                if (matchedSubs.size > 1) {
                    tryParseAndExecuteFormatAndCrop("all $actionBody", engine)
                }
                res
            } else {
                var lastRes = ""
                for (sub in matchedSubs) {
                    lastRes = executeScopedBody(sub.startMs, sub.endMs, actionBody, engine)
                }
                lastRes
            }
            engine.playheadMs = matchedSubs.first().startMs
            return "$labelPrefix ($cond → ${matchedSubs.size} Treffer): $execResult"
        }

        // C) `if/while text <qualifier>: <action>` oder `if/while emoji <qualifier>: <action>`
        val overlayCondRegex = Regex(
            pattern = """^(text|title|titel|emoji|sticker)(?:\s+(.+))?$""",
            option = RegexOption.IGNORE_CASE,
        )
        val ovMatch = overlayCondRegex.matchEntire(cond)
        if (ovMatch != null) {
            val kindToken = ovMatch.groupValues[1].lowercase()
            val qualifier = ovMatch.groupValues[2].trim().removePrefix(".").lowercase()
            val wantKind = if (kindToken.startsWith("e") || kindToken.startsWith("s")) OverlayKind.EMOJI else OverlayKind.TEXT
            val targetColor = colorMap[qualifier]

            val matchedOverlays = engine.project.overlays.filter { ov ->
                ov.kind == wantKind && (
                    qualifier.isEmpty() ||
                        (targetColor != null && ov.colorArgb == targetColor) ||
                        ov.content.lowercase().contains(qualifier) ||
                        ov.fontId.lowercase().contains(qualifier)
                    )
            }

            if (matchedOverlays.isEmpty()) {
                return "$labelPrefix ($cond): Kein passendes Overlay gefunden."
            }

            val combinedRangeExpr = buildCombinedScopedCommand(
                ranges = matchedOverlays.map { it.startMs to it.endMs },
                actionBody = actionBody,
            )
            val execResult = if (combinedRangeExpr != null) {
                tryParseAndExecuteFormatAndCrop(combinedRangeExpr, engine)
                    ?: engine.executeCommand(actionBody)
            } else {
                var lastRes = ""
                for (ov in matchedOverlays) {
                    lastRes = executeScopedBody(ov.startMs, ov.endMs, actionBody, engine)
                }
                lastRes
            }
            engine.playheadMs = matchedOverlays.first().startMs
            return "$labelPrefix ($cond → ${matchedOverlays.size} Overlay-Treffer): $execResult"
        }

        // D) Allgemeine Projekt-Bedingungen (`if filter cyberpunk: ...`, `if (9:16): ...`, `if blur: ...`)
        val isGeneralMatch = when {
            condLower.startsWith("filter") || condLower.startsWith("fx") -> {
                val q = condLower.removePrefix("filter").removePrefix("fx").trim()
                q.isEmpty() ||
                    engine.project.filter.preset.id.lowercase().contains(q) ||
                    engine.project.filter.preset.displayName.lowercase().contains(q)
            }
            condLower.startsWith("blur") -> {
                engine.project.blur.mode != BlurMode.OFF
            }
            ratioTokenRegex.matches(cond) -> {
                val m = ratioTokenRegex.matchEntire(cond)!!
                val w = m.groupValues[1].replace(",", ".").toFloatOrNull() ?: 9f
                val h = m.groupValues[2].replace(",", ".").toFloatOrNull() ?: 16f
                val targetRatio = w / h.coerceAtLeast(0.1f)
                kotlin.math.abs(engine.currentAspectFloat() - targetRatio) < 0.08f
            }
            else -> true
        }

        return if (isGeneralMatch) {
            val res = tryParseAndExecuteFormatAndCrop("all $actionBody", engine)
                ?: engine.executeCommand(actionBody)
            "$labelPrefix ($cond): $res"
        } else {
            "$labelPrefix ($cond): Bedingung aktuell nicht erfüllt."
        }
    }

    /**
     * Setzt einen benannten Schnittmarker auf der Timeline (`set cut 00:48`, `set cut1 00:48`, `cut at 00:48`).
     * Falls die Zeitangabe (z.B. `00:48` = 48.000 ms) jenseits der aktuellen Demo-Videolänge (15s) liegt,
     * wird die Projekt-Timeline automatisch erweitert (z.B. auf 60s), damit hinter `cut1` auch Material
     * existiert, das mit `delete after cut1` weggeschnitten werden kann.
     */
    private fun executeCutMarkerStatement(
        cutNumRaw: String,
        timeToken: String,
        engine: ProjectEngine,
    ): String {
        val cutTimeMs = parseSecondsToMs(timeToken).coerceAtLeast(100L)
        val explicitNum = cutNumRaw.trim().toIntOrNull()
        val cutId = if (explicitNum != null && explicitNum > 0) {
            "cut$explicitNum"
        } else {
            // Wenn `set cut 00:48` ohne Nummer aufgerufen wird:
            // Falls noch kein Marker existiert, wird es `cut1`; falls `cut1` schon existiert,
            // aktualisieren wir `cut1` (damit `set cut ...` + `delete after cut1` immer zusammenpassen).
            "cut1"
        }

        val timeFormatted = ProjectEngine.formatTimecode(cutTimeMs)
        engine.commitChange("Cut-Marker $cutId bei $timeFormatted gesetzt") { p ->
            // Falls die Schnittmarke jenseits oder genau am Ende der bisherigen Videolänge liegt,
            // erweitern wir die Quelldauer um mindestens 12s hinter den Cut, damit `delete after cut1`
            // den hinteren Teil sichtbar wegschneiden kann.
            val expandedDuration = if (cutTimeMs + 2_000L > p.sourceDurationMs) {
                cutTimeMs + 12_000L
            } else {
                p.sourceDurationMs
            }
            val expandedTrimEnd = if (p.trimEndMs >= p.sourceDurationMs - 200L && expandedDuration > p.sourceDurationMs) {
                expandedDuration
            } else {
                maxOf(p.trimEndMs, cutTimeMs + 1_000L).coerceAtMost(expandedDuration)
            }

            val marker = CutMarker(id = cutId, timeMs = cutTimeMs)
            val updatedMarkers = (p.cutMarkers.filterNot { it.id.equals(cutId, ignoreCase = true) } + marker)
                .sortedBy { it.timeMs }

            p.copy(
                sourceDurationMs = expandedDuration,
                trimEndMs = expandedTrimEnd,
                cutMarkers = updatedMarkers,
            )
        }
        engine.playheadMs = cutTimeMs
        return "Schnittmarke $cutId bei $timeFormatted (${ProjectEngine.formatSec(cutTimeMs)}) gesetzt."
    }

    /**
     * Löscht den Videobereich hinter oder vor einer Schnittmarke:
     * - `delete after cut1` / `delete behind cut1` / `delete behind cut` / `delete after 00:48`
     *   → Schneidet alles hinter `cut1` weg (`trimEndMs = cut1.timeMs`).
     * - `delete before cut1` / `delete vor cut1` / `delete before 00:48`
     *   → Schneidet alles vor `cut1` weg (`trimStartMs = cut1.timeMs`).
     */
    private fun executeDeleteCutStatement(
        dirRaw: String,
        targetRaw: String,
        engine: ProjectEngine,
    ): String {
        val dir = dirRaw.lowercase().trim().replace(Regex("\\s+"), " ")

        // `delete between cut1 cut2` / `delete zwischen cut1 und cut2` / `delete between 00:10 00:20`
        if (dir == "between" || dir == "zwischen") {
            val parts = targetRaw.trim()
                .split(Regex("""\s*(?:\band\b|\bund\b|&|,|\s)\s*|(?<=[a-z0-9])-(?=[a-z0-9])""", RegexOption.IGNORE_CASE))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (parts.size != 2) {
                return "Syntax: delete between cut1 cut2 (oder delete between 00:10 00:20)"
            }
            val a = resolveCutOrTime(parts[0], engine)
                ?: return "Cut-Marker '${parts[0]}' nicht gefunden. Setze zuerst z.B. 'set cut1 00:10'."
            val b = resolveCutOrTime(parts[1], engine)
                ?: return "Cut-Marker '${parts[1]}' nicht gefunden. Setze zuerst z.B. 'set cut2 00:20'."
            return executeRemoveRange(a, b, engine)
        }

        val cleanTarget = targetRaw.trim().lowercase().replace(Regex("\\s+"), "")

        // Ziel-Zeitpunkt bestimmen: entweder über `cut1` / `cut2` / `cut` oder direkt als Zeit `00:48`
        val resolvedMarker: CutMarker? = when {
            cleanTarget.startsWith("cut") -> {
                val numPart = cleanTarget.removePrefix("cut")
                if (numPart.isEmpty()) {
                    engine.project.cutMarkers.lastOrNull()
                } else {
                    engine.project.cutMarkers.firstOrNull { it.id.equals("cut$numPart", ignoreCase = true) }
                        ?: engine.project.cutMarkers.lastOrNull()
                }
            }
            else -> null
        }

        val cutTimeMs = resolvedMarker?.timeMs ?: parseSecondsToMs(targetRaw)
        if (cutTimeMs <= 0L && resolvedMarker == null) {
            return "Kein passender Cut-Marker ('$targetRaw') gefunden. Setze zuerst z.B. 'set cut 00:48'."
        }

        val markerLabel = resolvedMarker?.id ?: ProjectEngine.formatTimecode(cutTimeMs)
        val timeLabel = ProjectEngine.formatTimecode(cutTimeMs)

        return if (dir in listOf("after", "behind", "nach", "hinter")) {
            engine.commitChange("Alles nach $markerLabel ($timeLabel) gelöscht") { p ->
                val newSourceDur = maxOf(p.sourceDurationMs, cutTimeMs)
                val newEnd = cutTimeMs.coerceIn((p.trimStartMs + 500L).coerceAtMost(newSourceDur), newSourceDur)
                val trimmedSubs = p.subtitles.mapNotNull { sub ->
                    when {
                        sub.startMs >= newEnd -> null
                        sub.endMs > newEnd -> sub.copy(endMs = newEnd)
                        else -> sub
                    }
                }
                val trimmedOverlays = p.overlays.mapNotNull { ov ->
                    when {
                        ov.startMs >= newEnd -> null
                        ov.endMs > newEnd -> ov.copy(endMs = newEnd)
                        else -> ov
                    }
                }
                p.copy(
                    sourceDurationMs = newSourceDur,
                    trimEndMs = newEnd,
                    subtitles = trimmedSubs,
                    overlays = trimmedOverlays,
                )
            }
            engine.playheadMs = cutTimeMs.coerceAtMost(engine.project.trimEndMs)
            "Alles hinter $markerLabel ($timeLabel / ${ProjectEngine.formatSec(cutTimeMs)}) gelöscht – Video endet jetzt bei $timeLabel."
        } else {
            engine.commitChange("Alles vor $markerLabel ($timeLabel) gelöscht") { p ->
                val newSourceDur = maxOf(p.sourceDurationMs, cutTimeMs + 1_000L)
                val newStart = cutTimeMs.coerceIn(0L, (p.trimEndMs - 500L).coerceAtLeast(0L))
                val trimmedSubs = p.subtitles.mapNotNull { sub ->
                    when {
                        sub.endMs <= newStart -> null
                        sub.startMs < newStart -> sub.copy(startMs = newStart)
                        else -> sub
                    }
                }
                p.copy(
                    sourceDurationMs = newSourceDur,
                    trimStartMs = newStart,
                    subtitles = trimmedSubs,
                )
            }
            engine.playheadMs = engine.project.trimStartMs
            "Alles vor $markerLabel ($timeLabel / ${ProjectEngine.formatSec(cutTimeMs)}) gelöscht – Video startet jetzt bei $timeLabel."
        }
    }

    /** Löst `cut1` / `cut` / `00:48` / `12,5` in Millisekunden auf (oder `null`, falls Marker fehlt). */
    private fun resolveCutOrTime(token: String, engine: ProjectEngine): Long? {
        val clean = token.trim().lowercase()
        if (clean.startsWith("cut")) {
            val num = clean.removePrefix("cut")
            val marker = if (num.isEmpty()) {
                engine.project.cutMarkers.lastOrNull()
            } else {
                engine.project.cutMarkers.firstOrNull { it.id.equals("cut$num", ignoreCase = true) }
            }
            return marker?.timeMs
        }
        if (!Regex("""^\d+(?::\d+)*(?:[.,]\d+)?(?:s|sek)?$""").matches(clean)) return null
        return parseSecondsToMs(clean)
    }

    private fun executeRemoveRange(startMs: Long, endMs: Long, engine: ProjectEngine): String {
        val s = minOf(startMs, endMs)
        val e = maxOf(startMs, endMs)
        val sLabel = ProjectEngine.formatTimecode(s)
        val eLabel = ProjectEngine.formatTimecode(e)
        if (s >= engine.project.sourceDurationMs) {
            return "Bereich $sLabel–$eLabel liegt hinter dem Videoende (${ProjectEngine.formatTimecode(engine.project.sourceDurationMs)})."
        }
        return if (engine.addRemovedRange(s, e)) {
            "Bereich $sLabel–$eLabel herausgeschnitten – wird beim Abspielen übersprungen (Rest: ${ProjectEngine.formatSec(engine.project.playableDurationMs)})."
        } else {
            "Bereich $sLabel–$eLabel ist zu kurz (mind. 0,1s)."
        }
    }

    private fun buildCombinedScopedCommand(
        ranges: List<Pair<Long, Long>>,
        actionBody: String,
    ): String? {
        val bodyTrimmed = actionBody.trim()
        val tokens = bodyTrimmed.split(Regex("\\s+"))
        // Prüfen, ob actionBody ein Format `(9:16)`, Kanten-Cut `u20%` oder Move `move 10%l` ist
        if (tokens.all {
                ratioTokenRegex.matches(it) ||
                    edgeCropTokenRegex.matches(it) ||
                    moveSuffixTokenRegex.matches(it) ||
                    it.lowercase() in listOf("move", "shift", "pan", "verschiebe")
            }
        ) {
            return ranges.joinToString(" ") { (startMs, endMs) ->
                val startSec = startMs / 1000.0
                val endSec = endMs / 1000.0
                "${formatSecNumber(startSec)}-${formatSecNumber(endSec)} $bodyTrimmed"
            }
        }
        return null
    }

    private fun executeScopedBody(
        startMs: Long,
        endMs: Long,
        actionBody: String,
        engine: ProjectEngine,
    ): String {
        val rangePrefix = "${formatSecNumber(startMs / 1000.0)}-${formatSecNumber(endMs / 1000.0)}"
        val body = actionBody.trim()
        val withRange = "$rangePrefix $body"
        return tryExecuteScript(withRange, engine) ?: engine.executeCommand(body)
    }

    private fun formatSecNumber(sec: Double): String {
        val roundedTenths = (sec * 10.0).roundToInt()
        return if (roundedTenths % 10 == 0) {
            "${roundedTenths / 10}"
        } else {
            "${roundedTenths / 10}.${roundedTenths % 10}"
        }
    }

    /**
     * Parst und führt Ausdrücke mit Seitenverhältnissen `(8:15)`, Kanten-Zuschnitten
     * (`u20%` unten, `o20%` oben, `r20%` rechts, `l20%` links, `90 u90%`) sowie
     * prozentualen Verschiebungen (`all move 10%l` = 10% lower/runter, `all move 10%u` = 10% up/hoch,
     * `u20% move 10%l`) aus.
     */
    private fun tryParseAndExecuteFormatAndCrop(raw: String, engine: ProjectEngine): String? {
        var normalized = raw.trim()

        // Optionales führendes `crop ` entfernen, falls dahinter Richtungs-Cuts wie `u20%` folgen
        if (normalized.lowercase().startsWith("crop ") &&
            Regex("""\b(u|unten|o|oben|r|rechts|l|links)\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(normalized)
        ) {
            normalized = normalized.substring(5).trim()
        }

        // Leerzeichen innerhalb von Klammern `( 8 : 15 )` -> `(8:15)` normalisieren
        normalized = normalized.replace(
            Regex("""\(\s*(\d+(?:[.,]\d+)?)\s*([:/x])\s*(\d+(?:[.,]\d+)?)\s*\)"""),
            "($1$2$3)",
        )
        // Leerzeichen um Bindestrich bei Zeitbereichen `10 - 24,3` -> `10-24,3` normalisieren
        normalized = normalized.replace(
            Regex("""(\d+(?::\d+)+|\d+(?:[.,]\d+)?)\s*-\s*(\d+(?::\d+)+|\d+(?:[.,]\d+)?)"""),
            "$1-$2",
        )
        // Leerzeichen zwischen Prozentzahl und Richtungs-Suffix `10% lower` / `10% l` / `10% up` -> `10%lower` / `10%l`
        normalized = normalized.replace(
            Regex("""(\d+(?:[.,]\d+)?)\s*%\s*(lower|low|down|unten|up|upper|oben|right|rechts|left|links|l|u|d|o|r)\b""", RegexOption.IGNORE_CASE),
            "$1%$2",
        )
        // Leerzeichen zwischen Richtung und Prozentzahl `u 20%` -> `u20%` normalisieren
        normalized = normalized.replace(
            Regex("""\b(lower|low|down|unten|up|upper|oben|right|rechts|left|links|u|o|r|l)\s+(\d+(?:[.,]\d+)?%?)""", RegexOption.IGNORE_CASE),
            "$1$2",
        )

        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val segments = mutableListOf<FormatAndCropSegment>()
        var current = FormatAndCropSegment(timeToken = "all")
        var nextTokenIsMove = false

        for (token in tokens) {
            val lowerTok = token.lowercase()
            if (lowerTok in listOf("move", "shift", "pan", "verschiebe")) {
                nextTokenIsMove = true
                continue
            }

            val timeMatch = timeTokenRegex.matchEntire(token)
            val ratioMatch = ratioTokenRegex.matchEntire(token)
            val moveSuffixMatch = moveSuffixTokenRegex.matchEntire(token)
            val movePrefixMatch = if (nextTokenIsMove) movePrefixTokenRegex.matchEntire(token) else null
            val edgeMatch = if (!nextTokenIsMove) edgeCropTokenRegex.matchEntire(token) else null

            when {
                timeMatch != null -> {
                    if (current.hasModifiers) {
                        segments.add(current)
                        current = FormatAndCropSegment(timeToken = timeMatch.groupValues[1])
                    } else {
                        current.timeToken = timeMatch.groupValues[1]
                    }
                }
                ratioMatch != null -> {
                    current.ratioW = ratioMatch.groupValues[1].replace(",", ".").toFloatOrNull() ?: 9f
                    current.ratioH = ratioMatch.groupValues[2].replace(",", ".").toFloatOrNull() ?: 16f
                    nextTokenIsMove = false
                }
                moveSuffixMatch != null -> {
                    val pct = (moveSuffixMatch.groupValues[1].replace(",", ".").toFloatOrNull() ?: 0f).coerceIn(0f, 95f)
                    val dir = moveSuffixMatch.groupValues[2].lowercase()
                    when (dir) {
                        "l", "low", "lower", "d", "down", "unten" -> current.moveYPct = pct
                        "u", "up", "upper", "o", "oben" -> current.moveYPct = -pct
                        "r", "right", "rechts" -> current.moveXPct = pct
                        "left", "links" -> current.moveXPct = -pct
                    }
                    nextTokenIsMove = false
                }
                movePrefixMatch != null -> {
                    val dir = movePrefixMatch.groupValues[1].lowercase()
                    val pct = (movePrefixMatch.groupValues[2].replace(",", ".").toFloatOrNull() ?: 0f).coerceIn(0f, 95f)
                    when (dir) {
                        "u", "unten", "d", "down", "low", "lower" -> current.moveYPct = pct
                        "o", "oben", "up", "upper" -> current.moveYPct = -pct
                        "r", "rechts", "right" -> current.moveXPct = pct
                        "l", "links", "left" -> current.moveXPct = -pct
                    }
                    nextTokenIsMove = false
                }
                edgeMatch != null -> {
                    val dirChar = edgeMatch.groupValues[1].lowercase().first()
                    val pct = (edgeMatch.groupValues[2].replace(",", ".").toFloatOrNull() ?: 0f).coerceIn(0f, 98f)
                    when (dirChar) {
                        'u' -> current.cutBottomPct = pct
                        'o' -> current.cutTopPct = pct
                        'r' -> current.cutRightPct = pct
                        'l' -> current.cutLeftPct = pct
                    }
                }
                else -> {
                    // Unbekanntes Token -> kein reiner Format-/Kanten-Cut-/Move-Ausdruck
                    return null
                }
            }
        }

        if (current.hasModifiers) {
            segments.add(current)
        }
        if (segments.isEmpty()) return null

        val newSpans = mutableListOf<TimedFormatSpan>()
        var globalPreset: AspectRatioPreset? = null
        var maxEndRequired = engine.project.sourceDurationMs
        val summaryParts = mutableListOf<String>()

        // Startwerte aus dem aktuellen globalen Transform übernehmen, damit mehrere Befehle
        // (z.B. Zeile 1: `all u20%`, Zeile 2: `all move 10%l`) miteinander kombiniert werden können.
        var updatedGlobalLeft = engine.project.transform.cropLeft
        var updatedGlobalTop = engine.project.transform.cropTop
        var updatedGlobalRight = engine.project.transform.cropRight
        var updatedGlobalBottom = engine.project.transform.cropBottom
        var updatedGlobalPanX = engine.project.transform.panX
        var updatedGlobalPanY = engine.project.transform.panY
        var shouldUpdateGlobalCrop = false
        var clearTimedCropOverrides = false

        for ((idx, seg) in segments.withIndex()) {
            val range = parseTimeRange(seg.timeToken, engine)
            if (range.endMs > maxEndRequired) {
                maxEndRequired = range.endMs
            }

            val cropLeftVal = seg.cutLeftPct?.let { (it / 100f).coerceIn(0f, 0.98f) }
            val cropTopVal = seg.cutTopPct?.let { (it / 100f).coerceIn(0f, 0.98f) }
            val cropRightVal = seg.cutRightPct?.let { (1f - it / 100f).coerceIn(0.02f, 1.0f) }
            val cropBottomVal = seg.cutBottomPct?.let { (1f - it / 100f).coerceIn(0.02f, 1.0f) }
            val panXVal = seg.moveXPct?.let { (it / 100f).coerceIn(-0.95f, 0.95f) }
            val panYVal = seg.moveYPct?.let { (it / 100f).coerceIn(-0.95f, 0.95f) }

            val hasRatio = seg.ratioW != null && seg.ratioH != null
            val hasEdgeCrop = cropLeftVal != null || cropTopVal != null || cropRightVal != null || cropBottomVal != null
            val hasMove = panXVal != null || panYVal != null

            if (hasRatio || (!range.isAll && (hasEdgeCrop || hasMove))) {
                newSpans.add(
                    TimedFormatSpan(
                        id = "fmt_${System.currentTimeMillis()}_$idx",
                        startMs = range.startMs,
                        endMs = range.endMs,
                        ratioWidth = seg.ratioW ?: 9f,
                        ratioHeight = seg.ratioH ?: 16f,
                        hasCustomRatio = hasRatio,
                        cropLeft = cropLeftVal,
                        cropTop = cropTopVal,
                        cropRight = cropRightVal,
                        cropBottom = cropBottomVal,
                        panX = panXVal,
                        panY = panYVal,
                    ),
                )
            }

            if (hasRatio && (range.isAll || segments.size == 1)) {
                globalPreset = matchClosestAspectPreset(seg.ratioW ?: 9f, seg.ratioH ?: 16f)
            }

            // Wenn `all` (z.B. `all u20%`, `all move 10%l`) ODER ein einzelnes Segment (`90 u90%`) angegeben wurde,
            // aktualisieren wir auch den Basis-Transform, damit Zuschnitt und Verschiebung sofort global sitzen.
            if ((hasEdgeCrop || hasMove) && (range.isAll || segments.size == 1)) {
                shouldUpdateGlobalCrop = true
                if (range.isAll) {
                    clearTimedCropOverrides = true
                }
                if (cropLeftVal != null) {
                    updatedGlobalLeft = cropLeftVal
                    if (updatedGlobalRight <= updatedGlobalLeft + 0.02f) {
                        updatedGlobalRight = 1.0f
                    }
                }
                if (cropRightVal != null) {
                    updatedGlobalRight = cropRightVal
                    if (updatedGlobalLeft >= updatedGlobalRight - 0.02f) {
                        updatedGlobalLeft = 0.0f
                    }
                }
                if (cropTopVal != null) {
                    updatedGlobalTop = cropTopVal
                    if (updatedGlobalBottom <= updatedGlobalTop + 0.02f) {
                        updatedGlobalBottom = 1.0f
                    }
                }
                if (cropBottomVal != null) {
                    updatedGlobalBottom = cropBottomVal
                    if (updatedGlobalTop >= updatedGlobalBottom - 0.02f) {
                        updatedGlobalTop = 0.0f
                    }
                }
                if (panXVal != null) {
                    updatedGlobalPanX = panXVal
                }
                if (panYVal != null) {
                    updatedGlobalPanY = panYVal
                }
            }

            val descTokens = mutableListOf<String>()
            if (hasRatio) {
                descTokens.add("(${(seg.ratioW ?: 9f).toInt()}:${(seg.ratioH ?: 16f).toInt()})")
            }
            seg.cutBottomPct?.let { descTokens.add("unten -${it.roundToInt()}% (u${it.roundToInt()}%)") }
            seg.cutTopPct?.let { descTokens.add("oben -${it.roundToInt()}% (o${it.roundToInt()}%)") }
            seg.cutRightPct?.let { descTokens.add("rechts -${it.roundToInt()}% (r${it.roundToInt()}%)") }
            seg.cutLeftPct?.let { descTokens.add("links -${it.roundToInt()}% (l${it.roundToInt()}%)") }
            seg.moveYPct?.let {
                if (it >= 0f) {
                    descTokens.add("move ${it.roundToInt()}%l (runter)")
                } else {
                    descTokens.add("move ${(-it).roundToInt()}%u (hoch)")
                }
            }
            seg.moveXPct?.let {
                if (it >= 0f) {
                    descTokens.add("move ${it.roundToInt()}%r (rechts)")
                } else {
                    descTokens.add("move ${(-it).roundToInt()}%left (links)")
                }
            }

            val timeLabel = if (range.isAll) "all" else "${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)}"
            summaryParts.add("$timeLabel ${descTokens.joinToString(" ")}")
        }

        val summary = summaryParts.joinToString(", ")

        engine.commitChange("PocketScript Schnitt: $summary") { p ->
            val cleanedExisting = if (clearTimedCropOverrides) {
                p.timedFormatSpans.mapNotNull { old ->
                    val stripped = old.copy(
                        cropLeft = if (segments.any { it.cutLeftPct != null }) null else old.cropLeft,
                        cropTop = if (segments.any { it.cutTopPct != null }) null else old.cropTop,
                        cropRight = if (segments.any { it.cutRightPct != null }) null else old.cropRight,
                        cropBottom = if (segments.any { it.cutBottomPct != null }) null else old.cropBottom,
                        panX = if (segments.any { it.moveXPct != null }) null else old.panX,
                        panY = if (segments.any { it.moveYPct != null }) null else old.panY,
                    )
                    if (!stripped.hasCustomRatio &&
                        stripped.cropLeft == null &&
                        stripped.cropTop == null &&
                        stripped.cropRight == null &&
                        stripped.cropBottom == null &&
                        stripped.panX == null &&
                        stripped.panY == null
                    ) {
                        null
                    } else {
                        stripped
                    }
                }
            } else {
                p.timedFormatSpans
            }

            val mergedSpans = (cleanedExisting + newSpans).takeLast(24)
            val nextTransform = if (shouldUpdateGlobalCrop) {
                p.transform.copy(
                    cropLeft = updatedGlobalLeft.coerceIn(0f, 0.98f),
                    cropTop = updatedGlobalTop.coerceIn(0f, 0.98f),
                    cropRight = updatedGlobalRight.coerceIn((updatedGlobalLeft + 0.02f).coerceAtMost(1f), 1f),
                    cropBottom = updatedGlobalBottom.coerceIn((updatedGlobalTop + 0.02f).coerceAtMost(1f), 1f),
                    panX = updatedGlobalPanX.coerceIn(-0.95f, 0.95f),
                    panY = updatedGlobalPanY.coerceIn(-0.95f, 0.95f),
                )
            } else {
                p.transform
            }

            p.copy(
                sourceDurationMs = maxEndRequired,
                trimEndMs = if (p.trimEndMs < maxEndRequired && segments.any { !parseTimeRange(it.timeToken, engine).isAll }) maxEndRequired else p.trimEndMs,
                aspectRatio = globalPreset ?: p.aspectRatio,
                transform = nextTransform,
                timedFormatSpans = mergedSpans,
            )
        }

        // Falls ein konkreter Startzeitpunkt (nicht `all`) angegeben wurde, Playhead dorthin bewegen
        val firstNonAll = segments.firstOrNull { !parseTimeRange(it.timeToken, engine).isAll }
        if (firstNonAll != null) {
            engine.playheadMs = parseTimeRange(firstNonAll.timeToken, engine).startMs
        } else if (newSpans.isNotEmpty()) {
            engine.playheadMs = newSpans.first().startMs
        }

        return "Zuschnitt/Format aktiv: $summary"
    }

    private fun executeSetStatement(
        rangeToken: String,
        targetKind: String,
        rawPayload: String,
        engine: ProjectEngine,
    ): String {
        val range = parseTimeRange(rangeToken, engine)
        val styled = parseDotModifiers(rawPayload, engine)

        // Falls der Befehl jenseits der bisherigen Demo-Länge (15s) liegt, erweitern wir nahtlos die Timeline
        val neededDuration = maxOf(engine.project.sourceDurationMs, range.endMs)

        return when (targetKind) {
            "caption", "sub", "untertitel" -> {
                val color = styled.colorArgb ?: 0xFFFFFFFFL
                val bg = styled.bgColorArgb ?: 0xB3000000L
                val font = styled.fontId ?: engine.project.activeFontId
                val sizeSp = styled.fontSizeSp ?: 20f
                val yPos = styled.yPercent ?: 0.82f
                val sub = SubtitleSegment(
                    id = "ps_sub_${System.currentTimeMillis()}",
                    startMs = range.startMs,
                    endMs = range.endMs,
                    text = styled.cleanText,
                    fontId = font,
                    textColorArgb = color,
                    highlightColorArgb = color,
                    backgroundColorArgb = bg,
                    verticalPosition = yPos,
                    fontSizeSp = sizeSp,
                )
                engine.commitChange("Caption '${styled.cleanText}' (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
                    // Ersetze exakt gleiche Zeitfenster oder füge hinzu
                    val filtered = p.subtitles.filterNot { it.startMs == range.startMs && it.endMs == range.endMs }
                    p.copy(
                        sourceDurationMs = neededDuration,
                        trimEndMs = maxOf(p.trimEndMs, range.endMs),
                        subtitles = (filtered + sub).sortedBy { it.startMs },
                    )
                }
                engine.playheadMs = range.startMs
                "Untertitel (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}): \"${styled.cleanText}\""
            }

            "text", "title", "titel" -> {
                val color = styled.colorArgb ?: 0xFF00E5FFL
                val bg = styled.bgColorArgb ?: 0x99000000L
                val font = styled.fontId ?: engine.project.activeFontId
                val scale = styled.scale ?: 1.15f
                val yPos = styled.yPercent ?: 0.32f
                val overlay = CanvasOverlay(
                    id = "ps_txt_${System.currentTimeMillis()}",
                    kind = OverlayKind.TEXT,
                    content = styled.cleanText,
                    xPercent = 0.5f,
                    yPercent = yPos,
                    scale = scale,
                    fontId = font,
                    colorArgb = color,
                    backgroundArgb = bg,
                    startMs = range.startMs,
                    endMs = range.endMs,
                )
                engine.commitChange("Text '${styled.cleanText}' (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
                    p.copy(
                        sourceDurationMs = neededDuration,
                        trimEndMs = maxOf(p.trimEndMs, range.endMs),
                        overlays = p.overlays + overlay,
                    )
                }
                engine.selectedOverlayId = overlay.id
                engine.playheadMs = range.startMs
                "Text-Overlay (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}): \"${styled.cleanText}\""
            }

            "emoji", "sticker" -> {
                val scale = styled.scale ?: 1.4f
                val yPos = styled.yPercent ?: 0.40f
                val overlay = CanvasOverlay(
                    id = "ps_emo_${System.currentTimeMillis()}",
                    kind = OverlayKind.EMOJI,
                    content = styled.cleanText,
                    xPercent = 0.5f,
                    yPercent = yPos,
                    scale = scale,
                    startMs = range.startMs,
                    endMs = range.endMs,
                )
                engine.commitChange("Emoji ${styled.cleanText} (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
                    p.copy(
                        sourceDurationMs = neededDuration,
                        trimEndMs = maxOf(p.trimEndMs, range.endMs),
                        overlays = p.overlays + overlay,
                    )
                }
                engine.selectedOverlayId = overlay.id
                engine.playheadMs = range.startMs
                "Emoji ${styled.cleanText} (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}) gesetzt."
            }

            "zoom" -> {
                val z = styled.cleanText.replace(",", ".").toFloatOrNull() ?: 1.5f
                engine.setZoomAndPan(zoom = z)
                "Zoom auf ${engine.project.transform.zoom}x gesetzt."
            }

            "filter", "fx" -> {
                val preset = VideoFilterPreset.fromNameOrId(styled.cleanText) ?: VideoFilterPreset.CINEMATIC
                engine.setFilterPreset(preset)
                "Filter '${preset.displayName}' aktiviert."
            }

            "blur" -> {
                val radius = styled.cleanText.replace(",", ".").toFloatOrNull() ?: 20f
                val mode = BlurMode.entries.firstOrNull {
                    rawPayload.lowercase().contains(it.id)
                } ?: BlurMode.BACKGROUND_BOKEH
                engine.setBlur(mode = mode, radiusPx = radius)
                "Blur '${mode.label}' (${radius.roundToInt()}px) gesetzt."
            }

            "speed" -> {
                val s = styled.cleanText.replace(",", ".").toFloatOrNull() ?: 1.0f
                engine.setPlaybackSpeed(s)
                "Speed auf ${engine.project.playbackSpeed}x gesetzt."
            }

            "trim", "cut", "crop" -> {
                val edgeResult = tryParseAndExecuteFormatAndCrop("$rangeToken $rawPayload", engine)
                if (edgeResult != null) {
                    edgeResult
                } else {
                    engine.setTrimRange(range.startMs, range.endMs)
                    "Video gekürzt auf ${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}."
                }
            }

            else -> "Befehl ausgeführt."
        }
    }

    /**
     * Zerlegt einen String wie `Pinker Honig.red.serif.big` in den eigentlichen Text `"Pinker Honig"`
     * und seine Punkt-Modifier (`.red`, `.serif`, `.big`, `.top`, `.bgblack` usw.).
     */
    fun parseDotModifiers(raw: String, engine: ProjectEngine): StyledPayload {
        var working = raw.trim()
        var colorArgb: Long? = null
        var bgColorArgb: Long? = null
        var fontId: String? = null
        var fontSizeSp: Float? = null
        var scale: Float? = null
        var yPercent: Float? = null

        // Wir schälen von rechts nach links alle bekannten `.modifier` ab
        while (working.contains(".")) {
            val lastDot = working.lastIndexOf('.')
            if (lastDot <= 0) break
            val candidate = working.substring(lastDot + 1).trim().lowercase()
            if (candidate.isEmpty() || candidate.contains(" ")) break

            var matched = true
            when {
                colorMap.containsKey(candidate) -> {
                    colorArgb = colorMap[candidate]
                }
                candidate.startsWith("bg") && colorMap.containsKey(candidate.removePrefix("bg")) -> {
                    val base = colorMap[candidate.removePrefix("bg")] ?: 0xFF000000L
                    bgColorArgb = (base and 0x00FFFFFFL) or 0xCC000000L
                }
                candidate == "nobg" || candidate == "transparent" -> {
                    bgColorArgb = 0x00000000L
                }
                candidate == "big" || candidate == "gross" || candidate == "xl" || candidate == "large" -> {
                    fontSizeSp = 26f
                    scale = 1.45f
                }
                candidate == "huge" || candidate == "xxl" -> {
                    fontSizeSp = 32f
                    scale = 1.85f
                }
                candidate == "small" || candidate == "klein" || candidate == "sm" -> {
                    fontSizeSp = 14f
                    scale = 0.78f
                }
                candidate == "top" || candidate == "oben" -> {
                    yPercent = 0.16f
                }
                candidate == "center" || candidate == "mitte" || candidate == "mid" -> {
                    yPercent = 0.50f
                }
                candidate == "bottom" || candidate == "unten" -> {
                    yPercent = 0.84f
                }
                candidate == "sans" || candidate == "bold" -> {
                    fontId = BuiltInFont.SANS_BOLD.id
                }
                candidate == "serif" || candidate == "cinema" || candidate == "kino" -> {
                    fontId = BuiltInFont.SERIF_CINEMA.id
                }
                candidate == "mono" || candidate == "code" || candidate == "cyber" -> {
                    fontId = BuiltInFont.MONO_TECH.id
                }
                candidate == "cursive" || candidate == "script" || candidate == "hand" -> {
                    fontId = BuiltInFont.CURSIVE_VLOG.id
                }
                candidate == "impact" || candidate == "meme" -> {
                    fontId = BuiltInFont.CONDENSED_IMPACT.id
                }
                candidate == "rounded" || candidate == "pop" -> {
                    fontId = BuiltInFont.ROUNDED_POP.id
                }
                else -> {
                    // Prüfe, ob es eine importierte Custom-Font ist
                    val custom = engine.project.customFonts.firstOrNull {
                        it.displayName.lowercase().replace(" ", "") == candidate ||
                            it.id.lowercase().contains(candidate)
                    }
                    if (custom != null) {
                        fontId = custom.id
                    } else {
                        matched = false
                    }
                }
            }

            if (matched) {
                working = working.substring(0, lastDot).trim()
            } else {
                break
            }
        }

        return StyledPayload(
            cleanText = working,
            colorArgb = colorArgb,
            bgColorArgb = bgColorArgb,
            fontId = fontId,
            fontSizeSp = fontSizeSp,
            scale = scale,
            yPercent = yPercent,
        )
    }

    /**
     * Parst Zeitangaben wie:
     * - `all` → gesamtes Video (`0` bis `sourceDurationMs`)
     * - `10-24,3` oder `8-10` → Start 10s bis Ende 24,3s
     * - `9` → ab Sekunde 9 bis zum Ende des Videos (oder +4s falls Video kürzer ist)
     */
    fun parseTimeRange(token: String, engine: ProjectEngine): ParsedTimeRange {
        val clean = token.trim().lowercase()
        val totalMs = engine.project.sourceDurationMs.coerceAtLeast(1_000L)
        if (clean.isEmpty() || clean == "all" || clean == "alles" || clean == "*") {
            return ParsedTimeRange(0L, totalMs, isAll = true)
        }

        if (clean.contains("-")) {
            val parts = clean.split("-", limit = 2)
            val startMs = parseSecondsToMs(parts[0])
            val endMs = parseSecondsToMs(parts[1]).coerceAtLeast(startMs + 400L)
            return ParsedTimeRange(startMs, endMs, isAll = false)
        }

        val singleStartMs = parseSecondsToMs(clean)
        val defaultEndMs = if (singleStartMs + 4_000L <= totalMs) totalMs else singleStartMs + 5_000L
        return ParsedTimeRange(singleStartMs, defaultEndMs, isAll = false)
    }

    fun parseSecondsToMs(secStr: String): Long {
        val normalized = secStr.trim().replace(",", ".").removeSuffix("s").removeSuffix("sek")
        if (normalized.contains(":")) {
            val parts = normalized.split(":")
            val totalSeconds = when (parts.size) {
                2 -> {
                    val mins = parts[0].toDoubleOrNull() ?: 0.0
                    val secs = parts[1].toDoubleOrNull() ?: 0.0
                    mins * 60.0 + secs
                }
                3 -> {
                    val hours = parts[0].toDoubleOrNull() ?: 0.0
                    val mins = parts[1].toDoubleOrNull() ?: 0.0
                    val secs = parts[2].toDoubleOrNull() ?: 0.0
                    hours * 3600.0 + mins * 60.0 + secs
                }
                else -> 0.0
            }
            return (totalSeconds * 1000.0).toLong().coerceAtLeast(0L)
        }
        val seconds = normalized.toDoubleOrNull() ?: 0.0
        return (seconds * 1000.0).toLong().coerceAtLeast(0L)
    }

    private fun matchClosestAspectPreset(w: Float, h: Float): AspectRatioPreset {
        val ratio = w / h.coerceAtLeast(0.01f)
        return when {
            kotlin.math.abs(ratio - 1.0f) < 0.08f -> AspectRatioPreset.SQUARE_1_1
            kotlin.math.abs(ratio - 0.8f) < 0.08f -> AspectRatioPreset.PORTRAIT_4_5
            ratio > 1.3f -> AspectRatioPreset.LANDSCAPE_16_9
            kotlin.math.abs(ratio - (9f / 16f)) < 0.06f -> AspectRatioPreset.PORTRAIT_9_16
            else -> AspectRatioPreset.FREE_CROP
        }
    }
}

/**
 * Erzeugt die offizielle **PocketScript PDF-Anleitung** (`PocketScript_Anleitung.pdf`)
 * nativ mit Androids [PdfDocument]-API, speichert sie auf dem Gerät und bietet sie zum
 * direkten Ansehen in der App sowie zum Öffnen in externen PDF-Readern an.
 */
object PocketScriptPdfManualGenerator {

    data class ManualSection(
        val title: String,
        val codeExamples: List<Pair<String, String>>,
    )

    /**
     * Liefert aus einer Handbuch-Zeile (auch wenn dort Alternativen mit `  /  ` oder `   oder   `
     * dokumentiert sind) den direkt ausführbaren PocketScript-Befehl zum Copy-Pasten.
     */
    fun extractCopyableCommand(codeDisplay: String): String {
        val primary = codeDisplay
            .split(Regex("""\s+(?:oder|/)\s+"""))
            .firstOrNull()
            ?.trim()
            .orEmpty()
        return if (primary.startsWith(".")) {
            "8-10 set caption: Pinker Honig${primary.split(" ").first()}"
        } else {
            primary
        }
    }

    val sections: List<ManualSection> = listOf(
        ManualSection(
            title = "1. Bildränder wegschneiden (u%, o%, r%, l%) & Verschieben (move)",
            codeExamples = listOf(
                "all u20%" to "Unten (u) 20 % wegschneiden – nimmt die unteren 20 % des Bildes raus.",
                "all move 10%l" to "Verschiebt das (geschnittene) Bild um 10 % nach unten (10%l = 10% lower).",
                "all move 10%u" to "Verschiebt das Bild um 10 % nach oben (10%u = 10% up).",
                "u20% move 10%l" to "Unten 20 % abschneiden und direkt um 10 % nach unten verschieben.",
                "all o20%" to "Oben (o) 20 % wegschneiden (geht auch mit r20% rechts und l20% links).",
                "90 u90%" to "Schneidet unten 90 % weg – es bleibt oben nur noch ein schmaler 10%-Balken.",
            ),
        ),
        ManualSection(
            title = "2. Schnittmarken (set cut) & Dahinter/Davor löschen (delete)",
            codeExamples = listOf(
                "set cut 00:48" to "Setzt Schnittmarke cut1 exakt bei Minute 00:48 (48 Sekunden).",
                "delete after cut1" to "Löscht das gesamte Videomaterial hinter Schnittmarke cut1.",
                "delete before cut1" to "Löscht das gesamte Videomaterial vor Schnittmarke cut1.",
                "set cut2 01:15" to "Setzt eine zweite Schnittmarke cut2 bei 01:15.",
                "delete between cut1 cut2" to "Schneidet das Stück zwischen cut1 und cut2 heraus (wird übersprungen).",
                "delete 10-20" to "Schneidet Sekunde 10 bis 20 aus der Mitte des Videos heraus.",
            ),
        ),
        ManualSection(
            title = "2a. Untertitel, Texte & Emojis löschen (clear)",
            codeExamples = listOf(
                "clear all captions" to "Löscht alle Untertitel.",
                "clear caption1" to "Löscht den ersten Untertitel (nach Startzeit, 'captions' zeigt die Liste).",
                "clear caption Hallo" to "Löscht alle Untertitel, die 'Hallo' enthalten.",
                "clear texts" to "Löscht alle Text-Overlays (clear text2 = nur den zweiten).",
                "clear emojis" to "Löscht alle Emojis (clear emoji1 = nur das erste).",
                "clear cuts" to "Entfernt alle Schnittmarken und herausgeschnittenen Bereiche.",
                "clear all" to "Löscht alle Untertitel, Texte und Emojis auf einmal.",
            ),
        ),
        ManualSection(
            title = "2b. Projekte speichern & öffnen",
            codeExamples = listOf(
                "save" to "Speichert das aktuelle Projekt (wird auch automatisch gespeichert).",
                "save Urlaub 2026" to "Speichert und benennt das Projekt in 'Urlaub 2026' um.",
                "save as Version 2" to "Speichert eine Kopie als neues Projekt 'Version 2'.",
                "projects" to "Zeigt alle gespeicherten Projekte an.",
                "open Urlaub" to "Öffnet das Projekt mit diesem Namen (oder 'open 1' für das neueste).",
                "new Mein Reel" to "Legt ein neues, leeres Projekt an.",
                "export" to "Exportiert das Projekt (wie die Export-Taste).",
            ),
        ),
        ManualSection(
            title = "3. Bedingungen & Schleifen (if / while)",
            codeExamples = listOf(
                "if caption red: u20%" to "Wenn ein roter Untertitel aktiv ist, dort unten 20 % wegschneiden.",
                "if caption red: (9:16)" to "Wenn ein roter Untertitel aktiv ist, Format auf (9:16) setzen.",
                "while caption red: u40%" to "Solange rote Untertitel eingeblendet sind, unten 40 % wegschneiden.",
                "while caption: (1:1)" to "Solange irgendein Untertitel läuft, Format auf (1:1) schalten.",
                "if text cyan: u20%" to "Wenn ein cyanfarbenes Text-Overlay aktiv ist, unten 20 % wegschneiden.",
                "while 0-12 step 4: set emoji: 🔥" to "While-Schleife: setzt von 0–12s alle 4 Sekunden ein Feuer-Emoji.",
            ),
        ),
        ManualSection(
            title = "4. Grundprinzip: Zeitbereiche & Kommazahlen",
            codeExamples = listOf(
                "10-24,3" to "Von Sekunde 10,0 bis Sekunde 24,3 schneiden (Komma oder Punkt erlaubt).",
                "8-10" to "Zeitfenster von Sekunde 8 bis Sekunde 10.",
                "9" to "Einzelne Startzeit: gilt ab Sekunde 9 bis zum Clip-Ende.",
                "all" to "Wendet den Befehl auf das gesamte Video an (alle Sekunden).",
            ),
        ),
        ManualSection(
            title = "5. Auflösung & Format in Klammern (x:y)",
            codeExamples = listOf(
                "10-24,3 (8:15) 9 (16:18)" to "Mehrfach-Format: 10–24,3s im Format 8:15 und ab 9s im Format 16:18.",
                "all (9:16)" to "Setzt das gesamte Video auf 9:16 Hochformat (Reels/Shorts).",
                "all (1:1)" to "Setzt das gesamte Video auf 1:1 Quadrat.",
                "0-5 (4:5) 5-15 (16:9)" to "Wechselt bei Sekunde 5 dynamisch von 4:5 auf 16:9 Kino.",
            ),
        ),
        ManualSection(
            title = "6. Untertitel & Text mit Punkt-Farben (.farbe)",
            codeExamples = listOf(
                "8-10 set caption: Pinker Honig.red" to "Zeigt von 8–10s den Untertitel 'Pinker Honig' in Rot (.red).",
                "2-6 set caption: Mega Clip!.yellow.big" to "Großer gelber Untertitel von Sekunde 2 bis 6.",
                "0-12 set text: PIXEL 9.cyan.mono.top" to "Text-Overlay oben in Cyan mit Monospace-Schrift.",
                "4-9 set emoji: 🔥.huge" to "Setzt ein extra großes Feuer-Emoji von Sekunde 4 bis 9.",
            ),
        ),
        ManualSection(
            title = "7. Verfügbare Punkt-Modifier & Effekte",
            codeExamples = listOf(
                ".red .rot .pink .blue .blau .cyan" to "Textfarben: Rot, Pink, Blau, Cyan/Türkis.",
                ".green .gruen .yellow .gelb .gold .orange" to "Textfarben: Grün, Gelb, Gold, Orange.",
                ".sans .serif .mono .cursive .impact" to "Schriftart direkt im Befehl wechseln.",
                "zoom 1,8" to "Zoomt stufenlos ins Video (1.0x bis 5.0x).",
                "all set filter: cyberpunk" to "Aktiviert den Look-Filter (cyberpunk, cinematic, bw, vintage, vivid).",
                "undo" to "Macht den letzten Schritt rückgängig (redo stellt ihn wieder her).",
            ),
        ),
    )

    /**
     * Generiert eine echte mehrseitige A4 PDF-Datei im App-Speicher (`PocketScript_Anleitung.pdf`).
     */
    fun generatePdfFile(context: Context): File {
        val doc = PdfDocument()
        var pageNum = 1
        var page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNum).create())
        var canvas: Canvas = page.canvas

        // Dunkler Studio-Header + heller Lesebereich
        val bgPaint = Paint().apply { color = Color.parseColor("#0F172A") }
        canvas.drawRect(0f, 0f, 595f, 105f, bgPaint)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#00E5FF")
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 20f
        }
        canvas.drawText("POCKETCRAFT — POCKETSCRIPT ANLEITUNG", 32f, 46f, titlePaint)

        val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E2E8F0")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 11f
        }
        canvas.drawText("Kompakte Videoschnitt-Sprache für das 2-Sekunden-Code-Fenster auf dem Pixel 9", 32f, 70f, subPaint)
        canvas.drawText("Beispiele:  all u20%  |  90 u90%  |  10-24,3 (8:15)  |  8-10 set caption: Pinker Honig.red", 32f, 88f, subPaint)

        val sectionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textSize = 13f
        }
        val codeBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#F1F5F9")
        }
        val codeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0369A1")
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            textSize = 10.5f
        }
        val descTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
            textSize = 10f
        }

        var y = 128f
        for (sec in sections) {
            if (y + 60f > 805f) {
                doc.finishPage(page)
                pageNum++
                page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNum).create())
                canvas = page.canvas
                y = 48f
            }
            canvas.drawText(sec.title, 32f, y, sectionPaint)
            y += 10f
            for ((code, desc) in sec.codeExamples) {
                if (y + 38f > 810f) {
                    doc.finishPage(page)
                    pageNum++
                    page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, pageNum).create())
                    canvas = page.canvas
                    y = 48f
                }
                canvas.drawRoundRect(RectF(32f, y, 563f, y + 32f), 6f, 6f, codeBoxPaint)
                canvas.drawText(code, 40f, y + 13f, codeTextPaint)
                canvas.drawText(desc, 40f, y + 26f, descTextPaint)
                y += 37f
            }
            y += 10f
        }

        doc.finishPage(page)

        val outDir = File(context.getExternalFilesDir(null) ?: context.filesDir, "Manual").apply { mkdirs() }
        val pdfFile = File(outDir, "PocketScript_Anleitung.pdf")
        pdfFile.outputStream().use { out ->
            doc.writeTo(out)
        }
        doc.close()
        return pdfFile
    }
}

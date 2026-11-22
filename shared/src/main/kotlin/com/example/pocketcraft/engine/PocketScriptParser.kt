package com.example.pocketcraft.engine

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
import kotlin.math.roundToInt
import kotlin.math.roundToLong

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

    // Einheitliche Bausteine für Zeitangaben: Sekunden (10 / 24,3 / 24.3s) sowie Timecodes (00:33 / 00:33.3 / 1:02:03.5)
    private const val TIME_ATOM = """\d+(?::\d+)*(?:[.,]\d+)?(?:s|sek)?"""
    private const val TIME_RANGE_TOKEN = """(?:all|alles|\*|$TIME_ATOM(?:\s*-\s*$TIME_ATOM)?)"""

    // Regex für `if <Bedingung>: <Befehl>` und `while <Bedingung>: <Befehl>`
    // Beispiel: `if caption red: u20%`, `if caption red: (9:16)`, `while caption red: u20%`,
    //           `while 0-12 step 4: set emoji: 🔥`
    private val ifWhileRegex = Regex(
        pattern = """^(if|wenn|while|solange)\s+((?:\([^)]*\)|[^:])+?)\s*:\s*(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Zeitintervall-Schleife: `while 0-12 step 4: ...` oder `while step 3: ...`
    private val stepLoopRegex = Regex(
        pattern = """^(?:($TIME_RANGE_TOKEN)\s+)?(?:step|every|alle|schritt)\s+($TIME_ATOM)$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val idCounter = java.util.concurrent.atomic.AtomicLong()

    /**
     * Eindeutige ID für Untertitel, Overlays und Format-Spans. Die Uhrzeit allein reicht nicht:
     * ein Skript legt mehrere Elemente in derselben Millisekunde an, und gleiche IDs lassen den
     * Export das falsche (zwischengespeicherte) Bild zeigen.
     */
    private fun uniqueId(prefix: String): String = "${prefix}_${System.currentTimeMillis()}_${idCounter.incrementAndGet()}"

    private val legacyConditionWords = setOf(
        "caption", "sub", "untertitel", "text", "title", "titel", "emoji", "sticker", "filter", "fx", "blur",
    )

    /** `if caption red: u20%` / `while 0-12 step 4: …` – eine einzeilige Regel der klassischen Form. */
    fun isLegacyRuleLine(line: String): Boolean = ifWhileRegex.matches(line)

    /**
     * Klassische Bedingung, die sich auf Inhalte im Video bezieht (`caption red`, `text cyan`,
     * `filter cyberpunk`, `blur`, `(9:16)`, `0-12 step 4`) – im Unterschied zu einem Vergleich
     * wie `$i > 3` oder `captions == 0`, den der [PocketScriptInterpreter] ausrechnet.
     */
    fun isLegacyCondition(condition: String): Boolean {
        val cond = condition.trim()
        if (stepLoopRegex.matches(cond) || ratioTokenRegex.matches(cond.replace(" ", ""))) return true
        val first = cond.substringBefore(' ').lowercase()
        if (first !in legacyConditionWords) return false
        return !Regex("""[=<>!+*/%]""").containsMatchIn(cond.substringAfter(' ', ""))
    }

    // Regex für `set cut 00:48`, `set cut 00:33.3`, `set cut1 00:48`, `cut at 00:48`, `set cut: 00:48`
    private val cutMarkerRegex = Regex(
        pattern = """^(?:all\s+)?(?:set\s+)?cut(\d+)?(?:\s+at|\s*:)?\s+($TIME_ATOM)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für `delete after cut1`, `delete behind cut`, `delete before cut1`, `delete after 00:48.5`
    private val deleteCutRegex = Regex(
        pattern = """^(?:delete|del|remove|lösche|loesche|cut)\s+(after|behind|nach|hinter|before|in\s+front\s+of|vor|front|between|zwischen)\s+(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für `delete 10-20` / `delete 00:10.5-00:20.3` (Mittelstück herausschneiden)
    private val deleteRangeRegex = Regex(
        pattern = """^(?:delete|del|remove|lösche|loesche)\s+($TIME_ATOM)\s*-\s*($TIME_ATOM)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für `<Zeitbereich> set <ziel>: <inhalt>`
    // Beispiel: `8-10 set caption: Pinker Honig.red`, `00:50-00:53.3 set text: Hallo` oder `all set filter: cyberpunk`
    private val setStatementRegex = Regex(
        pattern = """^($TIME_RANGE_TOKEN)?\s*(?:set\s+)?(caption|sub|untertitel|text|title|titel|emoji|sticker|zoom|blur|filter|fx|speed|crop|cut|trim)\s*:\s*(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für direkte oder zeitgebundene Zoom-Befehle (`zoom in`, `zoom out`, `0-4 zoom 1 -> 2`, `2-6 zoom 1,8`, `reinzoomen`)
    private val standaloneZoomRegex = Regex(
        pattern = """^(?:($TIME_RANGE_TOKEN)\s+)?(?:set\s+)?(?:(zoom|z)(?:\s*:\s*|\s+)(.+)|(reinzoomen|zoomin|rauszoomen|zoomout)(?:(?:\s*:\s*|\s+)(.*))?)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Token-Regexes für Format-Klammern `(8:15)`, Richtungs-Zuschnitte `u20%`, `o20%`, `r20%`, `l20%`
    // sowie prozentuale Verschiebungen `move 10%l` (10% lower/runter) und `move 10%u` (10% up/hoch).
    private val timeTokenRegex = Regex(
        pattern = """^(all|alles|\*|$TIME_ATOM(?:-$TIME_ATOM)?)$""",
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
        var zoomStart: Float? = null,
        var zoomEnd: Float? = null,
    ) {
        val hasModifiers: Boolean
            get() = ratioW != null ||
                cutBottomPct != null ||
                cutTopPct != null ||
                cutRightPct != null ||
                cutLeftPct != null ||
                moveXPct != null ||
                moveYPct != null ||
                zoomStart != null
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

        // Programme: mehrere Zeilen (Zeilenumbruch oder `;`), Variablen, Schleifen, Funktionen, Blöcke.
        PocketScriptInterpreter.tryRun(input, engine)?.let { return it }

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

        // 1b. Zeitgesteuerte oder animierte Zoom-Befehle (`zoom in`, `zoom out`, `0-4 zoom 1 -> 2`, `2-6 zoom 1,8`)
        val zoomMatch = standaloneZoomRegex.matchEntire(input)
        if (zoomMatch != null) {
            val rangeTok = zoomMatch.groupValues[1].ifBlank { "all" }
            val payload = if (zoomMatch.groupValues[2].isNotEmpty()) {
                zoomMatch.groupValues[3].trim()
            } else {
                val verb = zoomMatch.groupValues[4].lowercase()
                val dir = if (verb.startsWith("rein") || verb.startsWith("zoomin")) "in" else "out"
                val arg = zoomMatch.groupValues[5].trim()
                if (arg.isEmpty()) dir else "$dir $arg"
            }
            executeZoomStatement(rangeTok, payload, engine)?.let { return it }
        }

        // 2. Prüfen auf Format-/Auflösungs-Ausdrücke (`10-24,3 (8:15) 9 (16:18)`, `all (9:16)`),
        //    prozentuale Kanten-Zuschnitte (`all u20%`, `o20%`, `r20%`, `l20%`, `90 u90%`),
        //    prozentuale Bildverschiebungen (`all move 10%l`, `all move 10%u`, `u20% move 10%l`)
        //    UND kombinierte Zooms (`0-5 u20% zoom in`, `50-53.3 r15% l15% zoom 1,5`)
        val formatOrCropResult = tryParseAndExecuteFormatAndCrop(input, engine)
        if (formatOrCropResult != null) {
            return formatOrCropResult
        }

        // 3. Prüfen auf direkten Zeitbereichs-Trim wie `10-24,3` oder `00:10.5-00:33.3` allein
        val pureRangeRegex = Regex("""^($TIME_ATOM)\s*-\s*($TIME_ATOM)$""", RegexOption.IGNORE_CASE)
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
     *    → Sucht alle Untertitel mit der Farbe `.red` (oder dem Text `"red"`), wendet **nur für deren
     *      Zeitfenster** den Befehl dahinter (`u20%`, `(9:16)`, `zoom 1,8` etc.) an und springt dorthin.
     *      Mehrere Befehle: `if caption red: u20%, move 10%u` (auch `&`, `und`, bei freiem Text `&&`).
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
        val stepMatch = stepLoopRegex.matchEntire(cond)
        if (stepMatch != null) {
            val rangeTok = stepMatch.groupValues[1].ifBlank { "all" }
            val range = parseTimeRange(rangeTok, engine)
            val stepMs = parseSecondsToMs(stepMatch.groupValues[2]).coerceAtLeast(1_000L)
            var t = range.startMs
            val windows = mutableListOf<Pair<Long, Long>>()
            while (t < range.endMs && windows.size < 12) {
                windows.add(t to (t + stepMs).coerceAtMost(range.endMs))
                t += stepMs
            }
            val result = if (windows.isEmpty()) actionBody else executeConditionActions(windows, actionBody, engine)
            return "$labelPrefix-Schleife (${windows.size} Schritte à ${ProjectEngine.formatSec(stepMs)}): $result"
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

            // Die Befehle gelten nur in den Zeitfenstern der gefundenen Untertitel – nicht fürs ganze Projekt
            val execResult = executeConditionActions(matchedSubs.map { it.startMs to it.endMs }.distinct(), actionBody, engine)
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

            val execResult = executeConditionActions(matchedOverlays.map { it.startMs to it.endMs }.distinct(), actionBody, engine)
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
            val res = splitConditionActions(actionBody).joinToString(" · ") { action ->
                tryParseAndExecuteFormatAndCrop("all $action", engine) ?: engine.executeCommand(action)
            }
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
                val trimmedSpans = p.timedFormatSpans.mapNotNull { span ->
                    when {
                        span.startMs >= newEnd -> null
                        span.endMs > newEnd -> span.copy(endMs = newEnd)
                        else -> span
                    }
                }
                p.copy(
                    sourceDurationMs = newSourceDur,
                    trimEndMs = newEnd,
                    subtitles = trimmedSubs,
                    overlays = trimmedOverlays,
                    timedFormatSpans = trimmedSpans,
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
                val trimmedOverlays = p.overlays.mapNotNull { ov ->
                    when {
                        ov.endMs <= newStart -> null
                        ov.startMs < newStart -> ov.copy(startMs = newStart)
                        else -> ov
                    }
                }
                val trimmedSpans = p.timedFormatSpans.mapNotNull { span ->
                    when {
                        span.endMs <= newStart -> null
                        span.startMs < newStart -> span.copy(startMs = newStart)
                        else -> span
                    }
                }
                p.copy(
                    sourceDurationMs = newSourceDur,
                    trimStartMs = newStart,
                    subtitles = trimmedSubs,
                    overlays = trimmedOverlays,
                    timedFormatSpans = trimmedSpans,
                )
            }
            engine.playheadMs = engine.project.trimStartMs
            "Alles vor $markerLabel ($timeLabel / ${ProjectEngine.formatSec(cutTimeMs)}) gelöscht – Video startet jetzt bei $timeLabel."
        }
    }

    /** Löst `cut1` / `cut` / `00:48` / `00:33.3` / `12,5` in Millisekunden auf (oder `null`, falls ungültig). */
    internal fun resolveCutOrTime(token: String, engine: ProjectEngine): Long? {
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
        if (!Regex("""^$TIME_ATOM$""", RegexOption.IGNORE_CASE).matches(clean)) return null
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

    /** Befehle mit freiem Text (`set caption: Du & ich, wir`) – dort trennt nur `&&` mehrere Befehle. */
    private val freeTextActionRegex = Regex(
        pattern = """(?:^|\s)(?:caption|sub|untertitel|text|title|titel|emoji|sticker|print|echo|sag|zeige)\b""",
        option = RegexOption.IGNORE_CASE,
    )

    // `zoom 1,8` / `zoom in` / `zoom out` / `zoom 1 -> 2` hinter einer Bedingung -> Zoom nur im Zeitfenster
    private val scopedZoomRegex = Regex(
        pattern = """^(?:set\s+)?(?:(zoom|z)(?:\s*:\s*|\s+)(.+)|(reinzoomen|zoomin|rauszoomen|zoomout)(?:(?:\s*:\s*|\s+)(.*))?)$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val zoomWordSet = setOf(
        "move", "shift", "pan", "verschiebe",
        "zoom", "z", "reinzoomen", "zoomin", "rauszoomen", "zoomout",
        "in", "rein", "out", "raus", "to", "bis", "->",
    )

    private fun formatZoomNumber(z: Float): String {
        val rounded = (z * 100f).roundToInt() / 100f
        return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}" else rounded.toString()
    }

    /**
     * Wandelt einen einzelnen Zoom-Wert (`1,8`, `1.8x`, `50%`, `0,2`, `150`) in einen Faktor (0.1x..5.0x) um.
     * Relative Werte mit `+`/`-` werden bewusst abgewiesen (die behandelt [parseRelativeOrAbsoluteZoom]).
     */
    internal fun parseZoomFactor(token: String): Float? {
        val clean = token.trim().lowercase().replace(",", ".")
        if (clean.isEmpty() || clean.startsWith("+") || clean.startsWith("-")) return null
        val hasPercent = clean.endsWith("%")
        val value = clean.removeSuffix("%").removeSuffix("x").toFloatOrNull() ?: return null
        if (!value.isFinite() || value <= 0f) return null
        val factor = if (hasPercent || value > 5f) value / 100f else value
        return factor.coerceIn(0.1f, 5.0f)
    }

    /** Parst relative (`+0.2`, `-50%`) und absolute (`0.5`, `1.8`) Zoom-Angaben für den Einzelbefehl `zoom`. */
    internal fun parseRelativeOrAbsoluteZoom(raw: String, currentZoom: Float): Float? {
        val clean = raw.trim().lowercase().replace(",", ".")
        if (clean.isEmpty()) return null
        if (clean.startsWith("+") || clean.startsWith("-")) {
            val sign = if (clean.startsWith("-")) -1f else 1f
            val body = clean.drop(1).trim()
            val hasPercent = body.endsWith("%")
            val value = body.removeSuffix("%").removeSuffix("x").toFloatOrNull() ?: return null
            if (!value.isFinite()) return null
            val delta = if (hasPercent) value / 100f else value
            return (currentZoom + sign * delta).coerceIn(0.1f, 5.0f)
        }
        return parseZoomFactor(clean)
    }

    /**
     * Parst Zoom-Spezifikationen für statischen oder animierten Zoom:
     * - `1,8` / `0,5` / `50%` → statischer Zoom `(f, null)`
     * - `1 -> 2` / `0,2 - 1,0` / `0,5 bis 1,5` → animierter Zoom `(a, b)`
     * - `in` / `rein` [+ optionaler Faktor] → Reinzoomen (Standard `1.0 -> 1.8`, bei `< 1.0` von klein in der Mitte auf `1.0`)
     * - `out` / `raus` [+ optionaler Faktor] → Rauszoomen (Standard `1.8 -> 1.0`, bei `< 1.0` von `1.0` auf klein in der Mitte)
     */
    internal fun parseZoomSpec(raw: String, currentStartZoom: Float = 1.0f): Pair<Float, Float?>? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("+") || trimmed.startsWith("-")) return null

        var normalized = trimmed
            .replace(Regex("""\s*(?:->|→|\.\.)\s*"""), " -> ")
            .replace(Regex("""\s+(?:to|bis)\s+""", RegexOption.IGNORE_CASE), " -> ")
            .replace(Regex("""(?<=[0-9%x])\s*-\s*(?=[0-9])""", RegexOption.IGNORE_CASE), " -> ")
        val hasArrow = normalized.contains(" -> ")
        val tokens = normalized.split(Regex("""\s+""")).filter { it.isNotEmpty() && it != "->" }
        if (tokens.isEmpty()) return null

        val firstLower = tokens.first().lowercase()
        val dir = when (firstLower) {
            "in", "rein", "zoomin", "reinzoomen" -> "in"
            "out", "raus", "zoomout", "rauszoomen" -> "out"
            else -> null
        }
        val rest = if (dir != null) tokens.drop(1) else tokens
        val factors = rest.map { parseZoomFactor(it) ?: return null }
        if (factors.size > 2) return null

        return when {
            factors.size == 2 -> factors[0] to factors[1]
            dir == "in" && factors.isEmpty() -> {
                if (currentStartZoom < 0.99f) currentStartZoom to 1.0f else 1.0f to 1.8f
            }
            dir == "in" && factors.size == 1 -> {
                val f = factors[0]
                when {
                    f > 1.0f -> 1.0f to f
                    f < 1.0f -> f to 1.0f
                    else -> 0.2f to 1.0f
                }
            }
            dir == "out" && factors.isEmpty() -> {
                if (currentStartZoom > 1.05f) currentStartZoom to 1.0f else 1.8f to 1.0f
            }
            dir == "out" && factors.size == 1 -> {
                val f = factors[0]
                when {
                    f < 1.0f -> 1.0f to f
                    f > 1.0f -> f to 1.0f
                    else -> 1.8f to 1.0f
                }
            }
            dir == null && factors.size == 1 && !hasArrow -> factors[0] to null
            else -> null
        }
    }

    /**
     * Führt einen (optional zeitgebundenen oder animierten) Zoom-Befehl aus.
     * Statische Zooms auf `all` aktualisieren den globalen Transform; animierte oder zeitgebundene
     * Zooms werden als [TimedFormatSpan] über das Zeitfenster gelegt.
     */
    private fun executeZoomStatement(
        rangeToken: String,
        rawPayload: String,
        engine: ProjectEngine,
        scopedOnly: Boolean = false,
    ): String? {
        val range = parseTimeRange(rangeToken, engine)
        val baseZoom = if (range.isAll) engine.project.transform.zoom else engine.currentTransform(range.startMs).zoom
        val (zoomStart, zoomEnd) = parseZoomSpec(rawPayload, baseZoom) ?: return null

        if (zoomEnd == null && range.isAll && !scopedOnly) {
            engine.setZoomAndPan(zoom = zoomStart, clearTimedZoom = true)
            return "Zoom auf ${engine.project.transform.zoom}x gesetzt."
        }

        val spanStart = if (range.isAll) engine.project.trimStartMs else range.startMs
        val spanEnd = if (range.isAll) maxOf(engine.project.trimEndMs, spanStart + 400L) else range.endMs
        val span = TimedFormatSpan(
            id = uniqueId("fmt"),
            startMs = spanStart,
            endMs = spanEnd,
            hasCustomRatio = false,
            zoom = zoomStart,
            zoomEnd = zoomEnd,
        )
        val neededDuration = maxOf(engine.project.sourceDurationMs, spanEnd)
        val desc = if (zoomEnd != null) {
            "Zoom ${formatZoomNumber(zoomStart)}x -> ${formatZoomNumber(zoomEnd)}x"
        } else {
            "Zoom ${formatZoomNumber(zoomStart)}x"
        }
        engine.commitChange("$desc (${ProjectEngine.formatSec(spanStart)}-${ProjectEngine.formatSec(spanEnd)})") { p ->
            p.copy(
                sourceDurationMs = neededDuration,
                trimEndMs = if (p.trimEndMs < spanEnd && !range.isAll) spanEnd else p.trimEndMs,
                timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, listOf(span)),
            )
        }
        engine.playheadMs = spanStart
        return "$desc (${ProjectEngine.formatSec(spanStart)}–${ProjectEngine.formatSec(spanEnd)})."
    }

    /**
     * Führt neue Spans in bestehende ein: haben zwei Spans exakt denselben Zeitbereich (`startMs..endMs`),
     * werden die neuen Eigenschaften in den vorhandenen Span gemischt, statt Duplikate anzuhäufen.
     */
    private fun mergeTimedFormatSpans(
        existing: List<TimedFormatSpan>,
        added: List<TimedFormatSpan>,
    ): List<TimedFormatSpan> {
        val result = existing.toMutableList()
        for (span in added) {
            val matchIndex = result.indexOfLast { it.startMs == span.startMs && it.endMs == span.endMs }
            if (matchIndex >= 0) {
                val prev = result[matchIndex]
                result[matchIndex] = prev.copy(
                    ratioWidth = if (span.hasCustomRatio) span.ratioWidth else prev.ratioWidth,
                    ratioHeight = if (span.hasCustomRatio) span.ratioHeight else prev.ratioHeight,
                    hasCustomRatio = prev.hasCustomRatio || span.hasCustomRatio,
                    cropLeft = span.cropLeft ?: prev.cropLeft,
                    cropTop = span.cropTop ?: prev.cropTop,
                    cropRight = span.cropRight ?: prev.cropRight,
                    cropBottom = span.cropBottom ?: prev.cropBottom,
                    panX = span.panX ?: prev.panX,
                    panY = span.panY ?: prev.panY,
                    zoom = span.zoom ?: prev.zoom,
                    zoomEnd = if (span.zoom != null) span.zoomEnd else prev.zoomEnd,
                    filterPreset = span.filterPreset ?: prev.filterPreset,
                    blurRadiusPx = span.blurRadiusPx ?: prev.blurRadiusPx,
                )
            } else {
                result.add(span)
            }
        }
        return result.takeLast(24)
    }

    /**
     * Zerlegt den Teil hinter `if …:` in einzelne Befehle:
     * `u20%, move 10%u` · `u20% & zoom 1,5` · `u20% und move 10%u` · `set text: Hi && u20%`.
     * Kommazahlen (`zoom 1,5`) bleiben ganz, weil ein Trenn-Komma ein Leerzeichen dahinter braucht.
     */
    private fun splitConditionActions(actionBody: String): List<String> {
        val body = actionBody.trim()
        val separator = if (freeTextActionRegex.containsMatchIn(body)) {
            Regex("""\s*&&\s*""")
        } else {
            Regex("""\s*&&?\s*|\s*,\s+|\s+(?:und|and)\s+""", RegexOption.IGNORE_CASE)
        }
        return body.split(separator).map { it.trim() }.filter { it.isNotEmpty() }.ifEmpty { listOf(body) }
    }

    /**
     * Führt die Befehle hinter einer Bedingung **nur in den Zeitfenstern [ranges]** aus (z.B. solange
     * der rote Untertitel eingeblendet ist). Der globale Zuschnitt / das globale Format bleiben unberührt –
     * fürs ganze Projekt gibt es weiterhin `all u20%`.
     */
    private fun executeConditionActions(
        ranges: List<Pair<Long, Long>>,
        actionBody: String,
        engine: ProjectEngine,
    ): String = splitConditionActions(actionBody).joinToString(" · ") { action ->
        val zoomMatch = scopedZoomRegex.matchEntire(action)
        val zoomPayload = zoomMatch?.let { m ->
            if (m.groupValues[1].isNotEmpty()) {
                m.groupValues[2].trim()
            } else {
                val verb = m.groupValues[3].lowercase()
                val dir = if (verb.startsWith("rein") || verb.startsWith("zoomin")) "in" else "out"
                val arg = m.groupValues[4].trim()
                if (arg.isEmpty()) dir else "$dir $arg"
            }
        }
        val parsedZoom = zoomPayload?.let { payload ->
            val baseZoom = ranges.firstOrNull()?.first?.let { engine.currentTransform(it).zoom } ?: 1.0f
            parseZoomSpec(payload, baseZoom)
        }
        val combined = buildCombinedScopedCommand(ranges, action)
        when {
            parsedZoom != null -> {
                val (zoomStart, zoomEnd) = parsedZoom
                val label = if (zoomEnd != null) {
                    "Zoom ${formatZoomNumber(zoomStart)}x -> ${formatZoomNumber(zoomEnd)}x"
                } else {
                    "Zoom ${formatZoomNumber(zoomStart)}x"
                }
                engine.commitChange("PocketScript $label (${ranges.size} Zeitfenster)") { p ->
                    val spans = ranges.map { (startMs, endMs) ->
                        TimedFormatSpan(
                            id = uniqueId("fmt"),
                            startMs = startMs,
                            endMs = endMs,
                            hasCustomRatio = false,
                            zoom = zoomStart,
                            zoomEnd = zoomEnd,
                        )
                    }
                    p.copy(timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, spans))
                }
                "$label nur in ${ranges.size} Zeitfenster(n)"
            }
            combined != null ->
                tryParseAndExecuteFormatAndCrop(combined, engine, scopedOnly = true) ?: engine.executeCommand(action)
            else -> ranges.map { (startMs, endMs) -> executeScopedBody(startMs, endMs, action, engine) }.last()
        }
    }

    private fun buildCombinedScopedCommand(
        ranges: List<Pair<Long, Long>>,
        actionBody: String,
    ): String? {
        val bodyTrimmed = actionBody.trim()
        val tokens = bodyTrimmed.split(Regex("\\s+"))
        val hasZoomWord = tokens.any { it.lowercase() in setOf("zoom", "z", "reinzoomen", "zoomin", "rauszoomen", "zoomout") }
        // Prüfen, ob actionBody aus Format `(9:16)`, Kanten-Cut `u20%`, Move `move 10%l` oder Zoom `zoom in` besteht
        if (tokens.all {
                ratioTokenRegex.matches(it) ||
                    edgeCropTokenRegex.matches(it) ||
                    moveSuffixTokenRegex.matches(it) ||
                    movePrefixTokenRegex.matches(it) ||
                    it.lowercase() in zoomWordSet ||
                    (hasZoomWord && parseZoomSpec(it) != null)
            }
        ) {
            return ranges.joinToString(" ") { (startMs, endMs) ->
                "${formatSecNumber(startMs)}-${formatSecNumber(endMs)} $bodyTrimmed"
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
        val rangePrefix = "${formatSecNumber(startMs)}-${formatSecNumber(endMs)}"
        val body = actionBody.trim()
        val withRange = "$rangePrefix $body"
        return tryExecuteScript(withRange, engine) ?: engine.executeCommand(body)
    }

    /** Formatiert Millisekunden einheitlich auf Zehntelsekunden (wie [ProjectEngine.formatSec], ohne `s`). */
    private fun formatSecNumber(ms: Long): String {
        val totalTenths = (ms / 100L).coerceAtLeast(0L)
        return if (totalTenths % 10L == 0L) {
            "${totalTenths / 10L}"
        } else {
            "${totalTenths / 10L}.${totalTenths % 10L}"
        }
    }

    /**
     * Parst und führt Ausdrücke mit Seitenverhältnissen `(8:15)`, Kanten-Zuschnitten
     * (`u20%` unten, `o20%` oben, `r20%` rechts, `l20%` links, `90 u90%`) sowie
     * prozentualen Verschiebungen (`all move 10%l` = 10% lower/runter, `all move 10%u` = 10% up/hoch,
     * `u20% move 10%l`) aus.
     */
    private fun tryParseAndExecuteFormatAndCrop(raw: String, engine: ProjectEngine, scopedOnly: Boolean = false): String? {
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
        // Pfeile und `to`/`bis` für Zoom-Bereiche `1 -> 2` / `1 to 2` / `50% - 150%` zusammenziehen
        normalized = normalized.replace(Regex("""\s*(?:->|→)\s*"""), "->")
        normalized = normalized.replace(
            Regex("""(\d+(?:[.,]\d+)?%?x?)\s+(?:to|bis)\s+(\d+(?:[.,]\d+)?%?x?)""", RegexOption.IGNORE_CASE),
            "$1->$2",
        )
        normalized = normalized.replace(
            Regex("""(\d+(?:[.,]\d+)?%x?)\s*-\s*(\d+(?:[.,]\d+)?%?x?)""", RegexOption.IGNORE_CASE),
            "$1->$2",
        )
        // Leerzeichen um Bindestrich bei Zeitbereichen `10 - 24,3` / `00:10.5 - 00:33.3` -> `10-24,3` normalisieren
        normalized = normalized.replace(
            Regex("""($TIME_ATOM)\s*-\s*($TIME_ATOM)"""),
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

        var idx = 0
        while (idx < tokens.size) {
            val token = tokens[idx]
            val lowerTok = token.lowercase()
            if (lowerTok in listOf("move", "shift", "pan", "verschiebe")) {
                nextTokenIsMove = true
                idx++
                continue
            }

            if (lowerTok in listOf("zoom", "z", "reinzoomen", "zoomin", "rauszoomen", "zoomout")) {
                nextTokenIsMove = false
                val rangeSoFar = parseTimeRange(current.timeToken, engine)
                val baseZoom = if (rangeSoFar.isAll) engine.project.transform.zoom else engine.currentTransform(rangeSoFar.startMs).zoom
                if (lowerTok in listOf("reinzoomen", "zoomin", "rauszoomen", "zoomout")) {
                    val dir = if (lowerTok.startsWith("rein") || lowerTok.startsWith("zoomin")) "in" else "out"
                    val nextTok = tokens.getOrNull(idx + 1)
                    val twoSpec = if (nextTok != null) parseZoomSpec("$dir $nextTok", baseZoom) else null
                    val spec = twoSpec ?: parseZoomSpec(dir, baseZoom) ?: return null
                    current.zoomStart = spec.first
                    current.zoomEnd = spec.second
                    idx += if (twoSpec != null) 2 else 1
                } else {
                    val t1 = tokens.getOrNull(idx + 1) ?: return null
                    val t2 = tokens.getOrNull(idx + 2)
                    if (t1.lowercase() in listOf("in", "rein", "out", "raus")) {
                        val twoSpec = if (t2 != null) parseZoomSpec("$t1 $t2", baseZoom) else null
                        val spec = twoSpec ?: parseZoomSpec(t1, baseZoom) ?: return null
                        current.zoomStart = spec.first
                        current.zoomEnd = spec.second
                        idx += if (twoSpec != null) 3 else 2
                    } else {
                        val spec = parseZoomSpec(t1, baseZoom) ?: return null
                        current.zoomStart = spec.first
                        current.zoomEnd = spec.second
                        idx += 2
                    }
                }
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
                    // Unbekanntes Token -> kein reiner Format-/Kanten-Cut-/Move-/Zoom-Ausdruck
                    return null
                }
            }
            idx++
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
        var updatedGlobalZoom = engine.project.transform.zoom
        var shouldUpdateGlobalCrop = false
        var shouldUpdateGlobalZoom = false
        var clearTimedCropOverrides = false
        var clearTimedZoomOverrides = false

        for (seg in segments) {
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
            val hasZoom = seg.zoomStart != null
            val isTimedZoom = hasZoom && (seg.zoomEnd != null || !range.isAll || scopedOnly)
            val isGlobalStaticZoom = hasZoom && seg.zoomEnd == null && range.isAll && !scopedOnly

            if (hasRatio || (!range.isAll && (hasEdgeCrop || hasMove)) || (scopedOnly && (hasEdgeCrop || hasMove)) || isTimedZoom) {
                val spanStart = if (range.isAll && isTimedZoom && !hasRatio) engine.project.trimStartMs else range.startMs
                val spanEnd = if (range.isAll && isTimedZoom && !hasRatio) maxOf(engine.project.trimEndMs, spanStart + 400L) else range.endMs
                val applyCropInSpan = !range.isAll || scopedOnly
                newSpans.add(
                    TimedFormatSpan(
                        id = uniqueId("fmt"),
                        startMs = spanStart,
                        endMs = spanEnd,
                        ratioWidth = seg.ratioW ?: 9f,
                        ratioHeight = seg.ratioH ?: 16f,
                        hasCustomRatio = hasRatio,
                        cropLeft = if (applyCropInSpan) cropLeftVal else null,
                        cropTop = if (applyCropInSpan) cropTopVal else null,
                        cropRight = if (applyCropInSpan) cropRightVal else null,
                        cropBottom = if (applyCropInSpan) cropBottomVal else null,
                        panX = if (applyCropInSpan) panXVal else null,
                        panY = if (applyCropInSpan) panYVal else null,
                        zoom = if (isTimedZoom) seg.zoomStart else null,
                        zoomEnd = if (isTimedZoom) seg.zoomEnd else null,
                    ),
                )
            }

            // Das Ausgabeformat ist pro Video fest, deshalb setzt ein einzelner Format-Befehl (`10-20 (1:1)`)
            // weiterhin auch das Projektformat – außer hinter einer Bedingung ([scopedOnly]).
            if (hasRatio && (range.isAll || (segments.size == 1 && !scopedOnly))) {
                globalPreset = matchClosestAspectPreset(seg.ratioW ?: 9f, seg.ratioH ?: 16f)
            }

            if (isGlobalStaticZoom) {
                updatedGlobalZoom = seg.zoomStart!!
                shouldUpdateGlobalZoom = true
                clearTimedZoomOverrides = true
            }

            // Nur `all` (oder gar keine Zeitangabe: `u20%`, `move 10%l`) ändert den Basis-Transform des ganzen
            // Videos. Mit Zeitbereich (`0-2 u20%`, `90 u90%`) gilt der Zuschnitt ausschließlich in diesem Fenster.
            if ((hasEdgeCrop || hasMove) && range.isAll && !scopedOnly) {
                shouldUpdateGlobalCrop = true
                clearTimedCropOverrides = true
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
            seg.zoomStart?.let { zStart ->
                val zEnd = seg.zoomEnd
                if (zEnd != null) {
                    descTokens.add("zoom ${formatZoomNumber(zStart)}x->${formatZoomNumber(zEnd)}x")
                } else {
                    descTokens.add("zoom ${formatZoomNumber(zStart)}x")
                }
            }

            val timeLabel = if (range.isAll) "all" else "${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)}"
            summaryParts.add("$timeLabel ${descTokens.joinToString(" ")}")
        }

        val summary = summaryParts.joinToString(", ")

        engine.commitChange("PocketScript Schnitt: $summary") { p ->
            val cleanedExisting = if (clearTimedCropOverrides || clearTimedZoomOverrides) {
                p.timedFormatSpans.mapNotNull { old ->
                    val stripped = old.copy(
                        cropLeft = if (clearTimedCropOverrides && segments.any { it.cutLeftPct != null }) null else old.cropLeft,
                        cropTop = if (clearTimedCropOverrides && segments.any { it.cutTopPct != null }) null else old.cropTop,
                        cropRight = if (clearTimedCropOverrides && segments.any { it.cutRightPct != null }) null else old.cropRight,
                        cropBottom = if (clearTimedCropOverrides && segments.any { it.cutBottomPct != null }) null else old.cropBottom,
                        panX = if (clearTimedCropOverrides && segments.any { it.moveXPct != null }) null else old.panX,
                        panY = if (clearTimedCropOverrides && segments.any { it.moveYPct != null }) null else old.panY,
                        zoom = if (clearTimedZoomOverrides) null else old.zoom,
                        zoomEnd = if (clearTimedZoomOverrides) null else old.zoomEnd,
                    )
                    if (!stripped.hasCustomRatio &&
                        stripped.cropLeft == null &&
                        stripped.cropTop == null &&
                        stripped.cropRight == null &&
                        stripped.cropBottom == null &&
                        stripped.panX == null &&
                        stripped.panY == null &&
                        stripped.zoom == null &&
                        stripped.filterPreset == null &&
                        stripped.blurRadiusPx == null
                    ) {
                        null
                    } else {
                        stripped
                    }
                }
            } else {
                p.timedFormatSpans
            }

            val mergedSpans = mergeTimedFormatSpans(cleanedExisting, newSpans)
            val nextTransform = if (shouldUpdateGlobalCrop || shouldUpdateGlobalZoom) {
                p.transform.copy(
                    cropLeft = updatedGlobalLeft.coerceIn(0f, 0.98f),
                    cropTop = updatedGlobalTop.coerceIn(0f, 0.98f),
                    cropRight = updatedGlobalRight.coerceIn((updatedGlobalLeft + 0.02f).coerceAtMost(1f), 1f),
                    cropBottom = updatedGlobalBottom.coerceIn((updatedGlobalTop + 0.02f).coerceAtMost(1f), 1f),
                    panX = updatedGlobalPanX.coerceIn(-0.95f, 0.95f),
                    panY = updatedGlobalPanY.coerceIn(-0.95f, 0.95f),
                    zoom = updatedGlobalZoom.coerceIn(0.1f, 5.0f),
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
                    id = uniqueId("ps_sub"),
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
                    id = uniqueId("ps_txt"),
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
                    // Duplikate im selben Zeitfenster (z.B. beim erneuten Ausführen eines Skripts) aktualisieren
                    val filtered = p.overlays.filterNot {
                        it.kind == OverlayKind.TEXT &&
                            it.startMs == overlay.startMs &&
                            it.endMs == overlay.endMs &&
                            (it.content == overlay.content ||
                                (kotlin.math.abs(it.xPercent - overlay.xPercent) < 0.005f &&
                                    kotlin.math.abs(it.yPercent - overlay.yPercent) < 0.005f))
                    }
                    p.copy(
                        sourceDurationMs = neededDuration,
                        trimEndMs = maxOf(p.trimEndMs, range.endMs),
                        overlays = filtered + overlay,
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
                    id = uniqueId("ps_emo"),
                    kind = OverlayKind.EMOJI,
                    content = styled.cleanText,
                    xPercent = 0.5f,
                    yPercent = yPos,
                    scale = scale,
                    startMs = range.startMs,
                    endMs = range.endMs,
                )
                engine.commitChange("Emoji ${styled.cleanText} (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
                    val filtered = p.overlays.filterNot {
                        it.kind == OverlayKind.EMOJI &&
                            it.startMs == overlay.startMs &&
                            it.endMs == overlay.endMs &&
                            (it.content == overlay.content ||
                                (kotlin.math.abs(it.xPercent - overlay.xPercent) < 0.005f &&
                                    kotlin.math.abs(it.yPercent - overlay.yPercent) < 0.005f))
                    }
                    p.copy(
                        sourceDurationMs = neededDuration,
                        trimEndMs = maxOf(p.trimEndMs, range.endMs),
                        overlays = filtered + overlay,
                    )
                }
                engine.selectedOverlayId = overlay.id
                engine.playheadMs = range.startMs
                "Emoji ${styled.cleanText} (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}) gesetzt."
            }

            "zoom" -> {
                executeZoomStatement(rangeToken, rawPayload, engine)
                    ?: "Syntax: zoom <0.1 - 5.0 | in | out | 1 -> 2>, z.B. '0-5 set zoom: in'"
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
            var endMs = parseSecondsToMs(parts[1]).coerceAtLeast(startMs + 400L)
            // Wenn die eingegebene Endzeit (z.B. 53.3) auf Zehntelsekunden genau der Videolänge (53358ms)
            // entspricht, auf das echte Ende einrasten, damit am Schluss keine Lücke entsteht.
            if (endMs < totalMs && endMs / 100L == totalMs / 100L) {
                endMs = totalMs
            }
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
            return (totalSeconds * 1000.0).roundToLong().coerceAtLeast(0L)
        }
        val seconds = normalized.toDoubleOrNull() ?: 0.0
        return (seconds * 1000.0).roundToLong().coerceAtLeast(0L)
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
 * Inhalt der offiziellen **PocketScript-Anleitung** (`PocketScript_Anleitung.pdf`).
 * Die Abschnitte werden in der App angezeigt (Antippen kopiert den Befehl) und von jeder
 * Plattform als echte PDF-Datei geschrieben.
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
                "0-2 u20%" to "Mit Zeitbereich gilt der Zuschnitt nur in diesem Fenster – hier in den ersten 2 Sekunden.",
                "90 u90%" to "Ab Sekunde 90 unten 90 % wegschneiden – dort bleibt oben nur ein schmaler 10%-Balken.",
            ),
        ),
        ManualSection(
            title = "1a. Rein- und Rauszoomen (zoom in, zoom out, 0.1x–5.0x)",
            codeExamples = listOf(
                "zoom in" to "Zoomt animiert von der Mitte her größer (1.0x -> 1.8x über den ganzen Clip).",
                "0-4 zoom in 0,2" to "Startet ganz klein in der Mitte (20 %) und macht das Video bis Sekunde 4 voll groß (100 %).",
                "0-4 zoom in 2,0" to "Zoomt in den ersten 4 Sekunden aus der Mitte von 1.0x auf 2.0x hinein.",
                "zoom out" to "Zoomt animiert wieder heraus (1.8x -> 1.0x über den ganzen Clip).",
                "0-4 zoom out 0,5" to "Zoomt in den ersten 4 Sekunden von voller Größe (100 %) auf 50 % in die Mitte heraus.",
                "0-3 zoom 1 -> 2" to "Animierter Zoom mit frei wählbarem Start- und Endfaktor (auch 2 -> 1 oder 0,3 -> 1,5).",
                "2-6 zoom 1,8" to "Statischer Zoom auf 1.8x nur von Sekunde 2 bis 6 (fürs ganze Video: zoom 1,8 oder zoom 0,5).",
                "if caption red: zoom in" to "Zoomt animiert hinein, solange der rote Untertitel läuft (auch mit while text red).",
            ),
        ),
        ManualSection(
            title = "2. Schnittmarken (set cut) & Dahinter/Davor löschen (delete)",
            codeExamples = listOf(
                "set cut 00:33.3" to "Setzt Schnittmarke cut1 exakt bei 00:33.3 (Zehntelsekunden-Genauigkeit überall einheitlich).",
                "delete after cut1" to "Löscht das gesamte Videomaterial hinter Schnittmarke cut1.",
                "delete before cut1" to "Löscht das gesamte Videomaterial vor Schnittmarke cut1.",
                "set cut2 01:15.5" to "Setzt eine zweite Schnittmarke cut2 bei 01:15.5.",
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
                "clear all texts" to "Löscht alle Text-Overlays (clear text2 = nur den zweiten, 'texts' zeigt die Liste).",
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
                "show edits" to "Zeigt alle Befehle, die dieses Projekt bisher geändert haben – zum Nachlesen, Kopieren oder erneut Ausführen (clear edits leert die Liste).",
            ),
        ),
        ManualSection(
            title = "3. Bedingungen & Schleifen (if / while)",
            codeExamples = listOf(
                "if caption red: u20%" to "Nur solange der rote Untertitel eingeblendet ist, unten 20 % wegschneiden (fürs ganze Video: all u20%).",
                "if caption red: u20%, move 10%u, zoom 1,5" to "Mehrere Befehle hinter einer Bedingung – getrennt mit Komma, & oder und (bei Text-Befehlen mit &&).",
                "if caption red; u20%; move 10%u; end" to "Dasselbe als Block – im Code-Fenster eine Zeile pro Befehl statt ';'.",
                "if caption red: (9:16)" to "Solange der rote Untertitel läuft, Format auf (9:16) schalten.",
                "while caption red: u40%" to "Solange rote Untertitel eingeblendet sind, unten 40 % wegschneiden.",
                "while caption: (1:1)" to "Solange irgendein Untertitel läuft, Format auf (1:1) schalten.",
                "if text cyan: u20%" to "Wenn ein cyanfarbenes Text-Overlay aktiv ist, unten 20 % wegschneiden.",
                "while 0-12 step 4: set emoji: 🔥" to "While-Schleife: setzt von 0–12s alle 4 Sekunden ein Feuer-Emoji.",
            ),
        ),
        ManualSection(
            title = "3a. Variablen & Rechnen (let, \$name, {Rechnung})",
            codeExamples = listOf(
                "let x = 2,5" to "Legt die Variable x an. Sie bleibt erhalten, bis du sie änderst (vars zeigt alle).",
                "zoom \$x" to "Setzt den Wert von x in den Befehl ein – hier also zoom 2,5.",
                "let ende = \$x * 2 + 1" to "Rechnen mit + - * / % und Klammern, auch min(), max(), round(), random().",
                "0-{\$x + 3} set caption: Start.gold" to "Geschweifte Klammern rechnen direkt im Befehl: 0 bis 5,5 Sekunden.",
                "x += 1" to "Ändert eine Variable (auch -=, *=, /=).",
                "global logo = 🚀" to "Globale Variable: gilt in allen Projekten und bleibt nach dem Neustart erhalten. Ohne global gehört eine Variable zum Projekt und wird mit ihm gespeichert.",
                "let favoriten = [🚀, ⭐, 🎉]" to "Eine Liste merken – danach: for e in \$favoriten: … (auch als global favoriten = […]).",
                "unset x" to "Löscht eine Variable (unset global x für eine globale, auch für Funktionen; clear vars / clear globals löscht alle).",
                "print Video dauert \$duration s, \$captions Untertitel" to "Eingebaute Werte: \$duration, \$trimstart, \$trimend, \$playhead, \$zoom, \$captions, \$texts, \$emojis.",
            ),
        ),
        ManualSection(
            title = "3b. Schleifen & Bedingungen mit Blöcken (for, repeat, if … else … end)",
            codeExamples = listOf(
                "for t in 0..12 step 4: \$t-{\$t + 2} set emoji: 🔥" to "Zählschleife: t läuft 0, 4, 8, 12 – jeweils 2 Sekunden lang ein Emoji.",
                "for c in captions: \$c_start-\$c_end u15%" to "Läuft über alle Untertitel (\$c_start, \$c_end, \$c_text) – auch texts, emojis, cuts.",
                "for farbe in [red, gold, cyan]: text Hallo.\$farbe" to "Schleife über eine eigene Liste.",
                "repeat 3: rotate 90" to "Wiederholt einen Befehl dreimal.",
                "if \$duration > 30: delete after 00:30" to "Bedingung mit Vergleich (== != < <= > >=, and, or, not).",
                "if \$captions == 0; autocaption; else; print Schon \$captions da; end" to "Block-Form mit else – im Code-Fenster eine Zeile pro Befehl statt ';'.",
            ),
        ),
        ManualSection(
            title = "3c. Eigene Funktionen (def)",
            codeExamples = listOf(
                "def titel(text, farbe): 0-3 set text: \$text.\$farbe.big.top" to "Definiert einen eigenen Befehl mit Parametern.",
                "titel(Mein Urlaub, gold)" to "Ruft ihn auf – beliebig oft, mit anderen Werten.",
                "def haelfte(x) = \$x / 2" to "Rechen-Funktion: liefert einen Wert zurück.",
                "zoom {haelfte(5)}" to "… und lässt sich in jeder Rechnung verwenden (zoom 2,5).",
                "def intro(); all (9:16); 0-2 set caption: Los gehts!.yellow; end" to "Mehrere Befehle als Block bis end.",
                "global def titel(text): 0-3 set text: \$text.gold.big.top" to "Globale Funktion: in allen Projekten verfügbar. Ohne global gehört sie zum Projekt; beide bleiben nach dem Neustart erhalten.",
            ),
        ),
        ManualSection(
            title = "4. Grundprinzip: Zeitbereiche & Kommazahlen (überall auf 0,1 s genau)",
            codeExamples = listOf(
                "10-24,3" to "Von Sekunde 10,0 bis Sekunde 24,3 schneiden (Komma oder Punkt erlaubt).",
                "00:10.5-00:24.3" to "Minuten:Sekunden mit Zehntelsekunden – funktioniert überall gleich genau (set cut, trim, delete, set text).",
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
            title = "6a. Texte & Emojis nachträglich verschieben und ändern (move, edit, size)",
            codeExamples = listOf(
                "texts" to "Listet alle Text-Overlays mit Nummer, Zeit, Position und Größe (emojis für Emojis).",
                "move text1 10%u" to "Schiebt text1 um 10 % nach oben (10%l runter, 10%r rechts, 10%left links; auch: move text1 up 10).",
                "move text1 50 20" to "Setzt die Mitte von text1 auf 50 % von links und 20 % von oben.",
                "move text1 top" to "Feste Plätze: top, center, bottom (ohne Nummer: move text … nimmt den ausgewählten Text).",
                "edit text1: Neuer Text" to "Ersetzt den Inhalt von text1 – lange Texte brechen automatisch um.",
                "edit text1: Neuer Text.gold.big" to "Neuer Inhalt samt Stil; nur den Stil ändern: edit text1: .gold.serif",
                "size text1 150" to "Größe in Prozent (auch size text1 big, small, huge oder size text1 +20).",
                "move emoji1 80 15" to "Alles funktioniert genauso für Emojis – oder direkt im Video: ziehen zum Verschieben, antippen zum Bearbeiten.",
            ),
        ),
        ManualSection(
            title = "7. Verfügbare Punkt-Modifier & Effekte (inkl. Rein-/Rauszoomen)",
            codeExamples = listOf(
                ".red .rot .pink .blue .blau .cyan" to "Textfarben: Rot, Pink, Blau, Cyan/Türkis.",
                ".green .gruen .yellow .gelb .gold .orange" to "Textfarben: Grün, Gelb, Gold, Orange.",
                ".sans .serif .mono .cursive .impact" to "Schriftart direkt im Befehl wechseln.",
                "zoom in" to "Zoomt animiert von der Mitte her größer (auch 0-5 zoom in 0,2 oder 0-5 zoom in 2,0).",
                "zoom out" to "Zoomt animiert wieder kleiner (auch zoom out 0,5 für Verkleinerung zur Mitte hin).",
                "0-3 zoom 1 -> 2" to "Animierter Zoom von Faktor 1.0x auf 2.0x in den ersten 3 Sekunden.",
                "zoom 0,5" to "Statischer Zoom auf 50 % (klein in der Mitte) bis 5,0 (500 %).",
                "zoom 1,8" to "Zoomt stufenlos ins Video (0.1x bis 5.0x).",
                "all set filter: cyberpunk" to "Aktiviert den Look-Filter (cyberpunk, cinematic, bw, vintage, vivid).",
                "undo" to "Macht den letzten Schritt rückgängig (redo stellt ihn wieder her).",
                "demo" to "Baut aus dem geladenen Video einen Vorführ-Clip, der jede Funktion in einem eigenen Kapitel zeigt (als Kopie 'PocketCraft Demo').",
            ),
        ),
    )
}

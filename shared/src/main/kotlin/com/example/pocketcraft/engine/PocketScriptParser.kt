package com.example.pocketcraft.engine

import com.example.pocketcraft.model.AspectRatioPreset
import com.example.pocketcraft.model.BlurConfig
import com.example.pocketcraft.model.BlurMode
import com.example.pocketcraft.model.BuiltInFont
import com.example.pocketcraft.model.CanvasOverlay
import com.example.pocketcraft.model.CutMarker
import com.example.pocketcraft.model.EasingCurve
import com.example.pocketcraft.model.OverlayAnimation
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
        val rotationDegrees: Float? = null,
        val rotationEndDegrees: Float? = null,
        val rotationXDegrees: Float? = null,
        val rotationXEndDegrees: Float? = null,
        val rotationYDegrees: Float? = null,
        val rotationYEndDegrees: Float? = null,
        val highlightColorArgb: Long? = null,
        val karaoke: Boolean? = null,
        val animation: OverlayAnimation? = null,
        val easing: EasingCurve? = null,
        val pulseBpm: Float? = null,
    )

    enum class RotationAxis { Z, X, Y }

    data class ParsedRotationSpec(
        val axis: RotationAxis,
        val startDegrees: Float,
        val endDegrees: Float?,
        val isRelative: Boolean = false,
        val isToggleFlip: Boolean = false,
        val isAbsoluteExplicit: Boolean = false,
        val easing: EasingCurve = EasingCurve.LINEAR,
    )

    internal data class ParsedZoomSpec(
        val startZoom: Float,
        val endZoom: Float? = null,
        val targetX: Float? = null, // normiert 0.0 (links) .. 1.0 (rechts), 0.5 = Mitte
        val targetY: Float? = null, // normiert 0.0 (oben)  .. 1.0 (unten),  0.5 = Mitte
        val targetLabel: String? = null,
        val easing: EasingCurve = EasingCurve.LINEAR,
    )

    /** Result of evaluating a legacy content/project condition (`if caption red`, `if filter bw`, etc.). */
    sealed class LegacyConditionMatch {
        data class Timed(val windows: List<Pair<Long, Long>>) : LegacyConditionMatch()
        data class General(val matched: Boolean) : LegacyConditionMatch()
        data class NoMatch(val message: String) : LegacyConditionMatch()
    }

    // Unified time building blocks: seconds (`10`, `24,3`), timecodes (`00:33.3`), percentages (`50%`),
    // named endpoints (`cut1`, `start`, `end`, `playhead`), plus `last 5`, `end-2..end`, and `+3`.
    private const val BASE_TIME_ATOM = """\d+(?::\d+)*(?:[.,]\d+)?(?:s|sek)?"""
    private const val TIME_ATOM = """$BASE_TIME_ATOM"""
    private const val TIME_PERCENT_ATOM = """\d+(?:[.,]\d+)?%"""
    private const val NAMED_TIME_ATOM = """(?:cut\d*|start|anfang|end|ende|playhead)"""
    private const val TIME_ENDPOINT = """(?:$BASE_TIME_ATOM|$TIME_PERCENT_ATOM|$NAMED_TIME_ATOM)"""
    private const val LAST_RANGE_TOKEN = """(?:last|letzte[rn]?)(?:\s+|_)$BASE_TIME_ATOM"""
    private const val END_MINUS_RANGE_TOKEN = """(?:end|ende)\s*-\s*$BASE_TIME_ATOM(?:\s*(?:\.\.|-)\s*(?:end|ende))?"""
    private const val PLUS_DURATION_TOKEN = """\+$BASE_TIME_ATOM"""
    private const val EXPLICIT_RANGE_TOKEN =
        """(?:all|alles|\*|$LAST_RANGE_TOKEN|$END_MINUS_RANGE_TOKEN|$PLUS_DURATION_TOKEN|$TIME_ENDPOINT\s*-\s*$TIME_ENDPOINT)"""
    private const val TIME_RANGE_TOKEN = """(?:$EXPLICIT_RANGE_TOKEN|$BASE_TIME_ATOM)"""

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

    // Regex für `delete 10-20`, `delete cut1-cut2`, `delete 00:10.5-00:20.3` (Mittelstück herausschneiden)
    private val deleteRangeRegex = Regex(
        pattern = """^(?:delete|del|remove|lösche|loesche)\s+($TIME_ENDPOINT)\s*-\s*($TIME_ENDPOINT)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für `<Zeitbereich> [set] <ziel>: <inhalt>` (auch leeres Ziel nach ':', damit Syntaxfehler klar gemeldet werden)
    private val setStatementRegex = Regex(
        pattern = """^($TIME_RANGE_TOKEN)?\s*(?:set\s+)?(caption|sub|untertitel|text|title|titel|emoji|sticker|zoom|blur|filter|fx|speed|crop|cut|trim|rotate|rot|spin|turn|drehen|flip|mirror)\s*:\s*(.*)$""",
        option = RegexOption.IGNORE_CASE,
    )

    private val unknownSetRegex = Regex(
        pattern = """^($TIME_RANGE_TOKEN)?\s*set\s+([a-zA-ZäöüÄÖÜß]+)\s*:\s*(.*)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Verb-zuerst-Syntax für Inhalte:
    // `caption 6,9-8,9: Text.red`, `caption 6,9 - 8,9 set text: Text.red`, `text 2-5 Hallo.gold`, `caption 6,9-8,9` (Fehler!)
    private val verbExplicitRangeColonRegex = Regex(
        pattern = """^(caption|sub|untertitel|text|title|titel|emoji|sticker)\s+(?:(?:at|@)\s+)?($EXPLICIT_RANGE_TOKEN)(?:\s+set\s+(?:caption|sub|untertitel|text|title|titel|emoji|sticker))?\s*:\s*(.*)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val verbSingleTimeColonRegex = Regex(
        pattern = """^(caption|sub|untertitel|text|title|titel|emoji|sticker)\s+(?:(?:at|@)\s+)?($TIME_ATOM)(?:\s+set\s+(?:caption|sub|untertitel|text|title|titel|emoji|sticker))?(?:\s+:|:(?!\d))\s*(.*)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val verbExplicitRangeSpaceRegex = Regex(
        pattern = """^(caption|sub|untertitel|text|title|titel|emoji|sticker)\s+(?:(?:at|@)\s+)?($EXPLICIT_RANGE_TOKEN)(?:\s+(.*))?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val rangeContentSpaceRegex = Regex(
        pattern = """^($EXPLICIT_RANGE_TOKEN)\s+(caption|sub|untertitel|text|title|titel|emoji|sticker)(?:\s+(.*))?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val verbFirstTimedModifierRegex = Regex(
        pattern = """^(crop|move|pan|filter|fx|blur|speed)\s+(?:(?:at|@)\s+)?($EXPLICIT_RANGE_TOKEN)(?:\s*:\s*|\s+)(.+)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val timedRotateRegex = Regex(
        pattern = """^(?:($TIME_RANGE_TOKEN)\s+)?(?:set\s+)?(rotate|rot|spin|turn|drehen|flip|mirror)(?:(?:\s*:\s*|\s+)(.*))?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val verbFirstTimedRotateRegex = Regex(
        pattern = """^(rotate|rot|spin|turn|drehen|flip|mirror)\s+(?:(?:at|@)\s+)?($EXPLICIT_RANGE_TOKEN)(?:\s+(.*))?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val timedMotionEffectRegex = Regex(
        pattern = """^(?:($TIME_RANGE_TOKEN)\s+)?(?:set\s+)?(fade|flash|shake|kenburns|pulse|puls)(?:(?:\s*:\s*|\s+)(.*))?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val verbFirstMotionEffectRegex = Regex(
        pattern = """^(fade|flash|shake|kenburns|pulse|puls)\s+(?:(?:at|@)\s+)?($EXPLICIT_RANGE_TOKEN)(?:\s+(.*))?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val bpmCommandRegex = Regex("""^bpm\s+(\d+(?:[.,]\d+)?)$""", RegexOption.IGNORE_CASE)
    private val audioTimedRegex = Regex(
        pattern = """^(?:($TIME_RANGE_TOKEN)\s+)?(mute|unmute|stumm|volume|lautstärke|lautstaerke|audio)(?:\s+(.*))?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val verbFirstAudioRegex = Regex(
        pattern = """^(mute|stumm|volume|lautstärke|lautstaerke|audio)\s+(?:(?:at|@)\s+)?($EXPLICIT_RANGE_TOKEN)(?:\s+(.*))?$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Regex für direkte oder zeitgebundene Zoom-Befehle (`zoom in`, `zoom out`, `0-4 zoom 1 -> 2`, `zoom 0-5,9 0 -> 1`, `2-6 zoom 1,8`)
    private val standaloneZoomRegex = Regex(
        pattern = """^(?:($TIME_RANGE_TOKEN)\s+)?(?:set\s+)?(?:(zoom|z)(?:\s*:\s*|\s+)(.+)|(reinzoomen|zoomin|rauszoomen|zoomout)(?:(?:\s*:\s*|\s+)(.*))?)$""",
        option = RegexOption.IGNORE_CASE,
    )

    // Token-Regexes für Format-Klammern `(8:15)`, Richtungs-Zuschnitte (`u20%`, `crop bottom 20%`, `crop top 20%`)
    // sowie prozentuale Verschiebungen (`move down 10%`, `move up 10%`, `move 10%l`, `move 10%u`).
    private val explicitTimeRangeTokenRegex = Regex(
        pattern = """^(all|alles|\*|$LAST_RANGE_TOKEN|$END_MINUS_RANGE_TOKEN|$PLUS_DURATION_TOKEN|$TIME_ENDPOINT-$TIME_ENDPOINT)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val timeTokenRegex = Regex(
        pattern = """^(all|alles|\*|$LAST_RANGE_TOKEN|$END_MINUS_RANGE_TOKEN|$PLUS_DURATION_TOKEN|$TIME_ENDPOINT-$TIME_ENDPOINT|$BASE_TIME_ATOM)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val ratioTokenRegex = Regex(
        pattern = """^\((\d+(?:[.,]\d+)?)[:/x](\d+(?:[.,]\d+)?)\)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val edgeCropTokenRegex = Regex(
        pattern = """^(u|unten|bottom|bot|o|oben|top|r|rechts|right|l|links|left)(\d+(?:[.,]\d+)?)%?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val moveSuffixTokenRegex = Regex(
        pattern = """^(\d+(?:[.,]\d+)?)%?(lower|low|down|runter|unten|d|l|up|upper|oben|hoch|u|o|right|rechts|r|left|links)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val movePrefixTokenRegex = Regex(
        pattern = """^(lower|low|down|runter|unten|d|up|upper|oben|hoch|right|rechts|left|links|u|o|r|l)(\d+(?:[.,]\d+)?)%?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val plainPercentTokenRegex = Regex("""^(\d+(?:[.,]\d+)?)%?$""")

    private data class FormatAndCropSegment(
        var timeToken: String = "all",
        var ratioW: Float? = null,
        var ratioH: Float? = null,
        var cutBottomPct: Float? = null, // u20% / bottom 20% -> untere 20% wegschneiden
        var cutTopPct: Float? = null,    // o20% / top 20% -> obere 20% wegschneiden
        var cutRightPct: Float? = null,  // r20% / right 20% -> rechte 20% wegschneiden
        var cutLeftPct: Float? = null,   // l20% / left 20% -> linke 20% wegschneiden
        var moveXPct: Float? = null,     // +10% = rechts, -10% = links
        var moveYPct: Float? = null,     // +10% = runter, -10% = hoch
        var zoomStart: Float? = null,
        var zoomEnd: Float? = null,
        var zoomTargetX: Float? = null,
        var zoomTargetY: Float? = null,
        var zoomTargetLabel: String? = null,
        var rotZStart: Float? = null,
        var rotZEnd: Float? = null,
        var rotXStart: Float? = null,
        var rotXEnd: Float? = null,
        var rotYStart: Float? = null,
        var rotYEnd: Float? = null,
        var easing: EasingCurve = EasingCurve.LINEAR,
    ) {
        val hasModifiers: Boolean
            get() = ratioW != null ||
                cutBottomPct != null ||
                cutTopPct != null ||
                cutRightPct != null ||
                cutLeftPct != null ||
                moveXPct != null ||
                moveYPct != null ||
                zoomStart != null ||
                rotZStart != null ||
                rotXStart != null ||
                rotYStart != null
    }

    /**
     * Prüft, ob die Eingabe ein PocketScript-Programm ist, führt es auf [engine] aus
     * und gibt `null` zurück, falls es stattdessen ein klassischer Einzelbefehl (`undo`, `zoom 1.5` etc.) ist.
     */
    fun tryExecuteScript(rawInput: String, engine: ProjectEngine): String? {
        var input = rawInput.trim()
        if (input.isEmpty()) return null

        // Unterstützt auch einzeilige nummerierte Aufzählungen wie:
        // `1. all u20% 2. all move 10%l 3. set cut 00:48 4. delete after cut1`
        if (!input.contains('\n') && Regex("""^\d+\.\s+[a-zA-Z(]""").containsMatchIn(input)) {
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

        // 0d. Prüfen auf `delete 10-20` / `delete cut1-cut2` (Bereich mitten aus dem Video herausschneiden)
        val delRangeMatch = deleteRangeRegex.matchEntire(input)
        if (delRangeMatch != null) {
            val startMs = resolveCutOrTime(delRangeMatch.groupValues[1], engine)
                ?: return "⚠ Startpunkt '${delRangeMatch.groupValues[1]}' nicht gefunden."
            val endMs = resolveCutOrTime(delRangeMatch.groupValues[2], engine)
                ?: return "⚠ Endpunkt '${delRangeMatch.groupValues[2]}' nicht gefunden."
            if (endMs <= startMs) {
                return "⚠ Ungültiger Zeitbereich '${delRangeMatch.groupValues[1]}-${delRangeMatch.groupValues[2]}': Endzeit muss nach der Startzeit liegen."
            }
            return executeRemoveRange(startMs, endMs, engine)
        }

        // Overlay-Befehle wie `move text1 10%u`, `edit text1: ...`, `rotate text1 15`, `spin emoji1 axis`, `pulse emoji1`
        // werden direkt an OverlayCommands im ProjectEngine durchgereicht.
        val inputTokens = input.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (inputTokens.size >= 2 && OverlayCommands.startsWithReference(inputTokens.drop(1)) &&
            inputTokens.first().lowercase() in setOf("move", "pos", "position", "verschiebe", "schiebe", "edit", "change", "size", "scale", "rotate", "rot", "spin", "turn", "drehen", "flip", "mirror", "pulse", "puls")
        ) {
            return null
        }

        // 0e. `bpm <20..300>`
        bpmCommandRegex.matchEntire(input)?.let { m ->
            val rawBpm = m.groupValues[1].replace(",", ".").toFloatOrNull()
                ?: return "⚠ Syntax: bpm <20..300>, z.B. 'bpm 120'"
            val clamped = rawBpm.coerceIn(20f, 300f)
            engine.commitChange("BPM auf ${clamped.roundToInt()} gesetzt") { it.copy(bpm = clamped) }
            return "Tempo auf ${clamped.roundToInt()} BPM gesetzt."
        }

        // 0f. Audio-Befehle (`mute`, `unmute`, `2-5 mute`, `volume 150%`, `0-2 audio fade in`, `end-3..end audio fade out`)
        verbFirstAudioRegex.matchEntire(input)?.let { m ->
            val verb = m.groupValues[1].lowercase()
            val rangeTok = normalizeRangeToken(m.groupValues[2])
            val args = m.groupValues[3].trim()
            return executeAudioCommand(rangeTok, verb, args, engine)
        }
        audioTimedRegex.matchEntire(input)?.let { m ->
            val rangeTok = normalizeRangeToken(m.groupValues[1].ifBlank { "all" })
            val verb = m.groupValues[2].lowercase()
            val args = m.groupValues[3].trim()
            return executeAudioCommand(rangeTok, verb, args, engine)
        }

        // 0g. Video-Motion- & Licht-Effekte (`fade in`, `fade out`, `flash`, `shake`, `kenburns`, `pulse`)
        verbFirstMotionEffectRegex.matchEntire(input)?.let { m ->
            val verb = m.groupValues[1].lowercase()
            val rangeTok = normalizeRangeToken(m.groupValues[2])
            val args = m.groupValues[3].trim()
            return executeMotionEffect(rangeTok, verb, args, engine)
        }
        timedMotionEffectRegex.matchEntire(input)?.let { m ->
            val rawRange = m.groupValues[1].trim()
            val verb = m.groupValues[2].lowercase()
            val args = m.groupValues[3].trim()
            // `flash` without an explicit range pulses briefly at the current playhead
            val rangeTok = if (rawRange.isEmpty() && verb == "flash") {
                val s = engine.playheadMs.coerceAtLeast(0L)
                "${formatSecNumber(s)}-${formatSecNumber(s + 350L)}"
            } else {
                normalizeRangeToken(rawRange.ifBlank { "all" })
            }
            return executeMotionEffect(rangeTok, verb, args, engine)
        }

        // 1. Verb-zuerst-Syntax für Inhalte (`caption 6,9-8,9: Text.red`, `caption 6,9 - 8,9 set text: ...`, `text 2-5 Hallo`)
        //    und `<Zeitbereich> [set] <Ziel>: <Inhalt>`
        tryParseContentOrSetStatement(input, engine)?.let { return it }

        // 1b. Zeitbereich + `filter` / `blur` / `speed` (vorne oder nach dem Verb: `2-6 filter bw`, `filter 2-6 bw`)
        verbFirstTimedModifierRegex.matchEntire(input)?.let { m ->
            val verb = m.groupValues[1].lowercase()
            val rangeTok = normalizeRangeToken(m.groupValues[2])
            val rest = m.groupValues[3].trim()
            if (verb in listOf("crop", "move", "pan")) {
                tryParseAndExecuteFormatAndCrop("$rangeTok $verb $rest", engine)?.let { return it }
            } else {
                return executeSetStatement(rangeTok, verb, rest, engine)
            }
        }
        Regex("""^($TIME_RANGE_TOKEN)\s+(filter|fx|blur|speed)\s+(.+)$""", RegexOption.IGNORE_CASE).matchEntire(input)?.let { m ->
            val rangeTok = normalizeRangeToken(m.groupValues[1])
            val verb = m.groupValues[2].lowercase()
            val rest = m.groupValues[3].trim()
            return executeSetStatement(rangeTok, verb, rest, engine)
        }

        // 2. Kombinierte Format-/Crop-/Move-/Zoom-/Rotate-Ausdrücke (`0-4 crop 20% zoom 0 -> 1 rotate y 0 -> 360`,
        //    `10-24,3 (8:15) 9 (16:18)`, `all u20%`, `0-4 crop bottom 20%`, `0-4 move down 10%`)
        tryParseAndExecuteFormatAndCrop(input, engine)?.let { return it }

        // 2b. Zeitgesteuerte oder animierte Zoom-Befehle (`zoom in`, `zoom 0-5,9 0 -> 1`, `0-4 zoom 1 -> 2`, `2-6 zoom 1,8`)
        standaloneZoomRegex.matchEntire(input)?.let { zoomMatch ->
            var rangeTok = zoomMatch.groupValues[1].ifBlank { "all" }
            var payload = if (zoomMatch.groupValues[2].isNotEmpty()) {
                zoomMatch.groupValues[3].trim()
            } else {
                val verb = zoomMatch.groupValues[4].lowercase()
                val dir = if (verb.startsWith("rein") || verb.startsWith("zoomin")) "in" else "out"
                val arg = zoomMatch.groupValues[5].trim()
                if (arg.isEmpty()) dir else "$dir $arg"
            }
            if (rangeTok == "all") {
                val trailingRange = Regex("""^(?:(?:at|@)\s+)?($EXPLICIT_RANGE_TOKEN)\s+(.+)$""", RegexOption.IGNORE_CASE).matchEntire(payload)
                    ?: Regex("""^(?:at|@)\s+($TIME_ATOM)\s+(.+)$""", RegexOption.IGNORE_CASE).matchEntire(payload)
                if (trailingRange != null) {
                    rangeTok = trailingRange.groupValues[1]
                    payload = trailingRange.groupValues[2].trim()
                }
            }
            executeZoomStatement(rangeTok, payload, engine)?.let { return it }
            // Relative Zooms (`zoom +0.2`, `zoom -50%`) dürfen an ProjectEngine weitergehen
            val isRelativeValid = rangeTok == "all" && (payload.startsWith("+") || payload.startsWith("-")) &&
                parseRelativeOrAbsoluteZoom(payload, 1f) != null
            if (!isRelativeValid) {
                return "⚠ Ungültiger Zoom '$payload' (erlaubt: 0,1 bis 5,0, 10% bis 500%, 'in', 'out', '0 -> 1' sowie optional Zielpunkt 'at 70% 40%' / 'at top-right')."
            }
        }

        // 2c. 2D- und 3D-Achsen-Drehung (`rotate 90`, `rotate y 0 -> 360`, `0-4 spin axis`, `spin 0-4 x 2x`, `2-6 flip`)
        verbFirstTimedRotateRegex.matchEntire(input)?.let { m ->
            val verb = m.groupValues[1].lowercase()
            val rangeTok = normalizeRangeToken(m.groupValues[2])
            val args = m.groupValues[3].trim()
            if (verb in listOf("rotate", "rot", "drehen") && args.isEmpty()) {
                return "⚠ Syntaxfehler: Nach '$verb $rangeTok' fehlt der Winkel (z.B. '$verb $rangeTok y 0 -> 360')."
            }
            return executeRotateStatement(rangeTok, verb, args, engine)
        }
        timedRotateRegex.matchEntire(input)?.let { m ->
            val rangeTok = m.groupValues[1].ifBlank { "all" }
            val verb = m.groupValues[2].lowercase()
            val args = m.groupValues[3].trim()
            return executeRotateStatement(rangeTok, verb, args, engine)
        }

        // 3. Prüfen auf direkten Zeitbereichs-Trim wie `10-24,3` oder `00:10.5-00:33.3` allein
        val pureRangeRegex = Regex("""^($TIME_ATOM)\s*-\s*($TIME_ATOM)$""", RegexOption.IGNORE_CASE)
        val pureMatch = pureRangeRegex.matchEntire(input)
        if (pureMatch != null) {
            val startMs = parseSecondsToMs(pureMatch.groupValues[1])
            val endMs = parseSecondsToMs(pureMatch.groupValues[2])
            if (endMs <= startMs) {
                return "⚠ Ungültiger Zeitbereich '${pureMatch.groupValues[1]}-${pureMatch.groupValues[2]}': Endzeit muss nach der Startzeit liegen."
            }
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

        return null
    }

    private fun normalizeRangeToken(token: String): String =
        token.trim().replace(Regex("""\s*-\s*"""), "-")

    private fun stripRedundantSetPrefix(payload: String): String {
        val m = Regex("""^(?:set\s+)?(?:caption|sub|untertitel|text|title|titel|emoji|sticker)\s*:\s*(.*)$""", RegexOption.IGNORE_CASE)
            .matchEntire(payload.trim())
        return m?.groupValues?.get(1)?.trim() ?: payload.trim()
    }

    private fun tryParseContentOrSetStatement(input: String, engine: ProjectEngine): String? {
        setStatementRegex.matchEntire(input)?.let { m ->
            val rangeToken = normalizeRangeToken(m.groupValues[1].ifBlank { "all" })
            val targetKind = m.groupValues[2].lowercase()
            val rawPayload = m.groupValues[3].trim()
            return executeSetStatement(rangeToken, targetKind, rawPayload, engine)
        }
        unknownSetRegex.matchEntire(input)?.let { m ->
            val target = m.groupValues[2]
            return "⚠ Unbekanntes Ziel '$target'. Erlaubt: caption, text, emoji, zoom, rotate, spin, flip, crop, move, filter, blur, speed, cut, trim."
        }
        verbExplicitRangeColonRegex.matchEntire(input)?.let { m ->
            val targetKind = m.groupValues[1].lowercase()
            val rangeToken = normalizeRangeToken(m.groupValues[2])
            val payload = stripRedundantSetPrefix(m.groupValues[3])
            if (payload.isEmpty()) {
                return "⚠ Syntaxfehler: Nach '$targetKind $rangeToken:' fehlt der Inhalt."
            }
            return executeSetStatement(rangeToken, targetKind, payload, engine)
        }
        verbSingleTimeColonRegex.matchEntire(input)?.let { m ->
            val targetKind = m.groupValues[1].lowercase()
            val rangeToken = normalizeRangeToken(m.groupValues[2])
            val payload = stripRedundantSetPrefix(m.groupValues[3])
            if (payload.isEmpty()) {
                return "⚠ Syntaxfehler: Nach '$targetKind $rangeToken:' fehlt der Inhalt."
            }
            return executeSetStatement(rangeToken, targetKind, payload, engine)
        }
        verbExplicitRangeSpaceRegex.matchEntire(input)?.let { m ->
            val targetKind = m.groupValues[1].lowercase()
            val rangeToken = normalizeRangeToken(m.groupValues[2])
            val payload = stripRedundantSetPrefix(m.groupValues[3])
            if (payload.isEmpty()) {
                return "⚠ Syntaxfehler: Nach dem Zeitbereich '$rangeToken' fehlt der Inhalt (z.B. '$rangeToken $targetKind: Dein Text')."
            }
            return executeSetStatement(rangeToken, targetKind, payload, engine)
        }
        rangeContentSpaceRegex.matchEntire(input)?.let { m ->
            val rangeToken = normalizeRangeToken(m.groupValues[1])
            val targetKind = m.groupValues[2].lowercase()
            val payload = stripRedundantSetPrefix(m.groupValues[3])
            if (payload.isEmpty()) {
                return "⚠ Syntaxfehler: Nach '$rangeToken $targetKind' fehlt der Inhalt (z.B. '$rangeToken $targetKind: Dein Text')."
            }
            return executeSetStatement(rangeToken, targetKind, payload, engine)
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
    /**
     * Evaluates a legacy content/project condition (`caption red`, `text cyan`, `filter bw`, `blur`, `(9:16)`,
     * `0-12 step 4`) without executing anything. Used by both one-line rules and multi-branch `if/elif/else`.
     */
    fun evaluateLegacyCondition(
        conditionRaw: String,
        engine: ProjectEngine,
    ): LegacyConditionMatch {
        val cond = conditionRaw.trim()
        val condLower = cond.lowercase()

        // A) Time-interval step loop (`while 0-12 step 4`, `while 0-20 step 0,5`)
        val stepMatch = stepLoopRegex.matchEntire(cond)
        if (stepMatch != null) {
            val rangeTok = stepMatch.groupValues[1].ifBlank { "all" }
            val (range, rangeError) = parseTimeRangeChecked(rangeTok, engine)
            if (range == null) return LegacyConditionMatch.NoMatch(rangeError ?: "Ungültiger Zeitbereich '$rangeTok'.")
            val stepMs = parseSecondsToMs(stepMatch.groupValues[2]).coerceAtLeast(100L)
            var t = range.startMs
            val windows = mutableListOf<Pair<Long, Long>>()
            while (t < range.endMs && windows.size < 500) {
                windows.add(t to (t + stepMs).coerceAtMost(range.endMs))
                t += stepMs
            }
            return LegacyConditionMatch.Timed(windows)
        }

        // B) `if/while caption <qualifier>`
        val captionCondRegex = Regex(
            pattern = """^(?:caption|sub|untertitel)(?:\s+(.+))?$""",
            option = RegexOption.IGNORE_CASE,
        )
        val capMatch = captionCondRegex.matchEntire(cond)
        if (capMatch != null) {
            val rawQual = capMatch.groupValues[1].trim()
            val isQuoted = rawQual.startsWith("\"") && rawQual.endsWith("\"") && rawQual.length >= 2
            val qualifier = rawQual.removePrefix(".").removeSurrounding("\"").lowercase()
            val targetColor = if (isQuoted) null else (colorMap[qualifier] ?: parseHexColor(qualifier))

            var matchedSubs = engine.project.subtitles.filter { sub ->
                when {
                    qualifier.isEmpty() -> true
                    targetColor != null -> sub.textColorArgb == targetColor || sub.highlightColorArgb == targetColor
                    qualifier in listOf("big", "gross", "xl", "large", "huge", "xxl") -> sub.fontSizeSp >= 24f
                    qualifier in listOf("small", "klein", "sm") -> sub.fontSizeSp <= 16f
                    else -> sub.text.lowercase().contains(qualifier) || sub.fontId.lowercase().contains(qualifier)
                }
            }

            // Only recolor the unmodified default demo subtitle so starter examples work out of the box
            val hasDemoSubs = engine.project.subtitles.isNotEmpty() &&
                engine.project.subtitles.all { sub ->
                    com.example.pocketcraft.model.defaultDemoSubtitles().any { it.id == sub.id }
                }
            if (matchedSubs.isEmpty() && targetColor != null && hasDemoSubs) {
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
                return LegacyConditionMatch.NoMatch("Kein passender Untertitel gefunden ($cond).")
            }
            return LegacyConditionMatch.Timed(matchedSubs.map { it.startMs to it.endMs }.distinct())
        }

        // C) `if/while text <qualifier>` or `if/while emoji <qualifier>`
        val overlayCondRegex = Regex(
            pattern = """^(text|title|titel|emoji|sticker)(?:\s+(.+))?$""",
            option = RegexOption.IGNORE_CASE,
        )
        val ovMatch = overlayCondRegex.matchEntire(cond)
        if (ovMatch != null) {
            val kindToken = ovMatch.groupValues[1].lowercase()
            val rawQual = ovMatch.groupValues[2].trim()
            val isQuoted = rawQual.startsWith("\"") && rawQual.endsWith("\"") && rawQual.length >= 2
            val qualifier = rawQual.removePrefix(".").removeSurrounding("\"").lowercase()
            val wantKind = if (kindToken.startsWith("e") || kindToken.startsWith("s")) OverlayKind.EMOJI else OverlayKind.TEXT
            val targetColor = if (isQuoted) null else (colorMap[qualifier] ?: parseHexColor(qualifier))

            val matchedOverlays = engine.project.overlays.filter { ov ->
                ov.kind == wantKind && (
                    qualifier.isEmpty() ||
                        (targetColor != null && ov.colorArgb == targetColor) ||
                        (targetColor == null && (ov.content.lowercase().contains(qualifier) || ov.fontId.lowercase().contains(qualifier)))
                    )
            }
            if (matchedOverlays.isEmpty()) {
                return LegacyConditionMatch.NoMatch("Kein passendes Overlay gefunden ($cond).")
            }
            return LegacyConditionMatch.Timed(matchedOverlays.map { it.startMs to it.endMs }.distinct())
        }

        // D) Filter, Blur, and Aspect Ratio conditions (including timed spans!)
        if (condLower.startsWith("filter") || condLower.startsWith("fx")) {
            val q = condLower.removePrefix("filter").removePrefix("fx").trim()
            val globalMatch = if (q.isEmpty()) {
                engine.project.filter.preset != VideoFilterPreset.NONE
            } else {
                engine.project.filter.preset.id.lowercase().contains(q) ||
                    engine.project.filter.preset.displayName.lowercase().contains(q)
            }
            if (globalMatch) return LegacyConditionMatch.General(true)
            val timedMatches = engine.project.timedFormatSpans.filter { span ->
                val p = span.filterPreset ?: return@filter false
                if (q.isEmpty()) p != VideoFilterPreset.NONE
                else p.id.lowercase().contains(q) || p.displayName.lowercase().contains(q)
            }
            return if (timedMatches.isNotEmpty()) {
                LegacyConditionMatch.Timed(timedMatches.map { it.startMs to it.endMs }.distinct())
            } else {
                LegacyConditionMatch.General(false)
            }
        }

        if (condLower.startsWith("blur")) {
            val q = condLower.removePrefix("blur").trim()
            val globalMatch = engine.project.blur.mode != BlurMode.OFF &&
                (q.isEmpty() || engine.project.blur.mode.id == q || engine.project.blur.mode.label.lowercase().contains(q))
            if (globalMatch) return LegacyConditionMatch.General(true)
            val timedMatches = engine.project.timedFormatSpans.filter { span ->
                val m = span.blurMode ?: return@filter false
                m != BlurMode.OFF && (q.isEmpty() || m.id == q || m.label.lowercase().contains(q))
            }
            return if (timedMatches.isNotEmpty()) {
                LegacyConditionMatch.Timed(timedMatches.map { it.startMs to it.endMs }.distinct())
            } else {
                LegacyConditionMatch.General(false)
            }
        }

        if (ratioTokenRegex.matches(cond.replace(" ", ""))) {
            val m = ratioTokenRegex.matchEntire(cond.replace(" ", ""))!!
            val w = m.groupValues[1].replace(",", ".").toFloatOrNull() ?: 9f
            val h = m.groupValues[2].replace(",", ".").toFloatOrNull() ?: 16f
            val targetRatio = w / h.coerceAtLeast(0.1f)
            return LegacyConditionMatch.General(kotlin.math.abs(engine.currentAspectFloat() - targetRatio) < 0.08f)
        }

        return LegacyConditionMatch.General(false)
    }

    private fun executeIfOrWhileStatement(
        keyword: String,
        conditionRaw: String,
        actionBody: String,
        engine: ProjectEngine,
    ): String {
        val cond = conditionRaw.trim()
        val labelPrefix = if (keyword.startsWith("w") && keyword != "wenn") "While" else "If"
        val isStep = stepLoopRegex.matches(cond)

        return when (val eval = evaluateLegacyCondition(cond, engine)) {
            is LegacyConditionMatch.Timed -> {
                val windows = eval.windows
                if (isStep) {
                    val stepMatch = stepLoopRegex.matchEntire(cond)!!
                    val stepMs = parseSecondsToMs(stepMatch.groupValues[2]).coerceAtLeast(100L)
                    val result = if (windows.isEmpty()) actionBody else executeConditionActions(windows, actionBody, engine)
                    val prefix = if (result.contains("⚠")) "⚠ " else ""
                    "$prefix$labelPrefix-Schleife (${windows.size} Schritte à ${ProjectEngine.formatSec(stepMs)}): $result"
                } else {
                    val execResult = executeConditionActions(windows, actionBody, engine)
                    engine.clampPlayheadTo(windows.first().first)
                    val prefix = if (execResult.contains("⚠")) "⚠ " else ""
                    "$prefix$labelPrefix ($cond → ${windows.size} Treffer): $execResult"
                }
            }
            is LegacyConditionMatch.General -> {
                if (eval.matched) {
                    val res = splitConditionActions(actionBody).joinToString(" · ") { action ->
                        tryParseAndExecuteFormatAndCrop("all $action", engine) ?: engine.executeCommand(action)
                    }
                    "$labelPrefix ($cond): $res"
                } else {
                    "$labelPrefix ($cond): Bedingung aktuell nicht erfüllt."
                }
            }
            is LegacyConditionMatch.NoMatch -> "⚠ $labelPrefix ($cond): ${eval.message}"
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
        if (engine.project.videoUri != null && cutTimeMs > engine.project.sourceDurationMs) {
            return "⚠ Schnittmarke bei ${ProjectEngine.formatTimecode(cutTimeMs)} liegt hinter dem Videoende (${ProjectEngine.formatTimecode(engine.project.sourceDurationMs)})."
        }
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
        engine.clampPlayheadTo(cutTimeMs)
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
                val found = if (numPart.isEmpty()) {
                    engine.project.cutMarkers.lastOrNull()
                } else {
                    engine.project.cutMarkers.firstOrNull { it.id.equals("cut$numPart", ignoreCase = true) }
                }
                if (found == null) {
                    val available = engine.project.cutMarkers.joinToString(", ") { it.id }.ifEmpty { "keine" }
                    return "⚠ Cut-Marker '$targetRaw' nicht gefunden (vorhanden: $available)."
                }
                found
            }
            else -> null
        }

        val cutTimeMs = resolvedMarker?.timeMs ?: parseSecondsToMs(targetRaw)
        if (cutTimeMs <= 0L && resolvedMarker == null) {
            return "⚠ Kein passender Cut-Marker ('$targetRaw') gefunden. Setze zuerst z.B. 'set cut 00:48'."
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

    /** Resolves `cut1`, `cut`, `start`, `end`, `playhead`, percentage (`50%`), or time (`00:33.3`, `12,5`). */
    internal fun resolveCutOrTime(token: String, engine: ProjectEngine): Long? {
        val clean = token.trim().lowercase()
        return when {
            clean == "start" || clean == "anfang" -> engine.project.trimStartMs
            clean == "end" || clean == "ende" -> engine.project.trimEndMs
            clean == "playhead" -> engine.playheadMs
            clean.startsWith("cut") -> {
                val num = clean.removePrefix("cut")
                val marker = if (num.isEmpty()) {
                    engine.project.cutMarkers.lastOrNull()
                } else {
                    engine.project.cutMarkers.firstOrNull { it.id.equals("cut$num", ignoreCase = true) }
                }
                marker?.timeMs
            }
            clean.endsWith("%") -> {
                val pct = clean.removeSuffix("%").replace(",", ".").toDoubleOrNull() ?: return null
                if (!pct.isFinite() || pct < 0.0 || pct > 100.0) return null
                ((pct / 100.0) * engine.project.sourceDurationMs).roundToLong().coerceIn(0L, engine.project.sourceDurationMs)
            }
            Regex("""^$BASE_TIME_ATOM$""", RegexOption.IGNORE_CASE).matches(clean) -> parseSecondsToMs(clean)
            else -> null
        }
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
        "at", "auf", "on", "nach", "@", "focus", "fokus", "target", "punkt",
        "crop", "rand", "top", "oben", "bottom", "bot", "unten", "left", "links", "right", "rechts", "all",
        "up", "hoch", "down", "runter", "lower", "low", "upper",
        "rotate", "rot", "spin", "turn", "drehen", "flip", "mirror",
        "x", "y", "z", "axis", "achse", "horizontal", "vertical", "vertikal",
    )

    private val zoomTargetAnchorMap: Map<String, Triple<Float, Float, String>> = mapOf(
        "center" to Triple(0.50f, 0.50f, "Mitte"),
        "mitte" to Triple(0.50f, 0.50f, "Mitte"),
        "mid" to Triple(0.50f, 0.50f, "Mitte"),
        "zentrum" to Triple(0.50f, 0.50f, "Mitte"),
        "top" to Triple(0.50f, 0.20f, "oben"),
        "oben" to Triple(0.50f, 0.20f, "oben"),
        "up" to Triple(0.50f, 0.20f, "oben"),
        "hoch" to Triple(0.50f, 0.20f, "oben"),
        "bottom" to Triple(0.50f, 0.80f, "unten"),
        "unten" to Triple(0.50f, 0.80f, "unten"),
        "down" to Triple(0.50f, 0.80f, "unten"),
        "runter" to Triple(0.50f, 0.80f, "unten"),
        "left" to Triple(0.20f, 0.50f, "links"),
        "links" to Triple(0.20f, 0.50f, "links"),
        "right" to Triple(0.80f, 0.50f, "rechts"),
        "rechts" to Triple(0.80f, 0.50f, "rechts"),
        "top-left" to Triple(0.20f, 0.20f, "oben-links"),
        "topleft" to Triple(0.20f, 0.20f, "oben-links"),
        "top_left" to Triple(0.20f, 0.20f, "oben-links"),
        "oben-links" to Triple(0.20f, 0.20f, "oben-links"),
        "obenlinks" to Triple(0.20f, 0.20f, "oben-links"),
        "links-oben" to Triple(0.20f, 0.20f, "oben-links"),
        "top-right" to Triple(0.80f, 0.20f, "oben-rechts"),
        "topright" to Triple(0.80f, 0.20f, "oben-rechts"),
        "top_right" to Triple(0.80f, 0.20f, "oben-rechts"),
        "oben-rechts" to Triple(0.80f, 0.20f, "oben-rechts"),
        "obenrechts" to Triple(0.80f, 0.20f, "oben-rechts"),
        "rechts-oben" to Triple(0.80f, 0.20f, "oben-rechts"),
        "bottom-left" to Triple(0.20f, 0.80f, "unten-links"),
        "bottomleft" to Triple(0.20f, 0.80f, "unten-links"),
        "bottom_left" to Triple(0.20f, 0.80f, "unten-links"),
        "unten-links" to Triple(0.20f, 0.80f, "unten-links"),
        "untenlinks" to Triple(0.20f, 0.80f, "unten-links"),
        "links-unten" to Triple(0.20f, 0.80f, "unten-links"),
        "bottom-right" to Triple(0.80f, 0.80f, "unten-rechts"),
        "bottomright" to Triple(0.80f, 0.80f, "unten-rechts"),
        "bottom_right" to Triple(0.80f, 0.80f, "unten-rechts"),
        "unten-rechts" to Triple(0.80f, 0.80f, "unten-rechts"),
        "untenrechts" to Triple(0.80f, 0.80f, "unten-rechts"),
        "rechts-unten" to Triple(0.80f, 0.80f, "unten-rechts"),
    )

    private val zoomTargetPrepositions = setOf(
        "at", "auf", "on", "nach", "@", "focus", "fokus", "target", "punkt",
    )

    /**
     * Berechnet aus Zoom-Faktor und normiertem Zielpunkt ([targetX], [targetY] in 0..1)
     * die benötigte Bildverschiebung (`panX`, `panY`), damit der Zoom ohne schwarze Ränder
     * synchron auf genau diesen Punkt zufährt.
     */
    internal fun zoomTargetPan(zoom: Float, targetX: Float, targetY: Float): Pair<Float, Float> {
        val tx = targetX.coerceIn(0f, 1f)
        val ty = targetY.coerceIn(0f, 1f)
        return if (zoom >= 1.0f) {
            val factor = zoom - 1.0f
            (((0.5f - tx) * factor).coerceIn(-0.95f, 0.95f)) to
                (((0.5f - ty) * factor).coerceIn(-0.95f, 0.95f))
        } else {
            val factor = 1.0f - zoom
            (((tx - 0.5f) * factor).coerceIn(-0.95f, 0.95f)) to
                (((ty - 0.5f) * factor).coerceIn(-0.95f, 0.95f))
        }
    }

    /**
     * Parst einen Zoom-Zielpunkt aus Tokens:
     * - benannte Punkte (`top`, `links`, `top-right`, `oben links`, `center`)
     * - Prozent-/Koordinaten-Paare in `0..100` (`70% 35%`, `70 35`, `(70, 35)`, `70/35`, `x70 y35`, `x=70 y=35`).
     */
    private fun parseZoomTargetPoint(tokens: List<String>): Triple<Float, Float, String>? {
        if (tokens.isEmpty()) return null
        if (tokens.size in 1..2) {
            val anchorKey = tokens.joinToString("-") { it.trim().lowercase() }
            zoomTargetAnchorMap[anchorKey]?.let { return it }
        }

        val joined = tokens.joinToString(" ").trim()
            .removePrefix("(")
            .removeSuffix(")")
            .trim()
            .replace(Regex("""\s*[/;]\s*"""), " ")
            .replace(Regex("""(?<=%)\s*,\s*"""), " ")
            .replace(Regex("""\s*,\s+"""), " ")
            .replace(",", ".") // Dezimalkomma nach Koordinatentrennung in Punkt wandeln

        val parts = joined.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (parts.size != 2) return null

        fun parseAxisCoord(rawTok: String): Pair<Char?, Float>? {
            val low = rawTok.trim().lowercase()
            val axisChar = when {
                low.startsWith("x=") || (low.startsWith("x") && low.length > 1) -> 'x'
                low.startsWith("y=") || (low.startsWith("y") && low.length > 1) -> 'y'
                else -> null
            }
            val stripped = when {
                low.startsWith("x=") || low.startsWith("y=") -> low.drop(2)
                axisChar != null -> low.drop(1)
                else -> low
            }.removeSuffix("%")
            val num = stripped.toFloatOrNull() ?: return null
            if (!num.isFinite() || num < 0f || num > 100f) return null
            return axisChar to num
        }

        val first = parseAxisCoord(parts[0]) ?: return null
        val second = parseAxisCoord(parts[1]) ?: return null
        val (xPct, yPct) = when {
            first.first == 'y' && second.first == 'x' -> second.second to first.second
            first.first == 'y' && second.first == null -> second.second to first.second
            first.first == null && second.first == 'x' -> second.second to first.second
            else -> first.second to second.second
        }
        return Triple(
            (xPct / 100f).coerceIn(0f, 1f),
            (yPct / 100f).coerceIn(0f, 1f),
            "${xPct.roundToInt()}% ${yPct.roundToInt()}%",
        )
    }

    private fun formatZoomNumber(z: Float): String {
        val rounded = (z * 100f).roundToInt() / 100f
        return if (rounded == rounded.toInt().toFloat()) "${rounded.toInt()}" else rounded.toString()
    }

    /**
     * Wandelt einen einzelnen Zoom-Wert (`1,8`, `1.8x`, `50%`, `150%`) in einen Faktor (0.1x..5.0x) um.
     * Werte `> 5,0` ohne `%` werden abgewiesen (z.B. `5,9`), damit Tippfehler nicht still zu 5,9 % werden.
     * Bei animierten Bereichen (`0 -> 1`) darf [allowZeroAsMin] `0` als `0.1x` (10 %) interpretieren.
     */
    internal fun parseZoomFactor(token: String, allowZeroAsMin: Boolean = false): Float? {
        val clean = token.trim().lowercase().replace(",", ".")
        if (clean.isEmpty() || clean.startsWith("+") || clean.startsWith("-")) return null
        val hasPercent = clean.endsWith("%")
        val value = clean.removeSuffix("%").removeSuffix("x").toFloatOrNull() ?: return null
        if (!value.isFinite() || value < 0f) return null
        if (value == 0f) return if (allowZeroAsMin) 0.1f else null
        if (!hasPercent && value > 5.0f) return null
        if (hasPercent && value > 500f) return null
        val factor = if (hasPercent) value / 100f else value
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
     * Parst Zoom-Spezifikationen inkl. optionalem Zielpunkt ("wo der Zoom hingeht"):
     * - `1,8` / `0,5` / `50%` → statischer Zoom `(f, null)`
     * - `0 -> 1` / `1 -> 2` / `0,2 - 1,0` / `0,5 bis 1,5` → animierter Zoom `(a, b)`
     * - `in` / `rein` [+ Faktor] [at 70% 35% | at top-right | auf links] → Reinzoomen auf Zielpunkt
     * - `out` / `raus` [+ Faktor] [at 70% 35% | at top-right | auf links] → Rauszoomen vom Zielpunkt
     */
    internal fun parseZoomSpecWithTarget(raw: String, currentStartZoom: Float = 1.0f): ParsedZoomSpec? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("+") || trimmed.startsWith("-")) return null

        val expandedAt = trimmed
            .replace(Regex("""@(?=\S)"""), "@ ")
            .replace(Regex("""\(\s*"""), "(")
            .replace(Regex("""\s*\)"""), ")")
        val splitTokens = expandedAt.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (splitTokens.isEmpty()) return null

        // Optional trailing easing curve (`0-4 zoom 0 -> 1 ease-out`, `0-4 zoom in at top-right bounce`)
        var easing = EasingCurve.LINEAR
        val rawTokens = if (splitTokens.size >= 2) {
            val maybeEasing = EasingCurve.fromToken(splitTokens.last())
            if (maybeEasing != null) {
                easing = maybeEasing
                splitTokens.dropLast(1)
            } else {
                splitTokens
            }
        } else {
            splitTokens
        }
        if (rawTokens.isEmpty()) return null

        var zoomTokens: List<String> = rawTokens
        var target: Triple<Float, Float, String>? = null

        val prepIdx = rawTokens.indexOfFirst { it.lowercase() in zoomTargetPrepositions }
        if (prepIdx >= 0) {
            if (prepIdx == 0) return null
            zoomTokens = rawTokens.subList(0, prepIdx)
            target = parseZoomTargetPoint(rawTokens.subList(prepIdx + 1, rawTokens.size)) ?: return null
        } else {
            val lastToIdx = rawTokens.indexOfLast { it.equals("to", ignoreCase = true) }
            val afterToTarget = if (lastToIdx > 0) {
                parseZoomTargetPoint(rawTokens.subList(lastToIdx + 1, rawTokens.size))
            } else {
                null
            }
            if (afterToTarget != null) {
                zoomTokens = rawTokens.subList(0, lastToIdx)
                target = afterToTarget
            } else if (rawTokens.size >= 2) {
                val twoWordAnchor = if (rawTokens.size >= 3) {
                    val tail2 = rawTokens.takeLast(2)
                    val key2 = tail2.joinToString("-") { it.lowercase() }
                    zoomTargetAnchorMap[key2]
                } else {
                    null
                }
                if (twoWordAnchor != null) {
                    zoomTokens = rawTokens.dropLast(2)
                    target = twoWordAnchor
                } else {
                    val lastLow = rawTokens.last().lowercase()
                    val oneWordAnchor = zoomTargetAnchorMap[lastLow]
                    if (oneWordAnchor != null) {
                        zoomTokens = rawTokens.dropLast(1)
                        target = oneWordAnchor
                    } else if (rawTokens.size >= 3 &&
                        Regex("""^[xy]=?\d+(?:[.,]\d+)?%?$""", RegexOption.IGNORE_CASE).matches(rawTokens[rawTokens.size - 2]) &&
                        Regex("""^[xy]=?\d+(?:[.,]\d+)?%?$""", RegexOption.IGNORE_CASE).matches(rawTokens.last())
                    ) {
                        val xyTarget = parseZoomTargetPoint(rawTokens.takeLast(2))
                        if (xyTarget != null) {
                            zoomTokens = rawTokens.dropLast(2)
                            target = xyTarget
                        }
                    } else if (rawTokens.last().contains("/") ||
                        (rawTokens.last().startsWith("(") && rawTokens.last().endsWith(")"))
                    ) {
                        val compactTarget = parseZoomTargetPoint(listOf(rawTokens.last()))
                        if (compactTarget != null) {
                            zoomTokens = rawTokens.dropLast(1)
                            target = compactTarget
                        }
                    }
                }
            }
        }

        val zoomPart = zoomTokens.joinToString(" ").trim()
        if (zoomPart.isEmpty()) return null

        val normalized = zoomPart
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
        val allowZero = hasArrow || dir != null || rest.size >= 2
        val factors = rest.map { parseZoomFactor(it, allowZeroAsMin = allowZero) ?: return null }
        if (factors.size > 2) return null

        val rangePair: Pair<Float, Float?> = when {
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
            else -> return null
        }

        return ParsedZoomSpec(
            startZoom = rangePair.first,
            endZoom = rangePair.second,
            targetX = target?.first,
            targetY = target?.second,
            targetLabel = target?.third,
            easing = easing,
        )
    }

    /** Abwärtskompatible Hilfsfunktion für Aufrufer, die nur Start-/End-Zoom benötigen. */
    internal fun parseZoomSpec(raw: String, currentStartZoom: Float = 1.0f): Pair<Float, Float?>? =
        parseZoomSpecWithTarget(raw, currentStartZoom)?.let { it.startZoom to it.endZoom }

    /**
     * Parst 2D- (Z) und 3D-Achsen-Drehungen (X = waagerechte Achse, Y = senkrechte eigene Achse):
     * - `rotate 90` / `rotate y 180` / `rotate = 90` / `rotate 0`
     * - `rotate y 0 -> 360` / `spin axis` / `spin y 2x` / `spin x`
     * - `flip` / `flip x` / `mirror`
     */
    internal fun parseRotationSpec(
        verb: String,
        tokens: List<String>,
        overlayMode: Boolean = false,
    ): ParsedRotationSpec? {
        val v = verb.lowercase()
        val isFlipVerb = v in listOf("flip", "mirror")
        val isSpinVerb = v in listOf("spin", "turn")
        var axis = if (isFlipVerb) RotationAxis.Y else RotationAxis.Z
        var isAbsoluteExplicit = false
        var easing = EasingCurve.LINEAR

        val working = mutableListOf<String>()
        for (rawTok in tokens) {
            var tok = rawTok.trim()
            if (tok.isEmpty()) continue
            val low = tok.lowercase()
            val maybeEasing = EasingCurve.fromToken(low)
            when {
                maybeEasing != null -> easing = maybeEasing
                low == "=" || low == "auf" -> isAbsoluteExplicit = true
                low == "to" && working.isEmpty() -> isAbsoluteExplicit = true
                low.startsWith("=") -> {
                    isAbsoluteExplicit = true
                    val stripped = tok.drop(1).trim()
                    if (stripped.isNotEmpty()) working.add(stripped)
                }
                low in listOf("z", "2d", "roll", "bild") -> axis = RotationAxis.Z
                low in listOf("y", "axis", "achse", "own", "eigen", "eigene", "3d", "yaw") -> axis = RotationAxis.Y
                low in listOf("x", "pitch") -> axis = RotationAxis.X
                low in listOf("vertical", "vertikal") -> axis = if (isFlipVerb) RotationAxis.X else RotationAxis.Y
                low == "horizontal" -> axis = if (isFlipVerb) RotationAxis.Y else RotationAxis.X
                else -> working.add(tok)
            }
        }

        if (working.isEmpty()) {
            return when {
                isFlipVerb -> ParsedRotationSpec(axis, 180f, null, isToggleFlip = true, isAbsoluteExplicit = true, easing = easing)
                isSpinVerb -> ParsedRotationSpec(axis, 0f, 360f, isAbsoluteExplicit = true, easing = easing)
                overlayMode -> null
                else -> ParsedRotationSpec(axis, 90f, null, isRelative = true, easing = easing)
            }
        }

        val joined = working.joinToString(" ")
            .replace(Regex("""\s*(?:->|→|\.\.)\s*"""), " -> ")
            .replace(Regex("""\s+(?:to|bis)\s+""", RegexOption.IGNORE_CASE), " -> ")
        val hasArrow = joined.contains(" -> ")
        val parts = joined.split(Regex("\\s+")).filter { it.isNotEmpty() && it != "->" }
        if (parts.isEmpty()) return null

        fun parseAngle(tok: String): Float? {
            val clean = tok.trim().lowercase()
                .replace(",", ".")
                .removeSuffix("°")
                .removeSuffix("deg")
                .removeSuffix("grad")
            val vNum = clean.toFloatOrNull() ?: return null
            return vNum.takeIf { it.isFinite() && kotlin.math.abs(it) <= 36000f }
        }

        fun parseTurnMultiplier(tok: String): Float? {
            val clean = tok.trim().lowercase().replace(",", ".")
            val m = Regex("""^([+-]?\d+(?:\.\d+)?)\s*(?:x|mal|turns?|runden?)$""").matchEntire(clean) ?: return null
            return m.groupValues[1].toFloatOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 100f }
        }

        if (parts.size == 1 && !hasArrow) {
            val single = parts.first()
            parseTurnMultiplier(single)?.let { turns ->
                return ParsedRotationSpec(axis, 0f, 360f * turns, isAbsoluteExplicit = true, easing = easing)
            }
            // Erlaube auch `0-360` als kompakte Bereichsangabe im Rotationsteil
            val positiveRange = Regex("""^(\d+(?:[.,]\d+)?)-(\d+(?:[.,]\d+)?)$""").matchEntire(single)
            if (positiveRange != null) {
                val a = parseAngle(positiveRange.groupValues[1]) ?: return null
                val b = parseAngle(positiveRange.groupValues[2]) ?: return null
                return ParsedRotationSpec(axis, a, b, isAbsoluteExplicit = true, easing = easing)
            }
            val angle = parseAngle(single) ?: return null
            if (isSpinVerb) {
                return ParsedRotationSpec(axis, 0f, angle, isAbsoluteExplicit = true, easing = easing)
            }
            val hasPlus = single.startsWith("+")
            val isRelative = if (overlayMode) hasPlus else (!isAbsoluteExplicit && angle != 0f)
            return ParsedRotationSpec(axis, angle, null, isRelative = isRelative, isAbsoluteExplicit = isAbsoluteExplicit, easing = easing)
        }

        if (parts.size == 2) {
            val a = parseAngle(parts[0]) ?: return null
            val b = parseAngle(parts[1]) ?: return null
            return ParsedRotationSpec(axis, a, b, isAbsoluteExplicit = true, easing = easing)
        }

        return null
    }

    /** Führt 2D- (Z) und 3D-Achsen-Drehungen (X/Y) auf dem Video aus (global oder im Zeitfenster). */
    private fun executeRotateStatement(
        rangeToken: String,
        verb: String,
        rawArgs: String,
        engine: ProjectEngine,
        scopedOnly: Boolean = false,
    ): String {
        val (range, rangeError) = parseTimeRangeChecked(rangeToken, engine)
        if (range == null) return rangeError!!

        val tokens = rawArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val spec = parseRotationSpec(verb, tokens, overlayMode = false)
            ?: return "⚠ Syntax: rotate [Zeitbereich] [x|y|z|axis] <Grad | 0 -> 360> · spin [Zeitbereich] [axis|x|y] [2x] · flip [Zeitbereich]"

        if (spec.endDegrees == null && range.isAll && !scopedOnly) {
            val cur = engine.project.transform
            when (spec.axis) {
                RotationAxis.Z -> {
                    val target = if (spec.isRelative) cur.rotationDegrees + spec.startDegrees else spec.startDegrees
                    engine.setAxisRotation(rotationZ = target, clearTimedZ = true)
                    return "Video (2D-Bild) auf ${engine.project.transform.rotationDegrees.roundToInt()}° gedreht."
                }
                RotationAxis.Y -> {
                    val target = when {
                        spec.isToggleFlip -> if (kotlin.math.abs(cur.rotationYDegrees - 180f) < 1f) 0f else 180f
                        spec.isRelative -> cur.rotationYDegrees + spec.startDegrees
                        else -> spec.startDegrees
                    }
                    engine.setAxisRotation(rotationY = target, clearTimedY = true)
                    return "Video um eigene Y-Achse auf ${engine.project.transform.rotationYDegrees.roundToInt()}° gedreht."
                }
                RotationAxis.X -> {
                    val target = when {
                        spec.isToggleFlip -> if (kotlin.math.abs(cur.rotationXDegrees - 180f) < 1f) 0f else 180f
                        spec.isRelative -> cur.rotationXDegrees + spec.startDegrees
                        else -> spec.startDegrees
                    }
                    engine.setAxisRotation(rotationX = target, clearTimedX = true)
                    return "Video um eigene X-Achse auf ${engine.project.transform.rotationXDegrees.roundToInt()}° gedreht."
                }
            }
        }

        val spanStart = if (range.isAll) engine.project.trimStartMs else range.startMs
        val spanEnd = if (range.isAll) maxOf(engine.project.trimEndMs, spanStart + 400L) else range.endMs
        val span = when (spec.axis) {
            RotationAxis.Z -> TimedFormatSpan(
                id = uniqueId("fmt"),
                startMs = spanStart,
                endMs = spanEnd,
                hasCustomRatio = false,
                rotationDegrees = spec.startDegrees,
                rotationEndDegrees = spec.endDegrees,
                easing = spec.easing,
            )
            RotationAxis.Y -> TimedFormatSpan(
                id = uniqueId("fmt"),
                startMs = spanStart,
                endMs = spanEnd,
                hasCustomRatio = false,
                rotationYDegrees = spec.startDegrees,
                rotationYEndDegrees = spec.endDegrees,
                easing = spec.easing,
            )
            RotationAxis.X -> TimedFormatSpan(
                id = uniqueId("fmt"),
                startMs = spanStart,
                endMs = spanEnd,
                hasCustomRatio = false,
                rotationXDegrees = spec.startDegrees,
                rotationXEndDegrees = spec.endDegrees,
                easing = spec.easing,
            )
        }
        val neededDuration = maxOf(engine.project.sourceDurationMs, spanEnd)
        val axisName = when (spec.axis) {
            RotationAxis.Z -> "2D-Bild"
            RotationAxis.Y -> "Y-Achse (eigene Achse)"
            RotationAxis.X -> "X-Achse"
        }
        val angleText = if (spec.endDegrees != null) {
            "${spec.startDegrees.roundToInt()}° -> ${spec.endDegrees.roundToInt()}°"
        } else {
            "${spec.startDegrees.roundToInt()}°"
        }
        val desc = "Drehung $axisName $angleText"
        engine.commitChange("$desc (${ProjectEngine.formatSec(spanStart)}-${ProjectEngine.formatSec(spanEnd)})") { p ->
            p.copy(
                sourceDurationMs = neededDuration,
                trimEndMs = if (p.trimEndMs < spanEnd && !range.isAll) spanEnd else p.trimEndMs,
                timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, listOf(span)),
            )
        }
        if (!range.isAll) engine.lastTimelineEndMs = spanEnd
        engine.clampPlayheadTo(spanStart)
        return "$desc (${ProjectEngine.formatSec(spanStart)}–${ProjectEngine.formatSec(spanEnd)})."
    }

    /**
     * Führt einen (optional zeitgebundenen oder animierten) Zoom-Befehl aus – inkl. optionalem
     * Zielpunkt (`at 70% 35%`, `at top-right`, `auf links`), auf den die Kamera beim Zoomen zufährt.
     * Statische Zooms auf `all` aktualisieren den globalen Transform; animierte oder zeitgebundene
     * Zooms werden als [TimedFormatSpan] über das Zeitfenster gelegt.
     */
    private fun executeZoomStatement(
        rangeToken: String,
        rawPayload: String,
        engine: ProjectEngine,
        scopedOnly: Boolean = false,
    ): String? {
        val (range, rangeError) = parseTimeRangeChecked(rangeToken, engine)
        if (range == null) return rangeError
        val baseZoom = if (range.isAll) engine.project.transform.zoom else engine.currentTransform(range.startMs).zoom
        val spec = parseZoomSpecWithTarget(rawPayload, baseZoom) ?: return null
        val zoomStart = spec.startZoom
        val zoomEnd = spec.endZoom
        val targetX = spec.targetX
        val targetY = spec.targetY
        val hasTarget = targetX != null && targetY != null
        val startPan = if (targetX != null && targetY != null) zoomTargetPan(zoomStart, targetX, targetY) else null
        val endPan = if (targetX != null && targetY != null && zoomEnd != null) zoomTargetPan(zoomEnd, targetX, targetY) else null

        if (zoomEnd == null && range.isAll && !scopedOnly) {
            if (startPan != null) {
                engine.setZoomAndPan(zoom = zoomStart, panX = startPan.first, panY = startPan.second, clearTimedZoom = true)
                return "Zoom auf ${engine.project.transform.zoom}x (Punkt ${spec.targetLabel}) gesetzt."
            }
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
            panX = startPan?.first,
            panY = startPan?.second,
            panXEnd = endPan?.first,
            panYEnd = endPan?.second,
            zoom = zoomStart,
            zoomEnd = zoomEnd,
            easing = spec.easing,
        )
        val neededDuration = maxOf(engine.project.sourceDurationMs, spanEnd)
        val targetSuffix = if (spec.targetLabel != null) " -> Punkt ${spec.targetLabel}" else ""
        val desc = if (zoomEnd != null) {
            "Zoom ${formatZoomNumber(zoomStart)}x -> ${formatZoomNumber(zoomEnd)}x$targetSuffix"
        } else {
            "Zoom ${formatZoomNumber(zoomStart)}x$targetSuffix"
        }
        engine.commitChange("$desc (${ProjectEngine.formatSec(spanStart)}-${ProjectEngine.formatSec(spanEnd)})") { p ->
            p.copy(
                sourceDurationMs = neededDuration,
                trimEndMs = if (p.trimEndMs < spanEnd && !range.isAll) spanEnd else p.trimEndMs,
                timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, listOf(span)),
            )
        }
        if (!range.isAll) engine.lastTimelineEndMs = spanEnd
        engine.clampPlayheadTo(spanStart)
        return "$desc (${ProjectEngine.formatSec(spanStart)}–${ProjectEngine.formatSec(spanEnd)})."
    }

    /** Executes `fade`, `flash`, `shake`, `kenburns`, and `pulse` video effects. */
    private fun executeMotionEffect(
        rangeToken: String,
        verb: String,
        rawArgs: String,
        engine: ProjectEngine,
    ): String {
        val (range, rangeError) = parseTimeRangeChecked(rangeToken, engine)
        if (range == null) return rangeError!!
        val spanStart = if (range.isAll) engine.project.trimStartMs else range.startMs
        val spanEnd = if (range.isAll) maxOf(engine.project.trimEndMs, spanStart + 400L) else range.endMs
        val tokens = rawArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val neededDuration = maxOf(engine.project.sourceDurationMs, spanEnd)

        val span: TimedFormatSpan
        val summary: String
        when (verb) {
            "fade" -> {
                var dir: String? = null
                var color = 0xFF000000L
                var easing = EasingCurve.EASE_IN_OUT
                for (tok in tokens) {
                    val low = tok.lowercase()
                    when {
                        low in listOf("in", "ein", "rein") -> dir = "in"
                        low in listOf("out", "aus", "raus") -> dir = "out"
                        colorMap.containsKey(low) -> color = colorMap[low]!!
                        parseHexColor(low) != null -> color = parseHexColor(low)!!
                        EasingCurve.fromToken(low) != null -> easing = EasingCurve.fromToken(low)!!
                        else -> return "⚠ Syntax: [Zeitbereich] fade in|out [black|white|<Farbe>] [ease]"
                    }
                }
                if (dir == null) return "⚠ Syntax: [Zeitbereich] fade in|out [black|white|<Farbe>] [ease]"
                val fromA = if (dir == "in") 1.0f else 0.0f
                val toA = if (dir == "in") 0.0f else 1.0f
                span = TimedFormatSpan(
                    id = uniqueId("fmt"),
                    startMs = spanStart,
                    endMs = spanEnd,
                    hasCustomRatio = false,
                    easing = easing,
                    fadeFromAlpha = fromA,
                    fadeToAlpha = toA,
                    fadeColorArgb = color,
                    isFlashPulse = false,
                )
                summary = "Fade $dir (${ProjectEngine.formatSec(spanStart)}–${ProjectEngine.formatSec(spanEnd)})"
            }
            "flash" -> {
                var color = 0xFFFFFFFFL
                for (tok in tokens) {
                    val low = tok.lowercase()
                    when {
                        colorMap.containsKey(low) -> color = colorMap[low]!!
                        parseHexColor(low) != null -> color = parseHexColor(low)!!
                        else -> return "⚠ Syntax: [Zeitbereich] flash [white|gold|<Farbe>]"
                    }
                }
                span = TimedFormatSpan(
                    id = uniqueId("fmt"),
                    startMs = spanStart,
                    endMs = spanEnd,
                    hasCustomRatio = false,
                    fadeFromAlpha = 0.92f,
                    fadeToAlpha = 0.0f,
                    fadeColorArgb = color,
                    isFlashPulse = true,
                )
                summary = "Flash (${ProjectEngine.formatSec(spanStart)}–${ProjectEngine.formatSec(spanEnd)})"
            }
            "shake" -> {
                val arg = tokens.firstOrNull()?.lowercase()
                val amplitude = when (arg) {
                    null, "normal", "medium", "mittel" -> 0.035f
                    "mild", "light", "leicht", "soft" -> 0.015f
                    "strong", "heavy", "stark", "hard" -> 0.065f
                    else -> {
                        val n = arg.replace(",", ".").toFloatOrNull()
                            ?: return "⚠ Syntax: [Zeitbereich] shake [light|normal|strong|0.1..3.0]"
                        (n * 0.035f).coerceIn(0.005f, 0.12f)
                    }
                }
                span = TimedFormatSpan(
                    id = uniqueId("fmt"),
                    startMs = spanStart,
                    endMs = spanEnd,
                    hasCustomRatio = false,
                    shakeIntensity = amplitude,
                )
                summary = "Kamera-Shake (${ProjectEngine.formatSec(spanStart)}–${ProjectEngine.formatSec(spanEnd)})"
            }
            "kenburns" -> {
                var z0 = 1.0f
                var z1 = 1.25f
                var px0 = 0f
                var px1 = 0f
                var py0 = 0f
                var py1 = -0.03f
                for (tok in tokens) {
                    when (tok.lowercase()) {
                        "in" -> { z0 = 1.0f; z1 = 1.25f }
                        "out" -> { z0 = 1.25f; z1 = 1.0f }
                        "left", "links" -> { px0 = 0.04f; px1 = -0.04f; py0 = 0f; py1 = 0f }
                        "right", "rechts" -> { px0 = -0.04f; px1 = 0.04f; py0 = 0f; py1 = 0f }
                        "up", "hoch" -> { py0 = 0.04f; py1 = -0.04f; px0 = 0f; px1 = 0f }
                        "down", "runter" -> { py0 = -0.04f; py1 = 0.04f; px0 = 0f; px1 = 0f }
                        else -> return "⚠ Syntax: [Zeitbereich] kenburns [in|out|left|right|up|down]"
                    }
                }
                span = TimedFormatSpan(
                    id = uniqueId("fmt"),
                    startMs = spanStart,
                    endMs = spanEnd,
                    hasCustomRatio = false,
                    panX = px0,
                    panXEnd = px1,
                    panY = py0,
                    panYEnd = py1,
                    zoom = z0,
                    zoomEnd = z1,
                    easing = EasingCurve.EASE_IN_OUT,
                )
                summary = "Ken Burns (${ProjectEngine.formatSec(spanStart)}–${ProjectEngine.formatSec(spanEnd)})"
            }
            else -> { // pulse / puls
                var bpm = engine.project.bpm ?: 120f
                var strength = 0.12f
                for (tok in tokens) {
                    val low = tok.lowercase()
                    when {
                        low in listOf("light", "leicht", "soft") -> strength = 0.06f
                        low in listOf("strong", "stark", "hard") -> strength = 0.22f
                        else -> {
                            val parsed = low.removeSuffix("bpm").replace(",", ".").toFloatOrNull()
                                ?: return "⚠ Syntax: [Zeitbereich] pulse [<BPM>bpm] [light|strong]"
                            bpm = parsed.coerceIn(20f, 300f)
                        }
                    }
                }
                span = TimedFormatSpan(
                    id = uniqueId("fmt"),
                    startMs = spanStart,
                    endMs = spanEnd,
                    hasCustomRatio = false,
                    pulseBpm = bpm,
                    pulseStrength = strength,
                )
                summary = "Pulse ${bpm.roundToInt()} BPM (${ProjectEngine.formatSec(spanStart)}–${ProjectEngine.formatSec(spanEnd)})"
            }
        }

        engine.commitChange(summary) { p ->
            p.copy(
                sourceDurationMs = neededDuration,
                trimEndMs = if (p.trimEndMs < spanEnd && !range.isAll) spanEnd else p.trimEndMs,
                timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, listOf(span)),
            )
        }
        if (!range.isAll) engine.lastTimelineEndMs = spanEnd
        engine.clampPlayheadTo(spanStart)
        return "$summary aktiv."
    }

    /** Executes `mute`, `unmute`, `volume`, and `audio fade in|out`. */
    private fun executeAudioCommand(
        rangeToken: String,
        verb: String,
        rawArgs: String,
        engine: ProjectEngine,
    ): String {
        val (range, rangeError) = parseTimeRangeChecked(rangeToken, engine)
        if (range == null) return rangeError!!
        val tokens = rawArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

        when (verb) {
            "unmute" -> {
                engine.commitChange("Ton wieder an (100%)") { it.copy(audioVolume = 1.0f) }
                return "Ton wieder aktiviert (100%)."
            }
            "mute", "stumm" -> {
                if (range.isAll) {
                    engine.commitChange("Ton stummgeschaltet") { p ->
                        val cleaned = p.timedFormatSpans.mapNotNull { s ->
                            val st = s.copy(audioVolumeStart = null, audioVolumeEnd = null)
                            if (!st.hasAnyEffect()) null else st
                        }
                        p.copy(audioVolume = 0f, timedFormatSpans = cleaned)
                    }
                    return "Ton komplett stummgeschaltet."
                }
                val span = TimedFormatSpan(
                    id = uniqueId("fmt"),
                    startMs = range.startMs,
                    endMs = range.endMs,
                    hasCustomRatio = false,
                    audioVolumeStart = 0f,
                    audioVolumeEnd = 0f,
                )
                val needed = maxOf(engine.project.sourceDurationMs, range.endMs)
                engine.commitChange("Ton stumm (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
                    p.copy(
                        sourceDurationMs = needed,
                        trimEndMs = maxOf(p.trimEndMs, range.endMs),
                        timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, listOf(span)),
                    )
                }
                engine.lastTimelineEndMs = range.endMs
                engine.clampPlayheadTo(range.startMs)
                return "Ton stumm (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)})."
            }
            "volume", "lautstärke", "lautstaerke" -> {
                val tok = tokens.firstOrNull()?.lowercase()
                    ?: return "⚠ Syntax: [Zeitbereich] volume <0..200%> (z.B. 'volume 150%' oder '2-5 volume 30%')"
                val hasPct = tok.endsWith("%")
                val num = tok.removeSuffix("%").replace(",", ".").toFloatOrNull()
                    ?: return "⚠ Ungültige Lautstärke '$tok' (erlaubt: 0% bis 200% oder 0.0 bis 2.0)."
                val gain = (if (hasPct || num > 2.5f) num / 100f else num).coerceIn(0f, 2f)
                if (range.isAll) {
                    engine.commitChange("Lautstärke ${(gain * 100).roundToInt()}%") { p ->
                        val cleaned = p.timedFormatSpans.mapNotNull { s ->
                            val st = s.copy(audioVolumeStart = null, audioVolumeEnd = null)
                            if (!st.hasAnyEffect()) null else st
                        }
                        p.copy(audioVolume = gain, timedFormatSpans = cleaned)
                    }
                    return "Lautstärke auf ${(gain * 100).roundToInt()}% gesetzt."
                }
                val span = TimedFormatSpan(
                    id = uniqueId("fmt"),
                    startMs = range.startMs,
                    endMs = range.endMs,
                    hasCustomRatio = false,
                    audioVolumeStart = gain,
                    audioVolumeEnd = gain,
                )
                val needed = maxOf(engine.project.sourceDurationMs, range.endMs)
                engine.commitChange("Lautstärke ${(gain * 100).roundToInt()}% (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
                    p.copy(
                        sourceDurationMs = needed,
                        trimEndMs = maxOf(p.trimEndMs, range.endMs),
                        timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, listOf(span)),
                    )
                }
                engine.lastTimelineEndMs = range.endMs
                engine.clampPlayheadTo(range.startMs)
                return "Lautstärke ${(gain * 100).roundToInt()}% (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)})."
            }
            else -> { // audio fade in|out | audio mute | audio volume ...
                val first = tokens.firstOrNull()?.lowercase()
                if (first == "mute" || first == "stumm") {
                    return executeAudioCommand(rangeToken, "mute", tokens.drop(1).joinToString(" "), engine)
                }
                if (first == "volume") {
                    return executeAudioCommand(rangeToken, "volume", tokens.drop(1).joinToString(" "), engine)
                }
                if (first == "fade") {
                    val dir = tokens.getOrNull(1)?.lowercase()
                    val baseVol = engine.project.audioVolume.coerceIn(0.05f, 2f)
                    val (g0, g1) = when (dir) {
                        "in", "ein" -> 0f to baseVol
                        "out", "aus" -> baseVol to 0f
                        else -> return "⚠ Syntax: [Zeitbereich] audio fade in|out"
                    }
                    val spanStart = if (range.isAll) engine.project.trimStartMs else range.startMs
                    val spanEnd = if (range.isAll) maxOf(engine.project.trimEndMs, spanStart + 400L) else range.endMs
                    val span = TimedFormatSpan(
                        id = uniqueId("fmt"),
                        startMs = spanStart,
                        endMs = spanEnd,
                        hasCustomRatio = false,
                        easing = EasingCurve.EASE_IN_OUT,
                        audioVolumeStart = g0,
                        audioVolumeEnd = g1,
                    )
                    val needed = maxOf(engine.project.sourceDurationMs, spanEnd)
                    engine.commitChange("Audio Fade $dir (${ProjectEngine.formatSec(spanStart)}-${ProjectEngine.formatSec(spanEnd)})") { p ->
                        p.copy(
                            sourceDurationMs = needed,
                            trimEndMs = if (p.trimEndMs < spanEnd && !range.isAll) spanEnd else p.trimEndMs,
                            timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, listOf(span)),
                        )
                    }
                    if (!range.isAll) engine.lastTimelineEndMs = spanEnd
                    engine.clampPlayheadTo(spanStart)
                    return "Audio Fade $dir (${ProjectEngine.formatSec(spanStart)}–${ProjectEngine.formatSec(spanEnd)})."
                }
                return "⚠ Syntax: mute · unmute · volume 150% · 0-2 audio fade in · end-3..end audio fade out"
            }
        }
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
                    panXEnd = if (span.panX != null) span.panXEnd else prev.panXEnd,
                    panY = span.panY ?: prev.panY,
                    panYEnd = if (span.panY != null) span.panYEnd else prev.panYEnd,
                    zoom = span.zoom ?: prev.zoom,
                    zoomEnd = if (span.zoom != null) span.zoomEnd else prev.zoomEnd,
                    rotationDegrees = span.rotationDegrees ?: prev.rotationDegrees,
                    rotationEndDegrees = if (span.rotationDegrees != null) span.rotationEndDegrees else prev.rotationEndDegrees,
                    rotationXDegrees = span.rotationXDegrees ?: prev.rotationXDegrees,
                    rotationXEndDegrees = if (span.rotationXDegrees != null) span.rotationXEndDegrees else prev.rotationXEndDegrees,
                    rotationYDegrees = span.rotationYDegrees ?: prev.rotationYDegrees,
                    rotationYEndDegrees = if (span.rotationYDegrees != null) span.rotationYEndDegrees else prev.rotationYEndDegrees,
                    filterPreset = span.filterPreset ?: prev.filterPreset,
                    blurRadiusPx = span.blurRadiusPx ?: prev.blurRadiusPx,
                    blurMode = span.blurMode ?: prev.blurMode,
                    easing = if (span.easing != EasingCurve.LINEAR) span.easing else prev.easing,
                    fadeFromAlpha = span.fadeFromAlpha ?: prev.fadeFromAlpha,
                    fadeToAlpha = if (span.fadeFromAlpha != null) span.fadeToAlpha else prev.fadeToAlpha,
                    fadeColorArgb = span.fadeColorArgb ?: prev.fadeColorArgb,
                    isFlashPulse = span.isFlashPulse || prev.isFlashPulse,
                    shakeIntensity = span.shakeIntensity ?: prev.shakeIntensity,
                    pulseBpm = span.pulseBpm ?: prev.pulseBpm,
                    pulseStrength = span.pulseStrength ?: prev.pulseStrength,
                    audioVolumeStart = span.audioVolumeStart ?: prev.audioVolumeStart,
                    audioVolumeEnd = if (span.audioVolumeStart != null) span.audioVolumeEnd else prev.audioVolumeEnd,
                )
            } else {
                result.add(span)
            }
        }
        return result.takeLast(500)
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
            parseZoomSpecWithTarget(payload, baseZoom)
        }
        val combined = buildCombinedScopedCommand(ranges, action)
        when {
            parsedZoom != null -> {
                val zoomStart = parsedZoom.startZoom
                val zoomEnd = parsedZoom.endZoom
                val targetX = parsedZoom.targetX
                val targetY = parsedZoom.targetY
                val startPan = if (targetX != null && targetY != null) zoomTargetPan(zoomStart, targetX, targetY) else null
                val endPan = if (targetX != null && targetY != null && zoomEnd != null) zoomTargetPan(zoomEnd, targetX, targetY) else null
                val targetSuffix = if (parsedZoom.targetLabel != null) " -> Punkt ${parsedZoom.targetLabel}" else ""
                val label = if (zoomEnd != null) {
                    "Zoom ${formatZoomNumber(zoomStart)}x -> ${formatZoomNumber(zoomEnd)}x$targetSuffix"
                } else {
                    "Zoom ${formatZoomNumber(zoomStart)}x$targetSuffix"
                }
                engine.commitChange("PocketScript $label (${ranges.size} Zeitfenster)") { p ->
                    val spans = ranges.map { (startMs, endMs) ->
                        TimedFormatSpan(
                            id = uniqueId("fmt"),
                            startMs = startMs,
                            endMs = endMs,
                            hasCustomRatio = false,
                            panX = startPan?.first,
                            panY = startPan?.second,
                            panXEnd = endPan?.first,
                            panYEnd = endPan?.second,
                            zoom = zoomStart,
                            zoomEnd = zoomEnd,
                            easing = parsedZoom.easing,
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
        val hasZoomTargetPrep = hasZoomWord && tokens.any { it.lowercase().removePrefix("@").let { low -> low in zoomTargetPrepositions || it.startsWith("@") } }
        val hasCropWord = tokens.any { it.lowercase() in setOf("crop", "rand") }
        val hasRotateWord = tokens.any { it.lowercase() in setOf("rotate", "rot", "spin", "turn", "drehen", "flip", "mirror") }
        // Prüfen, ob actionBody aus Format `(9:16)`, Crop (`u20%`/`crop 20%`/`crop top 20%`),
        // Move (`move 10%l`/`move up 10%`), Zoom (`zoom in [at 70% 40%]`) oder Drehung (`rotate y 0 -> 360`/`spin axis`) besteht
        if (tokens.all {
                ratioTokenRegex.matches(it) ||
                    edgeCropTokenRegex.matches(it) ||
                    moveSuffixTokenRegex.matches(it) ||
                    movePrefixTokenRegex.matches(it) ||
                    it.lowercase() in zoomWordSet ||
                    (hasZoomWord && (parseZoomSpec(it) != null || zoomTargetAnchorMap.containsKey(it.lowercase()) || it.startsWith("@") || EasingCurve.fromToken(it) != null)) ||
                    (hasZoomTargetPrep && parseZoomTargetPoint(listOf("0", it.trim('(', ')', ','))) != null) ||
                    (hasCropWord && plainPercentTokenRegex.matches(it)) ||
                    (hasRotateWord && (parseRotationSpec("rotate", listOf(it)) != null || EasingCurve.fromToken(it) != null))
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
     * (`crop bottom 20%`, `crop top 20%`, `crop 20%`, `u20%`, `o20%`, `r20%`, `l20%`),
     * prozentualen Verschiebungen (`move down 10%`, `move up 10%`, `move 10%l`),
     * Zoom (`zoom in`, `zoom 0 -> 1`) sowie Drehungen (`rotate y 0 -> 360`, `spin axis`) aus.
     */
    private fun tryParseAndExecuteFormatAndCrop(raw: String, engine: ProjectEngine, scopedOnly: Boolean = false): String? {
        var normalized = raw.trim()

        // Leerzeichen innerhalb von Klammern `( 8 : 15 )` -> `(8:15)` normalisieren
        normalized = normalized.replace(
            Regex("""\(\s*(\d+(?:[.,]\d+)?)\s*([:/x])\s*(\d+(?:[.,]\d+)?)\s*\)"""),
            "($1$2$3)",
        )
        // Pfeile und `to`/`bis` für animierte Bereiche `0 -> 1` / `0 to 360` zusammenziehen
        // (nicht bei `to 70% 35%`, wo `to` einen zweistelligen Zoom-Zielpunkt einleitet).
        normalized = normalized.replace(Regex("""\s*(?:->|→)\s*"""), "->")
        normalized = normalized.replace(
            Regex("""([+-]?\d+(?:[.,]\d+)?%?x?°?)\s+(?:to|bis)\s+([+-]?\d+(?:[.,]\d+)?%?x?°?)(?!\s+y?=?\d+(?:[.,]\d+)?%?(?:\s|$))""", RegexOption.IGNORE_CASE),
            "$1->$2",
        )
        // Normalize `last 5` -> `last_5` and `end - 3 .. end` -> `end-3..end` before tokenizing
        normalized = normalized.replace(
            Regex("""\b(last|letzte[rn]?)\s+($BASE_TIME_ATOM)\b""", RegexOption.IGNORE_CASE),
            "last_$2",
        )
        normalized = normalized.replace(
            Regex("""\b(end|ende)\s*-\s*($BASE_TIME_ATOM)\s*\.\.\s*(end|ende)\b""", RegexOption.IGNORE_CASE),
            "end-$2..end",
        )
        // Leerzeichen um Bindestrich bei Zeitbereichen `10 - 24,3` / `cut1 - cut2` / `00:10.5 - 00:33.3` -> `10-24,3`
        // (nicht bei `0.2 -0.1`, wo ein negatives Vorzeichen nach dem Leerzeichen steht)
        normalized = normalized.replace(
            Regex("""($TIME_ENDPOINT)\s*-\s*($TIME_ENDPOINT)""", RegexOption.IGNORE_CASE),
            "$1-$2",
        )
        // Zuerst Präfix-Richtung mit Zahl zusammenziehen (`top 25% left 10%` -> `top25% left10%`),
        // aber nicht bei Zeitbereichen danach (`at top 4-8` oder `at left 00:05`)!
        normalized = normalized.replace(
            Regex("""\b(lower|low|down|runter|unten|up|upper|oben|hoch|right|rechts|left|links|top|bottom|bot|u|o|r|l)\s+(\d+(?:[.,]\d+)?%?)(?!\s*-|\d*:)""", RegexOption.IGNORE_CASE),
            "$1$2",
        )
        // Danach Leerzeichen zwischen Prozentzahl und Richtungs-Suffix zusammenziehen (`10% lower` -> `10%lower`),
        // aber nur wenn nach dem Richtungswort keine neue Zahl folgt.
        normalized = normalized.replace(
            Regex("""\b(\d+(?:[.,]\d+)?)\s*%\s*(lower|low|down|runter|unten|up|upper|oben|hoch|right|rechts|left|links|l|u|d|o|r)\b(?!\s*\d)""", RegexOption.IGNORE_CASE),
            "$1%$2",
        )

        val tokens = normalized.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null

        val segments = mutableListOf<FormatAndCropSegment>()
        var current = FormatAndCropSegment(timeToken = "all")
        var nextTokenIsMove = false
        var inCropMode = false
        val fullMoveDirRegex = Regex(
            """^(?:lower|low|down|runter|unten|up|upper|oben|hoch|right|rechts|left|links)\d+""",
            RegexOption.IGNORE_CASE,
        )
        val rotateVerbs = setOf("rotate", "rot", "spin", "turn", "drehen", "flip", "mirror")
        val rotateAxisWords = setOf("x", "y", "z", "axis", "achse", "own", "eigen", "eigene", "2d", "3d", "roll", "pitch", "yaw", "horizontal", "vertical", "vertikal", "bild")

        var idx = 0
        while (idx < tokens.size) {
            val token = tokens[idx]
            val lowerTok = token.lowercase()
            if (lowerTok in listOf("move", "shift", "pan", "verschiebe")) {
                nextTokenIsMove = true
                inCropMode = false
                idx++
                continue
            }
            if (lowerTok in listOf("crop", "rand", "format", "ratio")) {
                inCropMode = true
                nextTokenIsMove = false
                idx++
                continue
            }

            if (lowerTok in listOf("zoom", "z", "reinzoomen", "zoomin", "rauszoomen", "zoomout")) {
                nextTokenIsMove = false
                inCropMode = false
                val rangeSoFar = parseTimeRange(current.timeToken, engine)
                val baseZoom = if (rangeSoFar.isAll) engine.project.transform.zoom else engine.currentTransform(rangeSoFar.startMs).zoom
                val nextStartsClause = { tok: String? ->
                    if (tok == null) true
                    else {
                        val low = tok.lowercase()
                        low in setOf(
                            "move", "shift", "pan", "verschiebe",
                            "crop", "rand", "format", "ratio",
                            "zoom", "z", "reinzoomen", "zoomin", "rauszoomen", "zoomout",
                            "rotate", "rot", "spin", "turn", "drehen", "flip", "mirror",
                            "all", "alles", "*",
                        ) || ratioTokenRegex.matches(tok) || edgeCropTokenRegex.matches(tok) ||
                            explicitTimeRangeTokenRegex.matches(tok)
                    }
                }
                val hasDirectionVerb = lowerTok in listOf("reinzoomen", "zoomin", "rauszoomen", "zoomout")
                val zoomCollected = mutableListOf<String>()
                if (hasDirectionVerb) {
                    zoomCollected.add(if (lowerTok.startsWith("rein") || lowerTok.startsWith("zoomin")) "in" else "out")
                }
                var j = idx + 1
                while (j < tokens.size) {
                    val cand = tokens[j]
                    if (zoomCollected.isNotEmpty() && nextStartsClause(cand)) break
                    zoomCollected.add(cand)
                    j++
                }
                val spec = if (zoomCollected.isEmpty()) {
                    null
                } else {
                    parseZoomSpecWithTarget(zoomCollected.joinToString(" "), baseZoom)
                } ?: return null
                current.zoomStart = spec.startZoom
                current.zoomEnd = spec.endZoom
                current.zoomTargetX = spec.targetX
                current.zoomTargetY = spec.targetY
                current.zoomTargetLabel = spec.targetLabel
                if (spec.easing != EasingCurve.LINEAR) current.easing = spec.easing
                idx = j
                continue
            }

            if (lowerTok in rotateVerbs) {
                nextTokenIsMove = false
                inCropMode = false
                val rotTokens = mutableListOf<String>()
                var j = idx + 1
                while (j < tokens.size) {
                    val cand = tokens[j]
                    val candLow = cand.lowercase()
                    val isKnownNextClause = candLow in rotateVerbs ||
                        candLow in listOf("move", "shift", "pan", "verschiebe", "crop", "rand", "zoom", "reinzoomen", "rauszoomen") ||
                        ratioTokenRegex.matches(cand) ||
                        edgeCropTokenRegex.matches(cand) ||
                        (rotTokens.isNotEmpty() && explicitTimeRangeTokenRegex.matches(cand))
                    if (isKnownNextClause) break
                    val looksLikeRotArg = candLow in rotateAxisWords ||
                        candLow == "=" || candLow == "to" || candLow == "auf" ||
                        cand.contains("->") ||
                        EasingCurve.fromToken(candLow) != null ||
                        Regex("""^[+-]?\d+(?:[.,]\d+)?(?:°|deg|grad|x|mal)?$""", RegexOption.IGNORE_CASE).matches(cand)
                    if (!looksLikeRotArg) break
                    rotTokens.add(cand)
                    j++
                }
                val spec = parseRotationSpec(lowerTok, rotTokens) ?: return null
                if (spec.easing != EasingCurve.LINEAR) current.easing = spec.easing
                when (spec.axis) {
                    RotationAxis.Z -> {
                        current.rotZStart = spec.startDegrees
                        current.rotZEnd = spec.endDegrees
                    }
                    RotationAxis.X -> {
                        current.rotXStart = spec.startDegrees
                        current.rotXEnd = spec.endDegrees
                    }
                    RotationAxis.Y -> {
                        current.rotYStart = spec.startDegrees
                        current.rotYEnd = spec.endDegrees
                    }
                }
                idx = j
                continue
            }

            val timeMatch = timeTokenRegex.matchEntire(token)
            val ratioMatch = ratioTokenRegex.matchEntire(token)
            val moveSuffixMatch = moveSuffixTokenRegex.matchEntire(token)
            val movePrefixMatch = if (nextTokenIsMove) movePrefixTokenRegex.matchEntire(token) else null
            val edgeMatch = if (!nextTokenIsMove) edgeCropTokenRegex.matchEntire(token) else null
            val plainCropPctMatch = if (inCropMode) plainPercentTokenRegex.matchEntire(token) else null

            when {
                inCropMode && lowerTok in listOf("all", "alles", "inset", "ränder", "raender") && current.hasModifiers -> {
                    // `crop all 20%`: `all` nach `crop` heißt "alle 4 Ränder", nicht neues Zeitsegment
                }
                plainCropPctMatch != null -> {
                    val pct = (plainCropPctMatch.groupValues[1].replace(",", ".").toFloatOrNull() ?: 0f).coerceIn(0f, 45f)
                    current.cutTopPct = pct
                    current.cutBottomPct = pct
                    current.cutLeftPct = pct
                    current.cutRightPct = pct
                    inCropMode = false
                }
                timeMatch != null -> {
                    inCropMode = false
                    nextTokenIsMove = false
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
                    inCropMode = false
                }
                moveSuffixMatch != null -> {
                    val pct = (moveSuffixMatch.groupValues[1].replace(",", ".").toFloatOrNull() ?: 0f).coerceIn(0f, 95f)
                    val dir = moveSuffixMatch.groupValues[2].lowercase()
                    when (dir) {
                        "l", "low", "lower", "d", "down", "unten", "runter" -> current.moveYPct = pct
                        "u", "up", "upper", "o", "oben", "hoch" -> current.moveYPct = -pct
                        "r", "right", "rechts" -> current.moveXPct = pct
                        "left", "links" -> current.moveXPct = -pct
                    }
                    val nextTok = tokens.getOrNull(idx + 1).orEmpty()
                    nextTokenIsMove = fullMoveDirRegex.containsMatchIn(nextTok)
                    inCropMode = false
                }
                movePrefixMatch != null -> {
                    val dir = movePrefixMatch.groupValues[1].lowercase()
                    val pct = (movePrefixMatch.groupValues[2].replace(",", ".").toFloatOrNull() ?: 0f).coerceIn(0f, 95f)
                    when (dir) {
                        "u", "unten", "d", "down", "low", "lower", "runter" -> current.moveYPct = pct
                        "o", "oben", "up", "upper", "hoch" -> current.moveYPct = -pct
                        "r", "rechts", "right" -> current.moveXPct = pct
                        "l", "links", "left" -> current.moveXPct = -pct
                    }
                    val nextTok = tokens.getOrNull(idx + 1).orEmpty()
                    nextTokenIsMove = fullMoveDirRegex.containsMatchIn(nextTok)
                    inCropMode = false
                }
                edgeMatch != null -> {
                    val dir = edgeMatch.groupValues[1].lowercase()
                    val pct = (edgeMatch.groupValues[2].replace(",", ".").toFloatOrNull() ?: 0f).coerceIn(0f, 98f)
                    when (dir) {
                        "u", "unten", "bottom", "bot" -> current.cutBottomPct = pct
                        "o", "oben", "top" -> current.cutTopPct = pct
                        "r", "rechts", "right" -> current.cutRightPct = pct
                        "l", "links", "left" -> current.cutLeftPct = pct
                    }
                }
                else -> {
                    // Unbekanntes Token -> kein reiner Format-/Kanten-Cut-/Move-/Zoom-/Rotate-Ausdruck
                    return null
                }
            }
            idx++
        }

        if (current.hasModifiers) {
            segments.add(current)
        }
        if (segments.isEmpty()) return null

        // Reine Einzel-Drehung ohne `scopedOnly` geht an `executeRotateStatement`, damit `rotate 90`
        // relativ bleibt (`repeat 2: rotate 90`) und `flip` global umschalten kann.
        if (!scopedOnly && segments.size == 1) {
            val only = segments.single()
            val onlyHasRotation = only.rotZStart != null || only.rotXStart != null || only.rotYStart != null
            val hasAnythingElse = only.ratioW != null || only.cutTopPct != null || only.cutBottomPct != null ||
                only.cutLeftPct != null || only.cutRightPct != null || only.moveXPct != null ||
                only.moveYPct != null || only.zoomStart != null
            if (onlyHasRotation && !hasAnythingElse) return null
        }

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
        var updatedGlobalRotZ = engine.project.transform.rotationDegrees
        var updatedGlobalRotX = engine.project.transform.rotationXDegrees
        var updatedGlobalRotY = engine.project.transform.rotationYDegrees
        var shouldUpdateGlobalCrop = false
        var shouldUpdateGlobalZoom = false
        var shouldUpdateGlobalRot = false
        var clearTimedCropOverrides = false
        var clearTimedZoomOverrides = false
        var clearTimedRotOverrides = false

        for (seg in segments) {
            val (range, rangeError) = parseTimeRangeChecked(seg.timeToken, engine)
            if (range == null) return rangeError
            if (range.endMs > maxEndRequired) {
                maxEndRequired = range.endMs
            }

            val cropLeftVal = seg.cutLeftPct?.let { (it / 100f).coerceIn(0f, 0.98f) }
            val cropTopVal = seg.cutTopPct?.let { (it / 100f).coerceIn(0f, 0.98f) }
            val cropRightVal = seg.cutRightPct?.let { (1f - it / 100f).coerceIn(0.02f, 1.0f) }
            val cropBottomVal = seg.cutBottomPct?.let { (1f - it / 100f).coerceIn(0.02f, 1.0f) }
            val explicitPanX = seg.moveXPct?.let { (it / 100f).coerceIn(-0.95f, 0.95f) }
            val explicitPanY = seg.moveYPct?.let { (it / 100f).coerceIn(-0.95f, 0.95f) }
            val hasZoomTarget = seg.zoomStart != null && seg.zoomTargetX != null && seg.zoomTargetY != null
            val zoomStartPan = if (hasZoomTarget) zoomTargetPan(seg.zoomStart!!, seg.zoomTargetX!!, seg.zoomTargetY!!) else null
            val zoomEndPan = if (hasZoomTarget && seg.zoomEnd != null) zoomTargetPan(seg.zoomEnd!!, seg.zoomTargetX!!, seg.zoomTargetY!!) else null
            val panXVal = when {
                explicitPanX != null && zoomStartPan != null -> (explicitPanX + zoomStartPan.first).coerceIn(-0.95f, 0.95f)
                zoomStartPan != null -> zoomStartPan.first
                else -> explicitPanX
            }
            val panYVal = when {
                explicitPanY != null && zoomStartPan != null -> (explicitPanY + zoomStartPan.second).coerceIn(-0.95f, 0.95f)
                zoomStartPan != null -> zoomStartPan.second
                else -> explicitPanY
            }
            val panXEndVal = zoomEndPan?.first?.let { ((explicitPanX ?: 0f) + it).coerceIn(-0.95f, 0.95f) }
            val panYEndVal = zoomEndPan?.second?.let { ((explicitPanY ?: 0f) + it).coerceIn(-0.95f, 0.95f) }

            val hasRatio = seg.ratioW != null && seg.ratioH != null
            val hasEdgeCrop = cropLeftVal != null || cropTopVal != null || cropRightVal != null || cropBottomVal != null
            val hasMove = explicitPanX != null || explicitPanY != null
            val hasZoom = seg.zoomStart != null
            val hasRotZ = seg.rotZStart != null
            val hasRotX = seg.rotXStart != null
            val hasRotY = seg.rotYStart != null
            val hasAnyRot = hasRotZ || hasRotX || hasRotY
            val hasAnimRot = seg.rotZEnd != null || seg.rotXEnd != null || seg.rotYEnd != null
            val isTimedZoom = hasZoom && (seg.zoomEnd != null || !range.isAll || scopedOnly)
            val isGlobalStaticZoom = hasZoom && seg.zoomEnd == null && range.isAll && !scopedOnly
            val isTimedRot = hasAnyRot && (hasAnimRot || !range.isAll || scopedOnly)
            val isGlobalStaticRot = hasAnyRot && !hasAnimRot && range.isAll && !scopedOnly

            if (hasRatio || (!range.isAll && (hasEdgeCrop || hasMove)) || (scopedOnly && (hasEdgeCrop || hasMove)) || isTimedZoom || isTimedRot) {
                val spanStart = if (range.isAll && (isTimedZoom || isTimedRot) && !hasRatio) engine.project.trimStartMs else range.startMs
                val spanEnd = if (range.isAll && (isTimedZoom || isTimedRot) && !hasRatio) maxOf(engine.project.trimEndMs, spanStart + 400L) else range.endMs
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
                        panX = if (applyCropInSpan || hasZoomTarget) panXVal else null,
                        panY = if (applyCropInSpan || hasZoomTarget) panYVal else null,
                        panXEnd = if (isTimedZoom) panXEndVal else null,
                        panYEnd = if (isTimedZoom) panYEndVal else null,
                        zoom = if (isTimedZoom) seg.zoomStart else null,
                        zoomEnd = if (isTimedZoom) seg.zoomEnd else null,
                        rotationDegrees = if (isTimedRot) seg.rotZStart else null,
                        rotationEndDegrees = if (isTimedRot) seg.rotZEnd else null,
                        rotationXDegrees = if (isTimedRot) seg.rotXStart else null,
                        rotationXEndDegrees = if (isTimedRot) seg.rotXEnd else null,
                        rotationYDegrees = if (isTimedRot) seg.rotYStart else null,
                        rotationYEndDegrees = if (isTimedRot) seg.rotYEnd else null,
                        easing = seg.easing,
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
                if (hasZoomTarget && panXVal != null && panYVal != null) {
                    updatedGlobalPanX = panXVal
                    updatedGlobalPanY = panYVal
                    shouldUpdateGlobalCrop = true
                }
            }

            if (isGlobalStaticRot) {
                shouldUpdateGlobalRot = true
                clearTimedRotOverrides = true
                seg.rotZStart?.let { updatedGlobalRotZ = ((it % 360f) + 360f) % 360f }
                seg.rotXStart?.let { updatedGlobalRotX = ((it % 360f) + 360f) % 360f }
                seg.rotYStart?.let { updatedGlobalRotY = ((it % 360f) + 360f) % 360f }
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
                val targetNote = seg.zoomTargetLabel?.let { " -> Punkt $it" }.orEmpty()
                if (zEnd != null) {
                    descTokens.add("zoom ${formatZoomNumber(zStart)}x->${formatZoomNumber(zEnd)}x$targetNote")
                } else {
                    descTokens.add("zoom ${formatZoomNumber(zStart)}x$targetNote")
                }
            }
            seg.rotZStart?.let { r ->
                descTokens.add(if (seg.rotZEnd != null) "rot ${r.roundToInt()}->${seg.rotZEnd!!.roundToInt()}°" else "rot ${r.roundToInt()}°")
            }
            seg.rotYStart?.let { r ->
                descTokens.add(if (seg.rotYEnd != null) "rotY ${r.roundToInt()}->${seg.rotYEnd!!.roundToInt()}°" else "rotY ${r.roundToInt()}°")
            }
            seg.rotXStart?.let { r ->
                descTokens.add(if (seg.rotXEnd != null) "rotX ${r.roundToInt()}->${seg.rotXEnd!!.roundToInt()}°" else "rotX ${r.roundToInt()}°")
            }

            val timeLabel = if (range.isAll) "all" else "${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)}"
            summaryParts.add("$timeLabel ${descTokens.joinToString(" ")}")
        }

        val summary = summaryParts.joinToString(", ")

        engine.commitChange("PocketScript Schnitt: $summary") { p ->
            val cleanedExisting = if (clearTimedCropOverrides || clearTimedZoomOverrides || clearTimedRotOverrides) {
                p.timedFormatSpans.mapNotNull { old ->
                    val stripPanFromTimedZoom = clearTimedZoomOverrides && (old.panXEnd != null || old.panYEnd != null)
                    val stripped = old.copy(
                        cropLeft = if (clearTimedCropOverrides && segments.any { it.cutLeftPct != null }) null else old.cropLeft,
                        cropTop = if (clearTimedCropOverrides && segments.any { it.cutTopPct != null }) null else old.cropTop,
                        cropRight = if (clearTimedCropOverrides && segments.any { it.cutRightPct != null }) null else old.cropRight,
                        cropBottom = if (clearTimedCropOverrides && segments.any { it.cutBottomPct != null }) null else old.cropBottom,
                        panX = if ((clearTimedCropOverrides && segments.any { it.moveXPct != null }) || stripPanFromTimedZoom) null else old.panX,
                        panXEnd = if ((clearTimedCropOverrides && segments.any { it.moveXPct != null }) || stripPanFromTimedZoom) null else old.panXEnd,
                        panY = if ((clearTimedCropOverrides && segments.any { it.moveYPct != null }) || stripPanFromTimedZoom) null else old.panY,
                        panYEnd = if ((clearTimedCropOverrides && segments.any { it.moveYPct != null }) || stripPanFromTimedZoom) null else old.panYEnd,
                        zoom = if (clearTimedZoomOverrides) null else old.zoom,
                        zoomEnd = if (clearTimedZoomOverrides) null else old.zoomEnd,
                        rotationDegrees = if (clearTimedRotOverrides && segments.any { it.rotZStart != null }) null else old.rotationDegrees,
                        rotationEndDegrees = if (clearTimedRotOverrides && segments.any { it.rotZStart != null }) null else old.rotationEndDegrees,
                        rotationXDegrees = if (clearTimedRotOverrides && segments.any { it.rotXStart != null }) null else old.rotationXDegrees,
                        rotationXEndDegrees = if (clearTimedRotOverrides && segments.any { it.rotXStart != null }) null else old.rotationXEndDegrees,
                        rotationYDegrees = if (clearTimedRotOverrides && segments.any { it.rotYStart != null }) null else old.rotationYDegrees,
                        rotationYEndDegrees = if (clearTimedRotOverrides && segments.any { it.rotYStart != null }) null else old.rotationYEndDegrees,
                    )
                    if (!stripped.hasAnyEffect()) null else stripped
                }
            } else {
                p.timedFormatSpans
            }

            val mergedSpans = mergeTimedFormatSpans(cleanedExisting, newSpans)
            val nextTransform = if (shouldUpdateGlobalCrop || shouldUpdateGlobalZoom || shouldUpdateGlobalRot) {
                p.transform.copy(
                    cropLeft = updatedGlobalLeft.coerceIn(0f, 0.98f),
                    cropTop = updatedGlobalTop.coerceIn(0f, 0.98f),
                    cropRight = updatedGlobalRight.coerceIn((updatedGlobalLeft + 0.02f).coerceAtMost(1f), 1f),
                    cropBottom = updatedGlobalBottom.coerceIn((updatedGlobalTop + 0.02f).coerceAtMost(1f), 1f),
                    panX = updatedGlobalPanX.coerceIn(-0.95f, 0.95f),
                    panY = updatedGlobalPanY.coerceIn(-0.95f, 0.95f),
                    zoom = updatedGlobalZoom.coerceIn(0.1f, 5.0f),
                    rotationDegrees = updatedGlobalRotZ,
                    rotationXDegrees = updatedGlobalRotX,
                    rotationYDegrees = updatedGlobalRotY,
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
        val nonAllRanges = segments.map { parseTimeRange(it.timeToken, engine) }.filter { !it.isAll }
        if (nonAllRanges.isNotEmpty()) {
            engine.lastTimelineEndMs = nonAllRanges.last().endMs
            engine.clampPlayheadTo(nonAllRanges.first().startMs)
        } else if (newSpans.isNotEmpty()) {
            engine.clampPlayheadTo(newSpans.first().startMs)
        }

        return "Zuschnitt/Format aktiv: $summary"
    }

    private fun executeSetStatement(
        rangeToken: String,
        targetKind: String,
        rawPayload: String,
        engine: ProjectEngine,
    ): String {
        val (range, rangeError) = parseTimeRangeChecked(rangeToken, engine)
        if (range == null) return rangeError!!

        val styled = parseDotModifiers(rawPayload, engine)
        val neededDuration = maxOf(engine.project.sourceDurationMs, range.endMs)

        return when (targetKind) {
            "caption", "sub", "untertitel" -> {
                if (styled.cleanText.isEmpty()) {
                    return "⚠ Syntaxfehler: Nach '$targetKind:' fehlt der Untertitel-Text."
                }
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
                    highlightColorArgb = styled.highlightColorArgb ?: color,
                    backgroundColorArgb = bg,
                    verticalPosition = yPos,
                    fontSizeSp = sizeSp,
                    karaoke = styled.karaoke ?: false,
                    animation = styled.animation ?: OverlayAnimation.NONE,
                )
                engine.commitChange("Caption '${styled.cleanText}' (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
                    val filtered = p.subtitles.filterNot { it.startMs == range.startMs && it.endMs == range.endMs }
                    p.copy(
                        sourceDurationMs = neededDuration,
                        trimEndMs = maxOf(p.trimEndMs, range.endMs),
                        subtitles = (filtered + sub).sortedBy { it.startMs },
                    )
                }
                if (!range.isAll) engine.lastTimelineEndMs = range.endMs
                engine.clampPlayheadTo(range.startMs)
                "Untertitel (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}): \"${styled.cleanText}\""
            }

            "text", "title", "titel" -> {
                if (styled.cleanText.isEmpty()) {
                    return "⚠ Syntaxfehler: Nach '$targetKind:' fehlt der Text-Inhalt."
                }
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
                    rotationDegrees = styled.rotationDegrees ?: 0f,
                    rotationEndDegrees = styled.rotationEndDegrees,
                    rotationXDegrees = styled.rotationXDegrees ?: 0f,
                    rotationXEndDegrees = styled.rotationXEndDegrees,
                    rotationYDegrees = styled.rotationYDegrees ?: 0f,
                    rotationYEndDegrees = styled.rotationYEndDegrees,
                    fontId = font,
                    colorArgb = color,
                    backgroundArgb = bg,
                    startMs = range.startMs,
                    endMs = range.endMs,
                    easing = styled.easing ?: EasingCurve.LINEAR,
                    animation = styled.animation ?: OverlayAnimation.NONE,
                    pulseBpm = styled.pulseBpm,
                )
                engine.commitChange("Text '${styled.cleanText}' (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
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
                if (!range.isAll) engine.lastTimelineEndMs = range.endMs
                engine.clampPlayheadTo(range.startMs)
                "Text-Overlay (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}): \"${styled.cleanText}\""
            }

            "emoji", "sticker" -> {
                if (styled.cleanText.isEmpty()) {
                    return "⚠ Syntaxfehler: Nach '$targetKind:' fehlt das Emoji."
                }
                val scale = styled.scale ?: 1.4f
                val yPos = styled.yPercent ?: 0.40f
                val overlay = CanvasOverlay(
                    id = uniqueId("ps_emo"),
                    kind = OverlayKind.EMOJI,
                    content = styled.cleanText,
                    xPercent = 0.5f,
                    yPercent = yPos,
                    scale = scale,
                    rotationDegrees = styled.rotationDegrees ?: 0f,
                    rotationEndDegrees = styled.rotationEndDegrees,
                    rotationXDegrees = styled.rotationXDegrees ?: 0f,
                    rotationXEndDegrees = styled.rotationXEndDegrees,
                    rotationYDegrees = styled.rotationYDegrees ?: 0f,
                    rotationYEndDegrees = styled.rotationYEndDegrees,
                    startMs = range.startMs,
                    endMs = range.endMs,
                    easing = styled.easing ?: EasingCurve.LINEAR,
                    animation = styled.animation ?: OverlayAnimation.NONE,
                    pulseBpm = styled.pulseBpm,
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
                if (!range.isAll) engine.lastTimelineEndMs = range.endMs
                engine.clampPlayheadTo(range.startMs)
                "Emoji ${styled.cleanText} (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}) gesetzt."
            }

            "zoom" -> {
                executeZoomStatement(rangeToken, rawPayload, engine)
                    ?: "⚠ Ungültiger Zoom '$rawPayload' (erlaubt: 0,1 bis 5,0, 10% bis 500%, 'in', 'out', '0 -> 1' sowie optional Zielpunkt 'at 70% 40%' / 'at top-right')."
            }

            "rotate", "rot", "spin", "turn", "drehen", "flip", "mirror" -> {
                executeRotateStatement(rangeToken, targetKind, rawPayload, engine)
            }

            "filter", "fx" -> {
                val tokens = rawPayload.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
                val query = tokens.firstOrNull().orEmpty()
                val preset = VideoFilterPreset.fromNameOrId(query)
                    ?: return "⚠ Filter '$rawPayload' nicht gefunden. Verfügbar: ${VideoFilterPreset.entries.joinToString { it.id }}"
                val intensity = tokens.getOrNull(1)?.replace(",", ".")?.toFloatOrNull() ?: engine.project.filter.intensity
                if (range.isAll) {
                    engine.setFilterPreset(preset, intensity)
                    "Filter '${preset.displayName}' aktiviert."
                } else {
                    val span = TimedFormatSpan(
                        id = uniqueId("fmt"),
                        startMs = range.startMs,
                        endMs = range.endMs,
                        hasCustomRatio = false,
                        filterPreset = preset,
                    )
                    engine.commitChange("Filter '${preset.displayName}' (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
                        p.copy(
                            sourceDurationMs = neededDuration,
                            trimEndMs = maxOf(p.trimEndMs, range.endMs),
                            timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, listOf(span)),
                        )
                    }
                    engine.lastTimelineEndMs = range.endMs
                    engine.clampPlayheadTo(range.startMs)
                    "Filter '${preset.displayName}' (${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}) gesetzt."
                }
            }

            "blur" -> {
                val lower = rawPayload.trim().lowercase()
                val isOff = lower in listOf("off", "aus", "0")
                val tokens = lower.split(Regex("\\s+|\\.")).filter { it.isNotEmpty() }
                val matchedMode = BlurMode.entries.firstOrNull { m -> tokens.any { it == m.id || m.label.lowercase().contains(it) } }
                val numRadius = tokens.firstNotNullOfOrNull { it.replace(",", ".").toFloatOrNull() }
                if (!isOff && matchedMode == null && numRadius == null) {
                    return "⚠ Syntax: blur [off|full|bokeh|privacy|tiltshift] [Radius 1..45]"
                }
                val mode = if (isOff) BlurMode.OFF else (matchedMode ?: BlurMode.BACKGROUND_BOKEH)
                val radius = if (isOff) 0f else (numRadius ?: 20f).coerceIn(1f, 45f)
                if (range.isAll) {
                    engine.setBlur(mode = mode, radiusPx = radius)
                    if (isOff) "Blur deaktiviert." else "Blur '${mode.label}' (${radius.roundToInt()}px) gesetzt."
                } else {
                    val span = TimedFormatSpan(
                        id = uniqueId("fmt"),
                        startMs = range.startMs,
                        endMs = range.endMs,
                        hasCustomRatio = false,
                        blurRadiusPx = radius,
                        blurMode = mode,
                    )
                    engine.commitChange("Blur '${mode.label}' (${ProjectEngine.formatSec(range.startMs)}-${ProjectEngine.formatSec(range.endMs)})") { p ->
                        p.copy(
                            sourceDurationMs = neededDuration,
                            trimEndMs = maxOf(p.trimEndMs, range.endMs),
                            timedFormatSpans = mergeTimedFormatSpans(p.timedFormatSpans, listOf(span)),
                        )
                    }
                    engine.lastTimelineEndMs = range.endMs
                    engine.clampPlayheadTo(range.startMs)
                    "Blur '${mode.label}' (${radius.roundToInt()}px, ${ProjectEngine.formatSec(range.startMs)}–${ProjectEngine.formatSec(range.endMs)}) gesetzt."
                }
            }

            "speed" -> {
                val s = styled.cleanText.replace(",", ".").removeSuffix("x").toFloatOrNull()
                    ?: return "⚠ Syntax: speed <0.25 - 3.0>, z.B. 'speed 1.5'"
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

            else -> "⚠ Unbekanntes Ziel '$targetKind'."
        }
    }

    /** Parses `#RRGGBB` or `#AARRGGBB` hex colors into ARGB `Long`. */
    private fun parseHexColor(raw: String): Long? {
        val hex = raw.trim().removePrefix("#")
        if (!Regex("""^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$""").matches(hex)) return null
        val value = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 6) (0xFF000000L or value) else value
    }

    /**
     * Zerlegt einen String wie `Pinker Honig.red.serif.big.spinaxis.pop` in den eigentlichen Text `"Pinker Honig"`
     * und seine Punkt-Modifier (inkl. Hex-Farben `.#FF5733`, Karaoke `.karaoke.hlgold`, Animationen `.pop`/`.fade`/`.slideup`/`.typewriter`,
     * Easing `.ease`/`.bounce`, Beat-Puls `.pulse120` und benannten Styles `.@hook`).
     */
    fun parseDotModifiers(raw: String, engine: ProjectEngine, visitedStyles: Set<String> = emptySet()): StyledPayload {
        var working = raw.trim()
        var colorArgb: Long? = null
        var bgColorArgb: Long? = null
        var fontId: String? = null
        var fontSizeSp: Float? = null
        var scale: Float? = null
        var yPercent: Float? = null
        var rotationDegrees: Float? = null
        var rotationEndDegrees: Float? = null
        var rotationXDegrees: Float? = null
        var rotationXEndDegrees: Float? = null
        var rotationYDegrees: Float? = null
        var rotationYEndDegrees: Float? = null
        var highlightColorArgb: Long? = null
        var karaoke: Boolean? = null
        var animation: OverlayAnimation? = null
        var easing: EasingCurve? = null
        var pulseBpm: Float? = null

        val rotZRegex = Regex("""^rot(?:ate)?(-?\d+(?:[.,]\d+)?)$""")
        val rotYRegex = Regex("""^roty(-?\d+(?:[.,]\d+)?)$""")
        val rotXRegex = Regex("""^rotx(-?\d+(?:[.,]\d+)?)$""")
        val spinCountRegex = Regex("""^spin(axis|y|x)?(\d+)x$""")
        val pulseNumRegex = Regex("""^pulse(\d+(?:[.,]\d+)?)(?:bpm)?$""")

        fun mergeStyleFrom(expanded: StyledPayload) {
            if (colorArgb == null) colorArgb = expanded.colorArgb
            if (bgColorArgb == null) bgColorArgb = expanded.bgColorArgb
            if (fontId == null) fontId = expanded.fontId
            if (fontSizeSp == null) fontSizeSp = expanded.fontSizeSp
            if (scale == null) scale = expanded.scale
            if (yPercent == null) yPercent = expanded.yPercent
            if (rotationDegrees == null && rotationEndDegrees == null) {
                rotationDegrees = expanded.rotationDegrees
                rotationEndDegrees = expanded.rotationEndDegrees
            }
            if (rotationXDegrees == null && rotationXEndDegrees == null) {
                rotationXDegrees = expanded.rotationXDegrees
                rotationXEndDegrees = expanded.rotationXEndDegrees
            }
            if (rotationYDegrees == null && rotationYEndDegrees == null) {
                rotationYDegrees = expanded.rotationYDegrees
                rotationYEndDegrees = expanded.rotationYEndDegrees
            }
            if (highlightColorArgb == null) highlightColorArgb = expanded.highlightColorArgb
            if (karaoke == null) karaoke = expanded.karaoke
            if (animation == null) animation = expanded.animation
            if (easing == null) easing = expanded.easing
            if (pulseBpm == null) pulseBpm = expanded.pulseBpm
        }

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
                parseHexColor(candidate) != null -> {
                    colorArgb = parseHexColor(candidate)
                }
                candidate.startsWith("bg#") -> {
                    val parsed = parseHexColor(candidate.removePrefix("bg"))
                    if (parsed != null) {
                        val hexDigits = candidate.removePrefix("bg#")
                        bgColorArgb = if (hexDigits.length == 6) ((parsed and 0x00FFFFFFL) or 0xCC000000L) else parsed
                    } else {
                        matched = false
                    }
                }
                candidate.startsWith("bg") && colorMap.containsKey(candidate.removePrefix("bg")) -> {
                    val base = colorMap[candidate.removePrefix("bg")] ?: 0xFF000000L
                    bgColorArgb = (base and 0x00FFFFFFL) or 0xCC000000L
                }
                candidate == "nobg" || candidate == "transparent" -> {
                    bgColorArgb = 0x00000000L
                }
                candidate == "karaoke" -> {
                    karaoke = true
                }
                candidate.startsWith("hl:") || candidate.startsWith("hl#") ||
                    (candidate.startsWith("hl") && colorMap.containsKey(candidate.removePrefix("hl"))) -> {
                    val hlSpec = candidate.removePrefix("hl:").removePrefix("hl")
                    val resolved = colorMap[hlSpec] ?: parseHexColor(hlSpec)
                    if (resolved != null) {
                        highlightColorArgb = resolved
                        karaoke = true
                    } else {
                        matched = false
                    }
                }
                candidate == "fade" -> animation = OverlayAnimation.FADE
                candidate == "pop" -> animation = OverlayAnimation.POP
                candidate == "slideup" || candidate == "slide_up" -> animation = OverlayAnimation.SLIDE_UP
                candidate == "slidedown" || candidate == "slide_down" -> animation = OverlayAnimation.SLIDE_DOWN
                candidate == "typewriter" || candidate == "tippen" -> animation = OverlayAnimation.TYPEWRITER
                EasingCurve.fromToken(candidate) != null -> easing = EasingCurve.fromToken(candidate)
                candidate == "pulse" || candidate == "puls" -> pulseBpm = engine.project.bpm ?: 120f
                pulseNumRegex.matches(candidate) -> {
                    val bpmVal = pulseNumRegex.matchEntire(candidate)!!.groupValues[1].replace(",", ".").toFloatOrNull()
                    if (bpmVal != null) pulseBpm = bpmVal.coerceIn(20f, 300f) else matched = false
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
                candidate == "spin" -> {
                    rotationDegrees = 0f
                    rotationEndDegrees = 360f
                }
                candidate == "spinaxis" || candidate == "spiny" || candidate == "achse" || candidate == "drehachse" -> {
                    rotationYDegrees = 0f
                    rotationYEndDegrees = 360f
                }
                candidate == "spinx" -> {
                    rotationXDegrees = 0f
                    rotationXEndDegrees = 360f
                }
                candidate == "flip" || candidate == "mirror" -> {
                    rotationYDegrees = 180f
                    rotationYEndDegrees = null
                }
                candidate == "flipx" -> {
                    rotationXDegrees = 180f
                    rotationXEndDegrees = null
                }
                spinCountRegex.matches(candidate) -> {
                    val m = spinCountRegex.matchEntire(candidate)!!
                    val axisPart = m.groupValues[1]
                    val turns = (m.groupValues[2].toIntOrNull() ?: 1).coerceIn(1, 20)
                    when (axisPart) {
                        "axis", "y" -> {
                            rotationYDegrees = 0f
                            rotationYEndDegrees = 360f * turns
                        }
                        "x" -> {
                            rotationXDegrees = 0f
                            rotationXEndDegrees = 360f * turns
                        }
                        else -> {
                            rotationDegrees = 0f
                            rotationEndDegrees = 360f * turns
                        }
                    }
                }
                rotZRegex.matches(candidate) -> {
                    rotationDegrees = rotZRegex.matchEntire(candidate)!!.groupValues[1].replace(",", ".").toFloatOrNull()
                    rotationEndDegrees = null
                }
                rotYRegex.matches(candidate) -> {
                    rotationYDegrees = rotYRegex.matchEntire(candidate)!!.groupValues[1].replace(",", ".").toFloatOrNull()
                    rotationYEndDegrees = null
                }
                rotXRegex.matches(candidate) -> {
                    rotationXDegrees = rotXRegex.matchEntire(candidate)!!.groupValues[1].replace(",", ".").toFloatOrNull()
                    rotationXEndDegrees = null
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
                    // Named reusable style (`.@hook` or `.hook`) with recursion protection
                    val styleName = candidate.removePrefix("@")
                    val namedStyle = if (styleName !in visitedStyles) engine.scriptEnv.style(styleName) else null
                    if (namedStyle != null) {
                        val expanded = parseDotModifiers("x$namedStyle", engine, visitedStyles + styleName)
                        mergeStyleFrom(expanded)
                    } else {
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
            }

            if (matched) {
                working = working.substring(0, lastDot).trim()
            } else {
                break
            }
        }

        val cleanText = if (working.length >= 2 && working.startsWith("\"") && working.endsWith("\"") &&
            !working.substring(1, working.length - 1).contains("\"")
        ) {
            working.substring(1, working.length - 1)
        } else {
            working
        }
        return StyledPayload(
            cleanText = cleanText,
            colorArgb = colorArgb,
            bgColorArgb = bgColorArgb,
            fontId = fontId,
            fontSizeSp = fontSizeSp,
            scale = scale,
            yPercent = yPercent,
            rotationDegrees = rotationDegrees,
            rotationEndDegrees = rotationEndDegrees,
            rotationXDegrees = rotationXDegrees,
            rotationXEndDegrees = rotationXEndDegrees,
            rotationYDegrees = rotationYDegrees,
            rotationYEndDegrees = rotationYEndDegrees,
            highlightColorArgb = highlightColorArgb,
            karaoke = karaoke,
            animation = animation,
            easing = easing,
            pulseBpm = pulseBpm,
        )
    }

    /**
     * Streng geprüfte Zeitbereichs-Analyse: unterstützt Sekunden (`3-8`), Timecodes (`00:10.5-00:24.3`),
     * `last 5`, `end-3..end`, `+3`, `cut1-cut2`, `start-cut1` sowie Prozent-Bereiche (`0-50%`).
     */
    internal fun parseTimeRangeChecked(token: String, engine: ProjectEngine): Pair<ParsedTimeRange?, String?> {
        val clean = token.trim().lowercase()
        val totalMs = engine.project.sourceDurationMs.coerceAtLeast(1_000L)
        if (clean.isEmpty() || clean == "all" || clean == "alles" || clean == "*") {
            return ParsedTimeRange(0L, totalMs, isAll = true) to null
        }

        // `last 5` / `last_5` / `letzte 5`
        val lastMatch = Regex("""^(?:last|letzte[rn]?)(?:\s+|_)($BASE_TIME_ATOM)$""").matchEntire(clean)
        if (lastMatch != null) {
            val durMs = parseSecondsToMs(lastMatch.groupValues[1])
            if (durMs <= 0L) return null to "⚠ Ungültige Dauer in '$clean'."
            val endMs = engine.project.trimEndMs.coerceAtLeast(500L)
            val startMs = (endMs - durMs).coerceAtLeast(engine.project.trimStartMs)
            if (endMs <= startMs) return null to "⚠ Zeitbereich '$clean' ist leer."
            return ParsedTimeRange(startMs, endMs, isAll = false) to null
        }

        // `end-3..end` / `end-3-end` / `end-3`
        val endMinusMatch = Regex("""^(?:end|ende)\s*-\s*($BASE_TIME_ATOM)(?:\s*(?:\.\.|-)\s*(?:end|ende))?$""").matchEntire(clean)
        if (endMinusMatch != null) {
            val durMs = parseSecondsToMs(endMinusMatch.groupValues[1])
            if (durMs <= 0L) return null to "⚠ Ungültige Dauer in '$clean'."
            val endMs = engine.project.trimEndMs.coerceAtLeast(500L)
            val startMs = (endMs - durMs).coerceAtLeast(engine.project.trimStartMs)
            if (endMs <= startMs) return null to "⚠ Zeitbereich '$clean' ist leer."
            return ParsedTimeRange(startMs, endMs, isAll = false) to null
        }

        // `+3` -> sequential range starting after the last timed element
        if (clean.startsWith("+")) {
            val durMs = parseSecondsToMs(clean.removePrefix("+"))
            if (durMs <= 0L) return null to "⚠ Ungültige Dauer '$clean' (z.B. '+3')."
            val startMs = engine.lastTimelineEndMs.coerceAtLeast(engine.project.trimStartMs)
            val endMs = startMs + durMs
            return ParsedTimeRange(startMs, endMs, isAll = false) to null
        }

        if (clean.contains("-")) {
            val parts = clean.split("-", limit = 2)
            val startMs = resolveCutOrTime(parts[0], engine)
                ?: return null to "⚠ Startzeit '${parts[0].trim()}' in '$clean' nicht gefunden."
            val rawEndMs = resolveCutOrTime(parts[1], engine)
                ?: return null to "⚠ Endzeit '${parts[1].trim()}' in '$clean' nicht gefunden."
            if (rawEndMs <= startMs) {
                return null to "⚠ Ungültiger Zeitbereich '$clean': Endzeit muss nach der Startzeit liegen (z.B. '3-8')."
            }
            var endMs = rawEndMs.coerceAtLeast(startMs + 100L)
            if (endMs < totalMs && endMs / 100L == totalMs / 100L) {
                endMs = totalMs
            }
            return ParsedTimeRange(startMs, endMs, isAll = false) to null
        }

        val singleStartMs = resolveCutOrTime(clean, engine) ?: parseSecondsToMs(clean)
        val defaultEndMs = if (singleStartMs + 4_000L <= totalMs) totalMs else singleStartMs + 5_000L
        return ParsedTimeRange(singleStartMs, defaultEndMs, isAll = false) to null
    }

    /**
     * Parst Zeitangaben wie:
     * - `all` → gesamtes Video (`0` bis `sourceDurationMs`)
     * - `10-24,3` oder `8-10` → Start 10s bis Ende 24,3s
     * - `9` → ab Sekunde 9 bis zum Ende des Videos (oder +4s falls Video kürzer ist)
     */
    fun parseTimeRange(token: String, engine: ProjectEngine): ParsedTimeRange {
        val (checked, _) = parseTimeRangeChecked(token, engine)
        if (checked != null) return checked
        val clean = token.trim().lowercase()
        val parts = clean.split("-", limit = 2)
        val startMs = parseSecondsToMs(parts[0])
        return ParsedTimeRange(startMs, startMs + 400L, isAll = false)
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
     * Kopierbarer System-Prompt & Schnitt-Plan für KI-Assistenten (ChatGPT, Claude, Gemini),
     * damit eine KI direkt fehlerfreien, ausführbaren PocketScript-3.0-Code erzeugt.
     */
    val AI_CUTTING_PROMPT: String = """
        Du bist ein Video-Schnitt-Assistent für PocketCraft und schreibst ausschließlich ausführbaren Code in PocketScript 3.0.

        WICHTIGE AUSGABE-REGELN:
        - Gib NUR gültige PocketScript-Befehle aus (eine Anweisung pro Zeile). Erkläre nichts außerhalb von `# Kommentaren` (Inline `# Kommentar` am Zeilenende ist erlaubt).
        - Zeiten: Sekunden (`6,9` / `6.9`), Timecodes (`00:33.3`), relative/symbolische Bereiche (`last 5`, `end-2..end`, `cut1-cut2`, `0-50%`, `+3` für nahtlos anschließend) oder `all`.
        - Der Zeitbereich darf VOR dem Befehl (`6,9-8,9 caption: Text`) ODER direkt HINTER dem Verb (`caption 6,9-8,9: Text`) stehen.

        SYNTAX-REFERENZ (POCKETSCRIPT 3.0):
        1. Format & Zuschnitt (Crop & Move):
           - `all (9:16)` | `all (1:1)` | `all (16:9)` | `0-5 (4:5) 5-15 (16:9)`
           - `0-4 crop 20%` (alle 4 Ränder 20% weg) | `0-4 crop top 20%` | `0-4 crop bottom 20%` | `0-4 crop left 15% right 15%`
           - Kurzformen: `u20%` (unten), `o20%` (oben), `l20%` (links), `r20%` (rechts)
           - `0-4 move down 10%` | `0-4 move up 10%` | `0-4 move left 10% right 5%`
        2. Zoom (0,1..5,0 / 10%..500%) + Zielpunkt + Easing:
           - `zoom 1,8` | `zoom in` | `zoom out` | `0-5,9 zoom 0 -> 1 ease-out`
           - Zielpunkt & Easing (`linear`, `ease`, `ease-in`, `ease-out`, `bounce`, `elastic`, `spring`):
             `0-4 zoom in at 70% 35% ease` | `0-4 zoom 1 -> 2 at top-right bounce` | `last 5 zoom in`
           - NIE Zoom > 5,0 ohne `%` schreiben (nicht `zoom 5,9`, wenn 5,9s Dauer gemeint ist).
        3. 2D/3D-Drehung, Fades, Shake, Ken Burns & Beat-Pulse:
           - `0-4 rotate y 0 -> 360 ease` | `0-4 spin axis` | `0-4 rotate x 0 -> 360` | `2-5 flip` | `rotate text1 15`
           - `0-1,5 fade in` | `end-2..end fade out` | `0-1 fade in white ease` | `4,8-5,1 flash`
           - `5-6 shake` (`light`, `normal`, `strong`) | `0-8 kenburns` (`in`, `out`, `left`, `right`, `up`, `down`)
           - `bpm 120` | `2-6 pulse 120bpm` | `pulse emoji1 120bpm`
        4. Untertitel (inkl. Karaoke), Texte, Emojis & Styles:
           - `6,9-8,9 caption: Text.red.mono.top` | `+3 caption: Weiter.white.karaoke.hlgold.pop`
           - `2-6 text: Titel.gold.big.top.spinaxis.pop` | `4-8 emoji: 🔥.huge.spin.bounce`
           - Hex-Farben: `.#FF5733`, `.bg#112233aa`, `.hl#00E5FF`
           - Animationen & Easing: `.fade`, `.pop`, `.slideup`, `.slidedown`, `.typewriter`, `.ease`, `.bounce`, `.pulse`
           - Wiederverwendbare Styles: `style hook = .gold.big.impact.top.pop` -> `0-3 text: HOOK!.@hook`
           - Nachträglich animieren/ändern: `move text1 20 80 -> 80 20 ease`, `size emoji1 50 -> 200 bounce`, `edit text1: Neu.gold`, `clear all`
        5. Schnitte, Tempo, Filter, Blur & Audio:
           - `set cut 00:33.3` | `delete before cut1` | `delete after cut1` | `delete between cut1 cut2` | `delete 10-20`
           - `all filter cyberpunk` | `2-6 filter bw` | `2-6 blur bokeh 25` | `speed 1,25`
           - `mute` | `2-5 mute` | `volume 150%` | `0-2 audio fade in` | `end-3..end audio fade out`
        6. Programmieren (Variablen, Listen, Schleifen, Funktionen):
           - `if caption red: crop 20%, zoom 1,3 ease` | `if ${'$'}title contains "Jam": ...`
           - `let favs = [🚀, ⭐]` | `${'$'}favs[0]` | `for b in beats(0..8): ${'$'}b-{${'$'}b + 0,25} flash`
           - `for ...` / `while ...` / `repeat ...` unterstützen `break` & `continue`
           - `def calc(x); return ${'$'}x * 2; end` | `clamp(x, lo, hi)`, `lerp(a, b, t)`, `timecode(s)`, `pick(...)`
    """.trimIndent()

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
            title = "1. Eindeutiger Zuschnitt (crop) & Verschieben (move)",
            codeExamples = listOf(
                "all u20%" to "Schneidet unten 20 % weg (eindeutig: 0-4 crop bottom 20% oder 0-4 crop 20% für alle 4 Ränder).",
                "0-4 crop 20%" to "Schneidet in den ersten 4 Sek. an allen 4 Rändern 20 % weg (auch: crop 0-4 20%).",
                "0-4 crop bottom 20%" to "Schneidet unten 20 % weg (auch top, left, right).",
                "all move down 10%" to "Verschiebt das Bild um 10 % nach unten (auch up, left, right – Kurzform: move 10%l / 10%u).",
                "u20% move up 10%" to "Kombiniert Rand-Zuschnitt und Verschiebung in einer Zeile.",
                "90 u90%" to "Ab Sekunde 90 unten 90 % wegschneiden – dort bleibt oben nur ein schmaler 10%-Balken.",
            ),
        ),
        ManualSection(
            title = "1a. Rein- und Rauszoomen inkl. Zielpunkt (0,1x–5,0x / 10%–500%)",
            codeExamples = listOf(
                "zoom in" to "Zoomt animiert von der Mitte her größer (1.0x -> 1.8x über den ganzen Clip).",
                "0-5,9 zoom 0 -> 1" to "Startet ganz klein in der Mitte (10 %) und vergrößert bis 5,9s auf volle Größe (100 %).",
                "0-4 zoom in at 70% 35%" to "Zoomt gezielt auf den Punkt bei 70 % von links, 35 % von oben (auch: at top-right, auf links, to bottom).",
                "zoom 0-4 1 -> 2 at top-right" to "Zeitbereich funktioniert vor ODER direkt hinter 'zoom', inklusive Zielpunkt.",
                "zoom out" to "Zoomt animiert wieder heraus (1.8x -> 1.0x über den ganzen Clip).",
                "0-4 zoom out 0,5" to "Zoomt in den ersten 4 Sekunden von 100 % auf 50 % in die Mitte heraus.",
                "2-6 zoom 1,8" to "Statischer Zoom auf 1.8x nur von Sekunde 2 bis 6 (fürs ganze Video: zoom 1,8).",
                "if caption red: zoom in at top-right" to "Zoomt animiert auf oben-rechts zu, solange der rote Untertitel läuft.",
            ),
        ),
        ManualSection(
            title = "1b. Drehung & 3D-Drehung um die eigene Achse (rotate, spin, flip)",
            codeExamples = listOf(
                "0-4 rotate y 0 -> 360" to "Dreht das Video in 4 Sek. einmal komplett um seine eigene senkrechte Y-Achse.",
                "0-4 spin axis" to "Kurzform für 360°-Drehung um die eigene Achse (auch: spin axis 2x für 2 Umdrehungen).",
                "0-4 rotate x 0 -> 360" to "Dreht das Video um seine eigene waagerechte X-Achse (Vorwärts-/Rückwärts-Salto).",
                "0-4 rotate 0 -> 360" to "2D-Drehung im Bild (Z-Achse); statisch: rotate 90 oder rotate 0 (Reset).",
                "2-6 flip" to "Spiegelt das Video von 2–6s horizontal (180° um Y-Achse; flip x spiegelt vertikal).",
                "spin emoji1 axis" to "Lässt emoji1 um seine eigene Achse drehen (auch: rotate text1 15, flip text1, .spinaxis).",
            ),
        ),
        ManualSection(
            title = "1c. Easing, Fades, Kamera-Effekte & Beat-Sync (3.0)",
            codeExamples = listOf(
                "0-4 zoom 0 -> 1 ease-out" to "Weiches Easing für Zoom & Rotation (ease, ease-in, ease-out, bounce, elastic, spring).",
                "0-1,5 fade in" to "Blendet weich aus Schwarz ein (auch fade out, fade in white, end-2..end fade out).",
                "4,8-5,1 flash" to "Kurzer weißer Lichtblitz auf dem Takt (auch flash gold).",
                "5-6 shake strong" to "Kamera-Wackler beim Drop (light, normal, strong).",
                "0-8 kenburns right" to "Sanfter Kino-Zoom mit Kamerafahrt (in, out, left, right, up, down).",
                "bpm 120" to "Setzt das Projekt-Tempo; danach `2-6 pulse 120bpm`, `for b in beats(0..8): ...` oder `.pulse`.",
            ),
        ),
        ManualSection(
            title = "1d. Audio, Smarte Zeitbereiche & Styles (3.0)",
            codeExamples = listOf(
                "last 5 zoom in" to "Wirkt auf den letzten 5 Sekunden (auch end-3..end, cut1-cut2, 0-50%, +3 für nahtlos anschließend).",
                "0-2 audio fade in" to "Blendet Ton weich ein (auch end-3..end audio fade out, mute, 2-5 mute, volume 150%).",
                "style hook = .gold.big.pop" to "Definiert einen wiederverwendbaren Stil: danach `0-3 text: HOOK!.@hook` (auch global style).",
                "move text1 20 80 -> 80 20 ease" to "Animiert die Position eines Textes/Emojis (auch `size emoji1 50 -> 200 bounce`).",
                "2-6 caption: Jam!.white.karaoke.hlgold" to "Echte Karaoke-Wort-Hervorhebung in Farbe, plus Animationen (.pop, .fade, .slideup, .typewriter) & Hex (.#FF5733).",
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

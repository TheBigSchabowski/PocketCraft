package com.example.pocketcraft.engine

import com.example.pocketcraft.model.CanvasOverlay
import com.example.pocketcraft.model.OverlayKind
import kotlin.math.roundToInt

/**
 * Befehle, die ein bereits vorhandenes Text- oder Emoji-Overlay nachträglich ändern:
 *
 * - `texts` / `emojis` / `overlays` → nummerierte Liste (text1, text2, … nach Startzeit)
 * - `move text1 10%u` · `move text1 up 10` · `move text1 50 20` · `move text1 top` → verschieben
 * - `edit text1: Neuer Text` · `edit text1: Neuer Text.red.big` · `edit text1: .gold` → Inhalt/Stil ändern
 * - `size text1 150` · `size text1 big` · `size text1 +20` → Größe ändern
 *
 * Angesprochen wird ein Element über seine Nummer (`text1`, `emoji2`), über einen Teil seines Inhalts
 * (`text Abend`), als Gruppe (`texts`, `all emojis`) oder – ohne Zusatz (`text`) – das ausgewählte bzw.
 * gerade sichtbare Element.
 */
internal object OverlayCommands {

    private val kindWordRegex = Regex(
        pattern = """^(texts?|texte|titles?|titel|emojis?|stickers?|overlays?)(\d+)?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val allWords = setOf("all", "alle")
    private val listVerbs = setOf("texts", "texte", "emojis", "stickers", "overlays")
    private val moveVerbs = setOf("move", "pos", "position", "verschiebe", "schiebe")
    private val editVerbs = setOf("edit", "change", "ändere", "aendere", "bearbeite")
    private val sizeVerbs = setOf("size", "scale", "größe", "groesse", "grösse")

    private const val MOVE_SYNTAX =
        "Syntax: move text1 10%u (hoch) · 10%l (runter) · 10%r (rechts) · 10%left (links) · " +
            "move text1 50 20 (x % von links, y % von oben) · move text1 top|center|bottom – 'texts' zeigt die Nummern."
    private const val EDIT_SYNTAX =
        "Syntax: edit text1: Neuer Text (mit Stil: edit text1: Neuer Text.red.big, nur Stil: edit text1: .gold) – 'texts' zeigt die Nummern."
    private const val SIZE_SYNTAX =
        "Syntax: size text1 150 (Prozent) · size text1 big|small|huge · size text1 +20 – 'texts' zeigt die Nummern."

    /** Führt [verb] aus, falls es ein Overlay-Befehl ist; sonst `null`. Ergebnis: Meldung + Fehler-Flag. */
    fun tryExecute(verb: String, rawArgs: String, engine: ProjectEngine): Pair<String, Boolean>? {
        val args = rawArgs.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return when {
            verb in listVerbs && args.isEmpty() -> list(verb, engine) to false
            verb in moveVerbs && startsWithReference(args) -> move(args, engine)
            verb in editVerbs -> edit(rawArgs.trim(), engine)
            verb in sizeVerbs -> size(args, engine)
            else -> null
        }
    }

    // ------------------------------------------------------------------ Element ansprechen

    private fun startsWithReference(args: List<String>): Boolean {
        val first = args.firstOrNull() ?: return false
        val word = if (first.lowercase() in allWords) args.getOrNull(1) ?: return false else first
        return kindWordRegex.matches(word.substringBefore(':'))
    }

    private fun kindsOf(word: String): Set<OverlayKind> {
        val w = word.lowercase()
        return when {
            w.startsWith("emoji") || w.startsWith("sticker") -> setOf(OverlayKind.EMOJI)
            w.startsWith("overlay") -> setOf(OverlayKind.TEXT, OverlayKind.EMOJI)
            else -> setOf(OverlayKind.TEXT)
        }
    }

    private fun refName(kinds: Set<OverlayKind>): String = when {
        kinds.size == 2 -> "overlay"
        OverlayKind.EMOJI in kinds -> "emoji"
        else -> "text"
    }

    private fun pluralLabel(kinds: Set<OverlayKind>): String = when {
        kinds.size == 2 -> "Overlays"
        OverlayKind.EMOJI in kinds -> "Emojis"
        else -> "Texte"
    }

    /** Dieselbe Nummerierung wie bei `clear text2`: nach Startzeit sortiert. */
    private fun numbered(kinds: Set<OverlayKind>, engine: ProjectEngine): List<CanvasOverlay> =
        engine.project.overlays.filter { it.kind in kinds }.sortedBy { it.startMs }

    private fun numberedList(kinds: Set<OverlayKind>, sorted: List<CanvasOverlay>): String =
        sorted.mapIndexed { i, ov -> "${refName(kinds)}${i + 1} \"${short(ov.content)}\"" }.joinToString(", ")

    private class Selection(val targets: List<CanvasOverlay>, val error: String? = null)

    /** Löst `[all] text[N] [N | Teil des Inhalts]` in die gemeinten Overlays auf. */
    private fun resolve(refTokens: List<String>, engine: ProjectEngine, syntax: String): Selection {
        var tokens = refTokens
        var all = false
        if (tokens.firstOrNull()?.lowercase() in allWords) {
            all = true
            tokens = tokens.drop(1)
        }
        val match = tokens.firstOrNull()?.let { kindWordRegex.matchEntire(it) } ?: return Selection(emptyList(), syntax)
        val word = match.groupValues[1].lowercase()
        val kinds = kindsOf(word)
        val name = refName(kinds)
        val sorted = numbered(kinds, engine)
        if (sorted.isEmpty()) return Selection(emptyList(), "Keine ${pluralLabel(kinds)} vorhanden.")

        val rest = tokens.drop(1)
        val index = match.groupValues[2].toIntOrNull() ?: rest.singleOrNull()?.toIntOrNull()
        return when {
            index != null -> sorted.getOrNull(index - 1)?.let { Selection(listOf(it)) }
                ?: Selection(emptyList(), "$name$index gibt es nicht (vorhanden: ${numberedList(kinds, sorted)}).")

            rest.isNotEmpty() -> {
                val query = rest.joinToString(" ").trim('"', '\'').lowercase()
                val hits = sorted.filter { it.content.lowercase().contains(query) }
                if (hits.isEmpty()) Selection(emptyList(), "Kein Element mit '$query' gefunden (vorhanden: ${numberedList(kinds, sorted)}).")
                else Selection(hits)
            }

            all || word.endsWith("s") || word == "texte" -> Selection(sorted)

            else -> {
                // Ohne Nummer: das ausgewählte Element, sonst das gerade sichtbare, sonst das einzige.
                val selected = sorted.firstOrNull { it.id == engine.selectedOverlayId }
                val visible = sorted.filter { engine.playheadMs in it.startMs..it.endMs }
                when {
                    sorted.size == 1 -> Selection(sorted)
                    selected != null -> Selection(listOf(selected))
                    visible.size == 1 -> Selection(visible)
                    else -> Selection(emptyList(), "Welches Element? Vorhanden: ${numberedList(kinds, sorted)} – z.B. '${name}1'.")
                }
            }
        }
    }

    /** Zeigt das geänderte Element: auswählen und – falls gerade nicht eingeblendet – hinspringen. */
    private fun focus(engine: ProjectEngine, overlay: CanvasOverlay) {
        engine.selectedOverlayId = overlay.id
        if (engine.playheadMs !in overlay.startMs..overlay.endMs) {
            engine.playheadMs = engine.project.resolvePlayablePosition(overlay.startMs)
        }
    }

    private fun short(content: String): String = if (content.length > 28) content.take(27).trimEnd() + "…" else content

    private fun pct(value: Float): Int = (value * 100f).roundToInt()

    private fun describe(targets: List<CanvasOverlay>): String =
        if (targets.size == 1) "'${short(targets.first().content)}'" else "${targets.size} Elemente"

    // ------------------------------------------------------------------ texts / emojis / overlays

    private fun list(verb: String, engine: ProjectEngine): String {
        val kinds = kindsOf(verb)
        val sorted = numbered(kinds, engine)
        if (sorted.isEmpty()) return "Keine ${pluralLabel(kinds)} vorhanden."
        return sorted.mapIndexed { i, ov ->
            "${refName(kinds)}${i + 1}: \"${ov.content}\" (${ProjectEngine.formatSec(ov.startMs)}–${ProjectEngine.formatSec(ov.endMs)}, " +
                "x${pct(ov.xPercent)}% y${pct(ov.yPercent)}%, Größe ${pct(ov.scale)}%)"
        }.joinToString(" · ")
    }

    // ------------------------------------------------------------------ move

    private const val NUM = """\d+(?:[.,]\d+)?"""
    private val numberRegex = Regex("""^$NUM%?$""")
    private val intRegex = Regex("""^\d+$""")
    private val moveSuffixRegex = Regex(
        pattern = """^($NUM)%?(upper|up|hoch|oben|lower|low|down|runter|unten|right|rechts|left|links|u|o|d|l|r)$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val movePrefixRegex = Regex(
        pattern = """^(upper|up|hoch|oben|lower|low|down|runter|unten|right|rechts|left|links|u|o|d|l|r)($NUM)%?$""",
        option = RegexOption.IGNORE_CASE,
    )
    private val axisRegex = Regex("""^([xy])=?($NUM)%?$""", RegexOption.IGNORE_CASE)
    private val fillerWords = setOf("to", "nach", "auf", "at", "um", "by")
    private val upWords = setOf("up", "upper", "hoch")
    private val downWords = setOf("down", "lower", "low", "runter")
    private val leftWords = setOf("left", "links")
    private val rightWords = setOf("right", "rechts")
    private val topWords = setOf("top", "oben")
    private val bottomWords = setOf("bottom", "unten")
    private val centerWords = setOf("center", "centre", "mitte", "mid", "middle", "zentrum")
    private val positionWords = fillerWords + upWords + downWords + leftWords + rightWords + topWords + bottomWords + centerWords

    private fun number(text: String): Float = text.replace(",", ".").toFloatOrNull() ?: 0f

    private fun isPositionToken(token: String): Boolean {
        val t = token.lowercase()
        return t in positionWords || numberRegex.matches(t) || moveSuffixRegex.matches(t) ||
            movePrefixRegex.matches(t) || axisRegex.matches(t)
    }

    private class Placement {
        var absX: Float? = null
        var absY: Float? = null
        var dx = 0f
        var dy = 0f
        val changesAnything get() = absX != null || absY != null || dx != 0f || dy != 0f

        /** Wie bei `move 10%l`: Zahl **vor** dem Buchstaben → l = lower (runter), u = up (hoch). */
        fun shiftSuffix(direction: String, amount: Float) = when (direction) {
            "u", "up", "upper", "o", "oben", "hoch" -> dy -= amount
            "l", "low", "lower", "d", "down", "unten", "runter" -> dy += amount
            "r", "right", "rechts" -> dx += amount
            else -> dx -= amount // left, links
        }

        /** Wie bei `move l10%`: Buchstabe **vor** der Zahl → l = links, u = unten, o = oben, r = rechts. */
        fun shiftPrefix(direction: String, amount: Float) = when (direction) {
            "o", "oben", "up", "upper", "hoch" -> dy -= amount
            "u", "unten", "d", "down", "low", "lower", "runter" -> dy += amount
            "r", "right", "rechts" -> dx += amount
            else -> dx -= amount // l, left, links
        }
    }

    private fun parsePlacement(tokens: List<String>): Placement? {
        val placement = Placement()
        val numbers = mutableListOf<Float>()
        var i = 0
        while (i < tokens.size) {
            val token = tokens[i].lowercase()
            val next = tokens.getOrNull(i + 1)?.lowercase()
            val suffix = moveSuffixRegex.matchEntire(token)
            val prefix = movePrefixRegex.matchEntire(token)
            val axis = axisRegex.matchEntire(token)
            val isDirection = token in upWords || token in downWords || token in leftWords || token in rightWords
            when {
                token in fillerWords -> Unit
                suffix != null -> placement.shiftSuffix(suffix.groupValues[2].lowercase(), number(suffix.groupValues[1]) / 100f)
                prefix != null -> placement.shiftPrefix(prefix.groupValues[1].lowercase(), number(prefix.groupValues[2]) / 100f)
                axis != null -> {
                    val value = (number(axis.groupValues[2]) / 100f).coerceIn(0f, 1f)
                    if (axis.groupValues[1].lowercase() == "x") placement.absX = value else placement.absY = value
                }
                // `up 10` / `links 5%`: Richtung mit Betrag; `up` allein = ein Schritt von 10 %
                isDirection -> {
                    val amount = if (next != null && numberRegex.matches(next)) {
                        i++
                        number(next.removeSuffix("%")) / 100f
                    } else {
                        0.10f
                    }
                    placement.shiftPrefix(token, amount)
                }
                token in topWords -> placement.absY = 0.16f
                token in bottomWords -> placement.absY = 0.84f
                token in centerWords -> {
                    placement.absX = 0.5f
                    // `top center` / `mitte unten`: nur waagerecht zentrieren, wenn die Höhe schon genannt ist
                    if (tokens.none { it.lowercase() in topWords || it.lowercase() in bottomWords }) placement.absY = 0.5f
                }
                numberRegex.matches(token) -> numbers.add(number(token.removeSuffix("%")))
                else -> return null
            }
            i++
        }
        when (numbers.size) {
            0 -> Unit
            2 -> {
                // `50 20` = Prozent, `0.5 0.2` = Anteil
                val divisor = if (numbers.all { it <= 1f }) 1f else 100f
                placement.absX = (numbers[0] / divisor).coerceIn(0f, 1f)
                placement.absY = (numbers[1] / divisor).coerceIn(0f, 1f)
            }
            else -> return null
        }
        return placement.takeIf { it.changesAnything }
    }

    private fun move(args: List<String>, engine: ProjectEngine): Pair<String, Boolean> {
        val head = if (args.first().lowercase() in allWords) 2 else 1
        val after = args.drop(head)
        val kindMatch = kindWordRegex.matchEntire(args[head - 1])
        val count = kindMatch?.let { numbered(kindsOf(it.groupValues[1]), engine).size } ?: 0
        val spacedIndex = kindMatch?.groupValues?.get(2).isNullOrEmpty() &&
            after.size >= 2 &&
            intRegex.matches(after[0]) &&
            (after[0].toIntOrNull() ?: 0) in 1..count &&
            // `move text 50 20` ist eine Position, `move text 1 50 20` meint text1
            !(after.size == 2 && after.all { numberRegex.matches(it) })
        var split = head
        if (spacedIndex) {
            split = head + 1
        } else {
            while (split < args.size && !isPositionToken(args[split])) split++
        }

        val selection = resolve(args.take(split), engine, MOVE_SYNTAX)
        selection.error?.let { return it to true }
        val placement = parsePlacement(args.drop(split)) ?: return MOVE_SYNTAX to true

        val ids = selection.targets.map { it.id }.toSet()
        engine.commitChange("${describe(selection.targets)} verschoben") { p ->
            p.copy(
                overlays = p.overlays.map { ov ->
                    if (ov.id !in ids) {
                        ov
                    } else {
                        ov.copy(
                            xPercent = ((placement.absX ?: ov.xPercent) + placement.dx).coerceIn(0.05f, 0.95f),
                            yPercent = ((placement.absY ?: ov.yPercent) + placement.dy).coerceIn(0.05f, 0.95f),
                        )
                    }
                },
            )
        }
        val moved = engine.project.overlays.first { it.id == selection.targets.first().id }
        focus(engine, moved)
        val where = "x ${pct(moved.xPercent)} % von links, y ${pct(moved.yPercent)} % von oben"
        return (if (selection.targets.size == 1) "${describe(selection.targets)} verschoben: $where." else "${selection.targets.size} Elemente verschoben.") to false
    }

    // ------------------------------------------------------------------ edit

    private fun edit(rawArgs: String, engine: ProjectEngine): Pair<String, Boolean> {
        val tokens = rawArgs.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (!startsWithReference(tokens)) return EDIT_SYNTAX to true

        // `edit text1 Neuer Text` und `edit text1: Neuer Text` – mit Nummer ist klar, wo der neue Text beginnt.
        // Sonst trennt der erste Doppelpunkt: `edit text Abend: Neuer Text`.
        val head = if (tokens.first().lowercase() in allWords) 2 else 1
        val hasNumber = kindWordRegex.matchEntire(tokens[head - 1])?.groupValues?.get(2)?.isNotEmpty() == true
        val refTokens: List<String>
        val payload: String
        if (hasNumber) {
            refTokens = tokens.take(head)
            var rest = rawArgs.trim()
            repeat(head) { rest = rest.dropWhile { !it.isWhitespace() }.trimStart() }
            payload = rest.removePrefix(":").trim()
        } else {
            val colon = rawArgs.indexOf(':')
            if (colon < 0) return EDIT_SYNTAX to true
            refTokens = rawArgs.substring(0, colon).split(Regex("\\s+")).filter { it.isNotEmpty() }
            payload = rawArgs.substring(colon + 1).trim()
        }
        if (payload.isEmpty()) return EDIT_SYNTAX to true

        val selection = resolve(refTokens, engine, EDIT_SYNTAX)
        selection.error?.let { return it to true }

        // `.gold.big` ohne Text davor ändert nur den Stil
        val styleProbe = if (payload.startsWith(".")) PocketScriptParser.parseDotModifiers("x$payload", engine) else null
        val styleOnly = styleProbe?.cleanText == "x"
        val styled = if (styleProbe != null && styleOnly) styleProbe else PocketScriptParser.parseDotModifiers(payload, engine)
        if (!styleOnly && styled.cleanText.isEmpty()) return EDIT_SYNTAX to true

        val ids = selection.targets.map { it.id }.toSet()
        engine.commitChange("${describe(selection.targets)} bearbeitet") { p ->
            p.copy(
                overlays = p.overlays.map { ov ->
                    if (ov.id !in ids) {
                        ov
                    } else {
                        val isText = ov.kind == OverlayKind.TEXT
                        ov.copy(
                            content = if (styleOnly) ov.content else styled.cleanText,
                            colorArgb = styled.colorArgb?.takeIf { isText } ?: ov.colorArgb,
                            backgroundArgb = styled.bgColorArgb?.takeIf { isText } ?: ov.backgroundArgb,
                            fontId = styled.fontId?.takeIf { isText } ?: ov.fontId,
                            scale = styled.scale ?: ov.scale,
                            yPercent = styled.yPercent ?: ov.yPercent,
                        )
                    }
                },
            )
        }
        val edited = engine.project.overlays.first { it.id == selection.targets.first().id }
        focus(engine, edited)
        return when {
            selection.targets.size > 1 -> "${selection.targets.size} Elemente geändert."
            styleOnly -> "Stil von '${short(edited.content)}' geändert."
            else -> "Text geändert: \"${edited.content}\""
        } to false
    }

    // ------------------------------------------------------------------ size

    private val sizeValueRegex = Regex("""^([+-])?($NUM)(%|x)?$""", RegexOption.IGNORE_CASE)

    private fun size(args: List<String>, engine: ProjectEngine): Pair<String, Boolean> {
        if (!startsWithReference(args) || args.size < 2) return SIZE_SYNTAX to true
        val valueToken = args.last().lowercase()
        val named = when (valueToken) {
            "small", "klein", "sm" -> 0.78f
            "normal", "mittel", "medium" -> 1.0f
            "big", "gross", "groß", "large", "xl" -> 1.45f
            "huge", "riesig", "xxl" -> 1.85f
            else -> null
        }
        val valueMatch = sizeValueRegex.matchEntire(valueToken)
        if (named == null && valueMatch == null) return SIZE_SYNTAX to true

        val selection = resolve(args.dropLast(1), engine, SIZE_SYNTAX)
        selection.error?.let { return it to true }

        fun newScale(current: Float): Float {
            if (named != null) return named
            val sign = valueMatch!!.groupValues[1]
            val value = number(valueMatch.groupValues[2])
            val unit = valueMatch.groupValues[3].lowercase()
            // `150` / `150%` = Prozent, `1.5` / `1.5x` = Faktor
            val asFactor = if (unit == "x" || (unit.isEmpty() && value <= 5f)) value else value / 100f
            return when (sign) {
                "+" -> current + asFactor
                "-" -> current - asFactor
                else -> asFactor
            }
        }

        val ids = selection.targets.map { it.id }.toSet()
        engine.commitChange("Größe von ${describe(selection.targets)} geändert") { p ->
            p.copy(overlays = p.overlays.map { ov -> if (ov.id in ids) ov.copy(scale = newScale(ov.scale).coerceIn(0.4f, 3.5f)) else ov })
        }
        val resized = engine.project.overlays.first { it.id == selection.targets.first().id }
        focus(engine, resized)
        return (if (selection.targets.size == 1) "Größe von ${describe(selection.targets)}: ${pct(resized.scale)} %." else "Größe von ${selection.targets.size} Elementen geändert.") to false
    }
}

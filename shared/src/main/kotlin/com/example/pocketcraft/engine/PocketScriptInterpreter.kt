package com.example.pocketcraft.engine

import com.example.pocketcraft.model.OverlayKind

/**
 * Variablen und selbst definierte Funktionen eines PocketScript-Laufs.
 * Gehört zur [ProjectEngine] und bleibt zwischen einzelnen Befehlen erhalten, sodass man in der
 * Befehlszeile erst `let x = 3` und danach `zoom $x` tippen kann.
 */
class ScriptEnvironment {
    /** Variablen des geöffneten Projekts (`let x = 5`) – werden mit dem Projekt gespeichert. */
    val variables = linkedMapOf<String, Any>()

    /** Globale Variablen (`global x = 5`) – gelten in allen Projekten. */
    val globals = linkedMapOf<String, Any>()

    /** Funktionen des geöffneten Projekts (`def name(a): …`) – werden mit dem Projekt gespeichert. */
    val functions = linkedMapOf<String, ScriptFunction>()

    /** Globale Funktionen (`global def name(a): …`) – gelten in allen Projekten. */
    val globalFunctions = linkedMapOf<String, ScriptFunction>()

    /** Funktion mit diesem Namen: die des Projekts hat Vorrang vor einer globalen. */
    fun function(name: String): ScriptFunction? = name.lowercase().let { functions[it] ?: globalFunctions[it] }

    companion object {
        /** Gespeicherter Text zurück in einen Wert: Zahlen werden wieder Zahlen. */
        fun decode(text: String): Any = ScriptExpression.parseNumber(text) ?: text
    }

    fun clear() {
        variables.clear()
        functions.clear()
    }
}

/** Eine mit `def` definierte Funktion: entweder ein Befehls-Block oder eine Rechenformel. */
class ScriptFunction(
    val name: String,
    val params: List<String>,
    internal val body: List<PocketScriptInterpreter.Stmt>,
    val formula: String?,
    /** Die Definition als Text (ohne `global`), damit sie gespeichert und wieder geladen werden kann. */
    val source: String = "",
)

/**
 * Programmier-Konstrukte für **PocketScript** – alles, was über einen einzelnen Schnittbefehl hinausgeht:
 *
 * ```
 * let abstand = 4                      # Variable dieses Projekts (wird mit ihm gespeichert)
 * global favoriten = [🚀, ⭐, 🎉]           # globale Variable: gilt in allen Projekten
 * for t in 0..12 step $abstand         # Zählschleife (Block bis `end`)
 *   $t-{$t + 2} set emoji: 🔥          # $name und {Rechnung} werden in Befehle eingesetzt
 * end
 * if $duration > 30: delete after 00:30   # Bedingung mit Vergleich (einzeilig)
 * if $captions == 0                    # … oder als Block mit else
 *   autocaption
 * else
 *   print Es gibt $captions Untertitel
 * end
 * def titel(text, farbe)               # eigene Funktion (global def …: in allen Projekten)
 *   0-3 set text: $text.$farbe.big.top
 * end
 * titel(Mein Urlaub, gold)
 * for c in captions: $c_start-$c_end u15%   # über alle Untertitel laufen
 * repeat 3: rotate 90
 * ```
 *
 * Normale Zeilen (alles andere) gehen nach dem Einsetzen der Variablen unverändert an die
 * bestehenden Befehle (`zoom 2`, `all u20%`, `8-10 set caption: …`, `if caption red: …`).
 */
object PocketScriptInterpreter {

    private const val MAX_LOOP_ITERATIONS = 500
    private const val MAX_STEPS = 5_000
    private const val MAX_CALL_DEPTH = 30

    // ---------------------------------------------------------------- Programmstruktur

    sealed class Stmt {
        abstract val line: Int

        data class Command(override val line: Int, val text: String) : Stmt()
        data class Assign(
            override val line: Int,
            val name: String,
            val op: String,
            val valueText: String,
            /** Mit `let`/`$` ausdrücklich angelegt (sonst darf `x = 1` auch eine globale Variable ändern). */
            val declared: Boolean = true,
            /** `global x = 1`: gilt in allen Projekten. */
            val global: Boolean = false,
        ) : Stmt()
        data class Print(override val line: Int, val text: String) : Stmt()
        data class If(override val line: Int, val branches: List<Branch>, val elseBody: List<Stmt>?) : Stmt()
        data class While(override val line: Int, val keyword: String, val condition: String, val body: List<Stmt>) : Stmt()
        data class For(override val line: Int, val variable: String, val source: String, val body: List<Stmt>) : Stmt()
        data class Repeat(override val line: Int, val count: String, val body: List<Stmt>) : Stmt()
        data class Def(override val line: Int, val function: ScriptFunction, val global: Boolean = false) : Stmt()
        data class Call(override val line: Int, val name: String, val args: String) : Stmt()
        data class Vars(override val line: Int, val clear: Boolean = false, val globals: Boolean = false) : Stmt()
        data class Unset(override val line: Int, val name: String, val global: Boolean = false) : Stmt()
    }

    data class Branch(val keyword: String, val condition: String, val body: List<Stmt>)

    private const val NAME = """[\p{L}_][\p{L}\p{N}_]*"""
    private val assignRegex = Regex(
        """^(?:(?:let|var|sei)\s+)?(?:(global)\s+)?(?:(?:let|var|sei)\s+)?\$?($NAME)\s*([+\-*/]?)=(?!=)\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val printRegex = Regex("""^(?:print|echo|sag|zeige)(?:\s+(.*))?$""", RegexOption.IGNORE_CASE)
    private val ifRegex = Regex("""^(if|wenn|elif|else\s+if|sonst\s+wenn)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val whileRegex = Regex("""^(while|solange)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val forRegex = Regex("""^(?:for|für|fuer)\s+\$?($NAME)\s+in\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val repeatRegex = Regex("""^(?:repeat|wiederhole)\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val defRegex = Regex(
        """^(?:(global)\s+)?(?:def|fn|func|function|funktion)\s+($NAME)\s*\(([^)]*)\)\s*(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val callRegex = Regex("""^($NAME)\((.*)\)$""")
    private val unsetRegex = Regex("""^(?:unset|vergiss)\s+(?:(global)\s+)?\$?($NAME)$""", RegexOption.IGNORE_CASE)
    private val clearGlobalsRegex = Regex(
        """^(?:clear|lösche|loesche|vergiss)\s+(?:all\s+|alle\s+)?(?:globals|global\s+vars|globale\s+variablen)$""",
        RegexOption.IGNORE_CASE,
    )
    private val clearVarsRegex = Regex("""^(?:clear|lösche|loesche|vergiss)\s+(?:all\s+|alle\s+)?(?:vars|variablen)$""", RegexOption.IGNORE_CASE)
    private val endWords = setOf("end", "ende", "}")
    private val elseWords = setOf("else", "sonst", "else:", "sonst:")

    /** Sprach-Schlüsselwörter: nie als Variablen-, Parameter- oder Funktionsname erlaubt. */
    private val keywordNames = setOf(
        "if", "wenn", "elif", "while", "solange", "for", "repeat", "def", "end", "ende", "else", "sonst", "let", "var", "global",
        "print", "true", "false", "and", "or", "not", "und", "oder", "nicht", "in", "step",
    )

    /** Befehlswörter: `zoom = 2` ohne `let` bleibt ein (fehlerhafter) Befehl statt still eine Variable anzulegen. */
    private val commandWords = setOf(
        "all", "alles", "set", "cut", "caption", "sub", "untertitel", "text", "title", "titel", "emoji", "sticker",
        "filter", "fx", "blur", "zoom", "speed", "crop", "trim", "move", "pan", "save", "open", "new", "undo", "redo",
        "delete", "clear", "export", "font", "rotate", "split", "reset",
    )

    /** Eingebaute, nur lesbare Werte über das aktuelle Projekt (Zeiten in Sekunden). */
    private val builtinVariables: Map<String, (ProjectEngine) -> Any> = mapOf(
        "duration" to { e -> e.project.sourceDurationMs / 1000.0 },
        "dauer" to { e -> e.project.sourceDurationMs / 1000.0 },
        "trimstart" to { e -> e.project.trimStartMs / 1000.0 },
        "trimend" to { e -> e.project.trimEndMs / 1000.0 },
        "length" to { e -> e.project.playableDurationMs / 1000.0 },
        "playhead" to { e -> e.playheadMs / 1000.0 },
        "zoom" to { e -> e.project.transform.zoom.toDouble() },
        "speed" to { e -> e.project.playbackSpeed.toDouble() },
        "captions" to { e -> e.project.subtitles.size.toDouble() },
        "texts" to { e -> e.project.overlays.count { it.kind == OverlayKind.TEXT }.toDouble() },
        "emojis" to { e -> e.project.overlays.count { it.kind == OverlayKind.EMOJI }.toDouble() },
        "cuts" to { e -> e.project.cutMarkers.size.toDouble() },
        "title" to { e -> e.project.title },
    )

    val builtinVariableNames: Set<String> get() = builtinVariables.keys

    // ---------------------------------------------------------------- Einstieg

    /** Schutz vor Endlos-Rekursion: Zeile, die bereits fertig eingesetzt an die Befehle weitergereicht wird. */
    private var passthroughLine: String? = null

    /**
     * Führt [input] aus, falls es ein Programm ist (mehrere Zeilen oder ein Programmier-Konstrukt).
     * Gibt `null` zurück, wenn es ein gewöhnlicher Einzelbefehl ist, den der Aufrufer selbst behandelt.
     */
    fun tryRun(input: String, engine: ProjectEngine): String? {
        if (input == passthroughLine) return null
        val hasSeparators = input.contains("\n") || input.contains(";")
        val lines = input.split(Regex("[\r\n;]+"))
            .map { it.trim().replace(Regex("""^\d+\.\s+"""), "") }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("//") }
        if (lines.isEmpty()) return if (hasSeparators) "Keine ausführbaren Zeilen." else null

        val run = Run(engine)
        val program = try {
            parseBlock(LineReader(lines), topLevel = true, run)
        } catch (e: ScriptError) {
            return "⚠ ${e.message}"
        }

        val plainOnly = program.all { it is Stmt.Command && !run.needsInterpolation(it.text) }
        if (plainOnly && program.size == 1) return null
        if (plainOnly) {
            // Reine Befehlslisten wie bisher: erst die Setup-Zeilen, danach die `if`/`while`-Regeln,
            // damit eine Regel auch dann greift, wenn sie vor dem passenden `set caption:` steht.
            val (rules, setup) = lines.partition { PocketScriptParser.isLegacyRuleLine(it) }
            val results = (setup + rules).map { engine.executeCommand(it) }
            return "${lines.size} Zeilen ausgeführt: " + results.joinToString(" · ")
        }

        try {
            run.execute(program)
        } catch (e: ScriptError) {
            return run.failure(e)
        }
        return run.summary()
    }

    /** Liest eine gespeicherte Funktions-Definition wieder ein (`null`, falls sie nicht mehr lesbar ist). */
    fun parseFunction(source: String, engine: ProjectEngine): ScriptFunction? = try {
        val lines = source.split(Regex("[\r\n;]+")).map { it.trim() }.filter { it.isNotEmpty() }
        (parseBlock(LineReader(lines), topLevel = true, Run(engine)).firstOrNull() as? Stmt.Def)?.function
    } catch (_: ScriptError) {
        null
    }

    // ---------------------------------------------------------------- Parser (Zeilen -> Anweisungen)

    private class LineReader(private val lines: List<String>) {
        var index = 0
        fun slice(from: Int, to: Int) = lines.subList(from, to)
        fun hasNext() = index < lines.size
        fun peek() = lines[index]
        fun next() = lines[index++]
        val lineNumber get() = index
    }

    /** Position des ersten `:` außerhalb von Klammern und Anführungszeichen (trennt Kopf und Einzeiler-Rumpf). */
    private fun topLevelColon(text: String): Int {
        var depth = 0
        var quote: Char? = null
        for ((i, c) in text.withIndex()) {
            when {
                quote != null -> if (c == quote) quote = null
                c == '"' -> quote = c
                c == '(' || c == '[' || c == '{' -> depth++
                c == ')' || c == ']' || c == '}' -> depth--
                c == ':' && depth <= 0 -> {
                    // `00:48` ist eine Zeitangabe, kein Trenner
                    val digitBefore = i > 0 && text[i - 1].isDigit()
                    val digitAfter = i + 1 < text.length && text[i + 1].isDigit()
                    if (!(digitBefore && digitAfter)) return i
                }
            }
        }
        return -1
    }

    /** Teilt `kopf: rumpf` – der Rumpf ist leer, wenn danach ein Block bis `end` folgt. */
    private fun splitHeader(rest: String): Pair<String, String> {
        val colon = topLevelColon(rest)
        return if (colon < 0) rest.trim() to "" else rest.substring(0, colon).trim() to rest.substring(colon + 1).trim()
    }

    private fun parseBlock(reader: LineReader, topLevel: Boolean, run: Run): List<Stmt> {
        val out = mutableListOf<Stmt>()
        while (reader.hasNext()) {
            val lower = reader.peek().lowercase()
            if (lower in endWords || lower in elseWords || lower.startsWith("elif ") ||
                lower.startsWith("else if ") || lower.startsWith("sonst wenn ")
            ) {
                if (topLevel) throw ScriptError("Zeile ${reader.lineNumber + 1}: '${reader.peek()}' ohne passenden Block-Anfang.")
                return out
            }
            out.add(parseStatement(reader, run))
        }
        if (!topLevel) throw ScriptError("Block wurde nicht mit 'end' abgeschlossen.")
        return out
    }

    private fun body(reader: LineReader, inline: String, run: Run, startLine: Int): List<Stmt> {
        if (inline.isNotEmpty()) return listOf(parseStatement(LineReader(listOf(inline)), run, startLine))
        val block = parseBlock(reader, topLevel = false, run)
        return block
    }

    private fun expectEnd(reader: LineReader, what: String, startLine: Int) {
        if (!reader.hasNext() || reader.next().lowercase() !in endWords) {
            throw ScriptError("Zeile $startLine: '$what' wurde nicht mit 'end' abgeschlossen.")
        }
    }

    private fun parseStatement(reader: LineReader, run: Run, fixedLine: Int? = null): Stmt {
        val text = reader.next()
        val line = fixedLine ?: reader.lineNumber

        if (text.equals("vars", ignoreCase = true) || text.equals("variablen", ignoreCase = true)) return Stmt.Vars(line)
        if (clearVarsRegex.matches(text)) return Stmt.Vars(line, clear = true)
        if (clearGlobalsRegex.matches(text)) return Stmt.Vars(line, clear = true, globals = true)
        unsetRegex.matchEntire(text)?.let { return Stmt.Unset(line, it.groupValues[2], global = it.groupValues[1].isNotEmpty()) }
        printRegex.matchEntire(text)?.let { return Stmt.Print(line, it.groupValues[1]) }

        defRegex.matchEntire(text)?.let { m ->
            val firstLine = reader.index - 1
            val global = m.groupValues[1].isNotEmpty()
            val name = m.groupValues[2]
            val params = m.groupValues[3].split(",").map { it.trim().removePrefix("$") }.filter { it.isNotEmpty() }
            val tail = m.groupValues[4].trim()
            checkName(name, line)
            params.forEach { checkName(it, line) }
            val formula = if (tail.startsWith("=")) tail.removePrefix("=").trim() else null
            val stmts = if (formula != null) {
                emptyList()
            } else {
                val inline = tail.removePrefix(":").trim()
                body(reader, inline, run, line).also { if (inline.isEmpty()) expectEnd(reader, "def $name", line) }
            }
            // Alle Zeilen der Definition (bei Blöcken bis `end`) als Text merken – ohne das `global` davor
            val source = reader.slice(firstLine, reader.index).joinToString("\n")
                .replaceFirst(Regex("""^global\s+""", RegexOption.IGNORE_CASE), "")
            run.declaredFunctions.add(name.lowercase())
            return Stmt.Def(line, ScriptFunction(name, params, stmts, formula, source), global)
        }

        forRegex.matchEntire(text)?.let { m ->
            val (source, inline) = splitHeader(m.groupValues[2])
            checkName(m.groupValues[1], line)
            val stmts = body(reader, inline, run, line)
            if (inline.isEmpty()) expectEnd(reader, "for", line)
            return Stmt.For(line, m.groupValues[1], source, stmts)
        }

        repeatRegex.matchEntire(text)?.let { m ->
            val (count, inline) = splitHeader(m.groupValues[1])
            val stmts = body(reader, inline, run, line)
            if (inline.isEmpty()) expectEnd(reader, "repeat", line)
            return Stmt.Repeat(line, count.replace(Regex("""(?<=\d)\s*(?:x|mal)$""", RegexOption.IGNORE_CASE), "").trim(), stmts)
        }

        ifRegex.matchEntire(text)?.let { m ->
            val keyword = m.groupValues[1].lowercase()
            if (keyword != "if" && keyword != "wenn") throw ScriptError("Zeile $line: '$keyword' ohne vorheriges 'if'.")
            val (condition, inline) = splitHeader(m.groupValues[2])
            if (inline.isNotEmpty()) {
                // Einzeiler mit klassischer Bedingung (`if caption red: u20%`) bleibt ein normaler Befehl
                if (PocketScriptParser.isLegacyCondition(condition)) return Stmt.Command(line, text)
                return Stmt.If(line, listOf(Branch(keyword, condition, body(reader, inline, run, line))), null)
            }
            val branches = mutableListOf(Branch(keyword, condition, parseBlock(reader, topLevel = false, run)))
            var elseBody: List<Stmt>? = null
            while (true) {
                if (!reader.hasNext()) throw ScriptError("Zeile $line: 'if' wurde nicht mit 'end' abgeschlossen.")
                val next = reader.next()
                val nextLower = next.lowercase()
                when {
                    nextLower in endWords -> break
                    nextLower in elseWords -> {
                        elseBody = parseBlock(reader, topLevel = false, run)
                        expectEnd(reader, "if", line)
                        break
                    }
                    else -> {
                        val em = ifRegex.matchEntire(next) ?: throw ScriptError("Zeile $line: Unerwartetes '$next'.")
                        val elifCondition = em.groupValues[2].trim().removeSuffix(":").trim()
                        branches.add(Branch(keyword, elifCondition, parseBlock(reader, topLevel = false, run)))
                    }
                }
            }
            return Stmt.If(line, branches, elseBody)
        }

        whileRegex.matchEntire(text)?.let { m ->
            val keyword = m.groupValues[1].lowercase()
            val (condition, inline) = splitHeader(m.groupValues[2])
            if (inline.isNotEmpty() && PocketScriptParser.isLegacyCondition(condition)) return Stmt.Command(line, text)
            val stmts = body(reader, inline, run, line)
            if (inline.isEmpty()) expectEnd(reader, "while", line)
            return Stmt.While(line, keyword, condition, stmts)
        }

        assignRegex.matchEntire(text)?.let { m ->
            val global = m.groupValues[1].isNotEmpty()
            val declared = global || text.startsWith("$") ||
                Regex("""^(let|var|sei)\s""", RegexOption.IGNORE_CASE).containsMatchIn(text)
            val name = m.groupValues[2]
            // `caption = x` o.ä. ohne let/$ ist kein Zuweisungsversuch, sondern ein (unbekannter) Befehl
            if (declared || name.lowercase() !in commandWords) {
                checkName(name, line)
                return Stmt.Assign(line, name, m.groupValues[3], m.groupValues[4].trim(), declared, global)
            }
        }

        callRegex.matchEntire(text)?.let { m ->
            val name = m.groupValues[1]
            if (name.lowercase() in run.declaredFunctions || run.engine.scriptEnv.function(name) != null) {
                return Stmt.Call(line, name, m.groupValues[2])
            }
        }

        return Stmt.Command(line, text)
    }

    private fun checkName(name: String, line: Int) {
        val lower = name.lowercase()
        if (lower in keywordNames) throw ScriptError("Zeile $line: '$name' ist ein Schlüsselwort und kann kein Name sein.")
        if (lower in builtinVariables) throw ScriptError("Zeile $line: '$name' ist ein eingebauter Wert und kann nicht überschrieben werden.")
    }

    // ---------------------------------------------------------------- Ausführung

    private class Run(val engine: ProjectEngine) : ScriptExpression.Scope {
        val env = engine.scriptEnv
        val messages = mutableListOf<String>()
        val declaredFunctions = mutableSetOf<String>()
        private val locals = ArrayDeque<MutableMap<String, Any>>()
        private var steps = 0
        private var executedCommands = 0

        // --- Variablen

        override fun variable(name: String): Any? {
            val key = name.lowercase()
            for (i in locals.indices.reversed()) locals[i][key]?.let { return it }
            // Reihenfolge: Funktions-Parameter, Projekt-Variable, globale Variable, eingebauter Wert
            return env.variables[key] ?: env.globals[key] ?: builtinVariables[key]?.invoke(engine)
        }

        /** Legt eine Variable an oder ändert sie; gibt zurück, ob dabei eine globale Variable geschrieben wurde. */
        private fun assign(name: String, value: Any, declared: Boolean = true, global: Boolean = false): Boolean {
            val key = name.lowercase()
            val frame = locals.lastOrNull()
            // `x = 1` / `x += 1` ohne let ändert eine bestehende globale Variable, statt sie im Projekt zu überdecken
            val updatesGlobal = global || (!declared && env.globals.containsKey(key) &&
                !env.variables.containsKey(key) && frame?.containsKey(key) != true)
            when {
                updatesGlobal -> {
                    env.globals[key] = value
                    engine.persistGlobals()
                }
                // In einer Funktion: bestehende Projekt-Variable ändern, sonst lokal anlegen
                frame != null && (frame.containsKey(key) || !env.variables.containsKey(key)) -> frame[key] = value
                else -> env.variables[key] = value
            }
            return updatesGlobal
        }

        override fun callFunction(name: String, args: List<Any>): Any? {
            val fn = env.function(name) ?: return null
            val formula = fn.formula
                ?: throw ScriptError("'${fn.name}' ist ein Befehls-Block und liefert keinen Wert (nutze def ${fn.name}(…) = Formel).")
            return withFrame(fn, args) { ScriptExpression.evaluate(formula, this) }
        }

        private fun <T> withFrame(fn: ScriptFunction, args: List<Any>, block: () -> T): T {
            if (args.size != fn.params.size) {
                throw ScriptError("${fn.name}(${fn.params.joinToString(", ")}) erwartet ${fn.params.size} Argument(e), bekam ${args.size}.")
            }
            if (locals.size >= MAX_CALL_DEPTH) throw ScriptError("Zu tiefe Verschachtelung von Funktionsaufrufen (${fn.name}).")
            locals.addLast(fn.params.map { it.lowercase() }.zip(args).toMap().toMutableMap())
            try {
                return block()
            } finally {
                locals.removeLast()
            }
        }

        // --- Einsetzen von $name, ${ausdruck} und {ausdruck}

        private val placeholder = Regex("""\$\{([^{}]+)\}|\{([^{}]+)\}|\$($NAME)""")

        fun needsInterpolation(text: String): Boolean = try {
            (text.contains('$') || text.contains('{')) && interpolate(text) != text
        } catch (_: ScriptError) {
            true // Rechenfehler: beim Ausführen mit Meldung abbrechen
        }

        fun interpolate(text: String): String = placeholder.replace(text) { m ->
            val expression = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (expression.isNotEmpty()) {
                ScriptExpression.evaluateOrNull(expression, this)?.let(ScriptExpression::format) ?: m.value
            } else {
                variable(m.groupValues[3])?.let(ScriptExpression::format) ?: m.value
            }
        }

        /** Wert rechts von `=`: Rechnung/Vergleich, sonst der (eingesetzte) Text selbst. */
        private fun valueOf(text: String): Any {
            ScriptExpression.evaluateOrNull(text, this)?.let { return it }
            val plain = interpolate(text).trim()
            return ScriptExpression.parseNumber(plain) ?: plain.removeSurrounding("\"")
        }

        private fun number(text: String, what: String, line: Int): Double {
            val value = ScriptExpression.evaluateOrNull(text, this)
                ?: ScriptExpression.parseNumber(interpolate(text))
                ?: throw ScriptError("Zeile $line: '$text' ist keine Zahl ($what).")
            return ScriptExpression.toNumber(value, what)
        }

        // --- Anweisungen

        fun execute(statements: List<Stmt>) {
            for (stmt in statements) executeOne(stmt)
        }

        private fun tick(line: Int) {
            if (++steps > MAX_STEPS) throw ScriptError("Zeile $line: Abbruch nach $MAX_STEPS Schritten (Endlosschleife?).")
        }

        private fun command(text: String) {
            val resolved = interpolate(text).trim()
            // Aufruf einer Funktion, die erst weiter unten im Skript definiert wurde
            callRegex.matchEntire(resolved)?.let { m ->
                if (env.function(m.groupValues[1]) != null) {
                    executeOne(Stmt.Call(0, m.groupValues[1], m.groupValues[2]))
                    return
                }
            }
            val previous = passthroughLine
            passthroughLine = resolved
            try {
                messages.add(engine.executeCommand(resolved))
            } finally {
                passthroughLine = previous
            }
            executedCommands++
        }

        private fun condition(text: String, line: Int): Boolean {
            val value = try {
                ScriptExpression.evaluate(text, this)
            } catch (e: ScriptError) {
                throw ScriptError("Zeile $line: Bedingung '$text' – ${e.message}")
            }
            return ScriptExpression.truthy(value)
        }

        /** Block unter einer klassischen Bedingung (`if caption red` … `end`): jede Zeile wird zur Regel. */
        private fun executeLegacyGuarded(keyword: String, condition: String, body: List<Stmt>) {
            for (stmt in body) {
                if (stmt is Stmt.Command) command("$keyword $condition: ${stmt.text}") else executeOne(stmt)
            }
        }

        private fun executeOne(stmt: Stmt) {
            tick(stmt.line)
            when (stmt) {
                is Stmt.Command -> command(stmt.text)

                is Stmt.Assign -> {
                    val value = valueOf(stmt.valueText)
                    val result = if (stmt.op.isEmpty()) {
                        value
                    } else {
                        val current = variable(stmt.name)
                            ?: throw ScriptError("Zeile ${stmt.line}: Variable '${stmt.name}' gibt es noch nicht.")
                        if (stmt.op == "+" && (current is String || value is String)) {
                            ScriptExpression.format(current) + ScriptExpression.format(value)
                        } else {
                            val a = ScriptExpression.toNumber(current, "${stmt.name} ${stmt.op}=")
                            val b = ScriptExpression.toNumber(value, "${stmt.name} ${stmt.op}=")
                            when (stmt.op) {
                                "+" -> a + b
                                "-" -> a - b
                                "*" -> a * b
                                else -> if (b == 0.0) throw ScriptError("Zeile ${stmt.line}: Division durch 0.", isRuntimeFault = true) else a / b
                            }
                        }
                    }
                    val wroteGlobal = assign(stmt.name, result, stmt.declared, stmt.global)
                    messages.add("${stmt.name} = ${ScriptExpression.format(result)}" + if (wroteGlobal) " (global – gilt in allen Projekten)" else "")
                }

                is Stmt.Print -> {
                    val value = ScriptExpression.evaluateOrNull(stmt.text, this)
                    messages.add(if (value != null) ScriptExpression.format(value) else interpolate(stmt.text))
                }

                is Stmt.If -> {
                    val first = stmt.branches.first()
                    if (PocketScriptParser.isLegacyCondition(interpolate(first.condition))) {
                        executeLegacyGuarded(first.keyword, interpolate(first.condition), first.body)
                    } else {
                        val hit = stmt.branches.firstOrNull { condition(it.condition, stmt.line) }
                        execute(hit?.body ?: stmt.elseBody ?: emptyList())
                    }
                }

                is Stmt.While -> {
                    if (PocketScriptParser.isLegacyCondition(interpolate(stmt.condition))) {
                        executeLegacyGuarded(stmt.keyword, interpolate(stmt.condition), stmt.body)
                    } else {
                        var iterations = 0
                        while (condition(stmt.condition, stmt.line)) {
                            if (++iterations > MAX_LOOP_ITERATIONS) {
                                throw ScriptError("Zeile ${stmt.line}: while-Schleife nach $MAX_LOOP_ITERATIONS Durchläufen abgebrochen (Bedingung wird nie falsch).")
                            }
                            execute(stmt.body)
                        }
                    }
                }

                is Stmt.Repeat -> {
                    val n = number(stmt.count, "repeat", stmt.line).toInt()
                    if (n > MAX_LOOP_ITERATIONS) throw ScriptError("Zeile ${stmt.line}: repeat höchstens $MAX_LOOP_ITERATIONS mal.")
                    repeat(n.coerceAtLeast(0)) { execute(stmt.body) }
                }

                is Stmt.For -> executeFor(stmt)

                is Stmt.Def -> {
                    val key = stmt.function.name.lowercase()
                    if (stmt.global) {
                        env.globalFunctions[key] = stmt.function
                        engine.persistGlobals()
                    } else {
                        env.functions[key] = stmt.function
                    }
                    messages.add(
                        "Funktion ${stmt.function.name}(${stmt.function.params.joinToString(", ")}) definiert" +
                            if (stmt.global) " (global – gilt in allen Projekten)" else "",
                    )
                }

                is Stmt.Call -> {
                    val fn = env.function(stmt.name)
                        ?: throw ScriptError("Zeile ${stmt.line}: Funktion '${stmt.name}' ist nicht definiert.")
                    val args = splitArguments(stmt.args).map { valueOf(it) }
                    val formula = fn.formula
                    if (formula != null) {
                        messages.add(ScriptExpression.format(withFrame(fn, args) { ScriptExpression.evaluate(formula, this) }))
                    } else {
                        withFrame(fn, args) { execute(fn.body) }
                    }
                }

                is Stmt.Vars -> if (stmt.clear && stmt.globals) {
                    env.globals.clear()
                    env.globalFunctions.clear()
                    engine.persistGlobals()
                    messages.add("Alle globalen Variablen und Funktionen gelöscht.")
                } else if (stmt.clear) {
                    env.clear()
                    messages.add("Alle Variablen und Funktionen dieses Projekts gelöscht (globale bleiben: clear globals).")
                } else {
                    val vars = env.variables.map { (k, v) -> "$k = ${ScriptExpression.format(v)}" }
                    val globals = env.globals.map { (k, v) ->
                        "global $k = ${ScriptExpression.format(v)}" + if (env.variables.containsKey(k)) " (im Projekt überdeckt)" else ""
                    }
                    val fns = env.functions.values.map { "def ${it.name}(${it.params.joinToString(", ")})" } +
                        env.globalFunctions.values.map { "global def ${it.name}(${it.params.joinToString(", ")})" }
                    messages.add(
                        when {
                            vars.isEmpty() && globals.isEmpty() && fns.isEmpty() ->
                                "Noch keine Variablen. Beispiel: let x = 5 (dieses Projekt) oder global x = 5 (alle Projekte) · Eingebaut: ${builtinVariables.keys.joinToString(", ") { "$$it" }}"
                            else -> (vars + globals + fns).joinToString(" · ")
                        },
                    )
                }

                is Stmt.Unset -> {
                    val key = stmt.name.lowercase()
                    val removed = when {
                        !stmt.global && (env.variables.remove(key) != null || env.functions.remove(key) != null) -> "'${stmt.name}' gelöscht."
                        env.globals.remove(key) != null -> {
                            engine.persistGlobals()
                            "Globale Variable '${stmt.name}' gelöscht."
                        }
                        env.globalFunctions.remove(key) != null -> {
                            engine.persistGlobals()
                            "Globale Funktion '${stmt.name}' gelöscht."
                        }
                        else -> null
                    }
                    messages.add(removed ?: "'${stmt.name}' gibt es nicht.")
                }
            }
        }

        private fun executeFor(stmt: Stmt.For) {
            val source = stmt.source.trim()
            val rangeMatch = Regex("""^(.+?)\.\.(.+?)(?:\s+(?:step|schritt)\s+(.+))?$""", RegexOption.IGNORE_CASE).matchEntire(source)
            val items: List<Map<String, Any>> = when {
                rangeMatch != null -> {
                    val from = number(rangeMatch.groupValues[1], "for-Start", stmt.line)
                    val to = number(rangeMatch.groupValues[2], "for-Ende", stmt.line)
                    val rawStep = rangeMatch.groupValues[3].takeIf { it.isNotBlank() }?.let { number(it, "step", stmt.line) } ?: 1.0
                    if (rawStep == 0.0) throw ScriptError("Zeile ${stmt.line}: step darf nicht 0 sein.")
                    val step = if (to >= from) kotlin.math.abs(rawStep) else -kotlin.math.abs(rawStep)
                    val values = mutableListOf<Map<String, Any>>()
                    var v = from
                    while (if (step > 0) v <= to + 1e-9 else v >= to - 1e-9) {
                        values.add(mapOf(stmt.variable to v))
                        if (values.size > MAX_LOOP_ITERATIONS) {
                            throw ScriptError("Zeile ${stmt.line}: for-Schleife hätte mehr als $MAX_LOOP_ITERATIONS Durchläufe.")
                        }
                        v += step
                    }
                    values
                }
                source.lowercase() in listOf("captions", "subs", "untertitel") ->
                    engine.project.subtitles.sortedBy { it.startMs }.mapIndexed { i, s ->
                        element(stmt.variable, i, s.startMs, s.endMs, s.text)
                    }
                source.lowercase() in listOf("texts", "texte", "emojis", "overlays") -> {
                    val kinds = when (source.lowercase()) {
                        "emojis" -> setOf(OverlayKind.EMOJI)
                        "overlays" -> setOf(OverlayKind.TEXT, OverlayKind.EMOJI)
                        else -> setOf(OverlayKind.TEXT)
                    }
                    engine.project.overlays.filter { it.kind in kinds }.sortedBy { it.startMs }.mapIndexed { i, o ->
                        element(stmt.variable, i, o.startMs, o.endMs, o.content)
                    }
                }
                source.lowercase() in listOf("cuts", "schnitte") ->
                    engine.project.cutMarkers.sortedBy { it.timeMs }.mapIndexed { i, c ->
                        element(stmt.variable, i, c.timeMs, c.timeMs, c.id)
                    }
                else -> {
                    // Liste: `for farbe in [rot, blau, gelb]` oder `for farbe in rot, blau, gelb`
                    // … oder eine gemerkte Liste: `let favoriten = [🚀, ⭐]` und dann `for e in $favoriten`
                    val list = splitArguments(interpolate(source).trim().removeSurrounding("[", "]"))
                    if (list.isEmpty()) throw ScriptError("Zeile ${stmt.line}: Worüber soll 'for ${stmt.variable} in $source' laufen? (z.B. 1..5, captions, [a, b])")
                    list.map { mapOf(stmt.variable to valueOf(it)) }
                }
            }
            for (item in items) {
                item.forEach { (k, v) -> assign(k, v) }
                execute(stmt.body)
            }
        }

        private fun element(name: String, index: Int, startMs: Long, endMs: Long, text: String): Map<String, Any> = mapOf(
            name to (index + 1).toDouble(),
            "${name}_start" to startMs / 1000.0,
            "${name}_end" to endMs / 1000.0,
            "${name}_text" to text,
        )

        /** Trennt `a, b, "c, d"` an Kommas – nicht in Klammern/Anführungszeichen und nicht in Kommazahlen (`2,5`). */
        private fun splitArguments(text: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var quote: Char? = null
            for ((i, c) in text.withIndex()) {
                when {
                    quote != null -> {
                        if (c == quote) quote = null
                        current.append(c)
                    }
                    c == '"' -> {
                        quote = c
                        current.append(c)
                    }
                    c == '(' || c == '[' -> {
                        depth++
                        current.append(c)
                    }
                    c == ')' || c == ']' -> {
                        depth--
                        current.append(c)
                    }
                    c == ',' && depth == 0 -> {
                        val decimalComma = i > 0 && text[i - 1].isDigit() && i + 1 < text.length && text[i + 1].isDigit()
                        if (decimalComma) {
                            current.append(c)
                        } else {
                            parts.add(current.toString().trim())
                            current.clear()
                        }
                    }
                    else -> current.append(c)
                }
            }
            if (current.isNotBlank()) parts.add(current.toString().trim())
            return parts.filter { it.isNotEmpty() }
        }

        fun summary(): String {
            if (messages.isEmpty()) return "Programm ausgeführt."
            if (messages.size == 1) return messages.first()
            val shown = messages.takeLast(4)
            val prefix = if (messages.size > shown.size) "… " else ""
            return "$executedCommands Befehle ausgeführt: $prefix" + shown.joinToString(" · ")
        }

        fun failure(error: ScriptError): String {
            val done = if (executedCommands > 0) " (abgebrochen nach $executedCommands Befehlen)" else ""
            return "⚠ ${error.message}$done"
        }
    }
}

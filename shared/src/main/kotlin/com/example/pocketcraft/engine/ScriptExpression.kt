package com.example.pocketcraft.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Fehler in einem PocketScript-Programm (mit verständlicher Meldung für die Konsole). */
class ScriptError(message: String, val isRuntimeFault: Boolean = false) : Exception(message)

/**
 * Rechen-, Listen- und Vergleichsausdrücke für PocketScript, z.B. `$start + 2,5`, `($i * 4) % 3 == 0`,
 * `"Teil $i"`, `$farben[1]` oder `max($dauer / 2, 3)`.
 *
 * Werte sind Zahlen ([Double]), Texte ([String]), Wahrheitswerte ([Boolean]) oder Listen ([List]).
 * Kommazahlen dürfen deutsch (`2,5`) oder englisch (`2.5`) geschrieben werden; Funktions-Argumente
 * und Listen-Elemente werden deshalb mit Komma **und Leerzeichen** (oder außerhalb von `1,5`) getrennt.
 */
object ScriptExpression {

    /** Liefert Variablenwerte und ruft benutzerdefinierte Funktionen auf. */
    interface Scope {
        fun variable(name: String): Any?
        fun callFunction(name: String, args: List<Any>): Any?
        fun bpm(): Float? = null
        fun durationSec(): Double = 0.0
    }

    fun evaluate(text: String, scope: Scope): Any {
        val tokens = tokenize(text)
        val parser = Parser(tokens)
        val expr = parser.parseOr()
        if (!parser.atEnd()) throw ScriptError("Unerwartetes '${parser.peekText()}' im Ausdruck '$text'.")
        return eval(expr, scope)
    }

    /**
     * Wie [evaluate], aber `null`, wenn [text] gar kein Ausdruck ist (z.B. normaler Untertitel-Text).
     * Echte Rechenfehler wie eine Division durch 0 oder `sqrt(-1)` werden weiterhin gemeldet.
     */
    fun evaluateOrNull(text: String, scope: Scope): Any? = try {
        evaluate(text, scope)
    } catch (e: ScriptError) {
        if (e.isRuntimeFault) throw e
        null
    }

    fun truthy(value: Any): Boolean = when (value) {
        is Boolean -> value
        is Double -> value != 0.0
        is List<*> -> value.isNotEmpty()
        is String -> value.isNotEmpty() && value.lowercase() !in listOf("false", "falsch", "0", "nein")
        else -> false
    }

    /** Zahl/Liste/Boolean als Text ohne unnötige Nachkommastellen (`4`, `2.5`), passend für Befehle. */
    fun format(value: Any): String = when (value) {
        is Double -> {
            if (!value.isFinite()) {
                throw ScriptError("Ungültiges Rechenergebnis (NaN/Unendlich).", isRuntimeFault = true)
            }
            if (value == floor(value) && abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                val scaled = value * 1000.0
                if (!scaled.isFinite()) {
                    throw ScriptError("Ungültiges Rechenergebnis (Überlauf).", isRuntimeFault = true)
                }
                val rounded = scaled.roundToLong() / 1000.0
                rounded.toString().trimEnd('0').trimEnd('.')
            }
        }
        is Boolean -> if (value) "true" else "false"
        is List<*> -> "[${value.joinToString(", ") { format(it ?: "") }}]"
        else -> value.toString()
    }

    fun toNumber(value: Any, context: String = "Ausdruck"): Double = when (value) {
        is Double -> value
        is Boolean -> if (value) 1.0 else 0.0
        is String -> parseNumber(value) ?: throw ScriptError("'$value' ist keine Zahl ($context).")
        else -> throw ScriptError("Keine Zahl ($context).")
    }

    /** `12`, `2,5`, `2.5`, `00:48` (mm:ss) oder `1:02:03` (h:mm:ss) → Zahl in Sekunden. */
    fun parseNumber(raw: String): Double? {
        val t = raw.trim()
        if (TIMECODE.matches(t)) {
            return t.split(":").fold(0.0) { acc, part -> acc * 60.0 + (part.replace(",", ".").toDoubleOrNull() ?: return null) }
        }
        if (!PLAIN_NUMBER.matches(t)) return null
        return t.replace(",", ".").toDoubleOrNull()
    }

    /** Parses a serialized list string like `[🚀, ⭐, 🎉]` back into a [List] of decoded elements. */
    fun parseListLiteral(raw: String): List<Any>? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return null
        val inner = trimmed.substring(1, trimmed.length - 1).trim()
        if (inner.isEmpty()) return emptyList()
        val parts = mutableListOf<String>()
        val cur = StringBuilder()
        var depth = 0
        var quote: Char? = null
        for ((i, c) in inner.withIndex()) {
            when {
                quote != null -> {
                    if (c == quote && (i == 0 || inner[i - 1] != '\\')) quote = null
                    cur.append(c)
                }
                c == '"' || c == '\'' -> {
                    quote = c
                    cur.append(c)
                }
                c == '(' || c == '[' -> {
                    depth++
                    cur.append(c)
                }
                c == ')' || c == ']' -> {
                    depth--
                    cur.append(c)
                }
                c == ',' && depth == 0 -> {
                    val decimalComma = i > 0 && inner[i - 1].isDigit() && i + 1 < inner.length && inner[i + 1].isDigit()
                    if (decimalComma) {
                        cur.append(c)
                    } else {
                        parts.add(cur.toString().trim())
                        cur.clear()
                    }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotBlank()) parts.add(cur.toString().trim())
        return parts.filter { it.isNotEmpty() }.map { el ->
            val unquoted = el.removeSurrounding("\"").removeSurrounding("'")
            parseNumber(unquoted) ?: unquoted
        }
    }

    /** Formats seconds into `MM:SS.d` timecode string (consistent with `ProjectEngine.formatTimecode`). */
    fun formatTimecodeSeconds(sec: Double): String {
        val totalTenths = (sec.coerceAtLeast(0.0) * 10.0).roundToLong().coerceAtLeast(0L)
        val totalSeconds = totalTenths / 10L
        val tenths = totalTenths % 10L
        val mins = totalSeconds / 60L
        val secs = totalSeconds % 60L
        return "${mins.toString().padStart(2, '0')}:${secs.toString().padStart(2, '0')}.$tenths"
    }

    private val TIMECODE = Regex("""\d+:\d{2}(?::\d{2})?(?:[.,]\d+)?""")
    private val PLAIN_NUMBER = Regex("""-?\d+(?:[.,]\d+)?""")

    // ---------------------------------------------------------------- Tokens

    private sealed class Token {
        data class Num(val value: Double) : Token()
        data class Str(val value: String, val interpolated: Boolean) : Token()
        data class Ident(val name: String, val explicitDollar: Boolean = false) : Token()
        data class Op(val symbol: String) : Token()
    }

    private val WORD_OPERATORS = mapOf(
        "and" to "&&", "und" to "&&",
        "or" to "||", "oder" to "||",
        "not" to "!", "nicht" to "!",
        "contains" to "contains", "enthält" to "contains", "enthaelt" to "contains",
        "startswith" to "startswith", "beginnt" to "startswith",
        "endswith" to "endswith", "endet" to "endswith",
        "in" to "in",
    )
    private val SYMBOLS = listOf("==", "!=", "<=", ">=", "&&", "||", "+", "-", "*", "/", "%", "^", "(", ")", "[", "]", "<", ">", "!", ",")
    private val SYMBOL_CHARS = setOf('=', '!', '<', '>', '&', '|', '+', '-', '*', '/', '%', '^', '(', ')', '[', ']', ',')
    private val NUMBER_AT = Regex("""\d+:\d{2}(?::\d{2})?(?:[.,]\d+)?|\d+(?:[.,]\d+)?""")

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun tokenize(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        var bracketDepth = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() -> {
                    val m = NUMBER_AT.matchAt(text, i) ?: throw ScriptError("Ungültige Zahl in '$text'.")
                    out.add(Token.Num(parseNumber(m.value) ?: throw ScriptError("Ungültige Zahl '${m.value}'.")))
                    i = m.range.last + 1
                }
                c == '"' || c == '\'' -> {
                    val sb = StringBuilder()
                    var j = i + 1
                    var closed = false
                    while (j < text.length) {
                        val ch = text[j]
                        if (ch == '\\' && j + 1 < text.length) {
                            val next = text[j + 1]
                            when (next) {
                                'n' -> sb.append('\n')
                                't' -> sb.append('\t')
                                '\\', '"', '\'' -> sb.append(next)
                                else -> sb.append(next)
                            }
                            j += 2
                        } else if (ch == c) {
                            closed = true
                            j++
                            break
                        } else {
                            sb.append(ch)
                            j++
                        }
                    }
                    if (!closed) throw ScriptError("Text in Anführungszeichen wurde nicht geschlossen: $text")
                    out.add(Token.Str(sb.toString(), interpolated = c == '"'))
                    i = j
                }
                c == '$' || isIdentStart(c) -> {
                    val isDollar = c == '$'
                    var j = if (isDollar) i + 1 else i
                    val begin = j
                    while (j < text.length && isIdentPart(text[j])) j++
                    if (j == begin) throw ScriptError("Nach '$' fehlt ein Variablenname.")
                    val name = text.substring(begin, j)
                    // Only suppress function-named operators (`contains(a, b)`) before `(`, never `and`/`or`/`not`
                    var lookahead = j
                    while (lookahead < text.length && text[lookahead].isWhitespace()) lookahead++
                    val followedByParen = lookahead < text.length && text[lookahead] == '('
                    val lowerName = name.lowercase()
                    val suppressAsFunction = followedByParen && lowerName in setOf("contains", "startswith", "endswith", "in")
                    val wordOp = if (isDollar || suppressAsFunction) null else WORD_OPERATORS[lowerName]
                    out.add(if (wordOp != null) Token.Op(wordOp) else Token.Ident(name, explicitDollar = isDollar))
                    i = j
                }
                else -> {
                    val sym = SYMBOLS.firstOrNull { text.startsWith(it, i) }
                    if (sym != null) {
                        if (sym == "[") bracketDepth++
                        else if (sym == "]") bracketDepth = (bracketDepth - 1).coerceAtLeast(0)
                        out.add(Token.Op(sym))
                        i += sym.length
                    } else if (bracketDepth > 0) {
                        // Allow bare emojis or symbols (e.g. `[🚀, ⭐, 🎉]`) inside list literals
                        var j = i
                        while (j < text.length && !text[j].isWhitespace() && text[j] !in SYMBOL_CHARS) {
                            j++
                        }
                        out.add(Token.Str(text.substring(i, j), interpolated = false))
                        i = j
                    } else {
                        throw ScriptError("Unerwartetes Zeichen '$c' im Ausdruck '$text'.")
                    }
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- AST

    private sealed class Expr {
        data class Literal(val value: Any) : Expr()
        data class StrTemplate(val template: String) : Expr()
        data class Var(val name: String, val explicitDollar: Boolean) : Expr()
        data class ListLit(val elements: List<Expr>) : Expr()
        data class Index(val target: Expr, val index: Expr) : Expr()
        data class Unary(val op: String, val inner: Expr) : Expr()
        data class Binary(val op: String, val left: Expr, val right: Expr) : Expr()
        data class Call(val name: String, val args: List<Expr>) : Expr()
    }

    // ---------------------------------------------------------------- Parser (Tokens -> AST)

    private class Parser(private val tokens: List<Token>) {
        private var pos = 0

        fun atEnd() = pos >= tokens.size

        fun peekText(): String = when (val t = tokens.getOrNull(pos)) {
            is Token.Num -> format(t.value)
            is Token.Str -> t.value
            is Token.Ident -> t.name
            is Token.Op -> t.symbol
            null -> "Ende"
        }

        private fun matchOp(vararg symbols: String): String? {
            val t = tokens.getOrNull(pos) as? Token.Op ?: return null
            if (t.symbol !in symbols) return null
            pos++
            return t.symbol
        }

        fun parseOr(): Expr {
            var left = parseAnd()
            while (matchOp("||") != null) {
                left = Expr.Binary("||", left, parseAnd())
            }
            return left
        }

        private fun parseAnd(): Expr {
            var left = parseNot()
            while (matchOp("&&") != null) {
                left = Expr.Binary("&&", left, parseNot())
            }
            return left
        }

        private fun parseNot(): Expr =
            if (matchOp("!") != null) Expr.Unary("!", parseNot()) else parseComparison()

        private fun parseComparison(): Expr {
            val left = parseSum()
            val op = matchOp("==", "!=", "<=", ">=", "<", ">", "contains", "startswith", "endswith", "in") ?: return left
            val right = parseSum()
            return Expr.Binary(op, left, right)
        }

        private fun parseSum(): Expr {
            var left = parseProduct()
            while (true) {
                val op = matchOp("+", "-") ?: return left
                left = Expr.Binary(op, left, parseProduct())
            }
        }

        private fun parseProduct(): Expr {
            var left = parseUnary()
            while (true) {
                val op = matchOp("*", "/", "%") ?: return left
                left = Expr.Binary(op, left, parseUnary())
            }
        }

        private fun parseUnary(): Expr {
            if (matchOp("-") != null) return Expr.Unary("-", parseUnary())
            if (matchOp("+") != null) return Expr.Unary("+", parseUnary())
            val base = parsePostfix()
            if (matchOp("^") != null) return Expr.Binary("^", base, parseUnary())
            return base
        }

        private fun parsePostfix(): Expr {
            var expr = parsePrimary()
            while (matchOp("[") != null) {
                val index = parseOr()
                matchOp("]") ?: throw ScriptError("Schließende Klammer ']' fehlt.")
                expr = Expr.Index(expr, index)
            }
            return expr
        }

        private fun parsePrimary(): Expr {
            val t = tokens.getOrNull(pos) ?: throw ScriptError("Ausdruck ist unvollständig.")
            pos++
            return when (t) {
                is Token.Num -> Expr.Literal(t.value)
                is Token.Str -> if (t.interpolated && (t.value.contains('$') || t.value.contains('{'))) {
                    Expr.StrTemplate(t.value)
                } else {
                    Expr.Literal(t.value)
                }
                is Token.Op -> when (t.symbol) {
                    "(" -> {
                        val inner = parseOr()
                        matchOp(")") ?: throw ScriptError("Schließende Klammer ')' fehlt.")
                        inner
                    }
                    "[" -> {
                        val elements = mutableListOf<Expr>()
                        if (matchOp("]") == null) {
                            do {
                                elements.add(parseOr())
                            } while (matchOp(",") != null)
                            matchOp("]") ?: throw ScriptError("Schließende Klammer ']' in Liste fehlt.")
                        }
                        Expr.ListLit(elements)
                    }
                    else -> throw ScriptError("Unerwartetes '${t.symbol}' im Ausdruck.")
                }
                is Token.Ident -> {
                    if (matchOp("(") != null) {
                        val args = mutableListOf<Expr>()
                        if (matchOp(")") == null) {
                            do {
                                args.add(parseOr())
                            } while (matchOp(",") != null)
                            matchOp(")") ?: throw ScriptError("Schließende Klammer ')' nach ${t.name}(… fehlt.")
                        }
                        Expr.Call(t.name, args)
                    } else {
                        when (t.name.lowercase()) {
                            "true", "wahr", "ja" -> if (t.explicitDollar) Expr.Var(t.name, true) else Expr.Literal(true)
                            "false", "falsch", "nein" -> if (t.explicitDollar) Expr.Var(t.name, true) else Expr.Literal(false)
                            "pi" -> if (t.explicitDollar) Expr.Var(t.name, true) else Expr.Literal(PI)
                            else -> Expr.Var(t.name, t.explicitDollar)
                        }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Evaluator

    private val templatePlaceholder = Regex("""\$\{([^{}]+)\}|\{([^{}]+)\}|\$([\p{L}_][\p{L}\p{N}_]*)""")

    private fun interpolateString(template: String, scope: Scope): String =
        templatePlaceholder.replace(template) { m ->
            val exprText = m.groupValues[1].ifEmpty { m.groupValues[2] }
            if (exprText.isNotEmpty()) {
                evaluateOrNull(exprText, scope)?.let(::format) ?: m.value
            } else {
                scope.variable(m.groupValues[3])?.let(::format) ?: m.value
            }
        }

    private fun eval(expr: Expr, scope: Scope): Any = when (expr) {
        is Expr.Literal -> expr.value
        is Expr.StrTemplate -> interpolateString(expr.template, scope)
        is Expr.Var -> scope.variable(expr.name) ?: throw ScriptError("Unbekannte Variable '${expr.name}'.")
        is Expr.ListLit -> expr.elements.map { el ->
            if (el is Expr.Var && !el.explicitDollar) {
                // Inside `[red, gold]`, bare identifiers without `$` act as strings if not defined as variables
                scope.variable(el.name) ?: el.name
            } else {
                eval(el, scope)
            }
        }
        is Expr.Index -> {
            val target = eval(expr.target, scope)
            val idxDouble = toNumber(eval(expr.index, scope), "Listen-Index")
            val rawIdx = idxDouble.toInt()
            when (target) {
                is List<*> -> {
                    if (target.isEmpty()) throw ScriptError("Liste ist leer (Index $rawIdx).", isRuntimeFault = true)
                    val resolved = if (rawIdx < 0) target.size + rawIdx else rawIdx
                    if (resolved !in target.indices) {
                        throw ScriptError("Listen-Index $rawIdx außerhalb (Größe ${target.size}).", isRuntimeFault = true)
                    }
                    target[resolved] ?: ""
                }
                is String -> {
                    if (target.isEmpty()) throw ScriptError("Text ist leer (Index $rawIdx).", isRuntimeFault = true)
                    val resolved = if (rawIdx < 0) target.length + rawIdx else rawIdx
                    if (resolved !in target.indices) {
                        throw ScriptError("Text-Index $rawIdx außerhalb (Länge ${target.length}).", isRuntimeFault = true)
                    }
                    target[resolved].toString()
                }
                else -> throw ScriptError("Indexierung '[...]' ist nur auf Listen und Texte möglich.")
            }
        }
        is Expr.Unary -> when (expr.op) {
            "!" -> !truthy(eval(expr.inner, scope))
            "-" -> -toNumber(eval(expr.inner, scope), "Vorzeichen")
            "+" -> toNumber(eval(expr.inner, scope), "Vorzeichen")
            else -> throw ScriptError("Unbekannter Operator '${expr.op}'.")
        }
        is Expr.Binary -> when (expr.op) {
            // Short-circuit evaluation prevents division by zero or side-effects on the right branch
            "||" -> if (truthy(eval(expr.left, scope))) true else truthy(eval(expr.right, scope))
            "&&" -> if (!truthy(eval(expr.left, scope))) false else truthy(eval(expr.right, scope))
            "^" -> {
                val base = toNumber(eval(expr.left, scope), "Potenz")
                val exp = toNumber(eval(expr.right, scope), "Potenz")
                val res = base.pow(exp)
                if (!res.isFinite()) throw ScriptError("Rechenüberlauf bei Potenz ($base ^ $exp).", isRuntimeFault = true)
                res
            }
            "*", "/", "%" -> {
                val l = toNumber(eval(expr.left, scope), "Rechnung ${expr.op}")
                val r = toNumber(eval(expr.right, scope), "Rechnung ${expr.op}")
                if (expr.op != "*" && r == 0.0) throw ScriptError("Division durch 0.", isRuntimeFault = true)
                val res = when (expr.op) {
                    "*" -> l * r
                    "/" -> l / r
                    else -> l % r
                }
                if (!res.isFinite()) throw ScriptError("Ungültiges Rechenergebnis.", isRuntimeFault = true)
                res
            }
            "+", "-" -> {
                val left = eval(expr.left, scope)
                val right = eval(expr.right, scope)
                if (expr.op == "+" && (left is String || right is String)) {
                    format(left) + format(right)
                } else if (expr.op == "+" && left is List<*>) {
                    if (right is List<*>) left + right else left + listOf(right)
                } else if (expr.op == "+") {
                    toNumber(left, "Plus") + toNumber(right, "Plus")
                } else {
                    toNumber(left, "Minus") - toNumber(right, "Minus")
                }
            }
            "==", "!=" -> {
                val left = eval(expr.left, scope)
                val right = eval(expr.right, scope)
                val equal = if (left is List<*> && right is List<*>) {
                    left.size == right.size && left.zip(right).all { (a, b) -> format(a ?: "").equals(format(b ?: ""), ignoreCase = true) }
                } else if (left is String || right is String) {
                    val ln = (left as? String)?.let(::parseNumber) ?: (left as? Double)
                    val rn = (right as? String)?.let(::parseNumber) ?: (right as? Double)
                    if (ln != null && rn != null) ln == rn else format(left).equals(format(right), ignoreCase = true)
                } else {
                    toNumber(left) == toNumber(right)
                }
                if (expr.op == "==") equal else !equal
            }
            "<", "<=", ">", ">=" -> {
                val l = toNumber(eval(expr.left, scope), "Vergleich ${expr.op}")
                val r = toNumber(eval(expr.right, scope), "Vergleich ${expr.op}")
                when (expr.op) {
                    "<" -> l < r
                    "<=" -> l <= r
                    ">" -> l > r
                    else -> l >= r
                }
            }
            "contains" -> evalContains(eval(expr.left, scope), eval(expr.right, scope))
            "startswith" -> format(eval(expr.left, scope)).startsWith(format(eval(expr.right, scope)), ignoreCase = true)
            "endswith" -> format(eval(expr.left, scope)).endsWith(format(eval(expr.right, scope)), ignoreCase = true)
            "in" -> evalContains(eval(expr.right, scope), eval(expr.left, scope))
            else -> throw ScriptError("Unbekannter Operator '${expr.op}'.")
        }
        is Expr.Call -> {
            val evaluatedArgs = expr.args.map { eval(it, scope) }
            callBuiltin(expr.name, evaluatedArgs, scope)
                ?: scope.callFunction(expr.name, evaluatedArgs)
                ?: throw ScriptError("Unbekannte Funktion '${expr.name}'.")
        }
    }

    private fun evalContains(container: Any, item: Any): Boolean = when (container) {
        is List<*> -> container.any { it != null && format(it).equals(format(item), ignoreCase = true) }
        else -> format(container).contains(format(item), ignoreCase = true)
    }

    private fun callBuiltin(name: String, args: List<Any>, scope: Scope): Any? {
        fun num(i: Int): Double {
            if (i >= args.size) throw ScriptError("$name(…) braucht mindestens ${i + 1} Argument(e).")
            return toNumber(args[i], "$name(…)")
        }
        return when (name.lowercase()) {
            "min" -> args.indices.minOfOrNull { num(it) } ?: num(0)
            "max" -> args.indices.maxOfOrNull { num(it) } ?: num(0)
            "abs" -> abs(num(0))
            "floor", "abrunden" -> floor(num(0))
            "ceil", "aufrunden" -> ceil(num(0))
            "sqrt", "wurzel" -> {
                val v = num(0)
                if (v < 0.0) throw ScriptError("Wurzel aus negativer Zahl ($v) ist nicht definiert.", isRuntimeFault = true)
                sqrt(v)
            }
            "round", "runden" -> {
                val factor = 10.0.pow(if (args.size > 1) num(1) else 0.0)
                val scaled = num(0) * factor
                if (!scaled.isFinite()) throw ScriptError("Ungültiges Rechenergebnis bei round().", isRuntimeFault = true)
                scaled.roundToLong() / factor
            }
            "sin" -> sin(num(0))
            "cos" -> cos(num(0))
            "deg" -> Math.toDegrees(num(0))
            "rad" -> Math.toRadians(num(0))
            "clamp" -> {
                val v = num(0)
                val a = num(1)
                val b = num(2)
                v.coerceIn(minOf(a, b), maxOf(a, b))
            }
            "lerp" -> {
                val a = num(0)
                val b = num(1)
                val t = num(2)
                a + (b - a) * t
            }
            "timecode" -> formatTimecodeSeconds(num(0))
            "beat" -> {
                val n = num(0)
                val bpm = (if (args.size > 1) num(1) else (scope.bpm()?.toDouble() ?: 120.0)).coerceIn(10.0, 400.0)
                n * 60.0 / bpm
            }
            "beats" -> {
                val start = num(0)
                val end = num(1)
                val bpm = (if (args.size > 2) num(2) else (scope.bpm()?.toDouble() ?: 120.0)).coerceIn(10.0, 400.0)
                val step = 60.0 / bpm
                val result = mutableListOf<Double>()
                var t = start
                while (t <= end + 1e-6 && result.size < 500) {
                    result.add((t * 100.0).roundToLong() / 100.0)
                    t += step
                }
                result
            }
            "pick" -> {
                if (args.isEmpty()) throw ScriptError("pick(…) braucht mindestens ein Argument.")
                val pool = if (args.size == 1 && args[0] is List<*>) {
                    @Suppress("UNCHECKED_CAST")
                    (args[0] as List<Any?>).filterNotNull()
                } else {
                    args
                }
                if (pool.isEmpty()) throw ScriptError("pick(…) bekam eine leere Liste.", isRuntimeFault = true)
                pool.random()
            }
            "random", "zufall" -> {
                val a = if (args.size > 1) num(0) else 0.0
                val b = if (args.size > 1) num(1) else if (args.size == 1) num(0) else 1.0
                val lo = minOf(a, b)
                val hi = maxOf(a, b)
                if (lo == floor(lo) && hi == floor(hi) && args.isNotEmpty()) {
                    (lo.toLong()..hi.toLong()).random().toDouble()
                } else {
                    lo + Math.random() * (hi - lo)
                }
            }
            "len", "länge", "laenge" -> {
                val arg = args.getOrNull(0) ?: throw ScriptError("len(…) braucht ein Argument.")
                when (arg) {
                    is List<*> -> arg.size.toDouble()
                    else -> format(arg).length.toDouble()
                }
            }
            "upper", "gross" -> format(args.getOrNull(0) ?: "").uppercase()
            "lower", "klein" -> format(args.getOrNull(0) ?: "").lowercase()
            "str", "text" -> format(args.getOrNull(0) ?: "")
            "num", "zahl" -> num(0)
            "contains" -> {
                if (args.size < 2) throw ScriptError("contains(…) braucht 2 Argumente.")
                evalContains(args[0], args[1])
            }
            "startswith" -> {
                if (args.size < 2) throw ScriptError("startswith(…) braucht 2 Argumente.")
                format(args[0]).startsWith(format(args[1]), ignoreCase = true)
            }
            "endswith" -> {
                if (args.size < 2) throw ScriptError("endswith(…) braucht 2 Argumente.")
                format(args[0]).endsWith(format(args[1]), ignoreCase = true)
            }
            "join" -> {
                val list = args.getOrNull(0) as? List<*> ?: throw ScriptError("join(liste, trenner) erwartet eine Liste als erstes Argument.")
                val sep = if (args.size > 1) format(args[1]) else ", "
                list.joinToString(sep) { format(it ?: "") }
            }
            else -> null
        }
    }
}

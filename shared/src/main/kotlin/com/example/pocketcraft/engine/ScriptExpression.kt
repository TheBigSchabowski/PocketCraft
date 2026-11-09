package com.example.pocketcraft.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Fehler in einem PocketScript-Programm (mit verständlicher Meldung für die Konsole). */
class ScriptError(message: String, val isRuntimeFault: Boolean = false) : Exception(message)

/**
 * Rechen- und Vergleichsausdrücke für PocketScript, z.B. `$start + 2,5`, `($i * 4) % 3 == 0`,
 * `"Teil " + $i` oder `max($dauer / 2, 3)`.
 *
 * Werte sind Zahlen ([Double]), Texte ([String]) oder Wahrheitswerte ([Boolean]).
 * Kommazahlen dürfen deutsch (`2,5`) oder englisch (`2.5`) geschrieben werden; Funktions-Argumente
 * werden deshalb mit Komma **und Leerzeichen** getrennt (`max(1, 5)`).
 */
object ScriptExpression {

    /** Liefert Variablenwerte und ruft benutzerdefinierte Funktionen auf. */
    interface Scope {
        fun variable(name: String): Any?
        fun callFunction(name: String, args: List<Any>): Any?
    }

    fun evaluate(text: String, scope: Scope): Any {
        val parser = Parser(tokenize(text), scope)
        val value = parser.parseOr()
        if (!parser.atEnd()) throw ScriptError("Unerwartetes '${parser.peekText()}' im Ausdruck '$text'.")
        return value
    }

    /**
     * Wie [evaluate], aber `null`, wenn [text] gar kein Ausdruck ist (z.B. normaler Untertitel-Text).
     * Echte Rechenfehler wie eine Division durch 0 werden weiterhin gemeldet.
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
        is String -> value.isNotEmpty() && value.lowercase() !in listOf("false", "falsch", "0", "nein")
        else -> false
    }

    /** Zahl ohne unnötige Nachkommastellen (`4`, `2.5`), damit sie in Befehle eingesetzt werden kann. */
    fun format(value: Any): String = when (value) {
        is Double -> {
            if (value == floor(value) && abs(value) < 1e15) {
                value.toLong().toString()
            } else {
                val rounded = (value * 1000.0).roundToLong() / 1000.0
                rounded.toString().trimEnd('0').trimEnd('.')
            }
        }
        is Boolean -> if (value) "true" else "false"
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

    private val TIMECODE = Regex("""\d+:\d{2}(?::\d{2})?(?:[.,]\d+)?""")
    private val PLAIN_NUMBER = Regex("""-?\d+(?:[.,]\d+)?""")

    // ---------------------------------------------------------------- Tokens

    private sealed class Token {
        data class Num(val value: Double) : Token()
        data class Str(val value: String) : Token()
        data class Ident(val name: String) : Token()
        data class Op(val symbol: String) : Token()
    }

    private val WORD_OPERATORS = mapOf(
        "and" to "&&", "und" to "&&",
        "or" to "||", "oder" to "||",
        "not" to "!", "nicht" to "!",
    )
    private val SYMBOLS = listOf("==", "!=", "<=", ">=", "&&", "||", "+", "-", "*", "/", "%", "^", "(", ")", "<", ">", "!", ",")
    private val NUMBER_AT = Regex("""\d+:\d{2}(?::\d{2})?(?:[.,]\d+)?|\d+(?:[.,]\d+)?""")

    private fun isIdentStart(c: Char) = c.isLetter() || c == '_'
    private fun isIdentPart(c: Char) = c.isLetterOrDigit() || c == '_'

    private fun tokenize(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
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
                    val close = text.indexOf(c, i + 1)
                    if (close < 0) throw ScriptError("Text in Anführungszeichen wurde nicht geschlossen: $text")
                    out.add(Token.Str(text.substring(i + 1, close)))
                    i = close + 1
                }
                c == '$' || isIdentStart(c) -> {
                    var j = if (c == '$') i + 1 else i
                    val begin = j
                    while (j < text.length && isIdentPart(text[j])) j++
                    if (j == begin) throw ScriptError("Nach '$' fehlt ein Variablenname.")
                    val name = text.substring(begin, j)
                    val wordOp = if (c == '$') null else WORD_OPERATORS[name.lowercase()]
                    out.add(if (wordOp != null) Token.Op(wordOp) else Token.Ident(name))
                    i = j
                }
                else -> {
                    val sym = SYMBOLS.firstOrNull { text.startsWith(it, i) }
                        ?: throw ScriptError("Unerwartetes Zeichen '$c' im Ausdruck '$text'.")
                    out.add(Token.Op(sym))
                    i += sym.length
                }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- Parser

    private class Parser(private val tokens: List<Token>, private val scope: Scope) {
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

        fun parseOr(): Any {
            var left = parseAnd()
            while (matchOp("||") != null) {
                val right = parseAnd()
                left = truthy(left) || truthy(right)
            }
            return left
        }

        private fun parseAnd(): Any {
            var left = parseNot()
            while (matchOp("&&") != null) {
                val right = parseNot()
                left = truthy(left) && truthy(right)
            }
            return left
        }

        private fun parseNot(): Any =
            if (matchOp("!") != null) !truthy(parseNot()) else parseComparison()

        private fun parseComparison(): Any {
            val left = parseSum()
            val op = matchOp("==", "!=", "<=", ">=", "<", ">") ?: return left
            val right = parseSum()
            if (op == "==" || op == "!=") {
                val equal = if (left is String || right is String) {
                    val ln = (left as? String)?.let(::parseNumber) ?: (left as? Double)
                    val rn = (right as? String)?.let(::parseNumber) ?: (right as? Double)
                    if (ln != null && rn != null) ln == rn else format(left).equals(format(right), ignoreCase = true)
                } else {
                    toNumber(left) == toNumber(right)
                }
                return if (op == "==") equal else !equal
            }
            val l = toNumber(left, "Vergleich $op")
            val r = toNumber(right, "Vergleich $op")
            return when (op) {
                "<" -> l < r
                "<=" -> l <= r
                ">" -> l > r
                else -> l >= r
            }
        }

        private fun parseSum(): Any {
            var left = parseProduct()
            while (true) {
                val op = matchOp("+", "-") ?: return left
                val right = parseProduct()
                left = if (op == "+" && (left is String || right is String)) {
                    // Text + irgendwas = aneinanderhängen ("Teil " + 3 -> "Teil 3")
                    format(left) + format(right)
                } else if (op == "+") {
                    toNumber(left) + toNumber(right)
                } else {
                    toNumber(left, "Minus") - toNumber(right, "Minus")
                }
            }
        }

        private fun parseProduct(): Any {
            var left = parseUnary()
            while (true) {
                val op = matchOp("*", "/", "%") ?: return left
                val l = toNumber(left, "Rechnung $op")
                val r = toNumber(parseUnary(), "Rechnung $op")
                if (op != "*" && r == 0.0) throw ScriptError("Division durch 0.", isRuntimeFault = true)
                left = when (op) {
                    "*" -> l * r
                    "/" -> l / r
                    else -> l % r
                }
            }
        }

        private fun parseUnary(): Any {
            if (matchOp("-") != null) return -toNumber(parseUnary(), "Vorzeichen")
            if (matchOp("+") != null) return toNumber(parseUnary(), "Vorzeichen")
            val base = parsePrimary()
            if (matchOp("^") != null) return toNumber(base, "Potenz").pow(toNumber(parseUnary(), "Potenz"))
            return base
        }

        private fun parsePrimary(): Any {
            val t = tokens.getOrNull(pos) ?: throw ScriptError("Ausdruck ist unvollständig.")
            pos++
            return when (t) {
                is Token.Num -> t.value
                is Token.Str -> t.value
                is Token.Op -> {
                    if (t.symbol != "(") throw ScriptError("Unerwartetes '${t.symbol}' im Ausdruck.")
                    val inner = parseOr()
                    matchOp(")") ?: throw ScriptError("Schließende Klammer ')' fehlt.")
                    inner
                }
                is Token.Ident -> {
                    if (matchOp("(") != null) {
                        val args = mutableListOf<Any>()
                        if (matchOp(")") == null) {
                            do {
                                args.add(parseOr())
                            } while (matchOp(",") != null)
                            matchOp(")") ?: throw ScriptError("Schließende Klammer ')' nach ${t.name}(… fehlt.")
                        }
                        callBuiltin(t.name, args)
                            ?: scope.callFunction(t.name, args)
                            ?: throw ScriptError("Unbekannte Funktion '${t.name}'.")
                    } else {
                        when (t.name.lowercase()) {
                            "true", "wahr", "ja" -> true
                            "false", "falsch", "nein" -> false
                            else -> scope.variable(t.name) ?: throw ScriptError("Unbekannte Variable '${t.name}'.")
                        }
                    }
                }
            }
        }

        private fun callBuiltin(name: String, args: List<Any>): Any? {
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
                "sqrt", "wurzel" -> sqrt(num(0))
                "round", "runden" -> {
                    val factor = 10.0.pow(if (args.size > 1) num(1) else 0.0)
                    (num(0) * factor).roundToLong() / factor
                }
                "random", "zufall" -> {
                    val lo = if (args.size > 1) num(0) else 0.0
                    val hi = if (args.size > 1) num(1) else if (args.size == 1) num(0) else 1.0
                    if (lo == floor(lo) && hi == floor(hi) && args.isNotEmpty()) {
                        (lo.toLong()..maxOf(lo, hi).toLong()).random().toDouble()
                    } else {
                        lo + Math.random() * (hi - lo)
                    }
                }
                "len", "länge", "laenge" -> format(args.getOrNull(0) ?: throw ScriptError("len(…) braucht ein Argument.")).length.toDouble()
                "upper", "gross" -> format(args.getOrNull(0) ?: "").uppercase()
                "lower", "klein" -> format(args.getOrNull(0) ?: "").lowercase()
                "str", "text" -> format(args.getOrNull(0) ?: "")
                "num", "zahl" -> num(0)
                else -> null
            }
        }
    }
}

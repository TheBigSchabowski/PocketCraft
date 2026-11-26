package com.example.pocketcraft.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation

/**
 * Live syntax highlighting for PocketScript 3.0 inside the multiline code editor.
 * Preserves exact 1:1 character offsets ([OffsetMapping.Identity]) so cursor and selection
 * behavior stay identical to plain text.
 */
class PocketScriptSyntaxTransformation : VisualTransformation {

    private val commentColor = Color(0xFF64748B)
    private val keywordColor = Color(0xFFC084FC)
    private val stringColor = Color(0xFFFDBA74)
    private val variableColor = Color(0xFF6EE7B7)
    private val timeNumberColor = Color(0xFFFFD54F)
    private val modifierColor = Color(0xFFF472B6)
    private val commandColor = Color(0xFF00E5FF)

    private val stringRegex = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'""")
    private val varExprRegex = Regex("""\$\{[^{}]+\}|\{[^{}]+\}|\$[\p{L}_][\p{L}\p{N}_]*""")
    private val dotModifierRegex = Regex("""\.[@#]?[\p{L}\p{N}_]+""")
    private val keywordRegex = Regex(
        """\b(?:let|var|sei|global|def|fn|func|function|for|in|step|while|if|wenn|elif|else|sonst|end|ende|repeat|wiederhole|break|continue|return|style|print|echo|bpm|and|und|or|oder|not|nicht|contains|startswith|endswith)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val commandRegex = Regex(
        """\b(?:zoom|crop|move|pan|rotate|rot|spin|flip|mirror|fade|flash|shake|kenburns|pulse|mute|unmute|volume|audio|filter|fx|blur|caption|sub|text|title|emoji|sticker|set|delete|del|clear|trim|speed|save|open|new|export|undo|redo|reset|demo|vars|unset|show|edits|autocaption)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val timeNumberRegex = Regex(
        """\b(?:all|alles|last\s+\d+(?:[.,]\d+)?|end-\d+(?:[.,]\d+)?(?:\.\.end)?|cut\d*|\d+(?::\d+)*(?:[.,]\d+)?(?:-\d+(?::\d+)*(?:[.,]\d+)?)?%?)\b|->|→|\+\d+(?:[.,]\d+)?""",
        RegexOption.IGNORE_CASE,
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val highlighted = buildAnnotatedString {
            append(raw)
            // Apply base token styles first, then strings and comments so they win visually
            timeNumberRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = timeNumberColor), m.range.first, m.range.last + 1)
            }
            commandRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = commandColor), m.range.first, m.range.last + 1)
            }
            keywordRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = keywordColor), m.range.first, m.range.last + 1)
            }
            dotModifierRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = modifierColor), m.range.first, m.range.last + 1)
            }
            varExprRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = variableColor), m.range.first, m.range.last + 1)
            }
            stringRegex.findAll(raw).forEach { m ->
                addStyle(SpanStyle(color = stringColor), m.range.first, m.range.last + 1)
            }
            // Highlight full-line and inline `#` / `//` comments per line
            var offset = 0
            for (line in raw.split('\n')) {
                val commentStart = findCommentStart(line)
                if (commentStart >= 0) {
                    addStyle(SpanStyle(color = commentColor), offset + commentStart, offset + line.length)
                }
                offset += line.length + 1
            }
        }
        return TransformedText(highlighted, OffsetMapping.Identity)
    }

    private fun findCommentStart(line: String): Int {
        val trimmed = line.trimStart()
        if (trimmed.startsWith("#") || trimmed.startsWith("//")) {
            return line.length - trimmed.length
        }
        var quote: Char? = null
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quote != null -> {
                    if (c == '\\' && i + 1 < line.length) {
                        i += 2
                        continue
                    }
                    if (c == quote) quote = null
                }
                c == '"' || c == '\'' -> quote = c
                c == '/' && i + 1 < line.length && line[i + 1] == '/' && (i == 0 || line[i - 1].isWhitespace()) -> return i
                c == '#' && i > 0 && line[i - 1].isWhitespace() &&
                    (i + 1 == line.length || line[i + 1].isWhitespace() || line[i + 1] == '#') -> return i
            }
            i++
        }
        return -1
    }
}

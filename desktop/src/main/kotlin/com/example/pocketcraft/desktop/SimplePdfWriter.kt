package com.example.pocketcraft.desktop

import com.example.pocketcraft.engine.PocketScriptPdfManualGenerator.ManualSection
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.charset.Charset
import java.util.Locale

/**
 * Schreibt die PocketScript-Anleitung als mehrseitiges A4-PDF – ohne Zusatzbibliothek, nur mit den
 * in jedem PDF-Betrachter eingebauten Schriften (Helvetica, Courier). Layout wie die Android-Version.
 */
object SimplePdfWriter {

    private const val PAGE_W = 595f
    private const val PAGE_H = 842f
    private val latin = Charset.forName("windows-1252")

    private class Page {
        val ops = StringBuilder()

        fun rect(x: Float, yTop: Float, w: Float, h: Float, rgb: Int) {
            ops.append(color(rgb, "rg")).append(String.format(Locale.US, "%.2f %.2f %.2f %.2f re f\n", x, PAGE_H - yTop - h, w, h))
        }

        fun text(text: String, x: Float, baselineY: Float, font: String, size: Float, rgb: Int, maxWidth: Float = 0f) {
            val clean = sanitize(text)
            // Zu lange Zeilen leicht verkleinern statt über den Seitenrand zu laufen
            val charWidth = if (font == "F3") 0.6f else 0.5f
            val estimated = clean.length * size * charWidth
            val fitted = if (maxWidth > 0f && estimated > maxWidth) (size * maxWidth / estimated).coerceAtLeast(6f) else size
            ops.append("BT ").append(color(rgb, "rg"))
                .append(String.format(Locale.US, "/%s %.2f Tf %.2f %.2f Td ", font, fitted, x, PAGE_H - baselineY))
                .append('(').append(escape(clean)).append(") Tj ET\n")
        }

        private fun color(rgb: Int, op: String) = String.format(
            Locale.US, "%.3f %.3f %.3f %s ", ((rgb shr 16) and 0xFF) / 255f, ((rgb shr 8) and 0xFF) / 255f, (rgb and 0xFF) / 255f, op,
        )
    }

    /** Zeichen außerhalb von Windows-1252 (z.B. Emojis) ersetzen, da die Standardschriften sie nicht kennen. */
    private fun sanitize(text: String): String {
        val encoder = latin.newEncoder()
        val out = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val chars = Character.charCount(cp)
            when {
                cp == '→'.code -> out.append("->")
                cp == 0xFE0F || cp == 0x200D -> Unit
                chars == 1 && encoder.canEncode(text[i]) -> out.append(text[i])
                else -> if (!out.endsWith("[Emoji]")) out.append("[Emoji]")
            }
            i += chars
        }
        return out.toString()
    }

    private fun escape(text: String): String = buildString {
        for (b in text.toByteArray(latin)) {
            val c = b.toInt() and 0xFF
            when {
                c == '('.code || c == ')'.code || c == '\\'.code -> append('\\').append(c.toChar())
                c in 32..126 -> append(c.toChar())
                else -> append(String.format("\\%03o", c))
            }
        }
    }

    fun writeManual(file: File, sections: List<ManualSection>) {
        val pages = mutableListOf<Page>()
        var page = Page().also { pages.add(it) }

        page.rect(0f, 0f, PAGE_W, 105f, 0x0F172A)
        page.text("POCKETCRAFT — POCKETSCRIPT ANLEITUNG", 32f, 46f, "F3", 20f, 0x00E5FF)
        page.text("Kompakte Videoschnitt-Sprache für das Code-Fenster (Android, macOS, Linux)", 32f, 70f, "F1", 11f, 0xE2E8F0)
        page.text("Beispiele:  all u20%  |  let x = 2  |  for t in 0..12 step 4: ...  |  8-10 set caption: Pinker Honig.red", 32f, 88f, "F1", 11f, 0xE2E8F0, 531f)

        var y = 128f
        for (section in sections) {
            if (y + 60f > 805f) {
                page = Page().also { pages.add(it) }
                y = 48f
            }
            page.text(section.title, 32f, y, "F2", 13f, 0x0F172A, 531f)
            y += 10f
            for ((code, description) in section.codeExamples) {
                if (y + 38f > 810f) {
                    page = Page().also { pages.add(it) }
                    y = 48f
                }
                page.rect(32f, y, 531f, 32f, 0xF1F5F9)
                page.text(code, 40f, y + 13f, "F3", 10.5f, 0x0369A1, 515f)
                page.text(description, 40f, y + 26f, "F1", 10f, 0x334155, 515f)
                y += 37f
            }
            y += 10f
        }

        file.writeBytes(assemble(pages))
    }

    private fun assemble(pages: List<Page>): ByteArray {
        val out = ByteArrayOutputStream()
        val offsets = mutableListOf<Int>()
        fun write(s: String) = out.write(s.toByteArray(latin))
        fun obj(body: String) {
            offsets.add(out.size())
            write("${offsets.size} 0 obj\n$body\nendobj\n")
        }

        write("%PDF-1.4\n")
        // Objekt 1: Katalog, 2: Seitenbaum, 3-5: Schriften, danach je Seite ein Seiten- und ein Inhaltsobjekt
        val firstPageObj = 6
        val kids = pages.indices.joinToString(" ") { "${firstPageObj + it * 2} 0 R" }
        obj("<< /Type /Catalog /Pages 2 0 R >>")
        obj("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>")
        obj("<< /Type /Font /Subtype /Type1 /BaseFont /Courier-Bold /Encoding /WinAnsiEncoding >>")
        for ((i, page) in pages.withIndex()) {
            val content = page.ops.toString()
            obj(
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${PAGE_W.toInt()} ${PAGE_H.toInt()}] " +
                    "/Resources << /Font << /F1 3 0 R /F2 4 0 R /F3 5 0 R >> >> /Contents ${firstPageObj + i * 2 + 1} 0 R >>",
            )
            obj("<< /Length ${content.toByteArray(latin).size} >>\nstream\n${content}endstream")
        }
        val xrefAt = out.size()
        write("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (offset in offsets) write(String.format("%010d 00000 n \n", offset))
        write("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        return out.toByteArray()
    }
}

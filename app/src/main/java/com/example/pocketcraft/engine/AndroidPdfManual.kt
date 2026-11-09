package com.example.pocketcraft.engine

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File

/** Schreibt die PocketScript-Anleitung nativ mit Androids [PdfDocument]-API. */
object AndroidPdfManual {

    /**
     * Generiert eine echte mehrseitige A4 PDF-Datei im App-Speicher (`PocketScript_Anleitung.pdf`).
     */
    fun generatePdfFile(context: Context): File {
        val sections = PocketScriptPdfManualGenerator.sections
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

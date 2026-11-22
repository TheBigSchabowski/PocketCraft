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
        drawFittedText(canvas, "Beispiele:  all u20%  |  0-4 zoom in 0,2  |  set cut 00:33.3  |  8-10 set caption: Pinker Honig.red", 32f, 88f, subPaint, 531f)

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
            drawFittedText(canvas, sec.title, 32f, y, sectionPaint, 531f)
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
                drawFittedText(canvas, code, 40f, y + 13f, codeTextPaint, 515f)
                drawFittedText(canvas, desc, 40f, y + 26f, descTextPaint, 515f)
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

    /** Zeichnet Text innerhalb von [maxWidth], indem zu lange Zeilen proportional verkleinert werden. */
    private fun drawFittedText(
        canvas: Canvas,
        text: String,
        x: Float,
        y: Float,
        basePaint: Paint,
        maxWidth: Float = 515f,
    ) {
        val measured = basePaint.measureText(text)
        if (measured <= maxWidth || measured <= 0f) {
            canvas.drawText(text, x, y, basePaint)
            return
        }
        val fitted = Paint(basePaint).apply {
            textSize = (basePaint.textSize * maxWidth / measured).coerceAtLeast(6f)
        }
        canvas.drawText(text, x, y, fitted)
    }
}

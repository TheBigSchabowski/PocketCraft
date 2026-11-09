package com.example.pocketcraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pocketcraft.engine.PocketScriptPdfManualGenerator
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.platform.LocalPlatformHooks

/**
 * Mehrzeiliges **PocketScript Code-Fenster** für das untere 1/3 (bzw. rechte 1/4 im Querformat).
 *
 * Ersetzt nach 2 Sekunden Gedrückthalten den normalen Schnitt-/Werkzeug-Screen innerhalb
 * des unteren 1/3-Docks, sodass:
 * - die oberen **2/3 Video-Vorschau** komplett sichtbar bleiben,
 * - das **untere 1/3** komplett als mehrzeiliger Code-Editor dient,
 * - die **Enter-Taste** ganz normal eine **neue Zeile** einfügt (`singleLine = false`),
 * - erst das Tippen auf **`▶ Ausführen`** das gesamte Skript Zeile für Zeile ausführt!
 */
@Composable
fun PocketScriptCodePanel(
    engine: ProjectEngine,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val platform = LocalPlatformHooks.current
    val lineCount = remember(engine.scriptEditorText) {
        engine.scriptEditorText.lines().size.coerceAtLeast(1)
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // 1. Kompakte Kopfzeile des Code-Editors (mit Ausführen, PDF-Handbuch, Einfügen, Leeren & Schließen)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = "⚡ POCKETSCRIPT",
                    color = Color(0xFF00E5FF),
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.ExtraBold,
                )
                Text(
                    text = "($lineCount Z.)",
                    color = Color(0xFF64748B),
                    fontSize = 10.5.sp,
                    fontFamily = FontFamily.Monospace,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // PDF-Anleitung öffnen
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { engine.isPdfManualOpen = true },
                ) {
                    Text(
                        text = "📄 PDF",
                        color = Color(0xFFFFD54F),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }

                // Aus Zwischenablage einfügen (Paste)
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable {
                        val clipText = platform.readClipboardText()?.trim()
                        if (!clipText.isNullOrEmpty()) {
                            engine.scriptEditorText = if (engine.scriptEditorText.isBlank()) {
                                clipText
                            } else {
                                engine.scriptEditorText.trimEnd() + "\n" + clipText
                            }
                        }
                    },
                ) {
                    Text(
                        text = "📋",
                        color = Color(0xFFA7F3D0),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                    )
                }

                // Editor leeren
                if (engine.scriptEditorText.isNotEmpty()) {
                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { engine.scriptEditorText = "" },
                    ) {
                        Text(
                            text = "🗑",
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 5.dp),
                        )
                    }
                }

                // Haupt-Button: Führt alle geschriebenen Zeilen aus
                Surface(
                    color = Color(0xFF00E5FF),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable {
                        val script = engine.scriptEditorText.trim()
                        if (script.isNotEmpty()) {
                            engine.executeCommand(script)
                        }
                    },
                ) {
                    Text(
                        text = "Ausführen",
                        color = Color(0xFF04080E),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }

                // Zurück zum normalen Schnitt-Fenster
                Surface(
                    color = Color(0xFF1E293B),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.clickable { onClose() },
                ) {
                    Text(
                        text = "✕",
                        color = Color(0xFFE2E8F0),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    )
                }
            }
        }

        // 2. Mehrzeiliger Code-Editor mit Zeilennummern (Enter erzeugt neue Zeile!)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color(0xFF080B12), RoundedCornerShape(10.dp))
                .border(1.5.dp, Color(0xFF00E5FF).copy(alpha = 0.55f), RoundedCornerShape(10.dp))
                .padding(8.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                // Zeilennummern-Spalte links
                val totalLines = maxOf(lineCount, 4)
                Column(
                    modifier = Modifier.padding(end = 8.dp),
                    horizontalAlignment = Alignment.End,
                ) {
                    for (i in 1..totalLines) {
                        Text(
                            text = "$i",
                            color = Color(0xFF475569),
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                        )
                    }
                }

                // Mehrzeiliges Textfeld (Enter = nächste Zeile!)
                Box(modifier = Modifier.weight(1f)) {
                    if (engine.scriptEditorText.isEmpty()) {
                        Text(
                            text = "Mehrzeiligen Code schreiben (Enter = neue Zeile)…",
                            color = Color(0xFF475569),
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                        )
                    }
                    BasicTextField(
                        value = engine.scriptEditorText,
                        onValueChange = { engine.scriptEditorText = it },
                        singleLine = false,
                        textStyle = TextStyle(
                            color = Color(0xFF00E5FF),
                            fontSize = 12.5.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            lineHeight = 18.sp,
                        ),
                        cursorBrush = SolidColor(Color(0xFFFFB300)),
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Default, // Enter macht immer einen Zeilenumbruch!
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Befehlseingabe" },
                    )
                }
            }
        }
    }
}

/**
 * Dialog zur Anzeige und zum Speichern der generierten **PocketScript PDF-Anleitung**
 * (`PocketScript_Anleitung.pdf`).
 */
@Composable
fun PocketScriptPdfManualDialog(
    engine: ProjectEngine,
    onClose: () -> Unit,
) {
    val platform = LocalPlatformHooks.current
    var savedPdfPath by remember { mutableStateOf<String?>(null) }
    var lastCopiedCommand by remember { mutableStateOf<String?>(null) }

    fun copyCommandToClipboard(rawCode: String) {
        val cleanCmd = PocketScriptPdfManualGenerator.extractCopyableCommand(rawCode)
        platform.copyToClipboard(cleanCmd)
        lastCopiedCommand = cleanCmd
        engine.statusBanner = "In Zwischenablage kopiert: $cleanCmd"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD905080F))
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
                .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(14.dp))
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "📄 POCKETSCRIPT PDF-ANLEITUNG",
                        color = Color(0xFF00E5FF),
                        fontSize = 12.5.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.ExtraBold,
                    )
                    Text(
                        text = "Tippe auf einen Befehl zum Kopieren (Copy & Paste)!",
                        color = Color(0xFF94A3B8),
                        fontSize = 10.5.sp,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = Color(0xFFFFB300),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable {
                            try {
                                val pdfFile = platform.saveManualPdf()
                                savedPdfPath = pdfFile.absolutePath
                                engine.statusBanner = "PDF gespeichert: ${pdfFile.name}"
                            } catch (e: Exception) {
                                engine.statusBanner = "PDF konnte nicht gespeichert werden: ${e.message}"
                            }
                        },
                    ) {
                        Text(
                            text = "💾 PDF speichern",
                            color = Color.Black,
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }

                    Surface(
                        color = Color(0xFF1E293B),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable { onClose() },
                    ) {
                        Text(
                            text = "✕",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                }
            }

            // Grünes Bestätigungs-Banner beim Antippen/Kopieren eines Befehls aus der PDF
            lastCopiedCommand?.let { copied ->
                Surface(
                    color = Color(0xFF064E3B),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "📋 Kopiert: $copied",
                            color = Color(0xFFA7F3D0),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Surface(
                            color = Color(0xFF00E5FF),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier.clickable {
                                engine.scriptEditorText = if (engine.scriptEditorText.isBlank()) {
                                    copied
                                } else {
                                    engine.scriptEditorText.trimEnd() + "\n" + copied
                                }
                                engine.isCommandConsoleOpen = true
                                onClose()
                            },
                        ) {
                            Text(
                                text = "Direkt in Editor ▸",
                                color = Color(0xFF04080E),
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }

            savedPdfPath?.let { path ->
                Surface(
                    color = Color(0xFF064E3B),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "✓ Echte PDF-Datei erstellt unter:\n$path",
                        color = Color(0xFFA7F3D0),
                        fontSize = 10.5.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Color(0xFF090D16), RoundedCornerShape(10.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PocketScriptPdfManualGenerator.sections.forEach { section ->
                    item {
                        Text(
                            text = section.title,
                            color = Color(0xFFFFD54F),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        )
                    }
                    items(section.codeExamples) { (code, explanation) ->
                        val cleanCopyTarget = PocketScriptPdfManualGenerator.extractCopyableCommand(code)
                        val isJustCopied = lastCopiedCommand == cleanCopyTarget
                        Surface(
                            color = if (isJustCopied) Color(0xFF0F292A) else Color(0xFF131B2E),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(
                                    1.dp,
                                    if (isJustCopied) Color(0xFF00E5FF) else Color(0xFF1E293B),
                                    RoundedCornerShape(8.dp),
                                )
                                .clickable { copyCommandToClipboard(code) },
                        ) {
                            Column(modifier = Modifier.padding(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = code,
                                        color = Color(0xFF00E5FF),
                                        fontSize = 11.5.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Text(
                                        text = if (isJustCopied) "✓ Kopiert" else "📋 Kopieren",
                                        color = if (isJustCopied) Color(0xFFA7F3D0) else Color(0xFF94A3B8),
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 8.dp),
                                    )
                                }
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = explanation,
                                    color = Color(0xFFCBD5E1),
                                    fontSize = 10.5.sp,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

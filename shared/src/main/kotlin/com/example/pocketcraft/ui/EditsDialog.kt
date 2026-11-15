package com.example.pocketcraft.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.platform.LocalPlatformHooks

/**
 * Befehlsverlauf des geöffneten Projekts (Befehl `show edits`): alle Konsolen-Eingaben, die das
 * Projekt geändert haben – zum Nachlesen, Kopieren oder zum erneuten Ausführen im Code-Fenster.
 */
@Composable
fun EditsDialog(
    engine: ProjectEngine,
    onClose: () -> Unit,
) {
    val platform = LocalPlatformHooks.current
    val log = engine.project.commandLog
    val listState = rememberLazyListState()
    // Die neuesten Befehle stehen unten – dorthin springen
    LaunchedEffect(log.size) { if (log.isNotEmpty()) listState.scrollToItem(log.lastIndex) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xD905080F))
            .clickable(onClick = onClose)
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
                .border(1.5.dp, Color(0xFF00E5FF), RoundedCornerShape(14.dp))
                // Klicks im Dialog nicht an den Hintergrund (Schließen) weiterreichen
                .clickable(enabled = true, onClick = {})
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "📜 BEFEHLSVERLAUF (${log.size})",
                    color = Color(0xFF00E5FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                DialogChip(label = "✕", onClick = onClose)
            }

            Text(
                text = "Alle Befehle, die „${engine.project.title}“ bisher geändert haben – der neueste steht unten.",
                color = Color(0xFF94A3B8),
                fontSize = 10.5.sp,
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 340.dp)
                    .background(Color(0xFF080B12), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(log) { index, command ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "${index + 1}",
                            color = Color(0xFF64748B),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            modifier = Modifier.widthIn(min = 22.dp),
                        )
                        Text(
                            text = command,
                            color = Color(0xFFE2E8F0),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogChip(
                    label = "📋 Kopieren",
                    onClick = {
                        platform.copyToClipboard(log.joinToString("\n"))
                        engine.statusBanner = "${log.size} Befehle in die Zwischenablage kopiert."
                        onClose()
                    },
                )
                DialogChip(
                    label = "⌨️ Ins Code-Fenster",
                    highlight = true,
                    onClick = {
                        engine.scriptEditorText = log.joinToString("\n")
                        engine.isCommandConsoleOpen = true
                        onClose()
                    },
                )
            }
        }
    }
}

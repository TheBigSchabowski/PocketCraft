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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pocketcraft.engine.ProjectEngine
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Projektverwaltung: aktuelles Projekt umbenennen & speichern, neues Projekt anlegen,
 * gespeicherte Projekte öffnen oder löschen.
 */
@Composable
fun ProjectsDialog(
    engine: ProjectEngine,
    onClose: () -> Unit,
) {
    var nameDraft by remember { mutableStateOf(engine.project.title) }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.GERMANY) }

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
                    text = "📁 PROJEKTE",
                    color = Color(0xFF00E5FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                DialogChip(label = "✕", onClick = onClose)
            }

            // Aktuelles Projekt: Name + Speichern
            Text("Aktuelles Projekt", color = Color(0xFF94A3B8), fontSize = 11.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF080B12), RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    BasicTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color(0xFFE2E8F0), fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                        cursorBrush = SolidColor(Color(0xFFFFB300)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = {
                            engine.statusBanner = engine.saveCurrentProject(nameDraft)
                        }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Projektname" },
                    )
                }
                DialogChip(
                    label = "💾 Speichern",
                    highlight = true,
                    onClick = { engine.statusBanner = engine.saveCurrentProject(nameDraft) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DialogChip(
                    label = "＋ Neues Projekt",
                    onClick = {
                        engine.statusBanner = engine.createNewProject()
                        nameDraft = engine.project.title
                    },
                )
                DialogChip(
                    label = "Als Kopie speichern",
                    onClick = {
                        engine.statusBanner = engine.saveProjectAs(
                            if (nameDraft.trim() == engine.project.title) "${engine.project.title} (Kopie)" else nameDraft,
                        )
                        nameDraft = engine.project.title
                    },
                )
            }

            Text(
                text = "Gespeicherte Projekte (${engine.savedProjects.size})",
                color = Color(0xFF94A3B8),
                fontSize = 11.sp,
            )
            if (engine.savedProjects.isEmpty()) {
                Text(
                    text = "Noch keine Projekte gespeichert. Tippe oben auf 💾 Speichern oder gib 'save' als Befehl ein.",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(engine.savedProjects, key = { it.id }) { summary ->
                        val isCurrent = summary.id == engine.currentProjectId
                        Surface(
                            color = if (isCurrent) Color(0xFF0F292A) else Color(0xFF131B2E),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .border(1.dp, if (isCurrent) Color(0xFF00E5FF) else Color(0xFF1E293B), RoundedCornerShape(8.dp))
                                .clickable {
                                    engine.statusBanner = engine.openProject(summary.id)
                                    nameDraft = engine.project.title
                                    onClose()
                                },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = (if (summary.hasRealVideo) "🎬 " else "✨ ") + summary.title + if (isCurrent) "  (offen)" else "",
                                        color = Color(0xFFE2E8F0),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        text = "${dateFormat.format(Date(summary.savedAtMs))} · ${ProjectEngine.formatSec(summary.durationMs)}",
                                        color = Color(0xFF64748B),
                                        fontSize = 10.sp,
                                    )
                                }
                                if (pendingDeleteId == summary.id) {
                                    DialogChip(
                                        label = "Wirklich löschen?",
                                        danger = true,
                                        onClick = {
                                            engine.statusBanner = engine.deleteProject(summary.id)
                                            pendingDeleteId = null
                                        },
                                    )
                                } else {
                                    DialogChip(label = "🗑", onClick = { pendingDeleteId = summary.id })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogChip(
    label: String,
    highlight: Boolean = false,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    Surface(
        color = when {
            danger -> Color(0xFFFF3B30)
            highlight -> Color(0xFF00E5FF)
            else -> Color(0xFF1E293B)
        },
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            color = if (highlight || danger) Color(0xFF04080E) else Color(0xFFE2E8F0),
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

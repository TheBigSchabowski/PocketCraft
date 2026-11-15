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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pocketcraft.engine.ProjectEngine
import com.example.pocketcraft.model.CanvasOverlay
import com.example.pocketcraft.model.OverlayKind

/**
 * Bearbeiten-Dialog für ein vorhandenes Text- oder Emoji-Overlay. Öffnet sich, wenn ein bereits
 * ausgewähltes Element im Video angetippt wird (oder über "✏️ Bearbeiten" im Text-Tab).
 */
@Composable
fun OverlayEditDialog(
    engine: ProjectEngine,
    overlay: CanvasOverlay,
    onClose: () -> Unit,
) {
    var draft by remember(overlay.id) {
        mutableStateOf(TextFieldValue(overlay.content, selection = TextRange(overlay.content.length)))
    }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(overlay.id) { runCatching { focusRequester.requestFocus() } }

    fun save() {
        engine.updateOverlayContent(overlay.id, draft.text)
        onClose()
    }

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
                    text = if (overlay.kind == OverlayKind.EMOJI) "✏️ EMOJI ÄNDERN" else "✏️ TEXT BEARBEITEN",
                    color = Color(0xFF00E5FF),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
                DialogChip(label = "✕", onClick = onClose)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp, max = 160.dp)
                    .background(Color(0xFF080B12), RoundedCornerShape(8.dp))
                    .border(1.dp, Color(0xFF334155), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = TextStyle(color = Color(0xFFE2E8F0), fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
                    cursorBrush = SolidColor(Color(0xFFFFB300)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .semantics { contentDescription = "Overlay-Text" },
                )
            }

            Text(
                text = "Im Video: ziehen = verschieben · antippen = bearbeiten. Größe und Schrift im Tab „Text & Fonts“.",
                color = Color(0xFF94A3B8),
                fontSize = 10.5.sp,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialogChip(
                    label = "🗑️ Löschen",
                    danger = true,
                    onClick = {
                        engine.removeOverlay(overlay.id)
                        onClose()
                    },
                )
                Box(modifier = Modifier.weight(1f))
                DialogChip(label = "Abbrechen", onClick = onClose)
                DialogChip(label = "✓ Übernehmen", highlight = true, onClick = { save() })
            }
        }
    }
}

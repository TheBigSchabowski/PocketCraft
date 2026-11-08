package com.example.pocketcraft.engine

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.pocketcraft.model.AspectRatioPreset
import com.example.pocketcraft.model.BlurConfig
import com.example.pocketcraft.model.BlurMode
import com.example.pocketcraft.model.BuiltInFont
import com.example.pocketcraft.model.CanvasOverlay
import com.example.pocketcraft.model.CropZoomState
import com.example.pocketcraft.model.CustomFontEntry
import com.example.pocketcraft.model.FilterConfig
import com.example.pocketcraft.model.OverlayKind
import com.example.pocketcraft.model.RemovedRange
import com.example.pocketcraft.model.SubtitleSegment
import com.example.pocketcraft.model.VideoFilterPreset
import com.example.pocketcraft.model.VideoProject
import kotlin.math.roundToInt

/**
 * Protokolleintrag in der Befehlskonsole (inspiriert von storytold/filmcraft JSON/Command-Engine).
 */
data class CommandLogEntry(
    val command: String,
    val response: String,
    val isError: Boolean = false,
)

/**
 * Verfügbare Bearbeitungs-Tabs im Werkzeugbereich (unteres 1/3 im Hochformat, rechtes 1/4 im Querformat).
 */
enum class EditorToolTab(val id: String, val label: String, val iconSymbol: String) {
    TRIM("trim", "Kürzen", "✂️"),
    CROP_ZOOM("crop", "Zoom & Schnitt", "🔍"),
    CAPTIONS("captions", "Untertitel", "💬"),
    TEXT_FONTS("text", "Text & Fonts", "🔤"),
    EMOJIS("emojis", "Emojis", "😎"),
    BLUR_FILTER("fx", "Filter & Blur", "🎨"),
}

/**
 * Zentrale nicht-destruktive Video-Engine für PocketCraft.
 *
 * Funktionsweise:
 * 1. Jede Änderung am [VideoProject] läuft über [commitChange], wodurch der vorherige Zustand
 *    mit einer klaren Beschreibung auf dem [undoStack] landet.
 * 2. Alle Touch-Werkzeuge (Pinch-to-Zoom, Trim-Regler, Emoji-Picker, Blur-Zone, Font-Auswahl)
 *    UND der textbasierte Befehls-Interpreter ([executeCommand]) arbeiten auf exakt demselben
 *    Zustandsbaum.
 * 3. Dadurch kann man eine Aktion per Finger im UI machen und sie danach über die 2-Sekunden-Befehlsseite
 *    per `undo` rückgängig machen – oder umgekehrt einen Befehl wie `zoom 2.0` eintippen und den
 *    Schieberegler sofort synchron an Position 2.0x sehen.
 */
class ProjectEngine(initialProject: VideoProject = VideoProject()) {

    /** Aktueller Zustand des Projekts (beobachtbar durch Compose). */
    var project by mutableStateOf(initialProject)
        private set

    /** Aktuelle Abspielposition auf der Timeline in Millisekunden. */
    var playheadMs by mutableLongStateOf(0L)

    /** Ob die Vorschau gerade abspielt. */
    var isPlaying by mutableStateOf(false)

    /** Aktuell ausgewählter Werkzeug-Tab im Menü. */
    var activeTab by mutableStateOf(EditorToolTab.CROP_ZOOM)

    /** Ob das mehrzeilige PocketScript-Code-Fenster im unteren 1/3 (bzw. rechten 1/4) aktiv ist. */
    var isCommandConsoleOpen by mutableStateOf(false)

    /** Aktueller mehrzeiliger Quelltext im PocketScript-Editor. */
    var scriptEditorText by mutableStateOf("")

    /** Ob die PDF-Anleitung für PocketScript gerade als Vorschau-Dialog geöffnet ist. */
    var isPdfManualOpen by mutableStateOf(false)

    /** Aktuell ausgewähltes Overlay auf dem Canvas (zum Verschieben/Bearbeiten). */
    var selectedOverlayId by mutableStateOf<String?>(initialProject.overlays.firstOrNull()?.id)

    /** Status-Indikator für die On-Device Auto-Caption Spracherkennung (Pixel 9 SpeechRecognizer). */
    var isListeningForCaptions by mutableStateOf(false)

    /** Statusmeldung für den letzten Export oder Auto-Caption-Lauf. Eine neue Meldung entfernt den Banner-Knopf. */
    private var statusBannerState by mutableStateOf<String?>(null)
    var statusBanner: String?
        get() = statusBannerState
        set(value) {
            statusBannerState = value
            bannerAction = null
        }

    /** Optionaler Knopf im Status-Banner (z.B. "▶ In Fotos ansehen" nach dem Export). */
    data class BannerAction(val label: String, val onClick: () -> Unit)

    var bannerAction by mutableStateOf<BannerAction?>(null)

    // =========================================================================
    // Projekte (Speichern / Laden)
    // =========================================================================

    /** Persistenz-Speicher für Projekte (wird von der MainActivity gesetzt; in Tests optional). */
    var projectStore: ProjectStore? = null
        set(value) {
            field = value
            refreshProjectList()
        }

    /** ID des aktuell geöffneten (bereits mindestens einmal gespeicherten) Projekts. */
    var currentProjectId by mutableStateOf<String?>(null)
        private set

    /** Projektzustand beim letzten Speichern/Laden (zum Erkennen ungespeicherter Änderungen). */
    private var lastSavedProject by mutableStateOf<VideoProject?>(null)

    /** Ob der Projekte-Dialog gerade offen ist. */
    var isProjectsDialogOpen by mutableStateOf(false)

    /** Liste aller gespeicherten Projekte (neueste zuerst). */
    val savedProjects = mutableStateListOf<ProjectSummary>()

    /** Callback für den Export (von der MainActivity gesetzt, damit auch der Befehl `export` funktioniert). */
    var onExportRequested: (() -> Unit)? = null

    /** Fortschritt eines laufenden Video-Exports (0–100) oder `null`, wenn gerade nicht exportiert wird. */
    var exportProgress by mutableStateOf<Int?>(null)

    val hasUnsavedChanges: Boolean
        get() = lastSavedProject != project

    /** Verschachtelungstiefe laufender Konsolenbefehle (dort wird nicht zusammengefasst). */
    private var commandDepth = 0
    private var lastCoalesceKey: String? = null
    private var lastCoalesceAtMs = 0L

    private data class HistorySnapshot(
        val description: String,
        val state: VideoProject,
    )

    private val undoStack = mutableStateListOf<HistorySnapshot>()
    private val redoStack = mutableStateListOf<HistorySnapshot>()

    /** Verlauf aller ausgeführten Konsolenbefehle und Antworten. */
    val commandHistory = mutableStateListOf(
        CommandLogEntry(
            command = "help",
            response = "FilmCraft Command Engine bereit. Tippe 'undo', 'redo', 'zoom 1.5', 'crop 1:1', 'trim 1.0 10.0', 'text Hallo', 'emoji 🔥', 'blur bokeh 18', 'filter cyberpunk', 'autocaption' oder 'reset'.",
        ),
    )

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoCount: Int get() = undoStack.size
    val redoCount: Int get() = redoStack.size
    val lastUndoLabel: String? get() = undoStack.lastOrNull()?.description

    /**
     * Führt eine zustandsverändernde Operation auf dem Projekt aus und speichert den
     * vorherigen Zustand auf dem Undo-Stack.
     */
    fun commitChange(
        description: String,
        coalesceKey: String? = null,
        transform: (VideoProject) -> VideoProject,
    ) {
        var next = transform(project)
        // Bei einem echten Video ist die Länge fest vorgegeben: PocketScript-Befehle dürfen die
        // Timeline nicht über das Videoende hinaus "verlängern" (sonst läuft die Vorschau ins Leere).
        if (next.videoUri != null && next.videoUri == project.videoUri && next.sourceDurationMs != project.sourceDurationMs) {
            next = next.clampedToDuration(project.sourceDurationMs)
        }
        if (next == project) return

        // Schnelle Folge-Änderungen derselben Geste (Slider ziehen, Pinch-Zoom, Overlay ziehen)
        // werden zu EINEM Undo-Schritt zusammengefasst, statt den Undo-Stack zu fluten.
        val now = System.currentTimeMillis()
        val coalesce = coalesceKey != null &&
            commandDepth == 0 &&
            coalesceKey == lastCoalesceKey &&
            now - lastCoalesceAtMs < 900L &&
            undoStack.isNotEmpty()
        if (coalesce) {
            val top = undoStack.removeAt(undoStack.lastIndex)
            undoStack.add(top.copy(description = description))
        } else {
            undoStack.add(HistorySnapshot(description = description, state = project))
            if (undoStack.size > 80) {
                undoStack.removeAt(0)
            }
        }
        lastCoalesceKey = if (commandDepth == 0) coalesceKey else null
        lastCoalesceAtMs = now
        redoStack.clear()
        project = next
        clampPlayhead()
    }

    private fun clampPlayhead() {
        if (playheadMs < project.trimStartMs) playheadMs = project.trimStartMs
        if (playheadMs > project.trimEndMs) playheadMs = project.trimEndMs
    }

    /**
     * Rückt die Demo-Vorschau um [deltaMs] weiter: springt über herausgeschnittene Bereiche
     * und beginnt am Trim-Ende wieder beim Trim-Start.
     */
    fun advancePlayhead(deltaMs: Long) {
        val p = project
        var next = playheadMs + deltaMs
        if (next < p.trimStartMs) next = p.trimStartMs
        next = p.resolvePlayablePosition(next)
        if (next >= p.trimEndMs) {
            next = p.resolvePlayablePosition(p.trimStartMs)
        }
        playheadMs = next
    }

    /**
     * Übernimmt die tatsächliche Länge des geladenen Videos (vom Player gemeldet),
     * OHNE Schnitte, Untertitel usw. zurückzusetzen und ohne Undo-Eintrag.
     */
    fun syncRealVideoDuration(durationMs: Long) {
        if (durationMs <= 0L || project.videoUri == null) return
        if (durationMs == project.sourceDurationMs) return
        val wasFullLength = project.trimEndMs >= project.sourceDurationMs - 200L
        var synced = project.clampedToDuration(durationMs)
        if (wasFullLength) synced = synced.copy(trimEndMs = durationMs)
        val savedWasInSync = lastSavedProject == project
        project = synced
        if (savedWasInSync) lastSavedProject = synced
        clampPlayhead()
    }

    /** Fügt einen herausgeschnittenen Bereich hinzu (überlappende Bereiche werden zusammengeführt). */
    fun addRemovedRange(startMs: Long, endMs: Long): Boolean {
        val s = minOf(startMs, endMs).coerceAtLeast(0L)
        val e = maxOf(startMs, endMs).coerceAtMost(project.sourceDurationMs)
        if (e - s < 100L) return false
        commitChange("Bereich ${formatSec(s)}–${formatSec(e)} herausgeschnitten") { p ->
            val merged = mutableListOf<RemovedRange>()
            for (r in (p.removedRanges + RemovedRange(s, e)).sortedBy { it.startMs }) {
                val last = merged.lastOrNull()
                if (last != null && r.startMs <= last.endMs) {
                    merged[merged.lastIndex] = last.copy(endMs = maxOf(last.endMs, r.endMs))
                } else {
                    merged.add(r)
                }
            }
            p.copy(removedRanges = merged)
        }
        playheadMs = project.resolvePlayablePosition(playheadMs)
        return true
    }

    // =========================================================================
    // Projekte: Speichern, Laden, Neu, Löschen
    // =========================================================================

    fun refreshProjectList() {
        val list = projectStore?.list().orEmpty()
        savedProjects.clear()
        savedProjects.addAll(list)
    }

    /** Speichert das aktuelle Projekt (legt beim ersten Mal ein neues Projekt an). */
    fun saveCurrentProject(newTitle: String? = null): String {
        val store = projectStore ?: return "Speichern nicht möglich (kein Projektspeicher verfügbar)."
        val cleanTitle = newTitle?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanTitle != null && cleanTitle != project.title) {
            commitChange("Projekt umbenannt in '$cleanTitle'") { it.copy(title = cleanTitle) }
        }
        val id = currentProjectId ?: newProjectId()
        return try {
            store.save(id, project)
            currentProjectId = id
            lastSavedProject = project
            refreshProjectList()
            "Projekt '${project.title}' gespeichert."
        } catch (e: Exception) {
            "Speichern fehlgeschlagen: ${e.message}"
        }
    }

    /** Speichert das aktuelle Projekt als NEUES Projekt unter einem neuen Namen. */
    fun saveProjectAs(title: String): String {
        val clean = title.trim().ifEmpty { "${project.title} (Kopie)" }
        currentProjectId = null
        lastSavedProject = null
        return saveCurrentProject(clean)
    }

    /** Speichert still im Hintergrund, falls es ungespeicherte Änderungen gibt. */
    fun autosave() {
        if (projectStore == null || !hasUnsavedChanges) return
        // Unberührtes Demo-Projekt nicht als eigenes Projekt anlegen
        if (currentProjectId == null && undoStack.isEmpty()) return
        saveCurrentProject()
    }

    fun openProject(id: String): String {
        val store = projectStore ?: return "Kein Projektspeicher verfügbar."
        if (id == currentProjectId && !hasUnsavedChanges) {
            return "Projekt '${project.title}' ist bereits geöffnet."
        }
        autosave()
        val saved = store.load(id) ?: return "Projekt konnte nicht geladen werden."
        replaceProject(saved.project)
        currentProjectId = saved.id
        lastSavedProject = saved.project
        store.setLastOpened(saved.id)
        refreshProjectList()
        return "Projekt '${saved.project.title}' geöffnet."
    }

    /** Sucht ein Projekt per Name (Teilstring) oder Listennummer (1 = neuestes). */
    fun findProject(query: String): ProjectSummary? {
        refreshProjectList()
        val q = query.trim().lowercase()
        q.toIntOrNull()?.let { idx -> return savedProjects.getOrNull(idx - 1) }
        return savedProjects.firstOrNull { it.title.lowercase() == q }
            ?: savedProjects.firstOrNull { it.title.lowercase().contains(q) }
    }

    fun createNewProject(title: String? = null): String {
        autosave()
        val nextNumber = savedProjects.size + 1
        val cleanTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: "Projekt $nextNumber"
        replaceProject(VideoProject(title = cleanTitle, customFonts = project.customFonts))
        currentProjectId = null
        lastSavedProject = null
        return "Neues Projekt '$cleanTitle' erstellt (wird beim Speichern angelegt)."
    }

    fun deleteProject(id: String): String {
        val store = projectStore ?: return "Kein Projektspeicher verfügbar."
        val title = savedProjects.firstOrNull { it.id == id }?.title ?: "Projekt"
        store.delete(id)
        if (id == currentProjectId) {
            currentProjectId = null
            lastSavedProject = null
        }
        refreshProjectList()
        return "Projekt '$title' gelöscht."
    }

    /** Lädt beim App-Start das zuletzt geöffnete Projekt. */
    fun restoreLastSession() {
        val store = projectStore ?: return
        val id = store.lastOpenedId() ?: return
        val saved = store.load(id) ?: return
        replaceProject(saved.project)
        currentProjectId = saved.id
        lastSavedProject = saved.project
    }

    private fun replaceProject(newProject: VideoProject) {
        isPlaying = false
        undoStack.clear()
        redoStack.clear()
        lastCoalesceKey = null
        project = newProject
        playheadMs = newProject.resolvePlayablePosition(newProject.trimStartMs)
        selectedOverlayId = newProject.overlays.firstOrNull()?.id
    }

    private fun newProjectId(): String = "p_${System.currentTimeMillis()}"

    /**
     * Macht den letzten Bearbeitungsschritt rückgängig (`undo`).
     */
    fun undo(): String {
        if (undoStack.isEmpty()) {
            return "Nichts zum Rückgängigmachen (Undo-Stack ist leer)."
        }
        val snapshot = undoStack.removeAt(undoStack.lastIndex)
        redoStack.add(HistorySnapshot(description = snapshot.description, state = project))
        project = snapshot.state
        lastCoalesceKey = null
        clampPlayhead()
        return "Rückgängig gemacht: ${snapshot.description}"
    }

    /**
     * Stellt den zuletzt rückgängig gemachten Schritt wieder her (`redo`).
     */
    fun redo(): String {
        if (redoStack.isEmpty()) {
            return "Nichts zum Wiederherstellen (Redo-Stack ist leer)."
        }
        val snapshot = redoStack.removeAt(redoStack.lastIndex)
        undoStack.add(HistorySnapshot(description = snapshot.description, state = project))
        project = snapshot.state
        lastCoalesceKey = null
        clampPlayhead()
        return "Wiederhergestellt: ${snapshot.description}"
    }

    // =========================================================================
    // Direkt-Methoden für UI & Gesten
    // =========================================================================

    fun setZoomAndPan(
        zoom: Float = project.transform.zoom,
        panX: Float = project.transform.panX,
        panY: Float = project.transform.panY,
    ) {
        val clampedZoom = zoom.coerceIn(1.0f, 5.0f)
        val clampedPanX = panX.coerceIn(-1.0f, 1.0f)
        val clampedPanY = panY.coerceIn(-1.0f, 1.0f)
        commitChange("Zoom ${(clampedZoom * 10).roundToInt() / 10f}x", coalesceKey = "zoom") { p ->
            p.copy(
                transform = p.transform.copy(
                    zoom = clampedZoom,
                    panX = clampedPanX,
                    panY = clampedPanY,
                ),
            )
        }
    }

    fun setCropMargins(left: Float, top: Float, right: Float, bottom: Float) {
        val safeLeft = left.coerceIn(0f, 0.98f)
        val safeTop = top.coerceIn(0f, 0.98f)
        val safeRight = right.coerceIn((safeLeft + 0.02f).coerceAtMost(1.0f), 1.0f)
        val safeBottom = bottom.coerceIn((safeTop + 0.02f).coerceAtMost(1.0f), 1.0f)
        commitChange("Bild kleiner geschnitten", coalesceKey = "crop") { p ->
            p.copy(
                transform = p.transform.copy(
                    cropLeft = safeLeft,
                    cropTop = safeTop,
                    cropRight = safeRight,
                    cropBottom = safeBottom,
                ),
            )
        }
    }

    fun setRotation(degrees: Float) {
        val norm = ((degrees % 360f) + 360f) % 360f
        commitChange("Rotation ${norm.roundToInt()}°") { p ->
            p.copy(transform = p.transform.copy(rotationDegrees = norm))
        }
    }

    fun setAspectRatio(preset: AspectRatioPreset) {
        commitChange("Format ${preset.label}") { p ->
            p.copy(aspectRatio = preset)
        }
    }

    fun setTrimRange(startMs: Long, endMs: Long) {
        val safeStart = startMs.coerceIn(0L, (project.sourceDurationMs - 500L).coerceAtLeast(0L))
        val safeEnd = endMs.coerceIn(safeStart + 500L, project.sourceDurationMs)
        commitChange("Clip gekürzt (${formatSec(safeStart)} - ${formatSec(safeEnd)})", coalesceKey = "trim") { p ->
            p.copy(trimStartMs = safeStart, trimEndMs = safeEnd)
        }
    }

    fun splitAtPlayhead() {
        val current = playheadMs
        if (current <= project.trimStartMs + 500L || current >= project.trimEndMs - 500L) {
            statusBanner = "Playhead muss für Schnitt mindestens 0,5s vom Rand entfernt sein."
            return
        }
        // Schneidet den vorderen Teil bis zum Playhead weg oder setzt In-Point auf Playhead
        commitChange("Schnitt bei ${formatSec(current)}") { p ->
            p.copy(trimStartMs = current)
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.25f, 3.0f)
        commitChange("Geschwindigkeit ${clamped}x") { p ->
            p.copy(playbackSpeed = clamped)
        }
    }

    fun setFilterPreset(preset: VideoFilterPreset, intensity: Float = project.filter.intensity) {
        commitChange("Filter ${preset.displayName}") { p ->
            p.copy(filter = p.filter.copy(preset = preset, intensity = intensity.coerceIn(0f, 1f)))
        }
    }

    fun updateFilterAdjustments(
        intensity: Float = project.filter.intensity,
        brightness: Float = project.filter.brightnessOffset,
        contrast: Float = project.filter.contrastMultiplier,
        saturation: Float = project.filter.saturationMultiplier,
        vignette: Float = project.filter.vignetteStrength,
    ) {
        commitChange("Farbkorrektur angepasst", coalesceKey = "filteradj") { p ->
            p.copy(
                filter = p.filter.copy(
                    intensity = intensity.coerceIn(0f, 1f),
                    brightnessOffset = brightness.coerceIn(-0.5f, 0.5f),
                    contrastMultiplier = contrast.coerceIn(0.5f, 2.0f),
                    saturationMultiplier = saturation.coerceIn(0.0f, 2.5f),
                    vignetteStrength = vignette.coerceIn(0.0f, 1.0f),
                ),
            )
        }
    }

    fun setBlur(
        mode: BlurMode = project.blur.mode,
        radiusPx: Float = project.blur.radiusPx,
        centerX: Float = project.blur.zoneCenterX,
        centerY: Float = project.blur.zoneCenterY,
        width: Float = project.blur.zoneWidth,
        height: Float = project.blur.zoneHeight,
    ) {
        commitChange("Blur: ${mode.label}", coalesceKey = "blur") { p ->
            p.copy(
                blur = BlurConfig(
                    mode = mode,
                    radiusPx = radiusPx.coerceIn(0f, 45f),
                    zoneCenterX = centerX.coerceIn(0.1f, 0.9f),
                    zoneCenterY = centerY.coerceIn(0.1f, 0.9f),
                    zoneWidth = width.coerceIn(0.15f, 0.9f),
                    zoneHeight = height.coerceIn(0.12f, 0.9f),
                ),
            )
        }
    }

    fun addTextOverlay(
        text: String,
        fontId: String = project.activeFontId,
        colorArgb: Long = 0xFF00E5FF,
    ) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val newId = "ov_text_${project.overlays.size + 1}_${clean.hashCode()}"
        val overlay = CanvasOverlay(
            id = newId,
            kind = OverlayKind.TEXT,
            content = clean,
            xPercent = 0.5f,
            yPercent = (0.25f + (project.overlays.size % 4) * 0.12f).coerceIn(0.15f, 0.75f),
            scale = 1.0f,
            fontId = fontId,
            colorArgb = colorArgb,
            startMs = project.trimStartMs,
            endMs = project.trimEndMs,
        )
        commitChange("Text hinzugefügt: \"$clean\"") { p ->
            p.copy(overlays = p.overlays + overlay)
        }
        selectedOverlayId = newId
    }

    fun addEmojiOverlay(emoji: String) {
        val clean = emoji.trim()
        if (clean.isEmpty()) return
        val newId = "ov_emoji_${project.overlays.size + 1}_${clean.hashCode()}"
        val overlay = CanvasOverlay(
            id = newId,
            kind = OverlayKind.EMOJI,
            content = clean,
            xPercent = (0.3f + (project.overlays.size % 5) * 0.11f).coerceIn(0.2f, 0.8f),
            yPercent = (0.3f + (project.overlays.size % 3) * 0.15f).coerceIn(0.2f, 0.7f),
            scale = 1.3f,
            startMs = project.trimStartMs,
            endMs = project.trimEndMs,
        )
        commitChange("Emoji hinzugefügt: $clean") { p ->
            p.copy(overlays = p.overlays + overlay)
        }
        selectedOverlayId = newId
    }

    fun moveOverlay(id: String, newXPercent: Float, newYPercent: Float) {
        val target = project.overlays.firstOrNull { it.id == id } ?: return
        val clampedX = newXPercent.coerceIn(0.05f, 0.95f)
        val clampedY = newYPercent.coerceIn(0.05f, 0.95f)
        commitChange("Overlay '${target.content}' verschoben", coalesceKey = "move:$id") { p ->
            p.copy(
                overlays = p.overlays.map {
                    if (it.id == id) it.copy(xPercent = clampedX, yPercent = clampedY) else it
                },
            )
        }
    }

    fun scaleOverlay(id: String, newScale: Float, newRotation: Float? = null) {
        commitChange("Overlay skaliert", coalesceKey = "scale:$id") { p ->
            p.copy(
                overlays = p.overlays.map {
                    if (it.id == id) {
                        it.copy(
                            scale = newScale.coerceIn(0.4f, 3.5f),
                            rotationDegrees = newRotation ?: it.rotationDegrees,
                        )
                    } else {
                        it
                    }
                },
            )
        }
    }

    fun removeOverlay(id: String) {
        val target = project.overlays.firstOrNull { it.id == id } ?: return
        commitChange("Overlay '${target.content}' gelöscht") { p ->
            p.copy(overlays = p.overlays.filterNot { it.id == id })
        }
        if (selectedOverlayId == id) {
            selectedOverlayId = project.overlays.firstOrNull()?.id
        }
    }

    fun selectFont(fontId: String) {
        commitChange("Schriftart geändert ($fontId)") { p ->
            val updatedOverlays = p.overlays.map { ov ->
                if (ov.id == selectedOverlayId && ov.kind == OverlayKind.TEXT) {
                    ov.copy(fontId = fontId)
                } else {
                    ov
                }
            }
            val updatedSubs = p.subtitles.map { sub ->
                sub.copy(fontId = fontId)
            }
            p.copy(
                activeFontId = fontId,
                overlays = updatedOverlays,
                subtitles = updatedSubs,
            )
        }
    }

    fun registerCustomFont(displayName: String, filePath: String): CustomFontEntry {
        val id = "custom_${displayName.lowercase().replace(Regex("[^a-z0-9]"), "_")}_${project.customFonts.size + 1}"
        val entry = CustomFontEntry(id = id, displayName = displayName, filePath = filePath)
        commitChange("Eigene Schriftart importiert: $displayName") { p ->
            p.copy(
                customFonts = p.customFonts + entry,
                activeFontId = id,
                subtitles = p.subtitles.map { it.copy(fontId = id) },
            )
        }
        return entry
    }

    fun addOrUpdateSubtitle(text: String, startMs: Long = playheadMs, durationMs: Long = 3_000L) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        val safeStart = startMs.coerceIn(0L, (project.sourceDurationMs - 500L).coerceAtLeast(0L))
        val safeEnd = (safeStart + durationMs).coerceAtMost(project.sourceDurationMs)
        val sub = SubtitleSegment(
            id = "sub_${project.subtitles.size + 1}_${clean.hashCode()}",
            startMs = safeStart,
            endMs = safeEnd,
            text = clean,
            fontId = project.activeFontId,
        )
        commitChange("Untertitel hinzugefügt: \"$clean\"") { p ->
            p.copy(subtitles = (p.subtitles + sub).sortedBy { it.startMs })
        }
    }

    fun updateSubtitleText(id: String, newText: String) {
        commitChange("Untertitel bearbeitet") { p ->
            p.copy(
                subtitles = p.subtitles.map {
                    if (it.id == id) it.copy(text = newText) else it
                },
            )
        }
    }

    fun removeSubtitle(id: String) {
        commitChange("Untertitel gelöscht") { p ->
            p.copy(subtitles = p.subtitles.filterNot { it.id == id })
        }
    }

    /**
     * Erzeugt intelligente Auto-Captions (entweder aus diktiertem Text des Pixel 9 SpeechRecognizers
     * oder als rhythmische Wort-für-Wort-Segmentierung über die Videodauer).
     */
    fun generateAutoCaptions(spokenTranscript: String? = null) {
        val rawText = spokenTranscript?.takeIf { it.isNotBlank() }
            ?: "Hey Leute willkommen zum neuen Pixel 9 Video! Heute schneiden wir direkt auf dem Handy mit Zoom, eigenen Fonts, Blur und der schnellen Befehls-Konsole."

        val words = rawText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val chunkSize = 5
        val chunks = words.chunked(chunkSize).map { it.joinToString(" ") }
        if (chunks.isEmpty()) return

        val totalMs = project.trimmedDurationMs
        val perChunkMs = (totalMs / chunks.size).coerceAtLeast(1_200L)

        val generated = chunks.mapIndexed { index, chunkText ->
            val start = project.trimStartMs + index * perChunkMs
            val end = (start + perChunkMs).coerceAtMost(project.trimEndMs)
            SubtitleSegment(
                id = "auto_sub_${index + 1}",
                startMs = start,
                endMs = end,
                text = chunkText,
                fontId = project.activeFontId,
            )
        }

        commitChange("Auto-Captions generiert (${generated.size} Segmente)") { p ->
            p.copy(subtitles = generated)
        }
        statusBanner = "Auto-Captions erstellt: ${generated.size} Untertitel-Segmente synchronisiert."
    }

    fun setVideoSource(uriString: String?, durationMs: Long, title: String = "Pixel 9 Video") {
        val safeDuration = durationMs.coerceAtLeast(2_000L)
        commitChange("Video geladen: $title") { p ->
            p.copy(
                title = title,
                videoUri = uriString,
                sourceDurationMs = safeDuration,
                trimStartMs = 0L,
                trimEndMs = safeDuration,
                cutMarkers = emptyList(),
                removedRanges = emptyList(),
            )
        }
        playheadMs = 0L
    }

    // =========================================================================
    // FilmCraft Command Interpreter (für die 2-Sekunden-Befehlsseite)
    // =========================================================================

    /**
     * Parst und führt einen Textbefehl aus der Befehlskonsole aus.
     * Unterstützt natürliche Kurz-Kommandos wie `undo`, `redo`, `zoom 1.8`, `crop 1:1`,
     * `trim 2 12`, `filter cyberpunk`, `blur bokeh 20`, `text Hallo Welt`, `emoji 🔥`, `autocaption`, `reset`.
     */
    fun executeCommand(rawInput: String): String {
        commandDepth++
        try {
            return executeCommandInternal(rawInput)
        } finally {
            commandDepth--
        }
    }

    private fun executeCommandInternal(rawInput: String): String {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return ""

        // 1. Zuerst prüfen, ob es ein PocketScript-Ausdruck ist:
        //    z.B. `10-24,3 (8:15) 9 (16:18)`, `all (9:16)` oder `8-10 set caption: Pinker Honig.red`
        val scriptResult = PocketScriptParser.tryExecuteScript(trimmed, this)
        if (scriptResult != null) {
            statusBanner = scriptResult
            commandHistory.add(
                CommandLogEntry(
                    command = trimmed,
                    response = scriptResult,
                    isError = false,
                ),
            )
            return scriptResult
        }

        val tokens = trimmed.split(Regex("\\s+"))
        val verb = tokens.first().lowercase()
        val args = tokens.drop(1)

        val resultMessage: String
        var isError = false

        when (verb) {
            "pdf", "manual", "anleitung", "handbuch" -> {
                isPdfManualOpen = true
                resultMessage = "PocketScript PDF-Anleitung geöffnet."
            }

            "save", "speichern", "sichern", "s" -> {
                if (args.firstOrNull()?.lowercase() in listOf("as", "als", "unter")) {
                    resultMessage = saveProjectAs(args.drop(1).joinToString(" "))
                } else {
                    resultMessage = saveCurrentProject(args.joinToString(" ").takeIf { it.isNotBlank() })
                }
                isError = resultMessage.startsWith("Speichern")
            }

            "saveas" -> {
                resultMessage = saveProjectAs(args.joinToString(" "))
            }

            "projects", "projekte", "project", "projekt" -> {
                refreshProjectList()
                isProjectsDialogOpen = true
                resultMessage = if (savedProjects.isEmpty()) {
                    "Noch keine gespeicherten Projekte. Tippe 'save <Name>' zum Speichern."
                } else {
                    "Projekte: " + savedProjects.mapIndexed { i, s -> "${i + 1}. ${s.title}" }.joinToString(" · ")
                }
            }

            "open", "load", "öffnen", "oeffnen", "laden" -> {
                val query = args.joinToString(" ").trim()
                if (query.isEmpty()) {
                    refreshProjectList()
                    isProjectsDialogOpen = true
                    resultMessage = "Projekte-Liste geöffnet."
                } else {
                    val found = findProject(query)
                    if (found == null) {
                        isError = true
                        resultMessage = "Kein Projekt '$query' gefunden. Tippe 'projects' für die Liste."
                    } else {
                        resultMessage = openProject(found.id)
                    }
                }
            }

            "new", "neu" -> {
                resultMessage = createNewProject(args.joinToString(" "))
            }

            "rename", "umbenennen" -> {
                val name = args.joinToString(" ").trim()
                if (name.isEmpty()) {
                    isError = true
                    resultMessage = "Syntax: rename <Neuer Projektname>"
                } else {
                    commitChange("Projekt umbenannt in '$name'") { it.copy(title = name) }
                    resultMessage = if (currentProjectId != null) saveCurrentProject() else "Projekt heißt jetzt '$name'."
                }
            }

            "export" -> {
                val exporter = onExportRequested
                if (exporter == null) {
                    isError = true
                    resultMessage = "Export ist hier nicht verfügbar."
                } else {
                    exporter()
                    resultMessage = if (exportProgress != null) "Video-Export läuft…" else (statusBanner ?: "Export gestartet…")
                }
            }

            "undo", "u", "zurück", "zurueck" -> {
                resultMessage = undo()
            }

            "redo", "r", "wiederholen" -> {
                resultMessage = redo()
            }

            "zoom", "z" -> {
                val factor = args.firstOrNull()?.replace(",", ".")?.toFloatOrNull()
                if (factor == null) {
                    isError = true
                    resultMessage = "Syntax: zoom <Faktor 1.0 - 5.0>, z.B. 'zoom 1.8'"
                } else {
                    setZoomAndPan(zoom = factor)
                    resultMessage = "Zoom auf ${project.transform.zoom}x gesetzt."
                }
            }

            "pan", "move" -> {
                val x = args.getOrNull(0)?.replace(",", ".")?.toFloatOrNull()
                val y = args.getOrNull(1)?.replace(",", ".")?.toFloatOrNull()
                if (x == null || y == null) {
                    isError = true
                    resultMessage = "Syntax: pan <x -1..1> <y -1..1>, z.B. 'pan 0.2 -0.1'"
                } else {
                    setZoomAndPan(panX = x, panY = y)
                    resultMessage = "Bildausschnitt verschoben auf (${project.transform.panX}, ${project.transform.panY})."
                }
            }

            "crop", "format", "ratio" -> {
                val arg = args.firstOrNull()?.lowercase()
                if (arg == null) {
                    isError = true
                    resultMessage = "Syntax: crop <9:16 | 1:1 | 4:5 | 16:9 | insetProzent>, z.B. 'crop 1:1' oder 'crop 15'"
                } else {
                    val preset = AspectRatioPreset.entries.firstOrNull {
                        it.cliAlias.lowercase() == arg || it.label.lowercase().contains(arg)
                    }
                    val insetPercent = arg.replace("%", "").toFloatOrNull()
                    when {
                        preset != null -> {
                            setAspectRatio(preset)
                            resultMessage = "Seitenverhältnis auf ${preset.label} gesetzt."
                        }
                        insetPercent != null -> {
                            val margin = (insetPercent / 100f).coerceIn(0f, 0.4f)
                            setCropMargins(margin, margin, 1f - margin, 1f - margin)
                            resultMessage = "Bildränder um ${insetPercent.roundToInt()}% kleiner geschnitten."
                        }
                        else -> {
                            isError = true
                            resultMessage = "Unbekanntes Crop-Format '$arg'. Nutze 9:16, 1:1, 4:5, 16:9 oder Prozentzahl (z.B. crop 15)."
                        }
                    }
                }
            }

            "trim", "cut", "kürzen", "kuerzen" -> {
                val startSec = args.getOrNull(0)?.replace(",", ".")?.toFloatOrNull()
                val endSec = args.getOrNull(1)?.replace(",", ".")?.toFloatOrNull()
                if (startSec == null || endSec == null) {
                    isError = true
                    resultMessage = "Syntax: trim <StartSek> <EndeSek>, z.B. 'trim 1.5 10.0'"
                } else {
                    setTrimRange((startSec * 1000).toLong(), (endSec * 1000).toLong())
                    resultMessage = "Video gekürzt auf ${formatSec(project.trimStartMs)} bis ${formatSec(project.trimEndMs)}."
                }
            }

            "split" -> {
                splitAtPlayhead()
                resultMessage = "Clip am Playhead (${formatSec(playheadMs)}) geteilt."
            }

            "speed", "tempo" -> {
                val s = args.firstOrNull()?.replace(",", ".")?.toFloatOrNull()
                if (s == null) {
                    isError = true
                    resultMessage = "Syntax: speed <0.25 - 3.0>, z.B. 'speed 1.5'"
                } else {
                    setPlaybackSpeed(s)
                    resultMessage = "Abspielgeschwindigkeit auf ${project.playbackSpeed}x gesetzt."
                }
            }

            "filter", "fx" -> {
                val name = args.firstOrNull()
                if (name == null) {
                    isError = true
                    resultMessage = "Verfügbare Filter: ${VideoFilterPreset.entries.joinToString { it.id }}"
                } else {
                    val preset = VideoFilterPreset.fromNameOrId(name)
                    if (preset == null) {
                        isError = true
                        resultMessage = "Filter '$name' nicht gefunden. Verfügbar: ${VideoFilterPreset.entries.joinToString { it.id }}"
                    } else {
                        val intensity = args.getOrNull(1)?.replace(",", ".")?.toFloatOrNull() ?: 1.0f
                        setFilterPreset(preset, intensity)
                        resultMessage = "Filter '${preset.displayName}' aktiviert (Intensität ${(intensity * 100).roundToInt()}%)."
                    }
                }
            }

            "blur", "unschärfe", "unschaerfe" -> {
                val first = args.firstOrNull()?.lowercase()
                if (first == null || first == "off" || first == "aus" || first == "0") {
                    setBlur(mode = BlurMode.OFF)
                    resultMessage = "Blur deaktiviert."
                } else {
                    val numericRadius = first.replace(",", ".").toFloatOrNull()
                    if (numericRadius != null) {
                        val mode = if (project.blur.mode == BlurMode.OFF) BlurMode.BACKGROUND_BOKEH else project.blur.mode
                        setBlur(mode = mode, radiusPx = numericRadius)
                        resultMessage = "Blur (${mode.label}) auf Radius ${numericRadius.roundToInt()}px gesetzt."
                    } else {
                        val matchedMode = BlurMode.entries.firstOrNull {
                            it.id == first || it.label.lowercase().contains(first)
                        } ?: BlurMode.BACKGROUND_BOKEH
                        val radius = args.getOrNull(1)?.replace(",", ".")?.toFloatOrNull() ?: 18f
                        setBlur(mode = matchedMode, radiusPx = radius)
                        resultMessage = "Blur-Modus '${matchedMode.label}' mit Radius ${radius.roundToInt()}px aktiviert."
                    }
                }
            }

            "text", "title", "titel" -> {
                val content = args.joinToString(" ").trim()
                if (content.isEmpty()) {
                    isError = true
                    resultMessage = "Syntax: text <Dein Text>, z.B. 'text Abonnieren!'"
                } else {
                    addTextOverlay(content)
                    resultMessage = "Text-Overlay '$content' hinzugefügt."
                }
            }

            "emoji", "sticker" -> {
                val emoji = args.firstOrNull() ?: "🔥"
                addEmojiOverlay(emoji)
                resultMessage = "Emoji-Overlay $emoji hinzugefügt."
            }

            "caption", "sub", "untertitel" -> {
                val content = args.joinToString(" ").trim()
                if (content.isEmpty()) {
                    isError = true
                    resultMessage = "Syntax: caption <Untertitel-Text> oder 'autocaption'"
                } else {
                    addOrUpdateSubtitle(content)
                    resultMessage = "Untertitel bei ${formatSec(playheadMs)} eingefügt: \"$content\""
                }
            }

            "autocaption", "autocaptions", "transcribe" -> {
                val customSpeech = args.joinToString(" ").takeIf { it.isNotBlank() }
                generateAutoCaptions(customSpeech)
                resultMessage = "Auto-Captions für Pixel 9 generiert (${project.subtitles.size} Segmente)."
            }

            "font", "schrift" -> {
                val query = args.joinToString(" ").trim().lowercase()
                if (query.isEmpty()) {
                    isError = true
                    resultMessage = "Verfügbare Fonts: ${BuiltInFont.entries.joinToString { it.id }}"
                } else {
                    val custom = project.customFonts.firstOrNull {
                        it.id.lowercase().contains(query) || it.displayName.lowercase().contains(query)
                    }
                    val builtIn = BuiltInFont.entries.firstOrNull {
                        it.id.lowercase().contains(query) || it.displayName.lowercase().contains(query)
                    }
                    when {
                        custom != null -> {
                            selectFont(custom.id)
                            resultMessage = "Eigene Schriftart '${custom.displayName}' aktiviert."
                        }
                        builtIn != null -> {
                            selectFont(builtIn.id)
                            resultMessage = "Schriftart '${builtIn.displayName}' aktiviert."
                        }
                        else -> {
                            isError = true
                            resultMessage = "Schriftart '$query' nicht gefunden."
                        }
                    }
                }
            }

            "rotate", "drehen" -> {
                val deg = args.firstOrNull()?.toFloatOrNull() ?: 90f
                setRotation(project.transform.rotationDegrees + deg)
                resultMessage = "Video auf ${project.transform.rotationDegrees.roundToInt()}° gedreht."
            }

            "clear", "delete", "del", "remove", "lösche", "loesche", "entferne" -> {
                val target = args.joinToString(" ").trim()
                if (target.isEmpty() && verb == "clear") {
                    commitChange("Projekt zurückgesetzt") {
                        VideoProject(
                            title = project.title,
                            videoUri = project.videoUri,
                            sourceDurationMs = project.sourceDurationMs,
                            trimEndMs = project.sourceDurationMs,
                            customFonts = project.customFonts,
                        )
                    }
                    resultMessage = "Alle Effekte, Zoom und Schnitte auf Ausgangszustand zurückgesetzt."
                } else {
                    val (msg, err) = clearElements(target)
                    resultMessage = msg
                    isError = err
                }
            }

            "captions", "subs", "list" -> {
                val subs = project.subtitles.sortedBy { it.startMs }
                resultMessage = if (subs.isEmpty()) {
                    "Keine Untertitel vorhanden."
                } else {
                    subs.mapIndexed { i, sub -> "caption${i + 1}: \"${sub.text}\" (${formatSec(sub.startMs)}–${formatSec(sub.endMs)})" }
                        .joinToString(" · ")
                }
            }

            "reset" -> {
                commitChange("Projekt zurückgesetzt") {
                    VideoProject(
                        title = project.title,
                        videoUri = project.videoUri,
                        sourceDurationMs = project.sourceDurationMs,
                        trimEndMs = project.sourceDurationMs,
                        customFonts = project.customFonts,
                    )
                }
                resultMessage = "Alle Effekte, Zoom und Schnitte auf Ausgangszustand zurückgesetzt."
            }

            "help", "?" -> {
                resultMessage = "Projekt: save [Name], save as <Name>, projects, open <Name|Nr>, new [Name], rename <Name>, export · Schnitt: delete 10-20, delete between cut1 cut2 · Löschen: clear all captions, clear caption1, clear texts, clear emoji1, clear cuts, clear all, captions (Liste) · Befehle: undo, redo, zoom <1..5>, pan <x> <y>, crop <9:16|1:1|4:5|16:9|Prozent>, trim <start> <ende>, split, speed <x>, filter <name>, blur <off|bokeh|privacy|full> [radius], text <Inhalt>, emoji <🔥>, caption <Text>, autocaption, font <name>, rotate <Grad>, reset"
            }

            else -> {
                isError = true
                resultMessage = "Unbekannter Befehl '$verb'. Tippe 'help' für alle Befehle oder 'undo' zum Rückgängigmachen."
            }
        }

        statusBanner = resultMessage
        commandHistory.add(
            CommandLogEntry(
                command = trimmed,
                response = resultMessage,
                isError = isError,
            ),
        )
        return resultMessage
    }

    /**
     * Entfernt gezielt Elemente:
     * - `clear all captions` / `clear captions` → alle Untertitel
     * - `clear caption1` / `clear caption 2` → einzelner Untertitel (nach Startzeit nummeriert)
     * - `clear caption Hallo` → alle Untertitel, die "Hallo" enthalten
     * - analog `clear texts` / `clear text1`, `clear emojis` / `clear emoji2`, `clear overlays`
     * - `clear cuts` → alle Schnittmarken & herausgeschnittenen Bereiche
     * - `clear all` → alle Untertitel, Texte und Emojis
     */
    private fun clearElements(rawTarget: String): Pair<String, Boolean> {
        val target = rawTarget.trim().lowercase().removeSuffix(".")
        if (target == "all" || target == "alles" || target == "alle") {
            if (project.subtitles.isEmpty() && project.overlays.isEmpty()) {
                return "Keine Untertitel, Texte oder Emojis vorhanden." to false
            }
            val n = project.subtitles.size + project.overlays.size
            commitChange("Alle Untertitel, Texte & Emojis gelöscht") { it.copy(subtitles = emptyList(), overlays = emptyList()) }
            selectedOverlayId = null
            return "$n Elemente gelöscht (alle Untertitel, Texte & Emojis)." to false
        }

        val m = Regex(
            """^(?:(?:all|alle)\s+(?:the\s+|die\s+)?)?(captions?|subs?|subtitles?|untertitel|texts?|titles?|titel|texte|emojis?|stickers?|overlays?|cuts?|schnitte?|cutmarker)\s*(.*)$""",
        ).matchEntire(target) ?: return "Was soll gelöscht werden? z.B. 'clear all captions', 'clear caption1', 'clear texts', 'clear emoji1', 'clear cuts'." to true

        val kindToken = m.groupValues[1]
        val rest = m.groupValues[2].trim()
        val isAll = rest.isEmpty() || rest == "all" || rest == "alle"
        val index = rest.toIntOrNull()

        when {
            kindToken.startsWith("cut") || kindToken.startsWith("schnitt") -> {
                if (project.cutMarkers.isEmpty() && project.removedRanges.isEmpty()) return "Keine Schnittmarken vorhanden." to false
                commitChange("Schnittmarken & herausgeschnittene Bereiche gelöscht") { it.copy(cutMarkers = emptyList(), removedRanges = emptyList()) }
                return "Alle Schnittmarken und herausgeschnittenen Bereiche entfernt (Trim-Anfang/-Ende bleiben – 'undo' oder Kürzen-Tab zum Ändern)." to false
            }

            kindToken.startsWith("cap") || kindToken.startsWith("sub") || kindToken == "untertitel" -> {
                val sorted = project.subtitles.sortedBy { it.startMs }
                if (sorted.isEmpty()) return "Keine Untertitel vorhanden." to false
                val toRemove = when {
                    isAll -> sorted
                    index != null -> listOfNotNull(sorted.getOrNull(index - 1))
                    else -> sorted.filter { it.text.lowercase().contains(rest) }
                }
                if (toRemove.isEmpty()) {
                    return (if (index != null) "caption$index gibt es nicht (es gibt ${sorted.size} Untertitel)." else "Kein Untertitel mit '$rest' gefunden.") to true
                }
                val ids = toRemove.map { it.id }.toSet()
                commitChange(if (isAll) "Alle Untertitel gelöscht" else "${toRemove.size} Untertitel gelöscht") { p ->
                    p.copy(subtitles = p.subtitles.filterNot { it.id in ids })
                }
                return when {
                    isAll -> "Alle ${toRemove.size} Untertitel gelöscht."
                    index != null -> "caption$index (\"${toRemove.first().text}\") gelöscht."
                    else -> "${toRemove.size} Untertitel mit '$rest' gelöscht."
                } to false
            }

            else -> {
                val kinds: Set<OverlayKind> = when {
                    kindToken.startsWith("emoji") || kindToken.startsWith("sticker") -> setOf(OverlayKind.EMOJI)
                    kindToken.startsWith("overlay") -> setOf(OverlayKind.TEXT, OverlayKind.EMOJI)
                    else -> setOf(OverlayKind.TEXT)
                }
                val label = when (kinds.size) {
                    2 -> "Overlays"
                    else -> if (OverlayKind.EMOJI in kinds) "Emojis" else "Texte"
                }
                val sorted = project.overlays.filter { it.kind in kinds }.sortedBy { it.startMs }
                if (sorted.isEmpty()) return "Keine $label vorhanden." to false
                val toRemove = when {
                    isAll -> sorted
                    index != null -> listOfNotNull(sorted.getOrNull(index - 1))
                    else -> sorted.filter { it.content.lowercase().contains(rest) }
                }
                if (toRemove.isEmpty()) {
                    return (if (index != null) "${kindToken.trimEnd('s')}$index gibt es nicht (es gibt ${sorted.size} $label)." else "Kein Element mit '$rest' gefunden.") to true
                }
                val ids = toRemove.map { it.id }.toSet()
                commitChange("${toRemove.size} $label gelöscht") { p -> p.copy(overlays = p.overlays.filterNot { it.id in ids }) }
                if (selectedOverlayId in ids) selectedOverlayId = project.overlays.firstOrNull()?.id
                return (if (toRemove.size == 1) "'${toRemove.first().content}' gelöscht." else "${toRemove.size} $label gelöscht.") to false
            }
        }
    }

    /**
     * Berechnet das zum aktuellen [playheadMs] gültige Seitenverhältnis:
     * Falls ein zeitgebundener PocketScript-Span (wie `10-24,3 (8:15) 9 (16:18)`) aktiv ist,
     * hat dessen Format Vorrang; andernfalls gilt `project.aspectRatio`.
     */
    fun currentAspectFloat(atMs: Long = playheadMs): Float {
        val activeSpan = project.timedFormatSpans.lastOrNull { atMs in it.startMs..it.endMs && it.hasCustomRatio }
        return activeSpan?.aspectFloat ?: project.aspectRatio.aspectFloat
    }

    /**
     * Berechnet den zum aktuellen [playheadMs] gültigen [CropZoomState]:
     * Kombiniert den globalen `project.transform` mit etwaigen zeitgebundenen Kanten-Zuschnitten
     * (`u20%`, `o20%`, `r20%`, `l20%`, `90 u90%`) aus `timedFormatSpans`.
     */
    fun currentTransform(atMs: Long = playheadMs): CropZoomState = project.transformAt(atMs)

    fun currentFormatLabel(atMs: Long = playheadMs): String {
        val activeSpan = project.timedFormatSpans.lastOrNull { atMs in it.startMs..it.endMs && it.hasCustomRatio }
        val baseRatio = activeSpan?.label ?: project.aspectRatio.cliAlias
        val edgeSummary = currentTransform(atMs).activeEdgeCropSummary()
        return if (edgeSummary.isEmpty()) baseRatio else "$baseRatio · $edgeSummary"
    }

    companion object {
        /** Stellt sicher, dass alle Zeitwerte innerhalb der Videolänge [durationMs] liegen. */
        internal fun VideoProject.clampedToDuration(durationMs: Long): VideoProject {
            val d = durationMs.coerceAtLeast(500L)
            val start = trimStartMs.coerceIn(0L, (d - 500L).coerceAtLeast(0L))
            val end = trimEndMs.coerceIn((start + 500L).coerceAtMost(d), d)
            return copy(
                sourceDurationMs = d,
                trimStartMs = start,
                trimEndMs = end,
                removedRanges = removedRanges
                    .map { it.copy(startMs = it.startMs.coerceIn(0L, d), endMs = it.endMs.coerceIn(0L, d)) }
                    .filter { it.endMs > it.startMs },
            )
        }

        fun formatSec(ms: Long): String {
            val totalTenths = (ms / 100L).coerceAtLeast(0L)
            val secs = totalTenths / 10
            val tenths = totalTenths % 10
            return "${secs}.${tenths}s"
        }

        fun formatTimecode(ms: Long): String {
            val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
            val mins = totalSeconds / 60L
            val secs = totalSeconds % 60L
            return "%02d:%02d".format(mins, secs)
        }
    }
}

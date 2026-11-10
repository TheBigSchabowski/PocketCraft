package com.example.pocketcraft.engine

import com.example.pocketcraft.model.VideoProject
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Datei-Inhalt eines gespeicherten Projekts (`filesDir/projects/<id>.json`).
 */
@Serializable
data class SavedProjectFile(
    val id: String,
    val savedAtMs: Long,
    val project: VideoProject,
)

/**
 * Kurzinfo für die Projektliste.
 */
data class ProjectSummary(
    val id: String,
    val title: String,
    val savedAtMs: Long,
    val durationMs: Long,
    val hasRealVideo: Boolean,
)

/**
 * Speichert und lädt Projekte als JSON-Dateien in einem Verzeichnis (auf dem Gerät `filesDir/projects`).
 */
class ProjectStore(private val dir: File) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
    }

    private val lastOpenedFile: File get() = File(dir, "last_opened.txt")

    private fun fileFor(id: String): File = File(dir, "${id.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.json")

    fun save(id: String, project: VideoProject, savedAtMs: Long = System.currentTimeMillis()) {
        dir.mkdirs()
        val payload = json.encodeToString(SavedProjectFile.serializer(), SavedProjectFile(id, savedAtMs, project))
        val target = fileFor(id)
        val tmp = File(dir, "${target.name}.tmp")
        tmp.writeText(payload)
        if (!tmp.renameTo(target)) {
            target.writeText(payload)
            tmp.delete()
        }
        setLastOpened(id)
    }

    fun load(id: String): SavedProjectFile? {
        val f = fileFor(id)
        if (!f.exists()) return null
        return try {
            json.decodeFromString(SavedProjectFile.serializer(), f.readText())
        } catch (_: Exception) {
            null
        }
    }

    fun list(): List<ProjectSummary> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { f ->
            try {
                val saved = json.decodeFromString(SavedProjectFile.serializer(), f.readText())
                ProjectSummary(
                    id = saved.id,
                    title = saved.project.title,
                    savedAtMs = saved.savedAtMs,
                    durationMs = saved.project.playableDurationMs,
                    hasRealVideo = saved.project.videoUri != null,
                )
            } catch (_: Exception) {
                null
            }
        }.sortedByDescending { it.savedAtMs }
    }

    fun delete(id: String): Boolean {
        if (lastOpenedId() == id) lastOpenedFile.delete()
        return fileFor(id).delete()
    }

    private val globalVariablesFile: File get() = File(dir, "globals.vars")

    /** Globale PocketScript-Variablen (`global x = 5`), die in allen Projekten gelten. */
    fun loadGlobalVariables(): Map<String, String> = try {
        globalVariablesFile.takeIf { it.exists() }
            ?.let { json.decodeFromString(variablesSerializer, it.readText()) }
            ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun saveGlobalVariables(variables: Map<String, String>) {
        try {
            dir.mkdirs()
            globalVariablesFile.writeText(json.encodeToString(variablesSerializer, variables))
        } catch (_: Exception) {
        }
    }

    private val globalFunctionsFile: File get() = File(dir, "globals.funcs")

    /** Globale PocketScript-Funktionen (`global def name(a): …`), Name → Definition als Text. */
    fun loadGlobalFunctions(): Map<String, String> = try {
        globalFunctionsFile.takeIf { it.exists() }
            ?.let { json.decodeFromString(variablesSerializer, it.readText()) }
            ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    fun saveGlobalFunctions(functions: Map<String, String>) {
        try {
            dir.mkdirs()
            globalFunctionsFile.writeText(json.encodeToString(variablesSerializer, functions))
        } catch (_: Exception) {
        }
    }

    private val variablesSerializer = MapSerializer(String.serializer(), String.serializer())

    fun lastOpenedId(): String? = try {
        lastOpenedFile.takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }
    } catch (_: Exception) {
        null
    }

    fun setLastOpened(id: String) {
        try {
            dir.mkdirs()
            lastOpenedFile.writeText(id)
        } catch (_: Exception) {
        }
    }
}

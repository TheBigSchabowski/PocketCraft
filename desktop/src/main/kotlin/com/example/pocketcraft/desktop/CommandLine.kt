package com.example.pocketcraft.desktop

import com.example.pocketcraft.engine.ProjectEngine
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * PocketCraft ohne Fenster: wendet ein PocketScript auf ein Video an und exportiert das Ergebnis.
 *
 * ```
 * PocketCraft --video urlaub.mp4 --script schnitt.pocket --out fertig.mp4
 * PocketCraft --video urlaub.mp4 --run "all (9:16); 0-3 set caption: Hallo.gold" --out fertig.mp4
 * ```
 */
object CommandLine {

    private val usage = """
        PocketCraft – Videoschnitt mit PocketScript

        Ohne Argumente startet die App mit Fenster; "PocketCraft video.mp4" öffnet direkt ein Video.

        Stapelverarbeitung ohne Fenster:
          --video <datei>    Quellvideo (mp4, mov, mkv, …)
          --script <datei>   PocketScript-Datei (eine Anweisung pro Zeile)
          --run "<befehle>"  PocketScript direkt angeben (mehrere Befehle mit ; trennen)
          --out <datei>      Zieldatei (.mp4); ohne --out wird nur das Skript ausgeführt
          --version          Version anzeigen
    """.trimIndent()

    fun run(args: Array<String>): Int {
        val options = mutableMapOf<String, String>()
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--help", "-h" -> {
                    println(usage)
                    return 0
                }
                "--version" -> {
                    println("PocketCraft ${System.getProperty("jpackage.app-version") ?: "(Entwicklungsversion)"}")
                    return 0
                }
                "--video", "--script", "--run", "--out" -> {
                    val value = args.getOrNull(i + 1) ?: return fail("Nach $arg fehlt ein Wert.")
                    options[arg] = value
                    i++
                }
                else -> return fail("Unbekanntes Argument '$arg'.")
            }
            i++
        }

        val script = options["--script"]?.let { path ->
            File(path).takeIf { it.isFile }?.readText() ?: return fail("Skriptdatei nicht gefunden: $path")
        } ?: options["--run"]
        if (script == null && options["--out"] == null) return fail("Nichts zu tun: --script, --run oder --out angeben.")

        val engine = ProjectEngine()
        options["--video"]?.let { path ->
            val file = File(path)
            val info = try {
                VideoSource.probe(file)
            } catch (e: Exception) {
                return fail("Video konnte nicht gelesen werden: ${e.message}")
            }
            engine.setVideoSource(file.absolutePath, info.durationMs, file.name)
            // Die Demo-Untertitel/-Overlays des leeren Startprojekts gehören nicht in ein echtes Video
            engine.executeCommand("clear all")
            println("Video: ${file.name} · ${info.displayWidth}x${info.displayHeight} · ${ProjectEngine.formatSec(info.durationMs)}")
        }

        if (script != null) {
            println(engine.executeCommand(script))
            if (engine.commandHistory.last().isError) return 2
        }

        val out = options["--out"] ?: return 0
        if (engine.project.videoUri == null) return fail("Für --out wird ein Video benötigt (--video).")
        var lastShown = -10
        val outcome = runBlocking {
            DesktopVideoExporter.export(engine.project, File(out).absoluteFile) { percent ->
                if (percent >= lastShown + 10) {
                    lastShown = percent
                    println("Export: $percent%")
                }
            }
        }
        return when (outcome) {
            is DesktopVideoExporter.Outcome.Success -> {
                println("Fertig: ${outcome.file.path}")
                0
            }
            is DesktopVideoExporter.Outcome.Failure -> fail(outcome.message)
        }
    }

    private fun fail(message: String): Int {
        System.err.println(message)
        System.err.println("Hilfe: --help")
        return 1
    }
}

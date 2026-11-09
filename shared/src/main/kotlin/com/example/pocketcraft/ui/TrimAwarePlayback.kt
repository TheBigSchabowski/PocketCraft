package com.example.pocketcraft.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.example.pocketcraft.engine.ProjectEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/**
 * Was die Vorschau von einem Videoplayer braucht – auf Android ein MediaPlayer,
 * auf dem Desktop ein FFmpeg-Decoder.
 */
interface PreviewPlaybackController {
    /** Beobachtbar (Compose-State): wird `true`, sobald das Video abspielbereit ist. */
    val isPrepared: Boolean
    val isPlaying: Boolean
    val positionMs: Long
    fun seekTo(ms: Long)
    fun start(speed: Float)
    fun applySpeed(speed: Float)
    fun pause()
}

/**
 * Koppelt einen Player an die Engine, sodass beim Abspielen nur der geschnittene Teil läuft:
 * - startet bei Trim-Start, springt am Trim-Ende zurück zum Trim-Start (Schleife),
 * - überspringt herausgeschnittene Bereiche (`delete 10-20`, `delete between cut1 cut2`),
 * - folgt dem Playhead beim Scrubben auf der Timeline (auch während des Abspielens),
 * - berücksichtigt die Abspielgeschwindigkeit.
 */
@Composable
fun TrimAwarePlaybackEffects(engine: ProjectEngine, controller: PreviewPlaybackController) {
    val prepared = controller.isPrepared

    // Abspielen: Video-Position treibt den Playhead, Trim-Grenzen & Löschbereiche werden erzwungen.
    LaunchedEffect(controller, prepared, engine.isPlaying) {
        if (!prepared) return@LaunchedEffect
        if (!engine.isPlaying) {
            controller.pause()
            controller.seekTo(engine.playheadMs)
            return@LaunchedEffect
        }

        fun loopStart(): Long = engine.project.resolvePlayablePosition(engine.project.trimStartMs)

        var target = engine.project.resolvePlayablePosition(engine.playheadMs)
        if (target >= engine.project.trimEndMs - 100L) target = loopStart()
        controller.seekTo(target)
        engine.playheadMs = target
        var lastReported = target
        var ignoreUntil = System.currentTimeMillis() + 400L
        controller.start(engine.project.playbackSpeed)

        while (isActive && engine.isPlaying) {
            delay(30L)
            val p = engine.project
            controller.applySpeed(p.playbackSpeed)

            // Nutzer hat während des Abspielens auf der Timeline gescrubbt -> dorthin springen
            if (abs(engine.playheadMs - lastReported) > 250L) {
                val jump = p.resolvePlayablePosition(engine.playheadMs)
                val dest = if (jump >= p.trimEndMs - 100L) loopStart() else jump
                controller.seekTo(dest)
                engine.playheadMs = dest
                lastReported = dest
                ignoreUntil = System.currentTimeMillis() + 400L
                continue
            }

            val pos = controller.positionMs
            // Direkt nach einem Sprung meldet der Player kurz noch die alte Position
            if (System.currentTimeMillis() < ignoreUntil && abs(pos - lastReported) > 600L) continue

            val removed = p.removedRangeAt(pos)
            val dest: Long? = when {
                pos >= p.trimEndMs - 30L -> loopStart()
                pos < p.trimStartMs - 300L -> loopStart()
                removed != null -> p.resolvePlayablePosition(removed.endMs).let {
                    if (it >= p.trimEndMs - 100L) loopStart() else it
                }
                else -> null
            }
            if (dest != null) {
                controller.seekTo(dest)
                if (!controller.isPlaying) controller.start(p.playbackSpeed)
                engine.playheadMs = dest
                lastReported = dest
                ignoreUntil = System.currentTimeMillis() + 400L
            } else {
                val clamped = pos.coerceIn(p.trimStartMs, p.trimEndMs)
                engine.playheadMs = clamped
                lastReported = clamped
            }
        }
    }

    // Pausiert: Scrubben auf der Timeline zeigt sofort das passende Bild.
    LaunchedEffect(controller, prepared, engine.playheadMs) {
        if (prepared && !engine.isPlaying) {
            controller.seekTo(engine.playheadMs)
        }
    }
}

package com.example.pocketcraft.engine

/**
 * Das eingebaute Vorführ-Skript hinter dem Befehl `demo`: schneidet aus dem geladenen Video einen
 * kurzen Clip, in dem jede PocketScript-Funktion ein eigenes Kapitel bekommt.
 * Es arbeitet auf einer Kopie ("PocketCraft Demo"), das ursprüngliche Projekt bleibt unverändert.
 */
object PocketScriptDemo {

    /** Kürzer lohnt sich nicht: zwölf Kapitel mit je mindestens zwei Sekunden. */
    const val MIN_DURATION_MS = 24_000L

    val script: String = """
        # PocketCraft-Demo: jede Funktion bekommt ein eigenes Kapitel
        save as PocketCraft Demo
        clear all
        clear cuts
        all (9:16)
        all set filter: vivid
        let n = 12
        let s = min(6, round(${'$'}duration / ${'$'}n - 0.5))

        # Eigene Funktionen: Kapitel-Titel oben, Erklärung als Untertitel
        def kapitel(i, titel): {(${'$'}i - 1) * ${'$'}s}-{${'$'}i * ${'$'}s} set text: ${'$'}titel.gold.big.top
        def sag(i, satz, farbe): {(${'$'}i - 1) * ${'$'}s}-{${'$'}i * ${'$'}s} set caption: ${'$'}satz.${'$'}farbe

        # 1 Intro
        0-${'$'}s set text: PocketCraft.gold.huge.top
        0-${'$'}s set emoji: 🎬.huge
        sag(1, Videoschnitt per Befehl, white)

        # 2 Untertitel in Farben und Schriften
        kapitel(2, 1 · Untertitel)
        let a = ${'$'}s
        let d = ${'$'}s / 3
        ${'$'}a-{${'$'}a + ${'$'}d} set caption: Rot.red
        {${'$'}a + ${'$'}d}-{${'$'}a + 2 * ${'$'}d} set caption: Gelb und gross.yellow.big.impact
        {${'$'}a + 2 * ${'$'}d}-{${'$'}a + 3 * ${'$'}d} set caption: Cyan in Schreibschrift.cyan.cursive

        # 3 Text und Emoji
        kapitel(3, 2 · Text und Emoji)
        {2 * ${'$'}s}-{3 * ${'$'}s} set text: set text.cyan.mono.center
        {2 * ${'$'}s}-{3 * ${'$'}s} set emoji: 🔥.huge
        sag(3, Texte und Emojis mit Zeitfenster, white)

        # 4 Rand wegschneiden
        kapitel(4, 3 · Zuschnitt)
        sag(4, u25% schneidet unten 25 Prozent weg, orange)
        if caption orange: u25%

        # 5 Verschieben
        kapitel(5, 4 · Verschieben)
        sag(5, move 15%u schiebt das Bild hoch, green)
        if caption green: move 15%u

        # 6 Zoom nur solange der blaue Untertitel läuft
        kapitel(6, 5 · Zoom)
        sag(6, Zoom 1.8 nur in diesem Kapitel, blue)
        if caption blue: zoom 1,8

        # 7 Bedingung mit mehreren Befehlen
        kapitel(7, 6 · Bedingung)
        sag(7, if caption pink: u20% und move 10%u und zoom, pink)
        if caption pink: u20%, move 10%u, zoom 1,4

        # 8 Variablen, Rechnen und Schleife über eine Liste
        kapitel(8, 7 · Schleife)
        sag(8, for e in Liste: drei Emojis nacheinander, white)
        let t = 7 * ${'$'}s
        let schritt = ${'$'}s / 3
        for e in [🚀, ⭐, 🎉]
          ${'$'}t-{${'$'}t + ${'$'}schritt} set emoji: ${'$'}e.huge
          t += ${'$'}schritt
        end

        # 9 Vergleich mit if und else
        kapitel(9, 8 · if und else)
        if ${'$'}duration > 60
          sag(9, Dieses Video ist länger als eine Minute, yellow)
        else
          sag(9, Dieses Video ist kürzer als eine Minute, yellow)
        end

        # 10 while-Schleife in festen Schritten
        kapitel(10, 9 · while step)
        sag(10, while step: Emoji im Takt, white)
        while {9 * ${'$'}s}-{10 * ${'$'}s} step 2: set emoji: 💥.big

        # 11 Filter
        kapitel(11, 10 · Filter)
        sag(11, all set filter: vivid gilt fürs ganze Video, cyan)

        # 12 Schnitt und Outro
        kapitel(12, 11 · Schnitt)
        sag(12, Der Rest ist mit delete after cut1 weg, red)
        {12 * ${'$'}s - 2}-{12 * ${'$'}s} set emoji: 👋.huge
        set cut {12 * ${'$'}s}
        delete after cut1
    """.trimIndent()
}

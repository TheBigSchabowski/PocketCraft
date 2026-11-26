# PocketCraft

Videoschnitt mit **PocketScript 3.0** – einer logischen, strengen Befehls- und Programmiersprache für schnelle Schnitte (inkl. Easing, Fades, 2D/3D-Achsendrehung, Beat-Sync, Karaoke, Audio-Fades & Live-Syntax-Highlighting).
Läuft auf **Android**, **macOS** und **Linux**.

```
all (9:16)                                  # Format setzen (Inline-Kommentare funktionieren!)
0-1,2 fade in                               # Weich aus Schwarz einblenden
0-5,9 zoom 0 -> 1 ease-out                  # animierter Zoom mit Easing von 10% auf 100%
0-4 rotate y 0 -> 360 ease                  # 3D-Drehung um die eigene senkrechte Achse
6,9-8,9 caption: Gitarren-Jam.white.karaoke.hlgold.top
if caption white: crop 20%, zoom 1,3        # gilt exakt, solange der Untertitel läuft
style hook = .gold.big.impact.top.pop       # wiederverwendbarer Stil
0-3 text: HOOK!.@hook                       # Stil per .@name anwenden
bpm 120
for b in beats(4..8): $b-{$b + 0,2} flash   # Lichtblitz im Takt (auch break/continue)
let abstand = 4                             # Variable
for t in 0..12 step $abstand                # Schleife
  $t-{$t + 2} emoji: 🔥.spinaxis.bounce     # Emoji dreht sich & federt
end
def titel(text, farbe): 0-3 text: $text.$farbe.big.top
titel(Mein Urlaub, gold)                    # eigene Funktion (auch return in Blöcken)
last 5 zoom in at top-right                 # smarte Zeitbereiche: last, end-.., cut1-cut2, 0-50%, +3
end-2..end audio fade out                   # Audio & Video sanft ausblenden
```

Für KI-gestützten Videoschnitt gibt es den fertigen System-Prompt in [POCKETSCRIPT_KI_PROMPT.md](POCKETSCRIPT_KI_PROMPT.md) (auch direkt in der App per **🤖 KI** / **🤖 KI-Prompt** kopierbar).

Zum Ausprobieren: Video laden und `demo` tippen – PocketCraft baut daraus einen Clip, der jede Funktion
in einem eigenen Kapitel zeigt.

Die vollständige Anleitung steckt in der App (📄-Knopf oder Befehl `pdf`).

## Aufbau

| Ordner | Inhalt |
|---|---|
| `shared/` | Gemeinsamer Code: Projektmodell, Engine, PocketScript, Oberfläche (Compose) |
| `app/` | Android-App (Player, Export über Media3, Spracherkennung) |
| `desktop/` | Desktop-App für macOS / Linux (Player und Export über FFmpeg, wird mitgeliefert) |

`shared/` ist kein eigenes Modul – der Ordner wird von `:app` und `:desktop` jeweils mitkompiliert.
Was sich zwischen den Plattformen unterscheidet, steht in `shared/…/platform/PlatformHooks.kt`.

## Bauen

```bash
./gradlew :app:assembleDebug          # Android-APK
./gradlew :desktop:run                # Desktop-App starten
./gradlew :desktop:test               # Tests (PocketScript, Oberfläche)
./gradlew :desktop:packageDmg         # macOS-Paket (auf einem Mac)
./gradlew :desktop:packageDeb         # Linux-Paket (auf Linux)
```

- Pakete enthalten FFmpeg für genau das System, auf dem gebaut wird (z.B. Apple Silicon, Linux x86_64).
- Zum Paketieren braucht es ein vollständiges JDK mit `jpackage`. Falls Gradle mit einem JDK ohne
  `jpackage` läuft: `-Ppocketcraft.packagingJdk=/pfad/zum/jdk` anhängen.
- `gradle.properties` verweist für Gradle selbst auf das JDK von Android Studio (macOS). Auf anderen
  Rechnern überschreiben: `-Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64`.
- Ohne Android SDK wird `:app` automatisch übersprungen, sodass ein Linux-Server die Desktop-Pakete bauen kann.

## Desktop ohne Fenster

```bash
PocketCraft --video urlaub.mp4 --script schnitt.pocket --out fertig.mp4
PocketCraft --video urlaub.mp4 --run "all (1:1); 0-3 set caption: Hallo.gold" --out fertig.mp4
```

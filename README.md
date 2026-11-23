# PocketCraft

Videoschnitt mit **PocketScript 2.0** – einer logischen, strengen Befehls- und Programmiersprache für schnelle Schnitte (inkl. 2D/3D-Drehung um die eigene Achse).
Läuft auf **Android**, **macOS** und **Linux**.

```
all (9:16)                                  # Format setzen
0-5,9 zoom 0 -> 1                           # animierter Zoom von klein (10%) auf 100%
0-4 rotate y 0 -> 360                       # 3D-Drehung um die eigene senkrechte Achse (auch: spin axis)
6,9-8,9 caption: Gitarren-Jam.red.mono.top  # Untertitel (Zeitbereich vor oder nach dem Verb!)
if caption red: crop 20%, zoom 1,3          # gilt exakt, solange der rote Untertitel läuft
let abstand = 4                             # Variable
for t in 0..12 step $abstand                # Schleife
  $t-{$t + 2} emoji: 🔥.spinaxis            # Emoji dreht sich um die eigene Achse
end
def titel(text, farbe): 0-3 text: $text.$farbe.big.top
titel(Mein Urlaub, gold)                    # eigene Funktion
if $duration > 30: delete after 00:30       # Bedingung
set cut 00:33.3                             # Schnittmarke auf 0,1s genau
global favoriten = [🚀, ⭐, 🎉]              # global: gilt in allen Projekten (let: nur in diesem)
move text1 up 10%                           # vorhandenen Text verschieben (auch: move text1 50 20)
edit text1: Schönen Abend!.gold             # Text und Stil nachträglich ändern, size text1 150 für die Größe
rotate text1 y 0 -> 360                     # vorhandenes Overlay um eigene Achse drehen
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

# PocketCraft

Videoschnitt mit **PocketScript** – einer kleinen Befehls- und Programmiersprache für schnelle Schnitte.
Läuft auf **Android**, **macOS** und **Linux**.

```
all (9:16)                                  # Format setzen
let abstand = 4                             # Variable
for t in 0..12 step $abstand                # Schleife
  $t-{$t + 2} set emoji: 🔥                 # $name und {Rechnung} werden eingesetzt
end
def titel(text, farbe): 0-3 set text: $text.$farbe.big.top
titel(Mein Urlaub, gold)                    # eigene Funktion
if $duration > 30: delete after 00:30       # Bedingung
if caption red: u20%, move 10%u, zoom 1,5   # gilt nur, solange der rote Untertitel läuft
```

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

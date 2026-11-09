import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
    // Gemeinsamer Code (Engine, PocketScript, Oberfläche) – derselbe Ordner wird auch von :app kompiliert
    sourceSets["main"].kotlin.srcDir("../shared/src/main/kotlin")
    sourceSets["test"].kotlin.srcDir("../shared/src/test/kotlin")
}

/** FFmpeg wird für genau das Betriebssystem mitgeliefert, auf dem gebaut wird (z.B. `macosx-arm64`, `linux-x86_64`). */
val nativeClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val osPart = when {
        os.contains("mac") -> "macosx"
        os.contains("win") -> "windows"
        else -> "linux"
    }
    val archPart = if (arch == "aarch64" || arch == "arm64") "arm64" else "x86_64"
    "$osPart-$archPart"
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // Video lesen/schreiben: JavaCV bringt viele Bibliotheken mit – wir brauchen nur FFmpeg.
    implementation(libs.javacv) {
        listOf(
            "opencv", "openblas", "flycapture", "libdc1394", "libfreenect", "libfreenect2", "librealsense",
            "librealsense2", "videoinput", "artoolkitplus", "leptonica", "tesseract", "flandmark",
        ).forEach { exclude(group = "org.bytedeco", module = it) }
    }
    runtimeOnly("org.bytedeco:javacpp:${libs.versions.javacv.get()}:$nativeClassifier")
    runtimeOnly("org.bytedeco:ffmpeg:${libs.versions.ffmpeg.get()}:$nativeClassifier")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

compose.desktop {
    application {
        mainClass = "com.example.pocketcraft.desktop.MainKt"
        // FFmpeg wird als native Bibliothek geladen
        jvmArgs("--enable-native-access=ALL-UNNAMED")

        // jpackage steckt nur in einem vollständigen JDK (nicht z.B. im JBR von Android Studio).
        // Bei Bedarf angeben: ./gradlew :desktop:packageDmg -Ppocketcraft.packagingJdk=/pfad/zum/jdk
        (findProperty("pocketcraft.packagingJdk") as String?)?.let { javaHome = it }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "PocketCraft"
            packageVersion = libs.versions.app.get()
            description = "PocketCraft – Videoschnitt mit PocketScript"
            vendor = "PocketCraft"
            modules("java.instrument", "java.management", "java.prefs", "jdk.unsupported")

            macOS {
                bundleID = "com.example.pocketcraft"
                iconFile.set(project.file("icons/PocketCraft.icns"))
            }
            linux {
                packageName = "pocketcraft"
                debMaintainer = "noreply@example.com"
                menuGroup = "AudioVideo"
                shortcut = true
                iconFile.set(project.file("icons/PocketCraft.png"))
            }
        }
    }
}

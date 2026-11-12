import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}

val appVersionName = "0.19.3"

/** JavaFX (built-in browser) natives for the OS/CPU the build runs on, like compose.desktop.currentOs. */
val javafxVersion = "21.0.13"
val javafxPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").lowercase().let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("win") -> "win"
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

/** Desktop release version (MAJOR > 0 is required by macOS/Windows installers). Override with -PdesktopVersion=x.y.z. */
val desktopVersion: String = (findProperty("desktopVersion") as String?)?.takeIf { it.isNotBlank() } ?: "1.0.2"
val appVersionCode = 14

val dexIrOriginal: Configuration by configurations.creating { isTransitive = false }

val patchedDexIr = tasks.register<Jar>("patchedDexIr") {
    archiveFileName.set("dex-ir-2.4.30-patched.jar")
    destinationDirectory.set(layout.buildDirectory.dir("patched"))
    from({ dexIrOriginal.map { zipTree(it) } }) {
        exclude("com/googlecode/dex2jar/ir/ts/NewTransformer*")
    }
}

dependencies {
    dexIrOriginal("de.femtopedia.dex2jar:dex-ir:2.4.30")
    implementation(project(":source-api"))

    // UI: Compose Multiplatform (same toolkit + Material 3 as Mihon Android). Linux x64 natives only.
    // Skiko natives for the OS the build runs on (CI builds Linux, Windows and macOS on their own runners).
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")

    // Built-in browser: JavaFX WebKit, embedded with JFXPanel. Only loaded when the browser is opened.
    // Not transitive: the openjfx POMs pick the platform with a Maven property Gradle can't evaluate.
    listOf("base", "graphics", "controls", "media", "web", "swing").forEach {
        implementation("org.openjfx:javafx-$it:$javafxVersion:$javafxPlatform") { isTransitive = false }
    }

    // Persistence: SQLite through JDBC (Mihon uses SQLite through SQLDelight).
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")


    // Extension loading: APK manifest parsing + Dalvik → JVM bytecode translation.
    implementation("net.dongliu:apk-parser:2.6.10")
    // dex-ir is replaced by a copy without NewTransformer: the app ships a patched NewTransformer, and this way it is
    // the only one on the classpath on every platform and packaging format.
    implementation("de.femtopedia.dex2jar:dex-translator:2.4.30") { exclude(group = "de.femtopedia.dex2jar", module = "dex-ir") }
    implementation("de.femtopedia.dex2jar:dex-reader-api:2.4.30")
    implementation(files(patchedDexIr))

    testImplementation(kotlin("test-junit"))
    testImplementation("com.jakewharton.android.repackaged:dalvik-dx:16.0.1")
}

/** Integration test input: the sample extension compiled to Java 8 bytecode (then dexed by the test). */
tasks.test {
    dependsOn(":test-extension:jar")
    systemProperty("testExtensionJar", project(":test-extension").layout.buildDirectory.file("libs/test-extension.jar").get().asFile.absolutePath)
    jvmArgs("-Xmx512m")
}

tasks.processResources {
    filesMatching("app.properties") {
        expand("versionName" to appVersionName, "versionCode" to appVersionCode)
    }
}

compose.desktop {
    application {
        mainClass = "inkoma.MainKt"
        jvmArgs += listOf(
            "-Xms16m", "-Xmx320m", "-XX:+UseSerialGC", "-XX:TieredStopAtLevel=1",
            "-XX:ReservedCodeCacheSize=48m", "-XX:MaxMetaspaceSize=160m", "-Xss512k",
            "-XX:MinHeapFreeRatio=10", "-XX:MaxHeapFreeRatio=20", "-Dfile.encoding=UTF-8",
            // Use the OS proxy settings (Windows/macOS/GNOME), like browsers do.
            "-Djava.net.useSystemProxies=true",
        )
        nativeDistributions {
            // Linux ships as an AppImage (packaging/build-appimage.sh); Windows and macOS use jpackage.
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi)
            packageName = "Inkoma"
            packageVersion = desktopVersion
            description = "Manga reader for the desktop, compatible with Mihon extensions"
            vendor = "Inkoma contributors"
            copyright = "Apache License 2.0"
            licenseFile.set(rootProject.file("LICENSE"))
            modules(
                "java.base", "java.desktop", "java.logging", "java.sql", "java.xml", "java.naming", "java.prefs",
                "java.scripting", "jdk.dynalink", "jdk.unsupported", "jdk.crypto.ec", "jdk.zipfs", "jdk.charsets",
                "jdk.localedata",
                // JavaFX WebKit (built-in browser) and its Swing bridge.
                "java.net.http", "jdk.jsobject", "jdk.xml.dom", "jdk.unsupported.desktop", "java.datatransfer",
            )
            windows {
                iconFile.set(rootProject.file("packaging/icons/inkoma.ico"))
                menuGroup = "Inkoma"
                perUserInstall = true
                shortcut = true
                upgradeUuid = "6b6f3c56-4f0e-4c8e-9c3b-7d0b1d5a8e21"
            }
            macOS {
                iconFile.set(rootProject.file("packaging/icons/inkoma.icns"))
                bundleID = "io.github.jrypzm.inkoma"
                appCategory = "public.app-category.books"
            }
        }
    }
}

/** Copies the app jar and its runtime classpath to build/dist/lib for the AppImage packager. */
val assembleDist by tasks.registering(Sync::class) {
    dependsOn(tasks.jar)
    from(tasks.jar)
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("dist/lib"))
    exclude("**/*-sources.jar")
}


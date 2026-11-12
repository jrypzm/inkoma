import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Interfaces get real default methods *and* DefaultImpls so both old (1.4) and new (1.6) extensions link.
        jvmDefault.set(JvmDefaultMode.ENABLE)
        freeCompilerArgs.addAll("-Xcontext-parameters", "-opt-in=kotlin.concurrent.atomics.ExperimentalAtomicApi")
    }
}

dependencies {
    // Everything an extension expects the host app to provide at runtime (versions match keiyoushi/extensions-source).
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json-okio:1.11.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-protobuf:1.11.0")
    api("com.squareup.okhttp3:okhttp:5.4.0")
    api("com.squareup.okhttp3:okhttp-brotli:5.4.0")
    api("com.squareup.okhttp3:logging-interceptor:5.4.0")
    api("com.squareup.okhttp3:okhttp-dnsoverhttps:5.4.0")
    // Keiyoushi extensions add zstd decompression to their clients (Mihon provides okhttp-zstd).
    api("com.squareup.okhttp3:okhttp-zstd:5.4.0")
    api("com.squareup.okio:okio:3.16.0")
    api("org.jsoup:jsoup:1.22.2")
    api("io.reactivex:rxjava:1.3.8")
    // Android ships org.json; some extensions use it.
    api("org.json:json:20250517")
    // JavaScriptEngine backend (QuickJS on Android).
    implementation("org.openjdk.nashorn:nashorn-core:15.6")
}

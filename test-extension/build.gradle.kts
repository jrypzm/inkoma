import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// A minimal extension used by the integration test. Like real extensions it is compiled only against the
// host API (compileOnly) and then dexed, so the test exercises the same Dalvik → JVM path as an APK.
plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
    compilerOptions { jvmTarget.set(JvmTarget.JVM_1_8) }
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    // Compile against the Java 21 host API while emitting Java 8 bytecode (what dx accepts).
    disableAutoTargetJvm()
}

dependencies {
    compileOnly(project(":source-api"))
}

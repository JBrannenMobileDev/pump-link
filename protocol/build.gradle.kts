import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
}

// No Android dependency, by design. Framing, the codec, and the connection
// state machine are the parts most worth testing exhaustively, and they are
// testable in milliseconds precisely because nothing here needs a device.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        allWarningsAsErrors = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// The plain `detekt` task cannot see types, and ElseCaseInsteadOfExhaustiveWhen
// needs to know whether the subject is sealed. Only detektMain enforces it, so
// that is what `check` has to depend on.
tasks.named("check") {
    dependsOn(tasks.named("detektMain"))
}

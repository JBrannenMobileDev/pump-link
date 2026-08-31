plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.detekt)
}

// Everything that needs a radio, a filesystem, or a permission lives here, so
// that nothing above or below it does.
android {
    namespace = "dev.pumplink.data"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":protocol"))
    implementation(project(":simulator"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

// Syntactic pass only; see the note in app/build.gradle.kts.
tasks.named("check") {
    dependsOn(tasks.named("detekt"))
}

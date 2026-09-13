plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.qalens.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.qalens.sample"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "com.qalens.sample.RecordingRetentionInstrumentation"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    androidTestImplementation(project(":qalens-android"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Tested coexistence baseline for this Kotlin 2.0 / compileSdk 35 sample.
    debugImplementation("com.github.chuckerteam.chucker:library:4.1.0")
    releaseImplementation("com.github.chuckerteam.chucker:library-no-op:4.1.0")
    debugImplementation(project(":qalens-compose"))
    debugImplementation(project(":qalens-navigation-compose"))
    debugImplementation(project(":qalens-replay"))
    releaseImplementation(project(":qalens-noop"))
}

// Compilation alone cannot prove release safety: the active SDK also compiles in release.
tasks.register("verifyReleaseIsolation") {
    group = "verification"
    description = "Require the no-op SDK and reject capture/replay modules in release."
    doLast {
        val projects = configurations.getByName("releaseRuntimeClasspath")
            .incoming.resolutionResult.allComponents.mapNotNull {
                (it.id as? org.gradle.api.artifacts.component.ProjectComponentIdentifier)?.projectPath
            }.toSet()
        check(":qalens-noop" in projects) { "Release must include qalens-noop" }
        val forbidden = projects.intersect(setOf(":qalens-compose", ":qalens-navigation-compose", ":qalens-android", ":qalens-replay"))
        check(forbidden.isEmpty()) { "Active QaLens modules leaked into release: $forbidden" }
        val modules = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult
            .allComponents.mapNotNull { it.moduleVersion?.let { id -> "${id.group}:${id.name}" } }.toSet()
        check("com.github.chuckerteam.chucker:library" !in modules) { "Active Chucker leaked into release" }
        check("com.github.chuckerteam.chucker:library-no-op" in modules) { "Release must include Chucker's no-op" }
    }
}
tasks.named("check") { dependsOn("verifyReleaseIsolation") }

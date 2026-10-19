plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}
android {
    namespace = "example.consumer"
    compileSdk = 35
    defaultConfig {
        applicationId = "example.consumer"; minSdk = 23; targetSdk = 35
        testInstrumentationRunner = "example.ControlRoomCompatibilityInstrumentation"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    compileOnly("androidx.room:room-runtime:2.6.1")
    debugImplementation("com.qalens:qalens-compose:0.9.0")
    releaseImplementation("com.qalens:qalens-noop:0.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
}

// A library is already compiled when a host supplies newer Compose at runtime. Keep the SDK
// compile classpath unchanged to expose binary breaks that same-BOM sample builds cannot catch.
providers.gradleProperty("qaComposeRuntime").orNull?.let { version ->
    configurations.matching { it.name in setOf("debugRuntimeClasspath", "debugAndroidTestRuntimeClasspath") }.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group in setOf("androidx.compose.animation", "androidx.compose.foundation",
                    "androidx.compose.runtime", "androidx.compose.ui")) useVersion(version)
        }
    }
}
tasks.register("verifyReleaseIsolation") {
    doLast {
        val ids = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult
            .allComponents.map { it.moduleVersion?.let { id -> "${id.group}:${id.name}" } }.toSet()
        check("com.qalens:qalens-noop" in ids)
        check(ids.none { it in setOf("com.qalens:qalens-compose", "com.qalens:qalens-android", "com.qalens:qalens-replay") })
    }
}

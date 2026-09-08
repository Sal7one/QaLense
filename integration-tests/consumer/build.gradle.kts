plugins {
    id("com.android.application") version "8.7.3"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}
android {
    namespace = "example.consumer"
    compileSdk = 35
    defaultConfig { applicationId = "example.consumer"; minSdk = 23; targetSdk = 35 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    debugImplementation("com.qalens:qalens-compose:0.9.0")
    releaseImplementation("com.qalens:qalens-noop:0.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
}
tasks.register("verifyReleaseIsolation") {
    doLast {
        val ids = configurations.getByName("releaseRuntimeClasspath").incoming.resolutionResult
            .allComponents.map { it.moduleVersion?.let { id -> "${id.group}:${id.name}" } }.toSet()
        check("com.qalens:qalens-noop" in ids)
        check(ids.none { it in setOf("com.qalens:qalens-compose", "com.qalens:qalens-android", "com.qalens:qalens-replay") })
    }
}

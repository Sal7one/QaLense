plugins {
    id("maven-publish")
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.qalens.noop"
    compileSdk = 35
    defaultConfig { minSdk = 23 }
    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api(project(":qalens-core"))
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation")

    // The no-op artifact must expose the SAME public API as the debug artifacts, including the
    // navigation wrappers and OkHttp interceptor, so release builds compile unchanged.
    implementation("androidx.navigation:navigation-compose:2.8.5")
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")
    compileOnly("com.jakewharton.timber:timber:5.0.1")
    compileOnly("androidx.room:room-runtime:2.6.1")
}

// Local/team integration: ./gradlew publishToMavenLocal →
// debugImplementation("com.qalens:qalens-noop:0.9.0") against mavenLocal().
android {
    publishing { singleVariant("release") { withSourcesJar() } }
}
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.qalens"
                artifactId = "qalens-noop"
                version = "0.9.0"
                from(components["release"])
            }
        }
    }
}

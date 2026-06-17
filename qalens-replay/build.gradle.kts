plugins {
    id("maven-publish")
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.qalens.replay"
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
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // Media3 — used to play the optional MediaProjection video.mp4 track in a .sal.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
}

// Local/team integration: ./gradlew publishToMavenLocal →
// debugImplementation("com.qalens:qalens-replay:0.9.0") against mavenLocal().
android {
    publishing { singleVariant("release") { withSourcesJar() } }
}
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.qalens"
                artifactId = "qalens-replay"
                version = "0.9.0"
                from(components["release"])
            }
        }
    }
}

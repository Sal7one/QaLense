plugins {
    id("maven-publish")
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.qalens.compose"
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
    implementation(project(":qalens-android"))

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.startup:startup-runtime:1.2.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    // Control Room activity (QaLensControlActivity) renders with Compose.
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    // compileOnly — QaLensOkHttpInterceptor compiles against OkHttp but does NOT pull it in
    // as a transitive dependency; apps add their own OkHttp version and opt in to the interceptor.
    compileOnly("com.squareup.okhttp3:okhttp:4.12.0")

    // compileOnly — QaLensTimberTree compiles against Timber; apps that use Timber plant the tree.
    compileOnly("com.jakewharton.timber:timber:5.0.1")

    // compileOnly — QaLens.observeRoom() compiles against Room's InvalidationTracker; apps that use
    // Room opt in. (observeDataStore needs only a kotlinx Flow, so it requires no extra dependency.)
    compileOnly("androidx.room:room-runtime:2.6.1")
}

// Local/team integration: ./gradlew publishToMavenLocal →
// debugImplementation("com.qalens:qalens-compose:0.9.0") against mavenLocal().
android {
    publishing { singleVariant("release") { withSourcesJar() } }
}
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.qalens"
                artifactId = "qalens-compose"
                version = "0.9.0"
                from(components["release"])
            }
        }
    }
}

plugins {
    id("maven-publish")
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.qalens.android"
    compileSdk = 35

    defaultConfig { minSdk = 23 }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    api(project(":qalens-core"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
}

// Local/team integration: ./gradlew publishToMavenLocal →
// debugImplementation("com.qalens:qalens-android:0.9.0") against mavenLocal().
android {
    publishing { singleVariant("release") { withSourcesJar() } }
}
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.qalens"
                artifactId = "qalens-android"
                version = "0.9.0"
                from(components["release"])
            }
        }
    }
}

plugins {
    id("maven-publish")
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.qalens.navigation"
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
    api(project(":qalens-core"))
    implementation(project(":qalens-compose"))
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.runtime:runtime")
}

// Local/team integration: ./gradlew publishToMavenLocal →
// debugImplementation("com.qalens:qalens-navigation-compose:0.9.0") against mavenLocal().
android {
    publishing { singleVariant("release") { withSourcesJar() } }
}
afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.qalens"
                artifactId = "qalens-navigation-compose"
                version = "0.9.0"
                from(components["release"])
            }
        }
    }
}

plugins {
    `maven-publish`
    id("org.jetbrains.kotlin.jvm")
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    testImplementation(kotlin("test"))
}

tasks.withType<Test> { useJUnitPlatform() }

// Local/team integration: ./gradlew publishToMavenLocal →
// implementation("com.qalens:qalens-core:0.9.0") against mavenLocal().
publishing {
    publications {
        register<MavenPublication>("maven") {
            groupId = project.group.toString()
            artifactId = "qalens-core"
            version = project.version.toString()
            from(components["java"])
        }
    }
}

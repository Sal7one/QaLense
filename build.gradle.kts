plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.jvm") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// ── Internal distribution ────────────────────────────────────────────────────
// `./gradlew qalensDist` publishes every library module into build/qalens-repo (a plain
// file-based Maven repository), then writes SHA-256SUMS for integrity verification.
// Ship that folder (or scripts/release_internal.sh's zip of it) to your internal hosting;
// consumers add it as a maven repo — see integration_llm.md.
subprojects {
    plugins.withId("maven-publish") {
        configure<PublishingExtension> {
            repositories {
                maven {
                    name = "internal"
                    url = uri(rootProject.layout.buildDirectory.dir("qalens-repo"))
                }
            }
        }
    }
}

tasks.register("qalensDist") {
    group = "qalens"
    description = "Publish all QaLens modules to build/qalens-repo with SHA-256 checksums"
    dependsOn(
        ":qalens-core:publishAllPublicationsToInternalRepository",
        ":qalens-android:publishAllPublicationsToInternalRepository",
        ":qalens-compose:publishAllPublicationsToInternalRepository",
        ":qalens-navigation-compose:publishAllPublicationsToInternalRepository",
        ":qalens-replay:publishAllPublicationsToInternalRepository",
        ":qalens-noop:publishAllPublicationsToInternalRepository"
    )
    doLast {
        val repo = layout.buildDirectory.dir("qalens-repo").get().asFile
        val sums = StringBuilder()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        repo.walkTopDown()
            .filter { it.isFile && !it.name.endsWith(".sha256") && it.name != "SHA-256SUMS" }
            .sortedBy { it.relativeTo(repo).path }
            .forEach { f ->
                digest.reset()
                val hash = digest.digest(f.readBytes()).joinToString("") { "%02x".format(it) }
                sums.appendLine("$hash  ${f.relativeTo(repo).path}")
            }
        repo.resolve("SHA-256SUMS").writeText(sums.toString())
        println("qalensDist → ${repo.path} (${sums.lines().count { it.isNotBlank() }} files, SHA-256SUMS written)")
    }
}

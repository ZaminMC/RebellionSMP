/*
 * RebellionAPI — shared contracts with one runtime class identity.
 *
 * This JAR is itself a Bukkit plugin (name: RebellionAPI, load: STARTUP) so that
 * every feature module can `depend: [RebellionAPI]` and share exactly one copy of
 * the API classes. It intentionally contains no game logic, no database code and
 * no command handlers. Feature modules must NOT shade it.
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionAPI"
}

java {
    toolchain {
        // Paper 26.3 API class files are Java 25 (major 69); verified 2026-10-28.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Provided by the server at runtime; the API JAR ships no bundled libraries.
    compileOnly(libs.paper.api)

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter)
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
        showExceptions = true
    }
}

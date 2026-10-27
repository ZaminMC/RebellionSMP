/*
 * RebellionTeams — Phase 3 vertical slice: atomic team membership, invites,
 * ranks, diplomacy, team chat, vanilla-client proximity text chat and
 * friendly-fire source attribution.
 *
 * Depends on RebellionAPI (contracts incl. RebellionDatabase + late module
 * migrations) and RebellionCore at runtime; neither is shaded.
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionTeams"
}

java {
    toolchain {
        // Paper 26.3 API class files are Java 25 (major 69); verified 2026-10-28.
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    compileOnly(project(":rebellion-api"))
    compileOnly(libs.paper.api)

    testImplementation(project(":rebellion-api"))
    // Persistence integration tests run against the real core service
    // (DatabaseService + MigrationRunner) over a real SQLite file.
    testImplementation(project(":rebellion-core"))
    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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

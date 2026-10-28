/*
 * RebellionArtifacts — Phase 6. The three dimension artifacts. The
 * serverless persistence layer (ADR-0013): dimension-keyed registry seeded
 * exactly once per artifact (never minted by gameplay), instance-stamped
 * possession CAS transitions (capture/transfer/find/voluntary return),
 * recoverable loss with a return-delay pipeline (LOST/SPENT -> RETURNING ->
 * ORIGIN), and the idempotent Rebellion event (singleton guard + durable
 * event rows). The controller slice (ADR-0015): protected-origin
 * presentation + capture, physical item flows, the Rebellion observer, the
 * config-gated once-per-occurrence purge (schema v2), commands,
 * placeholders and the maintenance sweep.
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionArtifacts"
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
    // Integration tests run against the real core DatabaseService +
    // MigrationRunner over a real SQLite file (same pattern as teams/wards/
    // dragons).
    testImplementation(project(":rebellion-core"))
    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.sqlite.jdbc)
    testRuntimeOnly(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Expand ${version} in plugin.yml (every shipped module does; the session-10
// boot smoke caught the dragons jar shipping the literal "${version}").
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

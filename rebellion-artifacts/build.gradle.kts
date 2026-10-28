/*
 * RebellionArtifacts — Phase 6 groundwork. Serverless persistence layer for
 * the three dimension artifacts (ADR-0013): dimension-keyed registry seeded
 * exactly once per artifact (never minted by gameplay), instance-stamped
 * possession CAS transitions (capture/transfer/find/voluntary return),
 * recoverable loss with a return-delay pipeline (LOST/SPENT -> RETURNING ->
 * ORIGIN), and the idempotent Rebellion event (singleton guard + durable
 * event rows). No plugin wiring in this slice — the module ships no plugin
 * descriptor (ADR-0009 precedent); the encounter structure, physical item
 * flows, catastrophe effects and commands are controller-phase work.
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

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        showStackTraces = true
        showExceptions = true
    }
}

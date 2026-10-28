/*
 * RebellionRPG — Phase 7. Rare souls, discoveries, rituals and tracking.
 * The serverless persistence layer (ADR-0016): the reserved ritual claim
 * protocol (reserve -> inventory -> all-or-none commit; stale reservations
 * released by sweep with refund evidence), the PK-idempotent discovery
 * ledger, precision-stamped tracking sessions with mask degradation and
 * derived expiry, time-bounded counter-ritual effects, the encounter
 * ledger (concurrency cap + cooldown + TTL + one-winner rewards), and the
 * append-only event audit. No plugin descriptor in this slice — the
 * controller phase (souls, inventory, signals, commands) rides this
 * contract.
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionRPG"
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
    // dragons/artifacts).
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

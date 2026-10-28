/*
 * RebellionEconomy — Phase 9 (master prompt §5.9): the small, explicit
 * internal economy the prison fee flow needs. The persistence layer
 * (ADR-0021): UUID accounts with exact long minor-unit balances, an
 * append-only ledger where every entry is keyed by (account, idempotency
 * key) so replays resolve to typed no-ops, refusal-on-insufficient-funds
 * (balances never go negative), and a configured maximum that makes every
 * accumulate overflow-safe by construction. Implements the shared
 * api-economy provider contract so prison (and a future optional Vault
 * bridge sibling) never hard-codes a currency backend. Serverless slice:
 * no plugin descriptor — the controller phase rides this contract.
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionEconomy"
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
    // dragons/artifacts/rpg/alchemy).
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

// Expand ${version} in plugin.yml (the session-10 boot-smoke lesson: an
// unexpanded placeholder ships a broken descriptor that Paper refuses).
// This module ships no descriptor yet; the stanza stays because the
// controller phase will add one without re-learning the lesson.
tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

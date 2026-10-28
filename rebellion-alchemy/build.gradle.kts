/*
 * RebellionAlchemy — Phase 8. Brewing progression, potion bags and
 * auto-consumption. The persistence layer (ADR-0019): derived brewing
 * levels over an atomic XP accumulate with per-source idempotent awards,
 * the two-path unlock ledger (POWER / EFFICIENCY) bought through the
 * reserve -> inventory -> all-or-none-commit claim protocol with
 * prerequisite gates answered from rows, player-defined auto-consumption
 * rules under a strict operator policy (trigger whitelist, potion-type
 * whitelist, cooldown floor, per-player cap, deterministic conflict
 * order), and execution claims that make potion consumption and the
 * cooldown stamp one logical operation. Serverless slice: no plugin
 * descriptor — the controller phase rides this contract.
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionAlchemy"
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
    // dragons/artifacts/rpg).
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

/*
 * RebellionPrison — Phase 9 (master prompt §5.8): death, imprisonment,
 * release fees, health scars and rehabilitation. The persistence layer
 * (ADR-0021): idempotent death processing (score accumulate + clamped
 * health-penalty scars + routing into one durable PENDING sentence per
 * live player), the sentence lifecycle as CAS state transitions with a
 * structural one-live-sentence guard, the release-fee claim protocol
 * (frozen BigDecimal quote -> audited provider debit -> all-or-none
 * commit) riding the shared api-economy contract, non-paid release routes
 * (escape / rescue / recorded staff override) that never silently clear
 * debt, and structurally idempotent rehabilitation completions whose
 * healing is clamped to the scar ledger. Serverless slice: no plugin
 * descriptor — the controller phase (teleports, guards, escape
 * objectives, commands) rides this contract.
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionPrison"
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
    // MigrationRunner over a real SQLite file, and inject the REAL
    // rebellion-economy service as the EconomyProvider — the cross-module
    // fee contract is proven over both modules' actual persistence.
    testImplementation(project(":rebellion-core"))
    testImplementation(project(":rebellion-economy"))
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

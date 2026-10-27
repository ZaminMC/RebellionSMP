/*
 * RebellionWards — Phase 4: no-claim watch wards with calibrated distance
 * reporting, enter/leave tracking, alert cooldowns, dwell delay, masking
 * countermeasures, decoys and a privacy-gated map marker interface.
 *
 * Depends on RebellionAPI (contracts incl. RebellionDatabase, module
 * migrations, TeamAffiliationService, MapMarkerProvider) and RebellionCore
 * at runtime; neither is shaded. RebellionTeams is OPTIONAL at runtime —
 * without it every non-owner is an outsider and alerts route to the owner.
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionWards"
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
    // MigrationRunner over a real SQLite file (same pattern as teams).
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

/*
 * Deterministic micro-benchmark (not a unit test — no timing assertions).
 * Measures the pure detection pipeline at realistic ward/player counts and
 * prints ns/op so the numbers in docs/PERFORMANCE.md are measured, not
 * invented. Run: ./gradlew :rebellion-wards:wardBenchmark
 */
tasks.register<JavaExec>("wardBenchmark") {
    group = "verification"
    description = "Measures movement-feed, spatial-index and detector costs at realistic counts."
    mainClass = "net.zaminmc.rebellion.wards.benchmark.WardBenchmark"
    classpath = sourceSets.test.get().runtimeClasspath
    jvmArgs("-Xms256m", "-Xmx256m")
}

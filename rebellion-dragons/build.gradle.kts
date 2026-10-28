/*
 * RebellionDragons — Phase 5. Hosts the mandated minimal 26.3 controller
 * spike (probe + report) and the serverless lineage persistence layer
 * (ADR-0009): gateway-backed 20-slot registry, egg issuance/hatch/death
 * idempotency, recovery mailbox, audited retirement. The ADR-0008
 * live-verification harness lives in the `experiments` source set below
 * (server-side proofs executed 2026-10-28; controller phase proceeds on
 * the proven surface — see docs/decisions/ADR-0008-dragon-spike-26.3.md).
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionDragons"
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
    // MigrationRunner over a real SQLite file (same pattern as teams/wards).
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

// Expand ${version} in plugin.yml (same as every shipped module; caught live
// by the session-10 boot smoke: the dragons jar printed "${version}").
tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

// ---------------------------------------------------------------------------
// ADR-0008 live-verification harness (never shipped).
//
// The `experiments` source set compiles the console-driven experiment
// plugin (DragonExperiments) against the same pinned paper-api as the
// module. It is NOT part of the module's shipped jar, and the harness jar
// is deliberately named DragonExperiments (not Rebellion*-<version>) and
// built into build/experiment-libs/ so the release pipeline's
// installable-jar staging (which looks for Rebellion*-$V.jar with a
// plugin.yml in build/libs) can never pick it up.
//
// Build on demand with:
//   ./gradlew :rebellion-dragons:dragonExperimentJar
// ---------------------------------------------------------------------------
sourceSets {
    // Default convention already maps src/experiments/java and
    // src/experiments/resources; no explicit srcDir needed.
    create("experiments")
}

dependencies {
    "experimentsCompileOnly"(libs.paper.api)
}

tasks.register<Jar>("dragonExperimentJar") {
    description =
        "Assembles the ADR-0008 live-experiment harness jar (never released)."
    archiveBaseName.set("DragonExperiments")
    archiveClassifier.set("")
    archiveVersion.set("")
    destinationDirectory.set(layout.buildDirectory.dir("experiment-libs"))
    from(sourceSets["experiments"].output)
    dependsOn(sourceSets["experiments"].compileJavaTaskName)
}

// CI parity: `./gradlew build` must keep the harness compiling so it can
// never silently rot, but the jar itself is built only on demand.
tasks.named("check") {
    dependsOn(sourceSets["experiments"].compileJavaTaskName)
}

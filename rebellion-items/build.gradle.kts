/*
 * RebellionItems — item identity, rarity registry, lore annotation and
 * the DeluxeMenus-shaped menu engine (first player-facing vertical slice).
 *
 * Depends on RebellionAPI (contracts) and RebellionCore (services:
 * ModuleRegistry, DiagnosticsService). Neither is shaded.
 */
plugins {
    `java-library`
}

base {
    archivesName = "RebellionItems"
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
    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
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

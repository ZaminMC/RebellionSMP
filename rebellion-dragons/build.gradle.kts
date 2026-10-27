/*
 * RebellionDragons — Phase 5. Currently hosts ONLY the mandated minimal
 * 26.3 controller spike (probe + report); production dragon controls are
 * blocked until passenger/flight/death behavior is proved on a live Paper
 * 26.3 server (master prompt §18 Phase 5, ADR-0008).
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
    testImplementation(libs.paper.api)
    testImplementation(libs.junit.jupiter)
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

/*
 * RebellionCore — lifecycle, configuration, shared database, migrations,
 * diagnostics, module health and the admin command surface.
 *
 * Ships shaded + relocated HikariCP (not bundled with Paper). JDBC drivers are
 * NOT shaded: Paper 26.3 bundles org.xerial:sqlite-jdbc and
 * com.mysql:mysql-connector-j (verified against paper-26.3-226.jar).
 */
plugins {
    `java-library`
    alias(libs.plugins.shadow)
}

base {
    archivesName = "RebellionCore"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    compileOnly(project(":rebellion-api"))
    compileOnly(libs.paper.api)
    compileOnly(libs.placeholderapi) // optional runtime integration

    implementation(libs.hikaricp) // shaded + relocated below

    testImplementation(project(":rebellion-api"))
    // YamlConfiguration/MiniMessage are server-free library types; running them in
    // unit tests does not require a live server (verified 2026-10-28).
    testImplementation(libs.paper.api)
    testImplementation(libs.hikaricp)
    testImplementation(libs.sqlite.jdbc) // same driver version the server bundles
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    val version = project.version.toString()
    inputs.property("version", version)
    // Only plugin.yml is a build-time template. The bundled config files are NOT
    // expanded: their ${ENV_VAR} sequences are runtime references read by the
    // server (see EnvExpansion), not Groovy template expressions.
    filesMatching("plugin.yml") {
        expand("version" to version)
    }
}

tasks {
    jar {
        // The shadow jar is the installable artifact; keep the thin jar out of it.
        archiveClassifier = "thin"
    }
    shadowJar {
        archiveClassifier = "" // RebellionCore-<version>.jar is the installable JAR
        // Single relocation rule: every com.zaxxer.hikari.* class moves under our
        // namespace so the server classpath and other plugins cannot collide.
        relocate("com.zaxxer.hikari", "net.zaminmc.rebellion.core.libs.hikari")
        // Deliberately NOT minimized: HikariCP uses reflection for optional metric
        // registries; stripping unused-looking classes risks runtime breakage for ~100KB.
    }
    build {
        dependsOn(shadowJar)
    }
    test {
        useJUnitPlatform()
        // Integration tests create real SQLite files; give them a private temp area.
        systemProperty("rebellion.test.tmp", layout.buildDirectory.dir("test-tmp").get().asFile.absolutePath)
        testLogging {
            events("failed", "skipped")
            showStackTraces = true
            showExceptions = true
        }
    }
}

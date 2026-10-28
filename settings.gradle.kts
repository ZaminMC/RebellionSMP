/*
 * RebellionSMP — Gradle multi-project settings.
 *
 * Modules are listed in startup/dependency order: API first, Core second, feature
 * modules after. Repositories are centralized here; per-project repositories are
 * rejected (FAIL_ON_PROJECT_REPOS) so accidental mirrors cannot fork dependency
 * identity between modules.
 */
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        // Paper API for Minecraft 26.3. The repository also proxies the API's
        // transitive dependencies (com.mojang:brigadier, net.md-5:bungeecord-chat)
        // that are not published to Maven Central — verified 2026-10-28.
        maven("https://repo.papermc.io/repository/maven-public/") {
            name = "PaperMC"
            content {
                includeGroup("io.papermc.paper")
                includeGroup("com.mojang")
                includeGroup("net.md-5")
            }
        }
        // PlaceholderAPI (optional runtime integration, compile-only at build time).
        maven("https://repo.helpch.at/releases/") {
            name = "ExtendedClip"
            content { includeGroup("me.clip") }
        }
    }
}

rootProject.name = "RebellionSMP"

include("rebellion-api")
include("rebellion-core")
include("rebellion-items")
include("rebellion-teams")
include("rebellion-wards")
include("rebellion-dragons")
include("rebellion-artifacts")
include("rebellion-rpg")

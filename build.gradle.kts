/*
 * RebellionSMP — root build.
 *
 * Shared group/version for all modules. Each module keeps its own build file so the
 * dependency edges between installable JARs stay explicit and reviewable.
 */
plugins {
    base
}

allprojects {
    group = property("group") as String
    version = property("version") as String
}

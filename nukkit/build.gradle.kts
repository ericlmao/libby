plugins {
    id("libby.java-conventions")
}

description = "libby library manager for Nukkit plugins"

dependencies {
    api(project(":libby-core"))
    // Only the plugin API types are needed; skip the server's many snapshot runtime dependencies.
    compileOnly(libs.nukkit) { isTransitive = false }
}

plugins {
    id("libby.java-conventions")
}

description = "libby library manager for BungeeCord plugins"

dependencies {
    api(project(":libby-core"))
    // Brigadier is only published to Mojang's repository and is not needed to compile against the plugin API.
    compileOnly(libs.bungeecord.api) { exclude(group = "com.mojang") }
}

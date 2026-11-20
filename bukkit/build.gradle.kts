plugins {
    id("libby.java-conventions")
}

description = "libby library manager for Bukkit plugins"

dependencies {
    api(project(":libby-core"))
    compileOnly(libs.spigot.api)
}

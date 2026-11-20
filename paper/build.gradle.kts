plugins {
    id("libby.java-conventions")
}

description = "libby library manager for Paper plugins"

dependencies {
    api(project(":libby-core"))
    compileOnly(libs.paper.api)
}

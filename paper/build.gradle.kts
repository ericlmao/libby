plugins {
    id("libby.java-conventions")
}

description = "libby library manager for Paper plugins"

dependencies {
    api(project(":libby-core"))
    api(project(":libby-slf4j"))
    compileOnly(libs.paper.api)
}

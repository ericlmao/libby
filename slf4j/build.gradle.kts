plugins {
    id("libby.java-conventions")
}

description = "SLF4J logging adapter for libby"

dependencies {
    api(project(":libby-core"))
    compileOnly(libs.slf4j.api)
}

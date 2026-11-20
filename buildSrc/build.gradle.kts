plugins {
    `kotlin-dsl`
}

dependencies {
    // Signs publications and uploads them to the Maven Central Portal.
    implementation("com.vanniktech.maven.publish:com.vanniktech.maven.publish.gradle.plugin:0.37.0")
}

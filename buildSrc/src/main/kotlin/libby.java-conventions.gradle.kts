plugins {
    `java-library`
    `maven-publish`
}

val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    "testImplementation"(platform(libs.findLibrary("junit-bom").get()))
    "testImplementation"(libs.findLibrary("junit-jupiter").get())
    "testRuntimeOnly"(libs.findLibrary("junit-platform-launcher").get())
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-parameters", "-Xlint:all,-processing,-serial", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        addBooleanOption("Xdoclint:all,-missing", true)
        addBooleanOption("Xwerror", true)
        addStringOption("-release", "17")
        quiet()
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

publishing {
    publications {
        register<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = project.name
                description = provider { project.description }
                url = "https://github.com/ericlmao/libby"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                scm {
                    url = "https://github.com/ericlmao/libby"
                    connection = "scm:git:https://github.com/ericlmao/libby.git"
                    developerConnection = "scm:git:ssh://git@github.com/ericlmao/libby.git"
                }
            }
        }
    }

    // A publishing repository is only configured when its URL is provided, either as the
    // Gradle properties libbyRepoUrl/libbyRepoUser/libbyRepoPassword or as the environment
    // variables LIBBY_REPO_URL/LIBBY_REPO_USER/LIBBY_REPO_PASSWORD.
    val repoUrl = providers.gradleProperty("libbyRepoUrl").orElse(providers.environmentVariable("LIBBY_REPO_URL"))
    val repoUser = providers.gradleProperty("libbyRepoUser").orElse(providers.environmentVariable("LIBBY_REPO_USER"))
    val repoPassword = providers.gradleProperty("libbyRepoPassword").orElse(providers.environmentVariable("LIBBY_REPO_PASSWORD"))
    if (repoUrl.isPresent) {
        repositories {
            maven {
                name = "libby"
                url = uri(repoUrl.get())
                if (repoUser.isPresent) {
                    credentials {
                        username = repoUser.get()
                        password = repoPassword.orNull
                    }
                }
            }
        }
    }
}

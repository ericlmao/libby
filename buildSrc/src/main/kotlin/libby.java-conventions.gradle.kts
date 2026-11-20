import com.vanniktech.maven.publish.DeploymentValidation

plugins {
    `java-library`
    `maven-publish`
    id("com.vanniktech.maven.publish.base")
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

// Tests run on the build toolchain (Java 21) unless -Plibby.testJdk=<version> selects another
// installed JDK, which CI uses to also test on the newest Java release.
val testJdk = providers.gradleProperty("libby.testJdk")

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    if (testJdk.isPresent) {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(testJdk.get())
        }
    }
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
                developers {
                    developer {
                        id = "ericlmao"
                        name = "Eric"
                        organization = "Moonrise Studios"
                        organizationUrl = "https://moonrise.gg"
                    }
                }
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

// Maven Central: `./gradlew -PcentralRelease publishToMavenCentral` signs every publication and
// uploads them to the Central Portal. It needs the Gradle properties mavenCentralUsername,
// mavenCentralPassword (a Central Portal user token), signingInMemoryKey and
// signingInMemoryKeyPassword, usually passed as ORG_GRADLE_PROJECT_* environment variables.
if (providers.gradleProperty("centralRelease").isPresent) {
    mavenPublishing {
        // Return once Central has validated the deployment; Central then publishes it on its own.
        publishToMavenCentral(automaticRelease = true, validateDeployment = DeploymentValidation.VALIDATED)
        signAllPublications()
    }
}

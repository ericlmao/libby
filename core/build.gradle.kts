import gg.moonrise.libby.gradle.GenerateLibbyProperties
import gg.moonrise.libby.gradle.LayoutMavenRepository
import gg.moonrise.libby.gradle.SystemPropertyDirectory
import org.gradle.api.artifacts.component.ModuleComponentIdentifier

plugins {
    id("libby.java-conventions")
}

description = "Runtime Maven dependency downloader, relocator and loader for plugins"

dependencies {
    compileOnlyApi(libs.jspecify)
    testCompileOnly(libs.jspecify)
}

val generateLibbyProperties = tasks.register<GenerateLibbyProperties>("generateLibbyProperties") {
    libbyVersion = provider { project.version.toString() }
    outputDirectory = layout.buildDirectory.dir("generated/sources/libby/java/main")
}

sourceSets.main {
    java.srcDir(generateLibbyProperties.flatMap { it.outputDirectory })
}

// The jar relocator and ASM jars that RelocationHelper downloads at runtime (asm-tree is not
// needed: jar-relocator only uses the visitor API and asm-commons remappers). The relocation
// test loads them from a file: repository laid out from this configuration so it runs offline
// while still exercising the real download, checksum and isolated class loading path.
val relocatorTestRuntime = configurations.create("relocatorTestRuntime") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    relocatorTestRuntime(libs.jar.relocator)
    relocatorTestRuntime(libs.asm)
    relocatorTestRuntime(libs.asm.commons)
}

val relocatorTestRepository = tasks.register<LayoutMavenRepository>("relocatorTestRepository") {
    val artifacts = relocatorTestRuntime.incoming.artifacts
    artifactFiles.from(artifacts.artifactFiles)
    coordinates = artifacts.resolvedArtifacts.map { results ->
        results.map {
            val id = it.id.componentIdentifier as ModuleComponentIdentifier
            "${id.group}:${id.module}:${id.version}:${it.file.name}"
        }
    }
    outputDirectory = layout.buildDirectory.dir("relocator-test-repository")
}

tasks.test {
    jvmArgumentProviders.add(objects.newInstance<SystemPropertyDirectory>().apply {
        propertyName = "libby.test.relocatorRepository"
        directory = relocatorTestRepository.flatMap { it.outputDirectory }
    })
}

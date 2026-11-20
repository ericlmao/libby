package net.byteflux.libby.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.inject.Inject

/**
 * Copies resolved artifacts into a directory laid out like a Maven repository
 * (`group/as/path/artifact/version/file`), so tests can use it as a `file:` repository.
 */
@CacheableTask
abstract class LayoutMavenRepository : DefaultTask() {
    /** The resolved artifact files. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val artifactFiles: ConfigurableFileCollection

    /** One `group:module:version:fileName` entry per resolved artifact. */
    @get:Input
    abstract val coordinates: ListProperty<String>

    /** The repository root to create. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Inject
    protected abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun layout() {
        val root = outputDirectory.get().asFile
        fileSystem.delete { delete(root) }
        val filesByName = artifactFiles.files.associateBy { it.name }
        for (entry in coordinates.get()) {
            val (group, module, version, fileName) = entry.split(':')
            val source = filesByName[fileName] ?: error("Missing resolved file $fileName")
            val target = root.resolve("${group.replace('.', '/')}/$module/$version/$module-$version.jar")
            target.parentFile.mkdirs()
            source.copyTo(target, overwrite = true)
        }
    }
}

package gg.moonrise.libby.gradle

import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/**
 * Passes a directory to a forked JVM as `-D<name>=<absolute path>` while tracking its contents
 * (rather than its absolute location) as a task input.
 */
abstract class SystemPropertyDirectory : CommandLineArgumentProvider {
    /** The system property name. */
    @get:Input
    abstract val propertyName: Property<String>

    /** The directory to pass. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val directory: DirectoryProperty

    override fun asArguments(): Iterable<String> = listOf("-D${propertyName.get()}=${directory.get().asFile.absolutePath}")
}

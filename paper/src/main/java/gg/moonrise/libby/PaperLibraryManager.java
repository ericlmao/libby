package gg.moonrise.libby;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.library.impl.JarLibrary;
import gg.moonrise.libby.logging.adapters.SLF4JLogAdapter;
import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * A runtime dependency manager for Paper plugins (plugins with a
 * {@code paper-plugin.yml}), used from the plugin's
 * {@link io.papermc.paper.plugin.loader.PluginLoader}.
 * <p>
 * Libraries are handed to Paper with
 * {@link PluginClasspathBuilder#addLibrary(io.papermc.paper.plugin.loader.library.ClassPathLibrary)},
 * so no reflection is needed. Note that Paper's plugin loader API is marked
 * {@code @ApiStatus.Experimental} and may change between Paper versions.
 * <pre>{@code
 * public final class MyPluginLoader implements PluginLoader {
 *     @Override
 *     public void classloader(PluginClasspathBuilder classpathBuilder) {
 *         PaperLibraryManager libraryManager = new PaperLibraryManager(classpathBuilder);
 *         libraryManager.addMavenCentral();
 *         libraryManager.loadLibrary(Library.builder()
 *             .groupId("com{}google{}code{}gson")
 *             .artifactId("gson")
 *             .version("2.11.0")
 *             .build());
 *     }
 * }
 * }</pre>
 * Register the loader with {@code loader: com.example.MyPluginLoader} in
 * {@code paper-plugin.yml}.
 */
@NullMarked
public class PaperLibraryManager extends LibraryManager {
    /**
     * The Paper classpath builder libraries are added to
     */
    private final PluginClasspathBuilder classpathBuilder;

    /**
     * Creates a new Paper library manager that saves libraries to the
     * {@value #DEFAULT_DIRECTORY_NAME} directory in the plugin's data
     * directory.
     *
     * @param classpathBuilder the classpath builder passed to
     *                         {@link io.papermc.paper.plugin.loader.PluginLoader#classloader(PluginClasspathBuilder)}
     */
    public PaperLibraryManager(PluginClasspathBuilder classpathBuilder) {
        this(classpathBuilder, DEFAULT_DIRECTORY_NAME);
    }

    /**
     * Creates a new Paper library manager.
     *
     * @param classpathBuilder the classpath builder passed to
     *                         {@link io.papermc.paper.plugin.loader.PluginLoader#classloader(PluginClasspathBuilder)}
     * @param directoryName    name of the directory in the plugin's data
     *                         directory that libraries are saved to
     */
    public PaperLibraryManager(PluginClasspathBuilder classpathBuilder, String directoryName) {
        super(new SLF4JLogAdapter(requireNonNull(classpathBuilder, "classpathBuilder").getContext().getLogger()),
            classpathBuilder.getContext().getDataDirectory(),
            directoryName);
        this.classpathBuilder = classpathBuilder;
    }

    /**
     * Adds a file to the Paper plugin's classpath.
     *
     * @param file the file to add
     */
    @Override
    protected void addToClasspath(Path file) {
        classpathBuilder.addLibrary(new JarLibrary(file));
    }
}

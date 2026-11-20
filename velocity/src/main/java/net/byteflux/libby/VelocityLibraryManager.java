package net.byteflux.libby;

import com.google.inject.Inject;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import net.byteflux.libby.logging.adapters.SLF4JLogAdapter;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * A runtime dependency manager for Velocity plugins.
 * <p>
 * Libraries are added to the plugin's classpath with
 * {@link PluginManager#addToClasspath(Object, Path)}. The manager can be
 * injected by Guice, for example as a constructor parameter of type
 * {@code VelocityLibraryManager<MyPlugin>}, or created directly:
 * <pre>{@code
 * @Inject
 * public MyPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
 *     VelocityLibraryManager<MyPlugin> libraryManager =
 *         new VelocityLibraryManager<>(this, logger, dataDirectory, server.getPluginManager());
 *     libraryManager.addMavenCentral();
 *     libraryManager.loadLibrary(Library.builder()
 *         .groupId("com{}google{}code{}gson")
 *         .artifactId("gson")
 *         .version("2.11.0")
 *         .build());
 * }
 * }</pre>
 *
 * @param <T> the plugin's main class
 */
@NullMarked
public class VelocityLibraryManager<T> extends LibraryManager {
    /**
     * Velocity plugin manager used for adding files to the plugin's classpath
     */
    private final PluginManager pluginManager;

    /**
     * The plugin instance required by the plugin manager to add files to the
     * plugin's classpath
     */
    private final Object plugin;

    /**
     * Creates a new Velocity library manager. This constructor is used by
     * Guice injection.
     *
     * @param logger        the plugin logger
     * @param dataDirectory plugin's data directory
     * @param pluginManager Velocity plugin manager
     * @param plugin        the plugin to manage
     */
    @Inject
    public VelocityLibraryManager(Logger logger,
                                  @DataDirectory Path dataDirectory,
                                  PluginManager pluginManager,
                                  T plugin) {

        this(plugin, logger, dataDirectory, pluginManager);
    }

    /**
     * Creates a new Velocity library manager that saves libraries to the
     * {@value #DEFAULT_DIRECTORY_NAME} directory in the plugin's data
     * directory.
     *
     * @param plugin        the plugin instance to manage
     * @param logger        the plugin logger
     * @param dataDirectory plugin's data directory
     * @param pluginManager Velocity plugin manager
     */
    public VelocityLibraryManager(Object plugin, Logger logger, Path dataDirectory, PluginManager pluginManager) {
        super(new SLF4JLogAdapter(logger), dataDirectory);
        this.pluginManager = requireNonNull(pluginManager, "pluginManager");
        this.plugin = requireNonNull(plugin, "plugin");
    }

    /**
     * Adds a file to the Velocity plugin's classpath.
     *
     * @param file the file to add
     */
    @Override
    protected void addToClasspath(Path file) {
        pluginManager.addToClasspath(plugin, file);
    }
}

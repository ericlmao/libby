package gg.moonrise.libby;

import cn.nukkit.plugin.Plugin;
import gg.moonrise.libby.classloader.URLClassLoaderHelper;
import gg.moonrise.libby.logging.adapters.NukkitLogAdapter;
import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * A runtime dependency manager for Nukkit plugins.
 * <p>
 * Libraries are added to the plugin's class loader, see
 * {@link URLClassLoaderHelper} for the requirements on modern Java versions.
 */
@NullMarked
public class NukkitLibraryManager extends LibraryManager {
    /**
     * Plugin classpath helper
     */
    private final URLClassLoaderHelper classLoader;

    /**
     * Creates a new Nukkit library manager that saves libraries to the
     * {@value #DEFAULT_DIRECTORY_NAME} directory in the plugin's data folder.
     *
     * @param plugin the plugin to manage
     * @throws IllegalStateException if the plugin's class loader isn't a {@link java.net.URLClassLoader}
     */
    public NukkitLibraryManager(Plugin plugin) {
        this(plugin, DEFAULT_DIRECTORY_NAME);
    }

    /**
     * Creates a new Nukkit library manager.
     *
     * @param plugin        the plugin to manage
     * @param directoryName name of the directory in the plugin's data folder
     *                      that libraries are saved to
     * @throws IllegalStateException if the plugin's class loader isn't a {@link java.net.URLClassLoader}
     */
    public NukkitLibraryManager(Plugin plugin, String directoryName) {
        super(new NukkitLogAdapter(requireNonNull(plugin, "plugin").getLogger()), plugin.getDataFolder().toPath(), directoryName);
        classLoader = URLClassLoaderHelper.forPlugin(plugin.getClass().getClassLoader(), logger);
    }

    /**
     * Adds a file to the Nukkit plugin's classpath.
     *
     * @param file the file to add
     */
    @Override
    protected void addToClasspath(Path file) {
        classLoader.addToClasspath(file);
    }
}

package gg.moonrise.libby;

import org.bukkit.plugin.Plugin;
import gg.moonrise.libby.classloader.URLClassLoaderHelper;
import gg.moonrise.libby.logging.adapters.JDKLogAdapter;
import org.jspecify.annotations.NullMarked;

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * A runtime dependency manager for Bukkit plugins.
 * <p>
 * Libraries are added to the plugin's class loader, see
 * {@link URLClassLoaderHelper} for the requirements on modern Java versions.
 */
@NullMarked
public class BukkitLibraryManager extends LibraryManager {
    /**
     * Plugin classpath helper
     */
    private final URLClassLoaderHelper classLoader;

    /**
     * Creates a new Bukkit library manager that saves libraries to the
     * {@value #DEFAULT_DIRECTORY_NAME} directory in the plugin's data folder.
     *
     * @param plugin the plugin to manage
     * @throws IllegalStateException if the plugin's class loader isn't a {@link java.net.URLClassLoader}
     */
    public BukkitLibraryManager(Plugin plugin) {
        this(plugin, DEFAULT_DIRECTORY_NAME);
    }

    /**
     * Creates a new Bukkit library manager.
     *
     * @param plugin        the plugin to manage
     * @param directoryName name of the directory in the plugin's data folder
     *                      that libraries are saved to
     * @throws IllegalStateException if the plugin's class loader isn't a {@link java.net.URLClassLoader}
     */
    public BukkitLibraryManager(Plugin plugin, String directoryName) {
        super(new JDKLogAdapter(requireNonNull(plugin, "plugin").getLogger()), plugin.getDataFolder().toPath(), directoryName);
        classLoader = URLClassLoaderHelper.forPlugin(plugin.getClass().getClassLoader(), logger);
    }

    /**
     * Adds a file to the Bukkit plugin's classpath.
     *
     * @param file the file to add
     */
    @Override
    protected void addToClasspath(Path file) {
        classLoader.addToClasspath(file);
    }
}

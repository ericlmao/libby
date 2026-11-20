package gg.moonrise.libby.classloader;

import org.jspecify.annotations.Nullable;

import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * This class loader is a simple child of {@code URLClassLoader} that by
 * default uses the JVM's platform class loader as the parent instead of the
 * system class loader to provide an unpolluted classpath.
 * <p>
 * Its {@link #addURL(URL)} method is public, so libraries can always be
 * loaded into it with
 * {@link gg.moonrise.libby.LibraryManager#loadLibrary(gg.moonrise.libby.Library, IsolatedClassLoader)}
 * even where the plugin's own class loader can't be modified.
 */
public class IsolatedClassLoader extends URLClassLoader {
    static {
        ClassLoader.registerAsParallelCapable();
    }

    /**
     * Creates a new isolated class loader for the given URLs.
     *
     * @param urls the URLs to add to the classpath
     */
    public IsolatedClassLoader(URL... urls) {
        this(ClassLoader.getPlatformClassLoader(), urls);
    }

    /**
     * Creates a new isolated class loader for the given URLs with a custom
     * parent, for example the plugin's class loader so the loaded libraries
     * can see the plugin's classes.
     *
     * @param parent the parent class loader, or null for the bootstrap class loader
     * @param urls   the URLs to add to the classpath
     */
    public IsolatedClassLoader(@Nullable ClassLoader parent, URL... urls) {
        super(requireNonNull(urls, "urls"), parent);
    }

    /**
     * Adds a URL to the classpath.
     *
     * @param url the URL to add
     */
    @Override
    public void addURL(URL url) {
        super.addURL(url);
    }

    /**
     * Adds a path to the classpath.
     *
     * @param path the path to add
     */
    public void addPath(Path path) {
        try {
            addURL(requireNonNull(path, "path").toUri().toURL());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

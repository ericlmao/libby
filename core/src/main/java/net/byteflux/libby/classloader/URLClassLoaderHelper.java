package net.byteflux.libby.classloader;

import net.byteflux.libby.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * A reflection-based wrapper around {@link URLClassLoader} for adding URLs to
 * the classpath.
 * <p>
 * {@code URLClassLoader#addURL} is protected, so it is reached with the first
 * of these strategies that works, resolved on the first call to
 * {@link #addToClasspath(URL)}:
 * <ol>
 *     <li>a public {@code addURL(URL)} method on the class loader's own class,
 *     for class loaders that widen its visibility;</li>
 *     <li>deep reflection on {@code URLClassLoader#addURL}, which works on
 *     Java 8 to 15 or when the JVM is started with
 *     {@code --add-opens java.base/java.net=ALL-UNNAMED};</li>
 *     <li>a trusted method handle lookup obtained through
 *     {@code sun.misc.Unsafe}. On JDK 24 and newer this makes the JVM print a
 *     one-time warning about terminally deprecated {@code sun.misc.Unsafe}
 *     methods, and it may stop working in a future JDK. Disable it by setting
 *     the system property {@value #UNSAFE_DISABLED_PROPERTY} or the
 *     environment variable {@value #UNSAFE_DISABLED_ENVIRONMENT} to
 *     {@code true}.</li>
 * </ol>
 * If none works, an {@link IllegalStateException} explains how to fix it.
 */
public class URLClassLoaderHelper {
    /**
     * System property that disables the {@code sun.misc.Unsafe} strategy when
     * set to {@code true}
     */
    public static final String UNSAFE_DISABLED_PROPERTY = "libby.classloaders.unsafeDisabled";

    /**
     * Environment variable that disables the {@code sun.misc.Unsafe} strategy
     * when set to {@code true}
     */
    public static final String UNSAFE_DISABLED_ENVIRONMENT = "LIBBY_CLASSLOADERS_UNSAFE_DISABLED";

    /**
     * The class loader being managed by this helper.
     */
    private final URLClassLoader classLoader;

    /**
     * Logger for the chosen strategy, or null
     */
    private final @Nullable Logger logger;

    /**
     * Guards lazy strategy resolution
     */
    private final Object lock = new Object();

    /**
     * Method handle of type {@code (URL)void} bound to the class loader that
     * adds a URL to its classpath, resolved on first use
     */
    private volatile @Nullable MethodHandle addURL;

    /**
     * Creates a new URL class loader helper.
     *
     * @param classLoader the class loader to manage
     */
    public URLClassLoaderHelper(URLClassLoader classLoader) {
        this(classLoader, null);
    }

    /**
     * Creates a new URL class loader helper that logs the chosen strategy at
     * debug level.
     *
     * @param classLoader the class loader to manage
     * @param logger      logger for the chosen strategy, or null
     */
    public URLClassLoaderHelper(URLClassLoader classLoader, @Nullable Logger logger) {
        this.classLoader = requireNonNull(classLoader, "classLoader");
        this.logger = logger;
    }

    /**
     * Adds a URL to the class loader's classpath.
     *
     * @param url the URL to add
     * @throws IllegalStateException if no strategy can access {@code addURL}
     */
    public void addToClasspath(URL url) {
        requireNonNull(url, "url");
        MethodHandle handle = addURLHandle();
        try {
            handle.invokeExact(url);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Throwable t) {
            throw new IllegalStateException("Failed to add " + url + " to the classpath", t);
        }
    }

    /**
     * Adds a path to the class loader's classpath.
     *
     * @param path the path to add
     * @throws IllegalStateException if no strategy can access {@code addURL}
     */
    public void addToClasspath(Path path) {
        try {
            addToClasspath(requireNonNull(path, "path").toUri().toURL());
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }

    /**
     * Gets the bound {@code addURL} method handle, resolving it on first use.
     *
     * @return the method handle
     */
    private MethodHandle addURLHandle() {
        MethodHandle handle = addURL;
        if (handle == null) {
            synchronized (lock) {
                handle = addURL;
                if (handle == null) {
                    handle = resolveAddURL();
                    addURL = handle;
                }
            }
        }

        return handle;
    }

    /**
     * Tries every strategy in order.
     *
     * @return method handle of type {@code (URL)void} bound to the class loader
     * @throws IllegalStateException if no strategy works
     */
    private MethodHandle resolveAddURL() {
        List<String> failures = new ArrayList<>();

        try {
            Method method = classLoader.getClass().getMethod("addURL", URL.class);
            method.setAccessible(true);
            return chosen("public addURL method of " + classLoader.getClass().getName(), MethodHandles.lookup().unreflect(method));
        } catch (NoSuchMethodException e) {
            failures.add("no public addURL method");
        } catch (ReflectiveOperationException | RuntimeException e) {
            failures.add("public addURL method: " + e);
        }

        try {
            Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
            method.setAccessible(true);
            return chosen("reflection on URLClassLoader#addURL", MethodHandles.lookup().unreflect(method));
        } catch (ReflectiveOperationException | RuntimeException e) {
            failures.add("reflection: " + e);
        }

        if (isUnsafeDisabled()) {
            failures.add("sun.misc.Unsafe: disabled by " + UNSAFE_DISABLED_PROPERTY + '/' + UNSAFE_DISABLED_ENVIRONMENT);
        } else {
            try {
                return chosen("sun.misc.Unsafe trusted lookup", trustedLookup()
                    .findVirtual(URLClassLoader.class, "addURL", MethodType.methodType(void.class, URL.class)));
            } catch (ReflectiveOperationException | RuntimeException e) {
                failures.add("sun.misc.Unsafe: " + e);
            }
        }

        throw new IllegalStateException("Can't add URLs to the classpath of " + classLoader
            + ": URLClassLoader#addURL is not accessible on Java " + Runtime.version().feature()
            + ". Add the JVM flag --add-opens java.base/java.net=ALL-UNNAMED, use PaperLibraryManager on Paper,"
            + " or load the library into an IsolatedClassLoader with LibraryManager#loadLibrary(Library, IsolatedClassLoader)."
            + " Attempts: " + failures);
    }

    /**
     * Binds the chosen method handle and logs the strategy.
     *
     * @param strategy strategy description
     * @param handle   unbound {@code addURL} method handle
     * @return the handle bound to the class loader
     */
    private MethodHandle chosen(String strategy, MethodHandle handle) {
        if (logger != null) {
            logger.debug("Adding URLs to " + classLoader.getClass().getName() + " using " + strategy);
        }

        return handle.bindTo(classLoader);
    }

    /**
     * Gets whether the {@code sun.misc.Unsafe} strategy is disabled. Read on
     * every resolution so it can be changed at runtime.
     *
     * @return true if disabled
     */
    private static boolean isUnsafeDisabled() {
        return Boolean.parseBoolean(System.getProperty(UNSAFE_DISABLED_PROPERTY))
            || Boolean.parseBoolean(System.getenv(UNSAFE_DISABLED_ENVIRONMENT));
    }

    /**
     * Reads the JDK's trusted {@code MethodHandles.Lookup.IMPL_LOOKUP} through
     * {@code sun.misc.Unsafe}. Everything is looked up reflectively so that no
     * proprietary API is referenced at compile time.
     *
     * @return the trusted lookup
     * @throws ReflectiveOperationException if any step fails
     */
    private static MethodHandles.Lookup trustedLookup() throws ReflectiveOperationException {
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Object unsafe = null;
        for (Field field : unsafeClass.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == unsafeClass) {
                field.setAccessible(true);
                unsafe = field.get(null);
                break;
            }
        }
        if (unsafe == null) {
            throw new NoSuchFieldException("sun.misc.Unsafe instance field");
        }

        Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
        Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, implLookup);
        long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, implLookup);
        return (MethodHandles.Lookup) unsafeClass.getMethod("getObject", Object.class, long.class).invoke(unsafe, base, offset);
    }
}

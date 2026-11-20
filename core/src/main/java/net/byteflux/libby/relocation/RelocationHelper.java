package net.byteflux.libby.relocation;

import net.byteflux.libby.Library;
import net.byteflux.libby.LibraryManager;
import net.byteflux.libby.classloader.IsolatedClassLoader;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * A reflection-based helper for relocating library jars. It automatically
 * downloads and invokes Luck's Jar Relocator to perform jar relocations.
 * <p>
 * The jar relocator and the ASM jars it needs are downloaded with pinned
 * checksums through the library manager's repositories, with Maven Central
 * as a fallback, and loaded into a dedicated {@link IsolatedClassLoader} that
 * is reused for every relocation.
 *
 * @see <a href="https://github.com/lucko/jar-relocator">Luck's Jar Relocator</a>
 */
public class RelocationHelper {
    /**
     * Version of Luck's Jar Relocator used to relocate jars
     */
    public static final String JAR_RELOCATOR_VERSION = "1.7";

    /**
     * Version of ObjectWeb ASM used by the jar relocator
     */
    public static final String ASM_VERSION = "9.11.1";

    /**
     * Identifies the relocation tooling. It's part of every relocated jar's
     * file name so upgrading the tooling produces new relocated jars.
     */
    public static final String TOOLCHAIN = "jar-relocator:" + JAR_RELOCATOR_VERSION + ",asm:" + ASM_VERSION;

    /**
     * Reflected constructor for creating new jar relocator instances
     */
    private final Constructor<?> jarRelocatorConstructor;

    /**
     * Reflected method for running a jar relocator
     */
    private final Method jarRelocatorRunMethod;

    /**
     * Reflected constructor for creating relocation instances
     */
    private final Constructor<?> relocationConstructor;

    /**
     * Creates a new relocation helper using the provided library manager to
     * download the dependencies required for runtime relocation.
     *
     * @param libraryManager the library manager used to download dependencies
     * @throws net.byteflux.libby.LibraryDownloadException if a dependency couldn't be downloaded
     */
    public RelocationHelper(LibraryManager libraryManager) {
        requireNonNull(libraryManager, "libraryManager");

        // Group IDs and class names use "{}" for "." so that plugins shading
        // and relocating libby don't rewrite these string literals.
        List<Library> libraries = List.of(
            // ObjectWeb ASM
            Library.builder()
                   .groupId("org{}ow2{}asm")
                   .artifactId("asm")
                   .version(ASM_VERSION)
                   .checksum("rk5uENWdChUAMXdkshiBrYg/hVDjPGbmFC9wMsb7Vmw=")
                   .build(),

            // ObjectWeb ASM Commons
            Library.builder()
                   .groupId("org{}ow2{}asm")
                   .artifactId("asm-commons")
                   .version(ASM_VERSION)
                   .checksum("alCV7FNyYVaniyzS79RbxOh3dU7KfCNPYSJKU9a/pmE=")
                   .build(),

            // Luck's Jar Relocator
            Library.builder()
                   .groupId("me{}lucko")
                   .artifactId("jar-relocator")
                   .version(JAR_RELOCATOR_VERSION)
                   .checksum("b30RhOF6kHiHl+O5suNLh/+eAr1iOFEFLXhwkHHDu4I=")
                   .build()
        );

        IsolatedClassLoader classLoader = new IsolatedClassLoader();
        for (Library library : libraries) {
            classLoader.addPath(download(libraryManager, library));
        }

        try {
            Class<?> jarRelocatorClass = classLoader.loadClass("me{}lucko{}jarrelocator{}JarRelocator".replace("{}", "."));
            Class<?> relocationClass = classLoader.loadClass("me{}lucko{}jarrelocator{}Relocation".replace("{}", "."));

            // me.lucko.jarrelocator.JarRelocator(File, File, Collection)
            jarRelocatorConstructor = jarRelocatorClass.getConstructor(File.class, File.class, Collection.class);

            // me.lucko.jarrelocator.JarRelocator#run()
            jarRelocatorRunMethod = jarRelocatorClass.getMethod("run");

            // me.lucko.jarrelocator.Relocation(String, String, Collection, Collection)
            relocationConstructor = relocationClass.getConstructor(String.class, String.class, Collection.class, Collection.class);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to load the jar relocator", e);
        }
    }

    /**
     * Downloads a relocation dependency, trying the library manager's
     * repositories first and Maven Central last.
     *
     * @param libraryManager the library manager
     * @param library        the dependency
     * @return local path to the dependency
     */
    private static Path download(LibraryManager libraryManager, Library library) {
        List<String> urls = new ArrayList<>(libraryManager.resolveLibrary(library));
        String central = LibraryManager.MAVEN_CENTRAL + library.getPath();
        if (!urls.contains(central)) {
            urls.add(central);
        }

        return libraryManager.downloadLibrary(library, urls);
    }

    /**
     * Invokes the jar relocator to process the input jar and generate an
     * output jar with the provided relocation rules applied.
     *
     * @param in          input jar
     * @param out         output jar
     * @param relocations relocations to apply
     * @throws UncheckedIOException if the jar relocator fails with an I/O error
     */
    public void relocate(Path in, Path out, Collection<Relocation> relocations) {
        requireNonNull(in, "in");
        requireNonNull(out, "out");
        requireNonNull(relocations, "relocations");

        try {
            List<Object> rules = new ArrayList<>(relocations.size());
            for (Relocation relocation : relocations) {
                rules.add(relocationConstructor.newInstance(
                    relocation.getPattern(),
                    relocation.getRelocatedPattern(),
                    relocation.getIncludes(),
                    relocation.getExcludes()
                ));
            }

            jarRelocatorRunMethod.invoke(jarRelocatorConstructor.newInstance(in.toFile(), out.toFile(), rules));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof IOException) {
                throw new UncheckedIOException("Failed to relocate " + in, (IOException) cause);
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new IllegalStateException("Failed to relocate " + in, cause);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to invoke the jar relocator", e);
        }
    }
}

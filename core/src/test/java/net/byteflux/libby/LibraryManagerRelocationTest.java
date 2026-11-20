package net.byteflux.libby;

import net.byteflux.libby.logging.LogLevel;
import net.byteflux.libby.relocation.Relocation;
import net.byteflux.libby.transport.Checksums;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the real runtime download, checksum verification and isolated
 * loading of jar-relocator and ASM from a {@code file:} repository prepared by
 * the build, so no network access is needed.
 */
public class LibraryManagerRelocationTest {
    @TempDir
    private Path dataDirectory;

    @TempDir
    private Path libraryRepository;

    @TempDir
    private Path work;

    private TestLibraryManager manager;

    @BeforeEach
    public void setUp() throws IOException {
        assertNotNull(System.getProperty("libby.test.relocatorRepository"), "the build must pass libby.test.relocatorRepository");

        Path artifact = libraryRepository.resolve("com/example/greeter/1.0/greeter-1.0.jar");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, greeterJar("hello"));

        manager = newManager();
    }

    /**
     * Creates a manager using the shared data directory, the relocator
     * repository and the library repository, like a restarted plugin.
     */
    private TestLibraryManager newManager() {
        TestLibraryManager created = TestLibraryManager.create(dataDirectory);
        created.addRepository(Path.of(System.getProperty("libby.test.relocatorRepository")).toUri().toString());
        created.addRepository(libraryRepository.toUri().toString());
        return created;
    }

    private byte[] greeterJar(String message) throws IOException {
        return TestJars.compiledJar(Files.createTempDirectory(work, "greeter"), Map.of(
            "com.example.lib.Greeter",
            "package com.example.lib; public class Greeter { public static String greet() { return com.example.lib.internal.Helper.message(); } }",
            "com.example.lib.internal.Helper",
            "package com.example.lib.internal; public class Helper { public static String message() { return \"" + message + "\"; } }"
        ));
    }

    private Library.Builder greeter() {
        return Library.builder().groupId("com{}example").artifactId("greeter").version("1.0");
    }

    private static Set<String> entries(Path jar) throws IOException {
        try (JarFile file = new JarFile(jar.toFile())) {
            return file.stream().map(JarEntry::getName).collect(Collectors.toSet());
        }
    }

    @Test
    public void relocatesWithDownloadedJarRelocator() throws Exception {
        Library library = greeter().relocate("com{}example{}lib", "shaded{}greeter").build();

        manager.loadLibrary(library);

        Path relocated = manager.saveDirectory().resolve(library.getRelocatedPath());
        assertEquals(List.of(relocated), manager.classpath());
        assertEquals(manager.saveDirectory().resolve(".relocated/com/example/greeter/1.0"), relocated.getParent());
        assertTrue(relocated.getFileName().toString().matches("greeter-1\\.0-relocated-[0-9a-f]{8}\\.jar"), relocated.toString());
        Set<String> entries = entries(relocated);
        assertTrue(entries.contains("shaded/greeter/Greeter.class"), entries.toString());
        assertTrue(entries.contains("shaded/greeter/internal/Helper.class"), entries.toString());
        assertTrue(entries.stream().noneMatch(name -> name.startsWith("com/example")), entries.toString());

        try (URLClassLoader loader = new URLClassLoader(new URL[] {relocated.toUri().toURL()}, null)) {
            Method greet = loader.loadClass("shaded.greeter.Greeter").getMethod("greet");
            assertEquals("hello", greet.invoke(null));
        }

        // The relocation tooling was fetched from the file repository with pinned checksums.
        assertTrue(Files.isRegularFile(manager.saveDirectory().resolve("me/lucko/jar-relocator/1.7/jar-relocator-1.7.jar")));
        assertEquals(0, manager.log().count(LogLevel.WARN, "INVALID CHECKSUM"));
    }

    @Test
    public void reusesRelocatedJarForSameRules() {
        Library library = greeter().relocate("com{}example{}lib", "shaded{}greeter").build();

        manager.loadLibrary(library);
        manager.loadLibrary(greeter().relocate(new Relocation("com.example.lib", "shaded.greeter")).build());

        assertEquals(1, manager.log().count(LogLevel.INFO, "Relocations applied"));
    }

    @Test
    public void relocatesAgainWhenTheDownloadedJarChanges() throws Exception {
        Library library = greeter().relocate("com{}example{}lib", "shaded{}greeter").build();
        manager.loadLibrary(library);

        // The cached jar is replaced, for example downloaded again after it failed its
        // recorded checksum, and the new download has different contents.
        Files.write(libraryRepository.resolve("com/example/greeter/1.0/greeter-1.0.jar"), greeterJar("updated"));
        Checksums.deleteWithRecord(manager.saveDirectory().resolve(library.getPath()));

        TestLibraryManager restarted = newManager();
        restarted.loadLibrary(library);

        assertEquals(1, restarted.log().count(LogLevel.INFO, "Relocations applied"));
        assertEquals(1, restarted.log().count(LogLevel.DEBUG, "wasn't created from the current greeter-1.0.jar"));
        Path relocated = restarted.saveDirectory().resolve(library.getRelocatedPath());
        try (URLClassLoader loader = new URLClassLoader(new URL[] {relocated.toUri().toURL()}, null)) {
            assertEquals("updated", loader.loadClass("shaded.greeter.Greeter").getMethod("greet").invoke(null));
        }

        // Unchanged input: the relocated jar is reused.
        TestLibraryManager again = newManager();
        again.loadLibrary(library);
        assertEquals(0, again.log().count(LogLevel.INFO, "Relocations applied"));
    }

    @Test
    public void transitiveVersionsAreTrackedPerRelocation() throws IOException {
        publish("com.example:d:1.0", "");
        publish("com.example:d:2.0", "");
        publish("com.example:a:1.0", "<dependencies><dependency><groupId>com.example</groupId><artifactId>d</artifactId><version>1.0</version></dependency></dependencies>");
        publish("com.example:b:1.0", "<dependencies><dependency><groupId>com.example</groupId><artifactId>d</artifactId><version>2.0</version></dependency></dependencies>");
        publish("com.example:c:1.0", "<dependencies><dependency><groupId>com.example</groupId><artifactId>d</artifactId><version>2.0</version></dependency></dependencies>");

        Library a = Library.builder().groupId("com.example").artifactId("a").version("1.0")
            .relocate("com{}example", "ra").resolveTransitiveDependencies(true).build();
        Library b = Library.builder().groupId("com.example").artifactId("b").version("1.0")
            .relocate("com{}example", "rb").resolveTransitiveDependencies(true).build();
        Library c = Library.builder().groupId("com.example").artifactId("c").version("1.0")
            .relocate("com{}example", "ra").resolveTransitiveDependencies(true).build();
        Library dForA = Library.builder().groupId("com.example").artifactId("d").version("1.0").relocate("com{}example", "ra").build();
        Library dForB = Library.builder().groupId("com.example").artifactId("d").version("2.0").relocate("com{}example", "rb").build();

        manager.loadLibrary(a);
        manager.loadLibrary(b);

        // b's code refers to rb.* classes, so its own relocated d 2.0 must be loaded.
        Path save = manager.saveDirectory();
        assertEquals(List.of(
            save.resolve(dForA.getRelocatedPath()),
            save.resolve(a.getRelocatedPath()),
            save.resolve(dForB.getRelocatedPath()),
            save.resolve(b.getRelocatedPath())
        ), manager.classpath());
        assertEquals(0, manager.log().count(LogLevel.WARN, "Not loading"));

        // With the same relocations, the first loaded version still wins.
        manager.loadLibrary(c);
        assertEquals(1, manager.log().count(LogLevel.WARN,
            "Not loading com.example:d:2.0 because version 1.0 of com.example:d (relocated " + dForA.getRelocationFingerprint() + ") is already loaded"));
        assertEquals(5, manager.classpath().size());
    }

    /**
     * Publishes a jar and a POM with the given body to the library repository.
     */
    private void publish(String coordinates, String body) throws IOException {
        String[] parts = coordinates.split(":");
        Path directory = libraryRepository.resolve(parts[0].replace('.', '/') + '/' + parts[1] + '/' + parts[2]);
        Files.createDirectories(directory);
        String name = parts[1] + '-' + parts[2];
        Files.write(directory.resolve(name + ".jar"), TestJars.simpleJar(coordinates));
        Files.writeString(directory.resolve(name + ".pom"), "<project><groupId>" + parts[0] + "</groupId><artifactId>" + parts[1]
            + "</artifactId><version>" + parts[2] + "</version>" + body + "</project>");
    }

    @Test
    public void changingRulesProducesNewJar() throws IOException {
        Library first = greeter().relocate("com{}example{}lib", "shaded{}greeter").build();
        Library second = greeter().relocate("com{}example{}lib", "other{}greeter").build();
        Library excluding = greeter().relocate(Relocation.builder()
            .pattern("com{}example{}lib")
            .relocatedPattern("shaded{}greeter")
            .exclude("com.example.lib.internal.**")
            .build()).build();

        manager.loadLibrary(first);
        manager.loadLibrary(second);
        manager.loadLibrary(excluding);

        assertNotEquals(first.getRelocatedPath(), second.getRelocatedPath());
        assertNotEquals(first.getRelocatedPath(), excluding.getRelocatedPath());
        assertTrue(entries(manager.saveDirectory().resolve(second.getRelocatedPath())).contains("other/greeter/Greeter.class"));
        assertTrue(entries(manager.saveDirectory().resolve(first.getRelocatedPath())).contains("shaded/greeter/Greeter.class"));
        assertEquals(3, manager.log().count(LogLevel.INFO, "Relocations applied"));
    }
}

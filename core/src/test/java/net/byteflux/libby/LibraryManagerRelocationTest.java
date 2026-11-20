package net.byteflux.libby;

import net.byteflux.libby.logging.LogLevel;
import net.byteflux.libby.relocation.Relocation;
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
        String relocatorRepository = System.getProperty("libby.test.relocatorRepository");
        assertNotNull(relocatorRepository, "the build must pass libby.test.relocatorRepository");

        byte[] jar = TestJars.compiledJar(work, Map.of(
            "com.example.lib.Greeter",
            "package com.example.lib; public class Greeter { public static String greet() { return com.example.lib.internal.Helper.message(); } }",
            "com.example.lib.internal.Helper",
            "package com.example.lib.internal; public class Helper { public static String message() { return \"hello\"; } }"
        ));
        Path artifact = libraryRepository.resolve("com/example/greeter/1.0/greeter-1.0.jar");
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, jar);

        manager = TestLibraryManager.create(dataDirectory);
        manager.addRepository(Path.of(relocatorRepository).toUri().toString());
        manager.addRepository(libraryRepository.toUri().toString());
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

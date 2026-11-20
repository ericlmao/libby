package gg.moonrise.libby;

import gg.moonrise.libby.classloader.IsolatedClassLoader;
import gg.moonrise.libby.logging.LogLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class LibraryManagerLoadTest {
    @TempDir
    private Path dataDirectory;

    @TempDir
    private Path repository;

    @TempDir
    private Path work;

    private TestLibraryManager manager;

    @BeforeEach
    public void setUp() throws IOException {
        publish("1.0", TestJars.simpleJar("v1"));
        publish("2.0", TestJars.simpleJar("v2"));
        manager = TestLibraryManager.create(dataDirectory);
        manager.addRepository(repository.toUri().toString());
    }

    private void publish(String version, byte[] jar) throws IOException {
        Path file = repository.resolve("com/example/lib/" + version + "/lib-" + version + ".jar");
        Files.createDirectories(file.getParent());
        Files.write(file, jar);
    }

    private static Library library(String version) {
        return Library.builder().groupId("com.example").artifactId("lib").version(version).build();
    }

    @Test
    public void loadsEachJarOnce() {
        manager.loadLibrary(library("1.0"));
        manager.loadLibrary(library("1.0"));

        assertEquals(List.of(manager.saveDirectory().resolve("com/example/lib/1.0/lib-1.0.jar")), manager.classpath());
    }

    @Test
    public void firstVersionWins() {
        manager.loadLibrary(library("1.0"));
        manager.loadLibrary(library("2.0"));

        assertEquals(1, manager.classpath().size());
        assertEquals(1, manager.log().count(LogLevel.WARN, "Not loading com.example:lib:2.0 because version 1.0 of com.example:lib is already loaded"));
        assertFalse(Files.exists(manager.saveDirectory().resolve("com/example/lib/2.0/lib-2.0.jar")));
    }

    @Test
    public void failedLoadDoesNotBlockOtherVersions() {
        assertThrows(LibraryDownloadException.class, () -> manager.loadLibrary(library("3.0")));
        manager.loadLibrary(library("2.0"));

        assertEquals(List.of(manager.saveDirectory().resolve("com/example/lib/2.0/lib-2.0.jar")), manager.classpath());
    }

    @Test
    public void classifiersAreSeparateLibraries() throws IOException {
        Path file = repository.resolve("com/example/lib/2.0/lib-2.0-extra.jar");
        Files.write(file, TestJars.simpleJar("extra"));

        manager.loadLibrary(library("1.0"));
        manager.loadLibrary(Library.builder().groupId("com.example").artifactId("lib").version("2.0").classifier("extra").build());

        assertEquals(2, manager.classpath().size());
    }

    @Test
    public void loadsIntoIsolatedClassLoader() throws Exception {
        byte[] jar = TestJars.compiledJar(work, Map.of(
            "com.example.isolated.Greeter",
            "package com.example.isolated; public class Greeter { public static String greet() { return \"isolated\"; } }"
        ));
        publish("3.0", jar);

        try (IsolatedClassLoader loader = new IsolatedClassLoader()) {
            manager.loadLibrary(library("3.0"), loader);
            manager.loadLibrary(library("3.0"), loader);

            assertEquals(1, loader.getURLs().length);
            assertEquals("isolated", loader.loadClass("com.example.isolated.Greeter").getMethod("greet").invoke(null));
            assertEquals(List.of(), manager.classpath());
        }
    }

    @Test
    public void collectLibrariesIsTheExtensionPoint() {
        TestLibraryManager expanding = TestLibraryManager.withDependencies(dataDirectory, List.of(library("2.0")));
        expanding.addRepository(repository.toUri().toString());

        expanding.loadLibrary(library("1.0"));

        assertEquals(List.of(
            expanding.saveDirectory().resolve("com/example/lib/2.0/lib-2.0.jar")
        ), expanding.classpath());
        assertEquals(1, expanding.log().count(LogLevel.WARN, "Not loading com.example:lib:1.0"));
    }
}

package net.byteflux.libby.classloader;

import net.byteflux.libby.RecordingLogAdapter;
import net.byteflux.libby.TestJars;
import net.byteflux.libby.logging.LogLevel;
import net.byteflux.libby.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

public class URLClassLoaderHelperTest {
    @TempDir
    private Path work;

    private Path jar;
    private RecordingLogAdapter log;
    private Logger logger;

    @BeforeEach
    public void setUp() throws IOException {
        jar = work.resolve("greeter.jar");
        Files.write(jar, TestJars.compiledJar(work.resolve("build"), Map.of(
            "com.example.injected.Greeter",
            "package com.example.injected; public class Greeter { public static String greet() { return \"injected\"; } }"
        )));
        log = new RecordingLogAdapter();
        logger = new Logger(log);
        logger.setLevel(LogLevel.DEBUG);
    }

    @AfterEach
    public void tearDown() {
        System.clearProperty(URLClassLoaderHelper.UNSAFE_DISABLED_PROPERTY);
    }

    private static boolean javaNetOpened() {
        return URLClassLoader.class.getModule().isOpen("java.net", URLClassLoaderHelperTest.class.getModule());
    }

    @Test
    public void injectsIntoPlainUrlClassLoader() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            URLClassLoaderHelper helper = new URLClassLoaderHelper(loader, logger);
            assertEquals(0, log.messages().size(), "strategies are resolved lazily");

            helper.addToClasspath(jar);

            assertEquals("injected", loader.loadClass("com.example.injected.Greeter").getMethod("greet").invoke(null));
            String expected = javaNetOpened() ? "reflection on URLClassLoader#addURL" : "sun.misc.Unsafe trusted lookup";
            assertEquals(1, log.count(LogLevel.DEBUG, expected), log.messages().toString());
        }
    }

    @Test
    public void prefersPublicAddUrl() throws Exception {
        try (IsolatedClassLoader loader = new IsolatedClassLoader()) {
            new URLClassLoaderHelper(loader, logger).addToClasspath(jar);

            assertEquals("injected", loader.loadClass("com.example.injected.Greeter").getMethod("greet").invoke(null));
            assertEquals(1, log.count(LogLevel.DEBUG, "public addURL method"), log.messages().toString());
        }
    }

    @Test
    public void explainsHowToFixWhenNoStrategyWorks() throws IOException {
        assumeFalse(javaNetOpened(), "java.net is opened to this test, reflection would succeed");
        System.setProperty(URLClassLoaderHelper.UNSAFE_DISABLED_PROPERTY, "true");

        try (URLClassLoader loader = new URLClassLoader(new URL[0], null)) {
            URLClassLoaderHelper helper = new URLClassLoaderHelper(loader);

            IllegalStateException e = assertThrows(IllegalStateException.class, () -> helper.addToClasspath(jar));

            assertTrue(e.getMessage().contains("--add-opens java.base/java.net=ALL-UNNAMED"), e.getMessage());
            assertTrue(e.getMessage().contains("PaperLibraryManager"), e.getMessage());
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass("com.example.injected.Greeter"));
        }
    }

    @Test
    public void isolatedClassLoaderUsesPlatformParentByDefault() throws IOException {
        try (IsolatedClassLoader loader = new IsolatedClassLoader();
             IsolatedClassLoader child = new IsolatedClassLoader(loader)) {
            assertEquals(ClassLoader.getPlatformClassLoader(), loader.getParent());
            assertEquals(loader, child.getParent());
            assertThrows(ClassNotFoundException.class, () -> loader.loadClass(URLClassLoaderHelperTest.class.getName()));
        }
    }
}

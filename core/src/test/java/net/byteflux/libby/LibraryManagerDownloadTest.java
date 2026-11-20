package net.byteflux.libby;

import net.byteflux.libby.logging.LogLevel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LibraryManagerDownloadTest {
    private static final String PATH = "com/example/lib/1.0/lib-1.0.jar";

    @TempDir
    private Path dataDirectory;

    private TestHttpRepository http;
    private TestLibraryManager manager;

    @BeforeEach
    public void setUp() {
        http = new TestHttpRepository();
        manager = TestLibraryManager.create(dataDirectory);
        manager.setAllowInsecureHttp(true);
    }

    @AfterEach
    public void tearDown() {
        http.close();
    }

    private static Library.Builder library() {
        return Library.builder().groupId("com.example").artifactId("lib").version("1.0");
    }

    private static byte[] sha256(byte[] bytes) {
        return TestJars.digest("SHA-256", bytes);
    }

    private Path target() {
        return manager.saveDirectory().resolve(PATH);
    }

    private List<Path> partFiles() throws IOException {
        if (!Files.exists(manager.saveDirectory())) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(manager.saveDirectory())) {
            return files.filter(file -> file.toString().endsWith(".part")).collect(Collectors.toList());
        }
    }

    @Test
    public void downloadsAndVerifiesPinnedChecksum() throws IOException {
        byte[] jar = TestJars.simpleJar("ok");
        http.put(PATH, jar);
        manager.addRepository(http.url());

        Library library = library().checksum(sha256(jar)).build();
        manager.loadLibrary(library);

        assertArrayEquals(jar, Files.readAllBytes(target()));
        assertEquals(List.of(target()), manager.classpath());
        assertEquals(0, http.requests(PATH + ".sha256"), "pinned checksums don't need checksum files");
        assertEquals(TestJars.hex(sha256(jar)) + "\n", Files.readString(target().resolveSibling("lib-1.0.jar.sha256")));
        assertTrue(partFiles().isEmpty());
    }

    @Test
    public void rejectsPinnedChecksumMismatchAndTriesNextRepository() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        try (TestHttpRepository other = new TestHttpRepository()) {
            http.put(PATH, TestJars.simpleJar("tampered"));
            other.put(PATH, jar);
            manager.addRepository(http.url());
            manager.addRepository(other.url());

            Path file = manager.downloadLibrary(library().checksum(sha256(jar)).build());

            assertArrayEquals(jar, Files.readAllBytes(file));
            assertEquals(1, manager.log().count(LogLevel.WARN, "*** INVALID CHECKSUM ***"));
            assertEquals(1, other.requests(PATH));
        }
        assertTrue(partFiles().isEmpty());
    }

    @Test
    public void redownloadsCorruptedCachedJarWithPinnedChecksum() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        http.put(PATH, jar);
        manager.addRepository(http.url());
        Files.createDirectories(target().getParent());
        Files.write(target(), "corrupted".getBytes(StandardCharsets.UTF_8));

        manager.downloadLibrary(library().checksum(sha256(jar)).build());

        assertArrayEquals(jar, Files.readAllBytes(target()));
        assertEquals(1, http.requests(PATH));
        assertEquals(1, manager.log().count(LogLevel.WARN, "doesn't match the pinned checksum"));
    }

    @Test
    public void redownloadsCachedJarThatFailsRecordedChecksum() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        http.put(PATH, jar);
        http.put(PATH + ".sha1", (TestJars.hex(TestJars.digest("SHA-1", jar)) + "  lib-1.0.jar\n").getBytes(StandardCharsets.US_ASCII));
        manager.addRepository(http.url());
        Library library = library().build();

        manager.downloadLibrary(library);
        Files.write(target(), "corrupted".getBytes(StandardCharsets.UTF_8));
        manager.downloadLibrary(library);

        assertArrayEquals(jar, Files.readAllBytes(target()));
        assertEquals(2, http.requests(PATH));
        assertEquals(1, manager.log().count(LogLevel.WARN, "doesn't match its recorded checksum"));
    }

    @Test
    public void reusesVerifiedCacheWithoutNetwork() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        http.put(PATH, jar);
        manager.addRepository(http.url());
        Library library = library().checksum(sha256(jar)).build();

        manager.downloadLibrary(library);
        manager.downloadLibrary(library);

        assertEquals(1, http.totalRequests());
    }

    @Test
    public void verifiesSha1ChecksumFile() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        http.put(PATH, jar);
        http.put(PATH + ".sha1", TestJars.hex(TestJars.digest("SHA-1", jar)).toUpperCase().getBytes(StandardCharsets.US_ASCII));
        manager.addRepository(http.url());

        Path file = manager.downloadLibrary(library().build());

        assertArrayEquals(jar, Files.readAllBytes(file));
        assertEquals(1, http.requests(PATH + ".sha256"));
        assertEquals(1, http.requests(PATH + ".sha512"));
        assertEquals(1, http.requests(PATH + ".sha1"));
        assertEquals(0, manager.log().count(LogLevel.WARN, "No checksum available"));
    }

    @Test
    public void rejectsDownloadThatFailsChecksumFile() {
        http.put(PATH, TestJars.simpleJar("tampered"));
        http.put(PATH + ".sha256", TestJars.hex(sha256(TestJars.simpleJar("good"))).getBytes(StandardCharsets.US_ASCII));
        manager.addRepository(http.url());

        LibraryDownloadException e = assertThrows(LibraryDownloadException.class, () -> manager.downloadLibrary(library().build()));

        assertTrue(e.getAttempts().get(0).reason().startsWith("checksum mismatch"), e.getMessage());
        assertFalse(Files.exists(target()));
    }

    @Test
    public void warnsOnceWhenNoChecksumIsAvailable() {
        http.put(PATH, TestJars.simpleJar("good"));
        manager.addRepository(http.url());
        Library library = library().build();

        manager.downloadLibrary(library);
        manager.downloadLibrary(library);

        assertEquals(1, manager.log().count(LogLevel.WARN, "No checksum available for com.example:lib:1.0"));
    }

    @Test
    public void neverCachesErrorBodies() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        try (TestHttpRepository other = new TestHttpRepository()) {
            http.putStatus(PATH, 500, "<html>Internal error</html>".getBytes(StandardCharsets.UTF_8));
            other.put(PATH, jar);
            String missing = http.url() + "missing/";
            manager.addRepository(missing);
            manager.addRepository(http.url());
            manager.addRepository(other.url());

            Path file = manager.downloadLibrary(library().build());

            assertArrayEquals(jar, Files.readAllBytes(file));
            assertEquals(1, http.requests("missing/" + PATH));
            assertEquals(1, http.requests(PATH));
        }
    }

    @Test
    public void failureListsEveryAttemptedUrl() {
        String first = http.url() + "a/";
        String second = http.url() + "b/";
        manager.addRepository(first);
        manager.addRepository(second);

        LibraryDownloadException e = assertThrows(LibraryDownloadException.class, () -> manager.downloadLibrary(library().build()));

        assertEquals("com.example:lib:1.0", e.getLibrary());
        assertEquals(List.of(first + PATH, second + PATH), e.getAttempts().stream().map(LibraryDownloadException.Attempt::url).collect(Collectors.toList()));
        assertTrue(e.getMessage().contains(first + PATH + ": not found (HTTP 404)"), e.getMessage());
        assertFalse(Files.exists(target()), "404 bodies must not be cached");
    }

    @Test
    public void failsWithoutRepositories() {
        LibraryDownloadException e = assertThrows(LibraryDownloadException.class, () -> manager.downloadLibrary(library().build()));

        assertTrue(e.getMessage().contains("add a repository"));
    }

    @Test
    public void refusesInsecureHttpByDefault() {
        TestLibraryManager secure = TestLibraryManager.create(dataDirectory);
        http.put(PATH, TestJars.simpleJar("good"));
        secure.addRepository(http.url());

        LibraryDownloadException e = assertThrows(LibraryDownloadException.class, () -> secure.downloadLibrary(library().build()));

        assertEquals(0, http.totalRequests());
        assertEquals("insecure http is not allowed", e.getAttempts().get(0).reason());
        assertEquals(1, secure.log().count(LogLevel.WARN, "uses insecure http"));
        assertEquals(1, secure.log().count(LogLevel.WARN, "Refusing to download " + http.url() + PATH));
    }

    @Test
    public void refusesUnsupportedSchemes() {
        LibraryDownloadException e = assertThrows(LibraryDownloadException.class,
            () -> manager.downloadLibrary(library().url("ftp://example.com/lib.jar").build()));

        assertEquals("unsupported URL scheme", e.getAttempts().get(0).reason());
    }

    @Test
    public void enforcesMaximumSizeWithContentLength() throws IOException {
        http.put(PATH, new byte[4096]);
        manager.addRepository(http.url());
        manager.setMaxDownloadSize(1024);

        LibraryDownloadException e = assertThrows(LibraryDownloadException.class, () -> manager.downloadLibrary(library().build()));

        assertTrue(e.getAttempts().get(0).reason().contains("maximum download size"), e.getMessage());
        assertFalse(Files.exists(target()));
        assertTrue(partFiles().isEmpty());
    }

    @Test
    public void enforcesMaximumSizeWhileStreaming() throws IOException {
        http.putChunked(PATH, new byte[64 * 1024]);
        manager.addRepository(http.url());
        manager.setMaxDownloadSize(1024);

        LibraryDownloadException e = assertThrows(LibraryDownloadException.class, () -> manager.downloadLibrary(library().build()));

        assertTrue(e.getAttempts().get(0).reason().contains("maximum download size"), e.getMessage());
        assertFalse(Files.exists(target()));
        assertTrue(partFiles().isEmpty());
    }

    @Test
    public void concurrentDownloadsFetchOnce() throws Exception {
        byte[] jar = TestJars.simpleJar("good");
        http.put(PATH, jar);
        http.setDelayMillis(100);
        manager.addRepository(http.url());
        Library library = library().checksum(sha256(jar)).build();

        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Path>> results = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                results.add(executor.submit(() -> {
                    start.await();
                    return manager.downloadLibrary(library);
                }));
            }
            start.countDown();

            Set<Path> paths = new HashSet<>();
            for (Future<Path> result : results) {
                paths.add(result.get());
            }

            assertEquals(Set.of(target()), paths);
            assertArrayEquals(jar, Files.readAllBytes(target()));
            assertEquals(1, http.requests(PATH));
            assertTrue(partFiles().isEmpty());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    public void stalledDownloadTimesOutAndTriesNextRepository() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        try (TestHttpRepository other = new TestHttpRepository()) {
            http.putStalled(PATH, new byte[16]);
            other.put(PATH, jar);
            manager.addRepository(http.url());
            manager.addRepository(other.url());
            manager.setDownloadTimeout(Duration.ofMillis(500));

            Path file = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> manager.downloadLibrary(library().checksum(sha256(jar)).build()));

            assertArrayEquals(jar, Files.readAllBytes(file));
            assertEquals(1, http.requests(PATH));
            assertEquals(1, manager.log().count(LogLevel.DEBUG, "Couldn't download " + http.url() + PATH + ": timed out after 500 ms"));
            assertTrue(partFiles().isEmpty());
        }

        List<Thread> watchdogs = Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().equals("libby-download-watchdog"))
            .collect(Collectors.toList());
        assertEquals(1, watchdogs.size(), watchdogs.toString());
        assertTrue(watchdogs.get(0).isDaemon());
    }

    @Test
    public void stalledChecksumFileTimesOutAndTriesNextRepository() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        try (TestHttpRepository other = new TestHttpRepository()) {
            http.put(PATH, TestJars.simpleJar("unverified"));
            http.putStalled(PATH + ".sha256", new byte[8]);
            other.put(PATH, jar);
            other.put(PATH + ".sha256", TestJars.hex(sha256(jar)).getBytes(StandardCharsets.US_ASCII));
            manager.addRepository(http.url());
            manager.addRepository(other.url());
            manager.setDownloadTimeout(Duration.ofMillis(500));

            Path file = assertTimeoutPreemptively(Duration.ofSeconds(20), () -> manager.downloadLibrary(library().build()));

            assertArrayEquals(jar, Files.readAllBytes(file));
            assertEquals(0, http.requests(PATH), "the jar isn't downloaded without its checksum");
        }
    }

    @Test
    public void downloadTimeoutDefaultsAndValidation() {
        assertEquals(Duration.ofMinutes(5), manager.getDownloadTimeout());
        assertThrows(IllegalArgumentException.class, () -> manager.setDownloadTimeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> manager.setDownloadTimeout(Duration.ofSeconds(-1)));
    }

    @Test
    public void checksumFileErrorFailsTheAttemptInsteadOfSkippingVerification() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        try (TestHttpRepository other = new TestHttpRepository()) {
            http.put(PATH, TestJars.simpleJar("tampered"));
            http.putStatus(PATH + ".sha256", 503, "<html>Unavailable</html>".getBytes(StandardCharsets.UTF_8));
            other.put(PATH, jar);
            other.put(PATH + ".sha1", TestJars.hex(TestJars.digest("SHA-1", jar)).getBytes(StandardCharsets.US_ASCII));
            manager.addRepository(http.url());
            manager.addRepository(other.url());

            Path file = manager.downloadLibrary(library().build());

            assertArrayEquals(jar, Files.readAllBytes(file));
            assertEquals(0, http.requests(PATH));
            assertEquals(0, http.requests(PATH + ".sha512"), "an error isn't treated like a missing checksum file");
            assertEquals(0, manager.log().count(LogLevel.WARN, "No checksum available"));
        }
    }

    @Test
    public void checksumFileErrorIsReportedWhenNoUrlWorks() {
        http.put(PATH, TestJars.simpleJar("good"));
        http.putStatus(PATH + ".sha256", 403, new byte[0]);
        manager.addRepository(http.url());

        LibraryDownloadException e = assertThrows(LibraryDownloadException.class, () -> manager.downloadLibrary(library().build()));

        assertEquals("checksum file " + http.url() + PATH + ".sha256: unexpected HTTP status 403", e.getAttempts().get(0).reason());
        assertFalse(Files.exists(target()));
        assertEquals(0, manager.log().count(LogLevel.WARN, "No checksum available"));
    }

    @Test
    public void legacyCacheIsNotTrustedWhenChecksumFilesFail() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        Files.createDirectories(target().getParent());
        Files.write(target(), jar);
        http.put(PATH, jar);
        http.putStatus(PATH + ".sha256", 500, new byte[0]);
        manager.addRepository(http.url());

        assertThrows(LibraryDownloadException.class, () -> manager.downloadLibrary(library().build()));

        assertFalse(Files.exists(target().resolveSibling("lib-1.0.jar.sha256")), "the cached jar must not be recorded as verified");
        assertEquals(1, manager.log().count(LogLevel.WARN, "because its checksum files couldn't be fetched"));
        assertEquals(0, http.requests(PATH));

        // Once the repository answers that there is no checksum file, the cached jar is accepted with a warning.
        http.putStatus(PATH + ".sha256", 404, new byte[0]);
        assertEquals(target(), manager.downloadLibrary(library().build()));
        assertTrue(Files.exists(target().resolveSibling("lib-1.0.jar.sha256")));
        assertEquals(1, manager.log().count(LogLevel.WARN, "No checksum available"));
        assertEquals(0, http.requests(PATH));
    }

    @Test
    public void legacyCacheIsVerifiedAgainstRepositoryChecksum() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        Files.createDirectories(target().getParent());
        Files.write(target(), "tampered".getBytes(StandardCharsets.UTF_8));
        http.put(PATH, jar);
        http.put(PATH + ".sha256", TestJars.hex(sha256(jar)).getBytes(StandardCharsets.US_ASCII));
        manager.addRepository(http.url());

        manager.downloadLibrary(library().build());

        assertArrayEquals(jar, Files.readAllBytes(target()));
        assertEquals(1, http.requests(PATH));
    }

    @Test
    public void hostlessUrlsAreSkipped() throws IOException {
        byte[] jar = TestJars.simpleJar("good");
        http.put(PATH, jar);
        manager.addRepository(http.url());

        LibraryDownloadException none = assertThrows(LibraryDownloadException.class,
            () -> manager.downloadLibrary(library().build(), List.of("https:foo", "https:///lib.jar", "http://")));
        assertEquals(List.of("invalid URL", "invalid URL", "invalid URL"),
            none.getAttempts().stream().map(LibraryDownloadException.Attempt::reason).collect(Collectors.toList()));

        Path file = manager.downloadLibrary(library().url("https:foo").url("https:///lib.jar").checksum(sha256(jar)).build());

        assertArrayEquals(jar, Files.readAllBytes(file));
        assertEquals(1, http.requests(PATH));
    }

    @Test
    public void downloadsFromFileRepository(@TempDir Path repository) throws IOException {
        byte[] jar = TestJars.simpleJar("file");
        Path artifact = repository.resolve(PATH);
        Files.createDirectories(artifact.getParent());
        Files.write(artifact, jar);
        Files.writeString(artifact.resolveSibling("lib-1.0.jar.sha512"), TestJars.hex(TestJars.digest("SHA-512", jar)));
        manager.addRepository(repository.toUri().toString());

        Path file = manager.downloadLibrary(library().build());

        assertArrayEquals(jar, Files.readAllBytes(file));
        assertEquals(0, manager.log().count(LogLevel.WARN, "No checksum available"));
    }

    @Test
    public void rejectsPathTraversalBeforeAnyDownload(@TempDir Path repository) {
        manager.addRepository(repository.toUri().toString());

        assertThrows(IllegalArgumentException.class, () -> manager.downloadLibrary(
            Library.builder().groupId("{}{}").artifactId("evil").version("1").build()));
        assertThrows(IllegalArgumentException.class, () -> manager.downloadLibrary(
            Library.builder().groupId("com.example").artifactId("..").version("1").build()));
        assertThrows(IllegalArgumentException.class, () -> manager.downloadLibrary(
            Library.builder().groupId("com.example").artifactId("lib").version("../../../evil").build()));
        assertEquals(0, http.totalRequests());
    }
}

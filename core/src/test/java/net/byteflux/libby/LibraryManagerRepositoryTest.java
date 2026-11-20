package net.byteflux.libby;

import net.byteflux.libby.logging.LogLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LibraryManagerRepositoryTest {
    @TempDir
    private Path dataDirectory;

    private List<String> repositories(LibraryManager manager) {
        return new ArrayList<>(manager.getRepositories());
    }

    @Test
    public void normalizesAndDeduplicatesRepositories() {
        TestLibraryManager manager = TestLibraryManager.create(dataDirectory);
        manager.addRepository("https://repo.example.com/maven");
        manager.addRepository("https://repo.example.com/maven/");
        manager.addRepository("HTTPS://repo.example.com/maven");
        manager.addMavenCentral();
        manager.addMavenCentral();

        assertEquals(List.of("https://repo.example.com/maven/", LibraryManager.MAVEN_CENTRAL), repositories(manager));
        assertThrows(UnsupportedOperationException.class, () -> manager.getRepositories().add("x"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "repo.example.com/maven", "ftp://repo.example.com/", "https://", "https://repo.example.com/?q=1", "https://repo example.com/", "mailto:someone@example.com"})
    public void rejectsInvalidRepositories(String url) {
        TestLibraryManager manager = TestLibraryManager.create(dataDirectory);

        assertThrows(IllegalArgumentException.class, () -> manager.addRepository(url));
    }

    @Test
    public void warnsOnceForInsecureRepository() {
        TestLibraryManager manager = TestLibraryManager.create(dataDirectory);
        manager.addRepository("http://repo.example.com/maven/");
        manager.addRepository("http://repo.example.com/maven/");

        assertEquals(1, manager.log().count(LogLevel.WARN, "uses insecure http"));
    }

    @Test
    public void addsWellKnownRepositories() {
        TestLibraryManager manager = TestLibraryManager.create(dataDirectory);
        manager.addMavenLocal();
        manager.addJitPack();
        manager.addSonatypeSnapshots();

        List<String> repositories = repositories(manager);
        assertTrue(repositories.get(0).startsWith("file:") && repositories.get(0).endsWith("/.m2/repository/"), repositories.get(0));
        assertEquals(List.of("https://jitpack.io/", "https://central.sonatype.com/repository/maven-snapshots/"), repositories.subList(1, 3));
    }

    @Test
    @SuppressWarnings("removal")
    public void deprecatedRepositoriesRedirectWithWarning() {
        TestLibraryManager manager = TestLibraryManager.create(dataDirectory);
        manager.addJCenter();
        manager.addSonatype();

        assertEquals(List.of(LibraryManager.MAVEN_CENTRAL, LibraryManager.SONATYPE_SNAPSHOTS), repositories(manager));
        assertEquals(1, manager.log().count(LogLevel.WARN, "JCenter has been shut down"));
        assertEquals(1, manager.log().count(LogLevel.WARN, "OSSRH repository has been shut down"));
    }

    @Test
    public void resolvesDirectUrlsBeforeRepositories() {
        TestLibraryManager manager = TestLibraryManager.create(dataDirectory);
        manager.addRepository("https://repo.example.com/");
        Library library = Library.builder().groupId("a.b").artifactId("c").version("1").url("https://direct.example.com/c.jar").build();

        assertEquals(List.of("https://direct.example.com/c.jar", "https://repo.example.com/a/b/c/1/c-1.jar"), new ArrayList<>(manager.resolveLibrary(library)));
    }

    @Test
    public void usesCustomDirectoryName() {
        assertEquals(dataDirectory.toAbsolutePath().resolve("lib"), TestLibraryManager.create(dataDirectory).saveDirectory());
    }
}

package net.byteflux.libby;

import net.byteflux.libby.logging.LogLevel;
import net.byteflux.libby.logging.Logger;
import net.byteflux.libby.logging.adapters.LogAdapter;
import net.byteflux.libby.relocation.RelocationHelper;
import net.byteflux.libby.transport.ArtifactDownloader;
import net.byteflux.libby.transport.Checksums;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.Objects.requireNonNull;

/**
 * A runtime dependency manager for plugins.
 * <p>
 * The library manager can resolve a dependency jar through the configured
 * Maven repositories, download it into a local cache, relocate it and then
 * load it into the plugin's classpath.
 * <p>
 * Transitive dependencies for a library aren't downloaded automatically and
 * must be explicitly loaded like every other library.
 * <p>
 * It's recommended that libraries are relocated to prevent any namespace
 * conflicts with different versions of the same library bundled with other
 * plugins or maybe even bundled with the server itself.
 * <p>
 * Downloads are only made over {@code https:} or from {@code file:} URLs
 * unless {@link #setAllowInsecureHttp(boolean)} is enabled, and every
 * download is verified against the library's pinned checksum or, if it has
 * none, the repository's checksum files. Library managers are thread-safe.
 *
 * @see Library
 */
public abstract class LibraryManager {
    /**
     * Maven Central repository URL
     */
    public static final String MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2/";

    /**
     * Sonatype Central Portal snapshots repository URL
     */
    public static final String SONATYPE_SNAPSHOTS = "https://central.sonatype.com/repository/maven-snapshots/";

    /**
     * JitPack repository URL
     */
    public static final String JITPACK = "https://jitpack.io/";

    /**
     * Default name of the directory inside the plugin's data directory that
     * libraries are saved to
     */
    public static final String DEFAULT_DIRECTORY_NAME = "lib";

    /**
     * Wrapped plugin logger
     */
    protected final Logger logger;

    /**
     * Directory where downloaded library jars are saved to
     */
    protected final Path saveDirectory;

    /**
     * Maven repositories used to resolve artifacts
     */
    private final CopyOnWriteArrayList<String> repositories = new CopyOnWriteArrayList<>();

    /**
     * Downloads and verifies library jars
     */
    private final ArtifactDownloader downloader;

    /**
     * Per-file locks so that concurrent requests for the same file are only
     * processed once
     */
    private final ConcurrentMap<Path, Object> fileLocks = new ConcurrentHashMap<>();

    /**
     * Guards lazy creation of the relocation helper
     */
    private final Object relocatorLock = new Object();

    /**
     * Lazily-initialized relocation helper that uses reflection to call into
     * Luck's Jar Relocator
     */
    private volatile @Nullable RelocationHelper relocator;

    /**
     * Creates a new library manager that saves libraries to the
     * {@value #DEFAULT_DIRECTORY_NAME} directory inside the plugin's data
     * directory.
     *
     * @param logAdapter    plugin logging adapter
     * @param dataDirectory plugin's data directory
     */
    protected LibraryManager(LogAdapter logAdapter, Path dataDirectory) {
        this(logAdapter, dataDirectory, DEFAULT_DIRECTORY_NAME);
    }

    /**
     * Creates a new library manager.
     *
     * @param logAdapter    plugin logging adapter
     * @param dataDirectory plugin's data directory
     * @param directoryName name of the directory inside the data directory
     *                      that libraries are saved to
     */
    protected LibraryManager(LogAdapter logAdapter, Path dataDirectory, String directoryName) {
        logger = new Logger(requireNonNull(logAdapter, "logAdapter"));
        if (requireNonNull(directoryName, "directoryName").isEmpty()) {
            throw new IllegalArgumentException("directoryName must not be empty");
        }
        saveDirectory = requireNonNull(dataDirectory, "dataDirectory").toAbsolutePath().resolve(directoryName).normalize();
        downloader = new ArtifactDownloader(logger);
    }

    /**
     * Adds a file to the plugin's classpath.
     *
     * @param file the file to add
     */
    protected abstract void addToClasspath(Path file);

    /**
     * Gets the logging level for this library manager.
     *
     * @return log level
     */
    public LogLevel getLogLevel() {
        return logger.getLevel();
    }

    /**
     * Sets the logging level for this library manager.
     * <p>
     * By setting this value, the library manager's logger will not log any
     * messages with a level less severe than the configured level. This can be
     * useful for silencing the download and relocation logging.
     * <p>
     * Setting this value to {@link LogLevel#WARN} would silence informational
     * logging but still print important things like invalid checksum warnings.
     *
     * @param level the log level to set
     */
    public void setLogLevel(LogLevel level) {
        logger.setLevel(level);
    }

    /**
     * Gets whether downloads over plain-text {@code http:} are allowed.
     *
     * @return true if insecure http downloads are allowed
     * @see #setAllowInsecureHttp(boolean)
     */
    public boolean isAllowInsecureHttp() {
        return downloader.isAllowInsecureHttp();
    }

    /**
     * Sets whether downloads over plain-text {@code http:} are allowed. This
     * is disabled by default: {@code http:} URLs are skipped with a warning.
     * Only enable it for trusted networks, and prefer pinning checksums with
     * {@link Library.Builder#checksum(String)} when you do.
     *
     * @param allowInsecureHttp true to allow insecure http downloads
     */
    public void setAllowInsecureHttp(boolean allowInsecureHttp) {
        downloader.setAllowInsecureHttp(allowInsecureHttp);
    }

    /**
     * Gets the timeout for establishing a connection to a repository.
     *
     * @return connect timeout, 10 seconds by default
     */
    public Duration getConnectTimeout() {
        return downloader.getConnectTimeout();
    }

    /**
     * Sets the timeout for establishing a connection to a repository.
     *
     * @param connectTimeout positive connect timeout
     */
    public void setConnectTimeout(Duration connectTimeout) {
        downloader.setConnectTimeout(connectTimeout);
    }

    /**
     * Gets the timeout for receiving the status and headers of a response.
     *
     * @return request timeout, 30 seconds by default
     */
    public Duration getRequestTimeout() {
        return downloader.getRequestTimeout();
    }

    /**
     * Sets the timeout for receiving the status and headers of a response.
     * The time spent streaming the response body isn't limited.
     *
     * @param requestTimeout positive request timeout
     */
    public void setRequestTimeout(Duration requestTimeout) {
        downloader.setRequestTimeout(requestTimeout);
    }

    /**
     * Gets the maximum size of a downloaded file.
     *
     * @return maximum size in bytes, 256 MiB by default
     */
    public long getMaxDownloadSize() {
        return downloader.getMaxDownloadSize();
    }

    /**
     * Sets the maximum size of a downloaded file. Larger downloads are
     * aborted and the next URL is attempted.
     *
     * @param maxDownloadSize positive maximum size in bytes
     */
    public void setMaxDownloadSize(long maxDownloadSize) {
        downloader.setMaxDownloadSize(maxDownloadSize);
    }

    /**
     * Gets the currently added repositories used to resolve artifacts.
     * <p>
     * For each library this list is traversed to download artifacts after the
     * direct download URLs have been attempted.
     *
     * @return current repositories
     */
    public Collection<String> getRepositories() {
        return List.copyOf(repositories);
    }

    /**
     * Adds a repository URL to this library manager.
     * <p>
     * Artifacts will be resolved using this repository when attempts to locate
     * the artifact through previously added repositories are all unsuccessful.
     * <p>
     * The URL must be an absolute {@code https:}, {@code http:} or
     * {@code file:} URL. A trailing slash is added if missing, and adding a
     * repository that was already added has no effect. Adding an
     * {@code http:} repository logs a warning because downloads from it are
     * refused unless {@link #setAllowInsecureHttp(boolean)} is enabled.
     *
     * @param url repository URL to add
     * @throws IllegalArgumentException if the URL is invalid or uses an unsupported scheme
     */
    public void addRepository(String url) {
        String repo = normalizeRepository(requireNonNull(url, "url"));
        if (!repositories.addIfAbsent(repo)) {
            return;
        }

        if (repo.startsWith("http:")) {
            logger.warn("Repository " + repo + " uses insecure http"
                + (isAllowInsecureHttp() ? "; downloads from it aren't protected against tampering" : "; downloads from it are refused unless setAllowInsecureHttp(true) is called"));
        }
    }

    /**
     * Adds the current user's local Maven repository.
     */
    public void addMavenLocal() {
        addRepository(Path.of(System.getProperty("user.home"), ".m2", "repository").toUri().toString());
    }

    /**
     * Adds the Maven Central repository.
     */
    public void addMavenCentral() {
        addRepository(MAVEN_CENTRAL);
    }

    /**
     * Adds the Sonatype OSS repository.
     *
     * @deprecated The Sonatype OSSRH service has been shut down. This method
     * now logs a warning and adds the Central Portal snapshots repository
     * instead, see {@link #addSonatypeSnapshots()}. Use
     * {@link #addMavenCentral()} for releases.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public void addSonatype() {
        logger.warn("addSonatype(): the Sonatype OSSRH repository has been shut down, adding the Central Portal snapshots repository instead");
        addSonatypeSnapshots();
    }

    /**
     * Adds the Sonatype Central Portal snapshots repository. Releases are
     * available from Maven Central.
     */
    public void addSonatypeSnapshots() {
        addRepository(SONATYPE_SNAPSHOTS);
    }

    /**
     * Adds the Bintray JCenter repository.
     *
     * @deprecated JCenter has been shut down. This method now logs a warning
     * and adds Maven Central instead, use {@link #addMavenCentral()}.
     */
    @Deprecated(since = "2.0.0", forRemoval = true)
    public void addJCenter() {
        logger.warn("addJCenter(): JCenter has been shut down, adding Maven Central instead");
        addMavenCentral();
    }

    /**
     * Adds the JitPack repository.
     */
    public void addJitPack() {
        addRepository(JITPACK);
    }

    /**
     * Gets all of the possible download URLs for this library. Entries are
     * ordered by direct download URLs first and then repository download URLs.
     *
     * @param library the library to resolve
     * @return download URLs
     */
    public Collection<String> resolveLibrary(Library library) {
        List<String> urls = new ArrayList<>(requireNonNull(library, "library").getUrls());
        for (String repository : getRepositories()) {
            urls.add(repository + library.getPath());
        }

        return List.copyOf(urls);
    }

    /**
     * Downloads a library jar to the save directory if it doesn't already
     * exist and returns the local file path.
     * <p>
     * Downloads are verified against the library's pinned SHA-256 checksum,
     * or if it has none, against the first checksum file ({@code .sha256},
     * {@code .sha512} or {@code .sha1}) found next to the artifact. If
     * verification fails, a warning is generated and the next download URL is
     * attempted. A warning is logged if no checksum is available at all.
     * <p>
     * Cached jars are re-verified against the pinned checksum, or the
     * checksum recorded when they were downloaded, and downloaded again if
     * they don't match. Concurrent calls for the same library download it
     * only once.
     * <p>
     * Most of the time it is advised to use {@link #loadLibrary(Library)}
     * instead of this method because this one is only concerned with
     * downloading the jar and returning the local path. It's usually more
     * desirable to download the jar and add it to the plugin's classpath in
     * one operation.
     *
     * @param library the library to download
     * @return local file path to library
     * @throws LibraryDownloadException if the library couldn't be downloaded
     * @see #loadLibrary(Library)
     */
    public Path downloadLibrary(Library library) {
        return downloadLibrary(library, resolveLibrary(library));
    }

    /**
     * Downloads a library jar from an explicit list of candidate URLs, which
     * are attempted in order, to the save directory if it doesn't already
     * exist and returns the local file path. The library's direct download
     * URLs and this manager's repositories are not used unless they are part
     * of {@code urls}.
     * <p>
     * Verification and caching work as described in
     * {@link #downloadLibrary(Library)}.
     *
     * @param library the library to download
     * @param urls    candidate download URLs in order of preference
     * @return local file path to library
     * @throws LibraryDownloadException if the library couldn't be downloaded
     * @see #resolveLibrary(Library)
     */
    public Path downloadLibrary(Library library, Collection<String> urls) {
        Path file = resolveSaveFile(requireNonNull(library, "library").getPath());
        List<String> candidates = List.copyOf(requireNonNull(urls, "urls"));
        synchronized (lockFor(file)) {
            return downloader.download(library, candidates, file);
        }
    }

    /**
     * Applies a library's relocation rules to its downloaded jar, unless a
     * relocated jar with a matching recorded checksum already exists, and
     * returns the path to the relocated jar.
     *
     * @param library the library being loaded
     * @param in      the library's downloaded jar
     * @return the relocated file
     * @see RelocationHelper#relocate(Path, Path, Collection)
     */
    private Path relocate(Library library, Path in) {
        String relocatedPath = requireNonNull(library.getRelocatedPath(), "relocatedPath");
        Path file = resolveSaveFile(relocatedPath);

        // Initialize the helper before taking the file lock: it downloads its
        // own dependencies under their file locks.
        RelocationHelper helper = relocator();

        synchronized (lockFor(file)) {
            Path temp = null;
            try {
                if (Files.isRegularFile(file)) {
                    byte[] recorded = Checksums.readRecorded(file);
                    if (recorded != null && MessageDigest.isEqual(recorded, Checksums.digest(file, Checksums.SHA_256))) {
                        return file;
                    }
                    logger.warn("Relocated jar " + file + " doesn't match its recorded checksum, relocating it again");
                    Checksums.deleteWithRecord(file);
                }

                temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".part");
                helper.relocate(in, temp, library.getRelocations());
                Checksums.record(file, Checksums.digest(temp, Checksums.SHA_256));
                ArtifactDownloader.install(temp, file);

                logger.info("Relocations applied to " + saveDirectory.getParent().relativize(in));
                return file;
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to relocate " + library, e);
            } finally {
                if (temp != null) {
                    try {
                        Files.deleteIfExists(temp);
                    } catch (IOException e) {
                        logger.debug("Couldn't delete temporary file " + temp, e);
                    }
                }
            }
        }
    }

    /**
     * Gets the relocation helper, creating it on first use.
     *
     * @return the relocation helper
     */
    private RelocationHelper relocator() {
        RelocationHelper helper = relocator;
        if (helper == null) {
            synchronized (relocatorLock) {
                helper = relocator;
                if (helper == null) {
                    helper = new RelocationHelper(this);
                    relocator = helper;
                }
            }
        }

        return helper;
    }

    /**
     * Resolves a path relative to the save directory and makes sure it doesn't
     * escape it.
     *
     * @param relativePath path relative to the save directory
     * @return the normalized absolute path
     * @throws IllegalArgumentException if the path resolves outside the save directory
     */
    private Path resolveSaveFile(String relativePath) {
        Path file = saveDirectory.resolve(relativePath).normalize();
        if (!file.startsWith(saveDirectory) || file.equals(saveDirectory)) {
            throw new IllegalArgumentException("Library path '" + relativePath + "' escapes the save directory " + saveDirectory);
        }

        return file;
    }

    /**
     * Gets the lock guarding a file in the save directory.
     *
     * @param file the file
     * @return lock object for the file
     */
    private Object lockFor(Path file) {
        return fileLocks.computeIfAbsent(file, key -> new Object());
    }

    /**
     * Validates and normalizes a repository URL.
     *
     * @param url repository URL
     * @return normalized URL ending with a slash
     * @throws IllegalArgumentException if the URL is invalid or uses an unsupported scheme
     */
    private static String normalizeRepository(String url) {
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid repository URL '" + url + "'", e);
        }

        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : null;
        if (!uri.isAbsolute() || uri.isOpaque()
            || !("https".equals(scheme) || "http".equals(scheme) || "file".equals(scheme))) {
            throw new IllegalArgumentException("Repository URL '" + url + "' must be an absolute https:, http: or file: URL");
        }
        if (!"file".equals(scheme) && (uri.getHost() == null || uri.getHost().isEmpty())) {
            throw new IllegalArgumentException("Repository URL '" + url + "' has no host");
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("Repository URL '" + url + "' must not have a query or fragment");
        }

        String normalized = scheme + uri.toString().substring(scheme.length());
        return normalized.endsWith("/") ? normalized : normalized + '/';
    }

    /**
     * Loads a library jar into the plugin's classpath. If the library jar
     * doesn't exist locally, it will be downloaded.
     * <p>
     * If the provided library has any relocations, they will be applied to
     * create a relocated jar and the relocated jar will be loaded instead.
     *
     * @param library the library to load
     * @throws LibraryDownloadException if the library couldn't be downloaded
     * @see #downloadLibrary(Library)
     */
    public void loadLibrary(Library library) {
        Path file = downloadLibrary(requireNonNull(library, "library"));
        if (library.hasRelocations()) {
            file = relocate(library, file);
        }

        addToClasspath(file);
    }
}

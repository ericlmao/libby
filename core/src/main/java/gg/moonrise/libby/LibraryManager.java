package gg.moonrise.libby;

import gg.moonrise.libby.classloader.IsolatedClassLoader;
import gg.moonrise.libby.logging.LogLevel;
import gg.moonrise.libby.logging.Logger;
import gg.moonrise.libby.logging.adapters.LogAdapter;
import gg.moonrise.libby.relocation.RelocationHelper;
import gg.moonrise.libby.resolver.DependencyResolver;
import gg.moonrise.libby.resolver.TransitiveDependencyException;
import gg.moonrise.libby.transport.ArtifactDownloader;
import gg.moonrise.libby.transport.Checksums;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * Transitive dependencies of a library are only downloaded and loaded if the
 * library enables {@link Library.Builder#resolveTransitiveDependencies(boolean)};
 * otherwise they must be explicitly loaded like every other library.
 * <p>
 * Libraries are added to the plugin's own class loader, which on modern Java
 * versions needs one of the strategies described in
 * {@link gg.moonrise.libby.classloader.URLClassLoaderHelper}. Where that
 * isn't possible, use a platform-specific manager such as
 * {@code PaperLibraryManager} or
 * {@link #loadLibrary(Library, IsolatedClassLoader)}.
 * <p>
 * It's recommended that libraries are relocated to prevent any namespace
 * conflicts with different versions of the same library bundled with other
 * plugins or maybe even bundled with the server itself.
 * <p>
 * Downloads are only made over {@code https:} or from {@code file:} URLs
 * unless {@link #setAllowInsecureHttp(boolean)} is enabled. A download is
 * verified against the library's pinned checksum or, if it has none, the
 * first checksum file the repository publishes next to it. If the
 * repository has no checksum file at all, the download is accepted
 * unverified with a warning; if a checksum file can't be fetched because of
 * an error, that URL is skipped. Library managers are thread-safe.
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
     * Libraries loaded into the plugin's classpath by
     * {@code groupId:artifactId[:classifier]} and relocation fingerprint; the
     * first loaded version wins
     */
    private final ConcurrentMap<String, Library> loadedLibraries = new ConcurrentHashMap<>();

    /**
     * Jars already added to the plugin's classpath, guarded by itself
     */
    private final Set<Path> classpathFiles = new HashSet<>();

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
     * Streaming the response body is limited by the
     * {@linkplain #setDownloadTimeout(Duration) download timeout}.
     *
     * @param requestTimeout positive request timeout
     */
    public void setRequestTimeout(Duration requestTimeout) {
        downloader.setRequestTimeout(requestTimeout);
    }

    /**
     * Gets the time limit for downloading a library jar from one URL.
     *
     * @return download timeout, 5 minutes by default
     * @see #setDownloadTimeout(Duration)
     */
    public Duration getDownloadTimeout() {
        return downloader.getDownloadTimeout();
    }

    /**
     * Sets the time limit for downloading a library jar from one URL,
     * including streaming its body, so that a server that stalls or sends
     * data very slowly can't block loading forever. A download that takes
     * longer is aborted and the next URL is attempted. POMs and checksum
     * files are limited to the shorter of this and 30 seconds.
     *
     * @param downloadTimeout positive download timeout
     */
    public void setDownloadTimeout(Duration downloadTimeout) {
        downloader.setDownloadTimeout(downloadTimeout);
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
    @Deprecated(since = "1.0.0", forRemoval = true)
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
    @Deprecated(since = "1.0.0", forRemoval = true)
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
     * Resolves the transitive dependencies of a library from its POM, and its
     * parent POMs and imported BOMs, downloaded from this manager's
     * repositories. This works whether or not the library enables
     * {@link Library.Builder#resolveTransitiveDependencies(boolean)}, which
     * makes it useful for inspecting a dependency tree or for pinning the
     * resolved versions as explicit libraries.
     * <p>
     * The returned libraries are ordered so that dependencies come before the
     * libraries that need them. They have no direct download URLs and no
     * pinned checksum (repository checksum files are used to verify them),
     * carry the library's relocations and don't resolve transitive
     * dependencies themselves. The library's
     * {@linkplain Library#getExcludedTransitiveDependencies() exclusions} are
     * applied. POMs are cached in the save directory like jars.
     * <p>
     * See {@link DependencyResolver} for the supported POM features.
     *
     * @param library the library to resolve the dependencies of
     * @return the transitive dependencies, without {@code library}
     * @throws TransitiveDependencyException if a dependency can't be resolved,
     *                                       for example because it uses a version range
     */
    public List<Library> resolveTransitiveLibraries(Library library) {
        return new DependencyResolver(logger, this::downloadPom).resolve(requireNonNull(library, "library"));
    }

    /**
     * Downloads a POM file to the save directory if it doesn't already exist
     * and returns the local file path.
     *
     * @param groupId    Maven group ID
     * @param artifactId Maven artifact ID
     * @param version    artifact version
     * @return local file path to the POM
     * @throws LibraryDownloadException if the POM couldn't be downloaded
     */
    private Path downloadPom(String groupId, String artifactId, String version) {
        String path = groupId.replace('.', '/') + '/' + artifactId + '/' + version + '/' + artifactId + '-' + version + ".pom";
        Path file = resolveSaveFile(path);
        List<String> urls = new ArrayList<>();
        for (String repository : getRepositories()) {
            urls.add(repository + path);
        }

        synchronized (lockFor(file)) {
            return downloader.downloadFile(groupId + ':' + artifactId + ':' + version + " (pom)", urls, file, DependencyResolver.MAX_POM_SIZE);
        }
    }

    /**
     * Downloads a library jar to the save directory if it doesn't already
     * exist and returns the local file path.
     * <p>
     * Downloads are verified against the library's pinned SHA-256 checksum,
     * or if it has none, against the first checksum file ({@code .sha256},
     * {@code .sha512} or {@code .sha1}) found next to the artifact. If
     * verification fails, a warning is generated and the next download URL is
     * attempted. The next URL is also attempted if a checksum file can't be
     * fetched for another reason than not existing (an HTTP error other than
     * 404 or 410, a timeout or an I/O error). If none of the checksum files
     * exists, the jar is accepted and a warning is logged.
     * <p>
     * Cached jars are re-verified against the pinned checksum, or the
     * checksum recorded when they were downloaded, and downloaded again if
     * they don't match. Jars cached without a recorded checksum are verified
     * against the repository's checksum files and downloaded again if that
     * isn't possible because of an error. Concurrent calls for the same
     * library download it only once.
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
     * relocated jar with a matching recorded checksum that was created from
     * the same downloaded jar already exists, and returns the path to the
     * relocated jar.
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

        // Lock the input first so it can't be downloaded again while it's relocated.
        synchronized (lockFor(in)) {
            synchronized (lockFor(file)) {
                return relocateLocked(library, in, file, helper);
            }
        }
    }

    /**
     * Applies a library's relocation rules while holding the locks of the
     * downloaded jar and the relocated jar.
     *
     * @param library the library being loaded
     * @param in      the library's downloaded jar
     * @param file    the relocated jar
     * @param helper  the relocation helper
     * @return the relocated file
     */
    private Path relocateLocked(Library library, Path in, Path file, RelocationHelper helper) {
        Path temp = null;
        try {
            byte[] source = Checksums.digest(in, Checksums.SHA_256);
            if (Files.isRegularFile(file)) {
                byte[] recorded = Checksums.readRecorded(file);
                byte[] recordedSource = Checksums.readRecordedSource(file);
                if (recorded == null || !MessageDigest.isEqual(recorded, Checksums.digest(file, Checksums.SHA_256))) {
                    logger.warn("Relocated jar " + file + " doesn't match its recorded checksum, relocating it again");
                } else if (recordedSource == null || !MessageDigest.isEqual(recordedSource, source)) {
                    logger.debug("Relocated jar " + file + " wasn't created from the current " + in.getFileName() + ", relocating it again");
                } else {
                    return file;
                }
                Checksums.deleteWithRecord(file);
            }

            Files.createDirectories(file.getParent());
            temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".part");
            helper.relocate(in, temp, library.getRelocations());
            Checksums.record(file, Checksums.digest(temp, Checksums.SHA_256), source);
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
     * <p>
     * If the library enables
     * {@link Library.Builder#resolveTransitiveDependencies(boolean)}, its
     * transitive dependencies are resolved with
     * {@link #resolveTransitiveLibraries(Library)} and loaded first.
     * <p>
     * Each jar is added to the classpath only once. The first version of a
     * library ({@code groupId:artifactId[:classifier]} with the same
     * relocation rules) loaded by this manager wins: loading a different
     * version later logs a warning and is skipped. This also applies to
     * transitive dependencies, which inherit the relocation rules of the
     * library that depends on them, so loading a library explicitly with the
     * same relocation rules before the library that depends on it pins its
     * version. The same library with different relocation rules is a
     * separate library, because code relocated with those rules refers to its
     * classes under different package names.
     *
     * @param library the library to load
     * @throws LibraryDownloadException      if a library couldn't be downloaded
     * @throws TransitiveDependencyException if transitive dependencies can't be resolved
     * @throws IllegalStateException    if the plugin's classpath can't be modified
     * @see #downloadLibrary(Library)
     * @see #loadLibrary(Library, IsolatedClassLoader)
     */
    public void loadLibrary(Library library) {
        for (Library dependency : collectLibraries(requireNonNull(library, "library"))) {
            loadIntoClasspath(dependency);
        }
    }

    /**
     * Loads a library jar into an isolated class loader instead of the
     * plugin's classpath, downloading and relocating it as described in
     * {@link #loadLibrary(Library)}. Jars already on the class loader's
     * classpath aren't added again.
     * <p>
     * This works on every platform, including ones where the plugin's own
     * class loader can't be modified, but classes loaded this way are only
     * visible through {@code classLoader}.
     *
     * @param library     the library to load
     * @param classLoader the class loader to add the library to
     * @throws LibraryDownloadException      if a library couldn't be downloaded
     * @throws TransitiveDependencyException if transitive dependencies can't be resolved
     */
    public void loadLibrary(Library library, IsolatedClassLoader classLoader) {
        requireNonNull(classLoader, "classLoader");
        for (Library dependency : collectLibraries(requireNonNull(library, "library"))) {
            Path file = fetch(dependency);
            synchronized (classLoader) {
                URL url = toURL(file);
                if (!Arrays.asList(classLoader.getURLs()).contains(url)) {
                    classLoader.addURL(url);
                }
            }
        }
    }

    /**
     * Collects the libraries that must be loaded for a requested library, in
     * load order.
     * <p>
     * The default implementation returns just the requested library, or if
     * it enables {@link Library.Builder#resolveTransitiveDependencies(boolean)},
     * its {@linkplain #resolveTransitiveLibraries(Library) transitive
     * dependencies} followed by the library itself.
     *
     * @param root the requested library
     * @return libraries to load, in order
     * @throws TransitiveDependencyException if transitive dependencies can't be resolved
     */
    protected List<Library> collectLibraries(Library root) {
        if (!root.isResolveTransitiveDependencies()) {
            return List.of(root);
        }

        List<Library> libraries = new ArrayList<>(resolveTransitiveLibraries(root));
        libraries.add(root);
        return List.copyOf(libraries);
    }

    /**
     * Loads a single library into the plugin's classpath, skipping duplicates
     * and conflicting versions.
     *
     * @param library the library to load
     */
    private void loadIntoClasspath(Library library) {
        String fingerprint = library.getRelocationFingerprint();
        String key = fingerprint != null ? coordinates(library) + '@' + fingerprint : coordinates(library);
        Library existing = loadedLibraries.putIfAbsent(key, library);
        if (existing != null && !existing.getVersion().equals(library.getVersion())) {
            String name = fingerprint != null ? coordinates(library) + " (relocated " + fingerprint + ')' : coordinates(library);
            logger.warn("Not loading " + library + " because version " + existing.getVersion() + " of " + name + " is already loaded");
            return;
        }

        Path target = resolveSaveFile(library.hasRelocations() ? requireNonNull(library.getRelocatedPath()) : library.getPath());
        synchronized (classpathFiles) {
            if (classpathFiles.contains(target)) {
                logger.debug("Library " + library + " is already loaded");
                return;
            }
        }

        Path file;
        try {
            file = fetch(library);
        } catch (RuntimeException | Error e) {
            if (existing == null) {
                loadedLibraries.remove(key, library);
            }
            throw e;
        }

        synchronized (classpathFiles) {
            if (classpathFiles.add(file)) {
                try {
                    addToClasspath(file);
                } catch (RuntimeException | Error e) {
                    classpathFiles.remove(file);
                    if (existing == null) {
                        loadedLibraries.remove(key, library);
                    }
                    throw e;
                }
            }
        }
    }

    /**
     * Downloads a library and applies its relocations.
     *
     * @param library the library
     * @return the jar to load
     */
    private Path fetch(Library library) {
        Path file = downloadLibrary(library);
        return library.hasRelocations() ? relocate(library, file) : file;
    }

    /**
     * Gets the version-less coordinates of a library.
     *
     * @param library the library
     * @return {@code groupId:artifactId[:classifier]}
     */
    private static String coordinates(Library library) {
        String key = library.getGroupId() + ':' + library.getArtifactId();
        return library.hasClassifier() ? key + ':' + library.getClassifier() : key;
    }

    /**
     * Converts a path to a URL.
     *
     * @param file the path
     * @return the URL
     */
    private static URL toURL(Path file) {
        try {
            return file.toUri().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException(e);
        }
    }
}

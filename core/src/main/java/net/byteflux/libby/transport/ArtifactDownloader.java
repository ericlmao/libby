package net.byteflux.libby.transport;

import net.byteflux.libby.LibbyProperties;
import net.byteflux.libby.Library;
import net.byteflux.libby.LibraryDownloadException;
import net.byteflux.libby.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/**
 * Downloads library jars from {@code https:}, {@code file:} and (when
 * explicitly allowed) {@code http:} URLs into the library manager's save
 * directory, verifies their integrity and installs them atomically.
 * <p>
 * Integrity is verified against the library's pinned SHA-256 checksum if it
 * has one, otherwise against the first checksum file found next to the
 * artifact ({@code .sha256}, {@code .sha512}, then {@code .sha1}). Every
 * installed file gets its SHA-256 checksum recorded in a {@code .sha256}
 * sidecar so cached files can be re-verified later.
 * <p>
 * Small files such as POMs are downloaded with
 * {@link #downloadFile(String, Collection, Path, long)}, which applies the same
 * policy with a smaller size limit.
 * <p>
 * This class does not lock: callers must make sure only one thread downloads
 * to a given target path at a time. It is an internal class and not part of
 * the stable API.
 */
public final class ArtifactDownloader {
    /**
     * Default maximum size of a downloaded file (256 MiB)
     */
    public static final long DEFAULT_MAX_DOWNLOAD_SIZE = 256L * 1024 * 1024;

    /**
     * Default timeout for establishing a connection
     */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Default timeout for receiving a response
     */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Maximum size of a remote checksum file
     */
    private static final int MAX_CHECKSUM_FILE_SIZE = 4096;

    /**
     * Logger used for download messages
     */
    private final Logger logger;

    /**
     * Libraries already warned about having no checksum
     */
    private final Set<String> warnedUnverified = ConcurrentHashMap.newKeySet();

    /**
     * Insecure URLs already warned about
     */
    private final Set<String> warnedInsecure = ConcurrentHashMap.newKeySet();

    /**
     * Guards lazy creation of the HTTP client
     */
    private final Object clientLock = new Object();

    /**
     * Lazily created HTTP client, reset when the connect timeout changes
     */
    private volatile @Nullable HttpClient client;

    /**
     * Whether plain-text http URLs may be used
     */
    private volatile boolean allowInsecureHttp;

    /**
     * Timeout for establishing a connection
     */
    private volatile Duration connectTimeout = DEFAULT_CONNECT_TIMEOUT;

    /**
     * Timeout for receiving a response
     */
    private volatile Duration requestTimeout = DEFAULT_REQUEST_TIMEOUT;

    /**
     * Maximum size of a downloaded file in bytes
     */
    private volatile long maxDownloadSize = DEFAULT_MAX_DOWNLOAD_SIZE;

    /**
     * Creates a new artifact downloader.
     *
     * @param logger logger used for download messages
     */
    public ArtifactDownloader(Logger logger) {
        this.logger = requireNonNull(logger, "logger");
    }

    /**
     * Gets whether plain-text {@code http:} URLs may be used.
     *
     * @return true if insecure http is allowed
     */
    public boolean isAllowInsecureHttp() {
        return allowInsecureHttp;
    }

    /**
     * Sets whether plain-text {@code http:} URLs may be used.
     *
     * @param allowInsecureHttp true to allow insecure http
     */
    public void setAllowInsecureHttp(boolean allowInsecureHttp) {
        this.allowInsecureHttp = allowInsecureHttp;
    }

    /**
     * Gets the timeout for establishing a connection.
     *
     * @return connect timeout
     */
    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Sets the timeout for establishing a connection.
     *
     * @param connectTimeout positive connect timeout
     */
    public void setConnectTimeout(Duration connectTimeout) {
        this.connectTimeout = requirePositive(connectTimeout, "connectTimeout");
        synchronized (clientLock) {
            client = null;
        }
    }

    /**
     * Gets the timeout for receiving a response's status and headers.
     *
     * @return request timeout
     */
    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    /**
     * Sets the timeout for receiving a response's status and headers.
     *
     * @param requestTimeout positive request timeout
     */
    public void setRequestTimeout(Duration requestTimeout) {
        this.requestTimeout = requirePositive(requestTimeout, "requestTimeout");
    }

    /**
     * Gets the maximum size of a downloaded file.
     *
     * @return maximum size in bytes
     */
    public long getMaxDownloadSize() {
        return maxDownloadSize;
    }

    /**
     * Sets the maximum size of a downloaded file. Larger downloads are
     * aborted and the next URL is attempted.
     *
     * @param maxDownloadSize positive maximum size in bytes
     */
    public void setMaxDownloadSize(long maxDownloadSize) {
        if (maxDownloadSize <= 0) {
            throw new IllegalArgumentException("maxDownloadSize must be positive");
        }
        this.maxDownloadSize = maxDownloadSize;
    }

    /**
     * Returns a usable cached copy of the library at {@code target}, or
     * downloads it from the first URL that serves a verified copy.
     *
     * @param library the library to download
     * @param urls    candidate download URLs in order of preference
     * @param target  the file to install the library to
     * @return {@code target}
     * @throws LibraryDownloadException if no URL served a verified copy
     */
    public Path download(Library library, Collection<String> urls, Path target) {
        requireNonNull(library, "library");
        return download(new Request(library.toString(), library.getChecksum(), maxDownloadSize, false), urls, target);
    }

    /**
     * Returns a usable cached copy of a small file, such as a POM, at
     * {@code target}, or downloads it from the first URL that serves a
     * verified copy.
     * <p>
     * The same URL scheme policy, status handling, checksum file verification
     * and checksum recording as for library jars apply, but downloads and
     * missing checksum files are only logged at debug level.
     *
     * @param name    name of the file used in messages, such as {@code g:a:v (pom)}
     * @param urls    candidate download URLs in order of preference
     * @param target  the file to install the file to
     * @param maxSize maximum size of the file in bytes, at most the
     *                {@linkplain #getMaxDownloadSize() maximum download size}
     * @return {@code target}
     * @throws LibraryDownloadException if no URL served a verified copy
     */
    public Path downloadFile(String name, Collection<String> urls, Path target, long maxSize) {
        requireNonNull(name, "name");
        if (maxSize <= 0) {
            throw new IllegalArgumentException("maxSize must be positive");
        }
        return download(new Request(name, null, Math.min(maxSize, maxDownloadSize), true), urls, target);
    }

    /**
     * Returns a usable cached copy of a file or downloads it.
     *
     * @param request what to download
     * @param urls    candidate download URLs in order of preference
     * @param target  the file to install to
     * @return {@code target}
     * @throws LibraryDownloadException if no URL served a verified copy
     */
    private Path download(Request request, Collection<String> urls, Path target) {
        requireNonNull(urls, "urls");
        requireNonNull(target, "target");

        try {
            if (isCacheUsable(request, urls, target)) {
                return target;
            }

            if (urls.isEmpty()) {
                throw new LibraryDownloadException(request.name, List.of(), null);
            }

            Files.createDirectories(target.getParent());
        } catch (IOException e) {
            throw new LibraryDownloadException(request.name, List.of(new LibraryDownloadException.Attempt(target.toString(), "cache error: " + e)), e);
        }

        List<LibraryDownloadException.Attempt> attempts = new ArrayList<>();
        for (String url : urls) {
            try {
                downloadFrom(request, url, target);
                if (request.quiet) {
                    logger.debug("Downloaded " + url);
                } else {
                    logger.info("Downloaded library " + url);
                }
                return target;
            } catch (AttemptFailedException e) {
                logger.debug("Couldn't download " + url + ": " + e.getMessage());
                attempts.add(new LibraryDownloadException.Attempt(url, e.getMessage()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                attempts.add(new LibraryDownloadException.Attempt(url, "interrupted"));
                throw new LibraryDownloadException(request.name, attempts, e);
            }
        }

        throw new LibraryDownloadException(request.name, attempts, null);
    }

    /**
     * Checks whether a cached copy of a file can be used, deleting it if it
     * fails a known checksum.
     *
     * @param request what is being downloaded
     * @param urls    candidate download URLs, used to look up checksums for
     *                files cached without a recorded checksum
     * @param target  the cached file
     * @return true if the cached file is usable
     * @throws IOException if the cached file can't be read or deleted
     */
    private boolean isCacheUsable(Request request, Collection<String> urls, Path target) throws IOException {
        if (!Files.isRegularFile(target)) {
            return false;
        }

        if (request.quiet && Files.size(target) > request.maxSize) {
            logger.warn("Cached file " + target + " is larger than " + request.maxSize + " bytes, downloading it again");
            Checksums.deleteWithRecord(target);
            return false;
        }

        byte[] sha256 = Checksums.digest(target, Checksums.SHA_256);
        byte[] pinned = request.pinned;
        if (pinned != null) {
            if (MessageDigest.isEqual(pinned, sha256)) {
                return true;
            }

            logger.warn("Cached jar " + target + " doesn't match the pinned checksum of " + request.name + ", downloading it again");
            Checksums.deleteWithRecord(target);
            return false;
        }

        byte[] recorded = Checksums.readRecorded(target);
        if (recorded != null) {
            if (MessageDigest.isEqual(recorded, sha256)) {
                return true;
            }

            logger.warn("Cached " + (request.quiet ? "file " : "jar ") + target + " doesn't match its recorded checksum, downloading it again");
            Checksums.deleteWithRecord(target);
            return false;
        }

        // Cached without a recorded checksum (e.g. by an older libby version):
        // verify it against the repository's checksum files if possible.
        for (String url : urls) {
            URI uri = toUri(url);
            if (uri == null || !isAllowedScheme(uri)) {
                continue;
            }

            ExpectedChecksum expected;
            try {
                expected = fetchRemoteChecksum(uri);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }

            if (expected != null) {
                byte[] actual = expected.algorithm == ChecksumAlgorithm.SHA_256 ? sha256 : Checksums.digest(target, expected.algorithm.digestName);
                if (MessageDigest.isEqual(expected.checksum, actual)) {
                    Checksums.record(target, sha256);
                    return true;
                }

                logger.warn("Cached " + (request.quiet ? "file " : "jar ") + target + " doesn't match " + expected.source + ", downloading it again");
                Checksums.deleteWithRecord(target);
                return false;
            }
        }

        warnUnverified(request);
        Checksums.record(target, sha256);
        return true;
    }

    /**
     * Downloads, verifies and installs a file from a single URL.
     *
     * @param request what is being downloaded
     * @param url     the URL to download from
     * @param target  the file to install to
     * @throws AttemptFailedException if this URL didn't produce a verified copy
     * @throws InterruptedException   if the thread was interrupted
     */
    private void downloadFrom(Request request, String url, Path target) throws AttemptFailedException, InterruptedException {
        URI uri = toUri(url);
        if (uri == null) {
            throw new AttemptFailedException("invalid URL");
        }
        if (!isAllowedScheme(uri)) {
            if ("http".equals(scheme(uri))) {
                if (warnedInsecure.add(url)) {
                    logger.warn("Refusing to download " + url + " over insecure http, use https or call setAllowInsecureHttp(true)");
                }
                throw new AttemptFailedException("insecure http is not allowed");
            }
            throw new AttemptFailedException("unsupported URL scheme");
        }

        byte[] pinned = request.pinned;
        ExpectedChecksum expected = pinned != null
            ? new ExpectedChecksum(ChecksumAlgorithm.SHA_256, pinned, "pinned checksum")
            : fetchRemoteChecksum(uri);

        Path temp = null;
        try {
            temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".part");

            MessageDigest sha256 = Checksums.newDigest(Checksums.SHA_256);
            MessageDigest verify = expected == null || expected.algorithm == ChecksumAlgorithm.SHA_256
                ? null
                : Checksums.newDigest(expected.algorithm.digestName);

            try (InputStream in = open(uri, request.maxSize);
                 OutputStream out = Files.newOutputStream(temp)) {
                copy(in, out, request.maxSize, sha256, verify);
            }

            byte[] sha256Bytes = sha256.digest();
            if (expected != null) {
                byte[] actual = verify != null ? verify.digest() : sha256Bytes;
                if (!MessageDigest.isEqual(expected.checksum, actual)) {
                    logger.warn("*** INVALID CHECKSUM ***");
                    logger.warn(" Library :  " + request.name);
                    logger.warn(" URL :  " + url);
                    logger.warn(" Expected :  " + Base64.getEncoder().encodeToString(expected.checksum) + " (" + expected.algorithm.digestName + ", " + expected.source + ')');
                    logger.warn(" Actual :  " + Base64.getEncoder().encodeToString(actual));
                    throw new AttemptFailedException("checksum mismatch (" + expected.source + ')');
                }
            } else {
                warnUnverified(request);
            }

            Checksums.record(target, sha256Bytes);
            install(temp, target);
        } catch (HttpTimeoutException e) {
            throw new AttemptFailedException("timed out");
        } catch (IOException e) {
            throw new AttemptFailedException(e.toString());
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
     * Looks up a checksum file next to the artifact, trying SHA-256, SHA-512
     * and then SHA-1.
     *
     * @param artifact the artifact URI
     * @return the expected checksum or null if no checksum file is available
     * @throws InterruptedException if the thread was interrupted
     */
    private @Nullable ExpectedChecksum fetchRemoteChecksum(URI artifact) throws InterruptedException {
        for (ChecksumAlgorithm algorithm : ChecksumAlgorithm.values()) {
            URI uri = URI.create(artifact + algorithm.extension);
            byte[] body;
            try (InputStream in = open(uri, MAX_CHECKSUM_FILE_SIZE)) {
                body = in.readNBytes(MAX_CHECKSUM_FILE_SIZE + 1);
            } catch (AttemptFailedException | IOException e) {
                logger.debug("No checksum at " + uri + ": " + e.getMessage());
                continue;
            }
            if (body.length > MAX_CHECKSUM_FILE_SIZE) {
                logger.debug("Ignoring oversized checksum file " + uri);
                continue;
            }

            byte[] checksum = Checksums.parseHex(new String(body, StandardCharsets.US_ASCII), algorithm.length);
            if (checksum == null) {
                logger.debug("Ignoring malformed checksum file " + uri);
                continue;
            }

            return new ExpectedChecksum(algorithm, checksum, uri.toString());
        }

        return null;
    }

    /**
     * Opens a stream to the resource at a {@code file:} or http(s) URI.
     *
     * @param uri     the resource URI, using an allowed scheme
     * @param maxSize maximum acceptable size in bytes if known up front
     * @return an open stream, which the caller must close
     * @throws AttemptFailedException if the resource doesn't exist or can't be used
     * @throws IOException            if an I/O error occurs
     * @throws InterruptedException   if the thread was interrupted
     */
    private InputStream open(URI uri, long maxSize) throws AttemptFailedException, IOException, InterruptedException {
        if ("file".equals(scheme(uri))) {
            Path path;
            try {
                path = Path.of(uri);
            } catch (IllegalArgumentException | FileSystemNotFoundException e) {
                throw new AttemptFailedException("invalid file URL");
            }
            if (!Files.isRegularFile(path)) {
                throw new AttemptFailedException("not found");
            }
            if (Files.size(path) > maxSize) {
                throw new AttemptFailedException("larger than the maximum download size of " + maxSize + " bytes");
            }
            return Files.newInputStream(path);
        }

        HttpRequest request = HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .header("User-Agent", LibbyProperties.HTTP_USER_AGENT)
            .GET()
            .build();

        HttpResponse<InputStream> response = httpClient().send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status != 200) {
            response.body().close();
            if (status == 404 || status == 410) {
                throw new AttemptFailedException("not found (HTTP " + status + ')');
            }
            throw new AttemptFailedException("unexpected HTTP status " + status);
        }

        long length = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        if (length > maxSize) {
            response.body().close();
            throw new AttemptFailedException("larger than the maximum download size of " + maxSize + " bytes");
        }

        return response.body();
    }

    /**
     * Copies a stream while updating digests, enforcing a size limit.
     *
     * @param in      source stream
     * @param out     destination stream
     * @param maxSize maximum number of bytes to copy
     * @param digest  digest to update
     * @param extra   additional digest to update, or null
     * @throws AttemptFailedException if the stream exceeds the size limit
     * @throws IOException            if an I/O error occurs
     */
    private static void copy(InputStream in, OutputStream out, long maxSize, MessageDigest digest, @Nullable MessageDigest extra)
        throws AttemptFailedException, IOException {

        byte[] buffer = new byte[8192];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > maxSize) {
                throw new AttemptFailedException("larger than the maximum download size of " + maxSize + " bytes");
            }

            digest.update(buffer, 0, read);
            if (extra != null) {
                extra.update(buffer, 0, read);
            }
            out.write(buffer, 0, read);
        }
    }

    /**
     * Moves a verified temporary file to its target, atomically if the file
     * system supports it, replacing any existing target.
     *
     * @param temp   the temporary file
     * @param target the target file
     * @throws IOException if the file can't be moved
     */
    public static void install(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Gets the shared HTTP client, creating it if needed.
     *
     * @return the HTTP client
     */
    private HttpClient httpClient() {
        HttpClient current = client;
        if (current == null) {
            synchronized (clientLock) {
                current = client;
                if (current == null) {
                    HttpClient.Builder builder = HttpClient.newBuilder()
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .connectTimeout(connectTimeout);
                    ProxySelector proxy = ProxySelector.getDefault();
                    if (proxy != null) {
                        builder.proxy(proxy);
                    }
                    current = builder.build();
                    client = current;
                }
            }
        }

        return current;
    }

    /**
     * Logs a warning, once per library, that a download couldn't be verified.
     * Quiet requests only log at debug level.
     *
     * @param request the unverified download
     */
    private void warnUnverified(Request request) {
        if (request.quiet) {
            logger.debug("No checksum available for " + request.name + ", integrity not verified");
        } else if (warnedUnverified.add(request.name)) {
            logger.warn("No checksum available for " + request.name + ", integrity not verified; pin one with Library.Builder#checksum");
        }
    }

    /**
     * Checks whether a URI uses a scheme downloads are allowed from.
     *
     * @param uri the URI
     * @return true if the scheme is allowed
     */
    private boolean isAllowedScheme(URI uri) {
        String scheme = scheme(uri);
        return "https".equals(scheme) || "file".equals(scheme) || ("http".equals(scheme) && allowInsecureHttp);
    }

    /**
     * Gets the lowercase scheme of a URI.
     *
     * @param uri the URI
     * @return lowercase scheme, or an empty string if it has none
     */
    private static String scheme(URI uri) {
        String scheme = uri.getScheme();
        return scheme != null ? scheme.toLowerCase(Locale.ROOT) : "";
    }

    /**
     * Parses an absolute URL.
     *
     * @param url the URL
     * @return the URI, or null if it isn't a valid absolute URL
     */
    private static @Nullable URI toUri(String url) {
        try {
            URI uri = new URI(url);
            return uri.isAbsolute() ? uri : null;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /**
     * Validates a timeout.
     *
     * @param duration the timeout
     * @param name     parameter name used in error messages
     * @return the timeout
     */
    private static Duration requirePositive(Duration duration, String name) {
        if (requireNonNull(duration, name).isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }

        return duration;
    }

    /**
     * Checksum algorithms supported for checksum files, in lookup order.
     */
    private enum ChecksumAlgorithm {
        /**
         * SHA-256
         */
        SHA_256(Checksums.SHA_256, ".sha256", 32),

        /**
         * SHA-512
         */
        SHA_512("SHA-512", ".sha512", 64),

        /**
         * SHA-1, still the only checksum published for many artifacts
         */
        SHA_1("SHA-1", ".sha1", 20);

        /**
         * Message digest algorithm name
         */
        private final String digestName;

        /**
         * Checksum file extension
         */
        private final String extension;

        /**
         * Digest length in bytes
         */
        private final int length;

        ChecksumAlgorithm(String digestName, String extension, int length) {
            this.digestName = digestName;
            this.extension = extension;
            this.length = length;
        }
    }

    /**
     * A file to download.
     *
     * @param name    name of the file used in messages
     * @param pinned  pinned SHA-256 checksum, or null to use checksum files
     * @param maxSize maximum size in bytes
     * @param quiet   whether to log routine messages at debug level only
     */
    private record Request(String name, byte @Nullable [] pinned, long maxSize, boolean quiet) {
    }

    /**
     * A checksum a download must match.
     *
     * @param algorithm the checksum algorithm
     * @param checksum  the expected digest
     * @param source    where the checksum came from, for messages
     */
    private record ExpectedChecksum(ChecksumAlgorithm algorithm, byte[] checksum, String source) {
    }

    /**
     * Signals that a single download attempt failed and the next URL should
     * be tried.
     */
    private static final class AttemptFailedException extends Exception {
        /**
         * Creates a new attempt failure.
         *
         * @param reason why the attempt failed
         */
        private AttemptFailedException(String reason) {
            super(reason, null, false, false);
        }
    }
}

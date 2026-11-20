package net.byteflux.libby;

import org.jspecify.annotations.Nullable;

import java.io.Serializable;
import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Thrown when a library couldn't be downloaded from any of its candidate
 * URLs. The exception lists every attempted URL together with the reason it
 * was rejected.
 */
public class LibraryDownloadException extends RuntimeException {
    /**
     * Name of the library that failed to download
     */
    private final String library;

    /**
     * Every attempted URL and why it failed, in attempt order
     */
    private final List<Attempt> attempts;

    /**
     * Creates a new library download exception.
     *
     * @param library  the library that failed to download
     * @param attempts every attempted URL and why it failed
     * @param cause    the cause, or null
     */
    public LibraryDownloadException(Library library, List<Attempt> attempts, @Nullable Throwable cause) {
        super(buildMessage(requireNonNull(library, "library"), requireNonNull(attempts, "attempts")), cause);
        this.library = library.toString();
        this.attempts = List.copyOf(attempts);
    }

    /**
     * Creates a new library download exception.
     *
     * @param library  the library that failed to download
     * @param attempts every attempted URL and why it failed
     */
    public LibraryDownloadException(Library library, List<Attempt> attempts) {
        this(library, attempts, null);
    }

    /**
     * Gets the name ({@code groupId:artifactId:version[:classifier]}) of the
     * library that failed to download.
     *
     * @return library name
     */
    public String getLibrary() {
        return library;
    }

    /**
     * Gets every attempted URL together with the reason it failed, in the
     * order they were attempted.
     *
     * @return failed attempts, empty if no URL could be resolved
     */
    public List<Attempt> getAttempts() {
        return attempts;
    }

    /**
     * Builds the exception message.
     *
     * @param library  the library that failed to download
     * @param attempts the failed attempts
     * @return exception message
     */
    private static String buildMessage(Library library, List<Attempt> attempts) {
        if (attempts.isEmpty()) {
            return "Library '" + library + "' couldn't be resolved, add a repository";
        }

        StringBuilder message = new StringBuilder("Failed to download library '").append(library).append("', tried:");
        for (Attempt attempt : attempts) {
            message.append(System.lineSeparator()).append("  - ").append(attempt.url()).append(": ").append(attempt.reason());
        }

        return message.toString();
    }

    /**
     * A single failed download attempt.
     *
     * @param url    the attempted URL
     * @param reason why the attempt failed
     */
    public record Attempt(String url, String reason) implements Serializable {
        /**
         * Creates a new failed attempt.
         *
         * @param url    the attempted URL
         * @param reason why the attempt failed
         */
        public Attempt {
            requireNonNull(url, "url");
            requireNonNull(reason, "reason");
        }
    }
}

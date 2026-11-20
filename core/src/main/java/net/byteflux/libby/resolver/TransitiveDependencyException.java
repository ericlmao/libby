package net.byteflux.libby.resolver;

import org.jspecify.annotations.Nullable;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Thrown when a library's transitive dependencies can't be resolved, for
 * example because a POM uses a version range, a property that can't be
 * resolved or a {@code SNAPSHOT} version, or because a required POM couldn't
 * be downloaded.
 * <p>
 * The message names the dependency path from the requested library to the
 * problematic dependency and suggests excluding it with
 * {@link net.byteflux.libby.Library.Builder#excludeTransitiveDependency(String, String)}
 * and declaring the version you need explicitly.
 */
public class TransitiveDependencyException extends RuntimeException {
    /**
     * Dependency path from the requested library to the problematic dependency
     */
    private final List<String> dependencyPath;

    /**
     * Creates a new transitive dependency exception.
     *
     * @param reason         what went wrong
     * @param dependencyPath dependency path from the requested library to the
     *                       problematic dependency, as {@code groupId:artifactId[:version]}
     *                       entries; must not be empty
     * @param groupId        group ID of the problematic dependency, or null if unknown
     * @param artifactId     artifact ID of the problematic dependency, or null if unknown
     * @param cause          the cause, or null
     */
    public TransitiveDependencyException(String reason,
                                         List<String> dependencyPath,
                                         @Nullable String groupId,
                                         @Nullable String artifactId,
                                         @Nullable Throwable cause) {
        super(buildMessage(requireNonNull(reason, "reason"), requireNonNull(dependencyPath, "dependencyPath"), groupId, artifactId), cause);
        if (dependencyPath.isEmpty()) {
            throw new IllegalArgumentException("dependencyPath must not be empty");
        }
        this.dependencyPath = List.copyOf(dependencyPath);
    }

    /**
     * Gets the dependency path from the requested library (first) to the
     * dependency that couldn't be resolved (last).
     *
     * @return dependency path as {@code groupId:artifactId[:version]} entries
     */
    public List<String> getDependencyPath() {
        return dependencyPath;
    }

    /**
     * Builds the exception message.
     *
     * @param reason         what went wrong
     * @param dependencyPath the dependency path
     * @param groupId        group ID of the problematic dependency, or null
     * @param artifactId     artifact ID of the problematic dependency, or null
     * @return exception message
     */
    private static String buildMessage(String reason, List<String> dependencyPath, @Nullable String groupId, @Nullable String artifactId) {
        String newline = System.lineSeparator();
        StringBuilder message = new StringBuilder("Failed to resolve transitive dependencies of ")
            .append(dependencyPath.isEmpty() ? "?" : dependencyPath.get(0)).append(": ").append(reason)
            .append(newline).append("  Dependency path: ").append(String.join(" -> ", dependencyPath));

        if (dependencyPath.size() > 1 && groupId != null && artifactId != null) {
            message.append(newline).append("  Exclude it with Library.Builder#excludeTransitiveDependency(\"")
                   .append(groupId).append("\", \"").append(artifactId)
                   .append("\") and, if it's needed, declare the version you want as a separate library");
        } else {
            message.append(newline).append("  Declare the library's dependencies explicitly instead of using Library.Builder#resolveTransitiveDependencies(true)");
        }

        return message.toString();
    }
}

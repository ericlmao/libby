package gg.moonrise.libby;

import gg.moonrise.libby.relocation.Relocation;
import gg.moonrise.libby.relocation.RelocationHelper;
import gg.moonrise.libby.transport.Checksums;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * An immutable representation of a Maven artifact that can be downloaded,
 * relocated and then loaded into a plugin's classpath at runtime.
 *
 * <p>
 * Coordinates are validated when the library is built: the group ID, artifact
 * ID, version and classifier may only contain {@code [A-Za-z0-9_.+-]}, must
 * not be empty, must not start or end with {@code .} and must not contain
 * {@code ..}. This guarantees that the derived {@link #getPath() path} always
 * stays inside the library manager's save directory.
 *
 * @see #builder()
 */
public class Library {
    /**
     * Characters allowed in Maven coordinates
     */
    private static final Pattern COORDINATE = Pattern.compile("[A-Za-z0-9_.+\\-]+");

    /**
     * Length in bytes of a SHA-256 checksum
     */
    private static final int SHA_256_LENGTH = 32;

    /**
     * Directory inside the save directory that relocated jars are saved to.
     * A group ID can't start with {@code .}, so no artifact path can start
     * with this directory and collide with a relocated jar.
     */
    private static final String RELOCATED_DIRECTORY = ".relocated";

    /**
     * Direct download URLs for this library
     */
    private final Collection<String> urls;

    /**
     * Maven group ID
     */
    private final String groupId;

    /**
     * Maven artifact ID
     */
    private final String artifactId;

    /**
     * Artifact version
     */
    private final String version;

    /**
     * Artifact classifier
     */
    private final @Nullable String classifier;

    /**
     * Binary SHA-256 checksum for this library's jar file
     */
    private final byte @Nullable [] checksum;

    /**
     * Jar relocations to apply
     */
    private final Collection<Relocation> relocations;

    /**
     * Whether transitive dependencies are resolved from POMs and loaded too
     */
    private final boolean resolveTransitiveDependencies;

    /**
     * Transitive dependencies that are never resolved
     */
    private final List<ExcludedDependency> excludedTransitiveDependencies;

    /**
     * Relative Maven path to this library's artifact
     */
    private final String path;

    /**
     * Fingerprint of the relocation rules, or null without relocations
     */
    private final @Nullable String relocationFingerprint;

    /**
     * Relative path to this library's relocated jar
     */
    private final @Nullable String relocatedPath;

    /**
     * Creates a new library.
     *
     * @param urls        direct download URLs
     * @param groupId     Maven group ID
     * @param artifactId  Maven artifact ID
     * @param version     artifact version
     * @param classifier  artifact classifier or null
     * @param checksum    binary SHA-256 checksum or null
     * @param relocations jar relocations
     * @param resolveTransitiveDependencies whether to resolve transitive dependencies
     * @param excludedTransitiveDependencies transitive dependencies to exclude
     * @throws IllegalArgumentException if a coordinate or the checksum is invalid
     */
    private Library(Collection<String> urls,
                    @Nullable String groupId,
                    @Nullable String artifactId,
                    @Nullable String version,
                    @Nullable String classifier,
                    byte @Nullable [] checksum,
                    Collection<Relocation> relocations,
                    boolean resolveTransitiveDependencies,
                    Collection<ExcludedDependency> excludedTransitiveDependencies) {

        this.urls = List.copyOf(urls);
        // "{}" is accepted in place of "." so that build-time shading/relocation tools
        // don't rewrite group ID string literals in the plugin's own code.
        this.groupId = requireCoordinate("groupId", requireNonNull(groupId, "groupId").replace("{}", "."));
        this.artifactId = requireCoordinate("artifactId", requireNonNull(artifactId, "artifactId"));
        this.version = requireCoordinate("version", requireNonNull(version, "version"));
        this.classifier = classifier != null ? requireCoordinate("classifier", classifier) : null;
        if (checksum != null && checksum.length != SHA_256_LENGTH) {
            throw new IllegalArgumentException("checksum must be a " + SHA_256_LENGTH + "-byte SHA-256 digest, got " + checksum.length + " bytes");
        }
        this.checksum = checksum != null ? checksum.clone() : null;
        this.relocations = List.copyOf(relocations);
        this.resolveTransitiveDependencies = resolveTransitiveDependencies;
        this.excludedTransitiveDependencies = List.copyOf(excludedTransitiveDependencies);

        String path = this.groupId.replace('.', '/') + '/' + artifactId + '/' + version + '/' + artifactId + '-' + version;
        if (hasClassifier()) {
            path += '-' + classifier;
        }

        this.path = path + ".jar";
        relocationFingerprint = hasRelocations() ? computeRelocationFingerprint(this.relocations) : null;
        relocatedPath = relocationFingerprint != null
            ? RELOCATED_DIRECTORY + '/' + path + "-relocated-" + relocationFingerprint + ".jar"
            : null;
    }

    /**
     * Gets the direct download URLs for this library.
     *
     * @return direct download URLs
     */
    public Collection<String> getUrls() {
        return urls;
    }

    /**
     * Gets the Maven group ID for this library.
     *
     * @return Maven group ID
     */
    public String getGroupId() {
        return groupId;
    }

    /**
     * Gets the Maven artifact ID for this library.
     *
     * @return Maven artifact ID
     */
    public String getArtifactId() {
        return artifactId;
    }

    /**
     * Gets the artifact version for this library.
     *
     * @return artifact version
     */
    public String getVersion() {
        return version;
    }

    /**
     * Gets the artifact classifier for this library.
     *
     * @return artifact classifier or null
     */
    public @Nullable String getClassifier() {
        return classifier;
    }

    /**
     * Gets whether this library has an artifact classifier.
     *
     * @return true if library has classifier, false otherwise
     */
    public boolean hasClassifier() {
        return classifier != null;
    }

    /**
     * Gets the binary SHA-256 checksum of this library's jar file.
     *
     * @return a copy of the checksum or null
     */
    public byte @Nullable [] getChecksum() {
        return checksum != null ? checksum.clone() : null;
    }

    /**
     * Gets whether this library has a checksum.
     *
     * @return true if library has checksum, false otherwise
     */
    public boolean hasChecksum() {
        return checksum != null;
    }

    /**
     * Gets the jar relocations to apply to this library.
     *
     * @return jar relocations to apply
     */
    public Collection<Relocation> getRelocations() {
        return relocations;
    }

    /**
     * Gets whether this library has any jar relocations.
     *
     * @return true if library has relocations, false otherwise
     */
    public boolean hasRelocations() {
        return !relocations.isEmpty();
    }

    /**
     * Gets whether this library's transitive dependencies are resolved from
     * its POM and loaded together with it.
     *
     * @return true if transitive dependencies are resolved, false by default
     * @see Builder#resolveTransitiveDependencies(boolean)
     */
    public boolean isResolveTransitiveDependencies() {
        return resolveTransitiveDependencies;
    }

    /**
     * Gets the transitive dependencies that are never resolved for this
     * library, in the order they were added.
     *
     * @return excluded transitive dependencies
     * @see Builder#excludeTransitiveDependency(String, String)
     */
    public List<ExcludedDependency> getExcludedTransitiveDependencies() {
        return excludedTransitiveDependencies;
    }

    /**
     * Gets the relative Maven path to this library's artifact.
     *
     * @return Maven path for this library
     */
    public String getPath() {
        return path;
    }

    /**
     * Gets the relative path to this library's relocated jar.
     * <p>
     * Relocated jars are kept apart from downloaded artifacts in a
     * {@code .relocated} directory, as
     * {@code .relocated/<Maven path without .jar>-relocated-<hash>.jar}, where
     * the hash covers the relocation rules and the relocation tooling
     * version, so changing either produces a new relocated jar.
     *
     * @return path to relocated artifact or null if has no relocations
     */
    public @Nullable String getRelocatedPath() {
        return relocatedPath;
    }

    /**
     * Gets the fingerprint of this library's relocation rules and the
     * relocation tooling, which is also part of the relocated jar's name.
     *
     * @return 8 hexadecimal digits, or null if this library has no relocations
     */
    @Nullable String getRelocationFingerprint() {
        return relocationFingerprint;
    }

    /**
     * Gets a concise, human-readable string representation of this library.
     *
     * @return string representation
     */
    @Override
    public String toString() {
        String name = groupId + ':' + artifactId + ':' + version;
        if (hasClassifier()) {
            name += ':' + classifier;
        }

        return name;
    }

    /**
     * Compares this library with another object. Two libraries are equal when
     * their coordinates, checksum, direct download URLs, relocations and
     * transitive dependency settings are equal.
     *
     * @param o the object to compare with
     * @return true if the object is an equal library
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Library)) {
            return false;
        }

        Library other = (Library) o;
        return groupId.equals(other.groupId)
            && artifactId.equals(other.artifactId)
            && version.equals(other.version)
            && Objects.equals(classifier, other.classifier)
            && Arrays.equals(checksum, other.checksum)
            && urls.equals(other.urls)
            && relocations.equals(other.relocations)
            && resolveTransitiveDependencies == other.resolveTransitiveDependencies
            && excludedTransitiveDependencies.equals(other.excludedTransitiveDependencies);
    }

    /**
     * Gets a hash code consistent with {@link #equals(Object)}.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return 31 * Objects.hash(groupId, artifactId, version, classifier, urls, relocations,
            resolveTransitiveDependencies, excludedTransitiveDependencies) + Arrays.hashCode(checksum);
    }

    /**
     * Computes a short fingerprint of relocation rules and the relocation
     * tooling. Rule order is kept because the first matching rule wins, while
     * includes and excludes are sorted because their order doesn't matter.
     *
     * @param relocations the relocation rules
     * @return first 8 hexadecimal digits of the SHA-256 fingerprint
     */
    private static String computeRelocationFingerprint(Collection<Relocation> relocations) {
        StringBuilder canonical = new StringBuilder(RelocationHelper.TOOLCHAIN);
        for (Relocation relocation : relocations) {
            canonical.append('\n').append(relocation.getPattern())
                     .append('\0').append(relocation.getRelocatedPattern())
                     .append('\0').append(String.join("\1", relocation.getIncludes().stream().sorted().toArray(String[]::new)))
                     .append('\0').append(String.join("\1", relocation.getExcludes().stream().sorted().toArray(String[]::new)));
        }

        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }

        return Checksums.toHex(Arrays.copyOf(digest, 4));
    }

    /**
     * Validates a Maven coordinate.
     *
     * @param name  coordinate name used in error messages
     * @param value coordinate value
     * @return the value
     * @throws IllegalArgumentException if the value is not a safe coordinate
     */
    private static String requireCoordinate(String name, String value) {
        if (value.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be empty");
        }
        if (!COORDINATE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " '" + value + "' may only contain letters, digits and _ . + -");
        }
        if (value.startsWith(".") || value.endsWith(".")) {
            throw new IllegalArgumentException(name + " '" + value + "' must not start or end with '.'");
        }
        if (value.contains("..")) {
            throw new IllegalArgumentException(name + " '" + value + "' must not contain '..'");
        }

        return value;
    }

    /**
     * Creates a new library builder.
     *
     * @return new library builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Due to the constructor complexity of an immutable {@link Library},
     * instead this fluent builder is used to configure and then construct
     * a new library.
     */
    public static class Builder {
        /**
         * Direct download URLs for this library
         */
        private final List<String> urls = new ArrayList<>();

        /**
         * Maven group ID
         */
        private @Nullable String groupId;

        /**
         * Maven artifact ID
         */
        private @Nullable String artifactId;

        /**
         * Artifact version
         */
        private @Nullable String version;

        /**
         * Artifact classifier
         */
        private @Nullable String classifier;

        /**
         * Binary SHA-256 checksum for this library's jar file
         */
        private byte @Nullable [] checksum;

        /**
         * Jar relocations to apply
         */
        private final List<Relocation> relocations = new ArrayList<>();

        /**
         * Whether transitive dependencies are resolved
         */
        private boolean resolveTransitiveDependencies;

        /**
         * Transitive dependencies that are never resolved
         */
        private final List<ExcludedDependency> excludedTransitiveDependencies = new ArrayList<>();

        /**
         * Adds a direct download URL for this library.
         *
         * @param url direct download URL
         * @return this builder
         */
        public Builder url(String url) {
            urls.add(requireNonNull(url, "url"));
            return this;
        }

        /**
         * Sets the Maven group ID for this library.
         *
         * @param groupId Maven group ID
         * @return this builder
         */
        public Builder groupId(String groupId) {
            this.groupId = requireNonNull(groupId, "groupId");
            return this;
        }

        /**
         * Sets the Maven artifact ID for this library.
         *
         * @param artifactId Maven artifact ID
         * @return this builder
         */
        public Builder artifactId(String artifactId) {
            this.artifactId = requireNonNull(artifactId, "artifactId");
            return this;
        }

        /**
         * Sets the artifact version for this library.
         *
         * @param version artifact version
         * @return this builder
         */
        public Builder version(String version) {
            this.version = requireNonNull(version, "version");
            return this;
        }

        /**
         * Sets the artifact classifier for this library.
         *
         * @param classifier artifact classifier
         * @return this builder
         */
        public Builder classifier(String classifier) {
            this.classifier = requireNonNull(classifier, "classifier");
            return this;
        }

        /**
         * Sets the binary SHA-256 checksum for this library. The array is
         * copied, later changes to it have no effect.
         *
         * @param checksum binary SHA-256 checksum
         * @return this builder
         */
        public Builder checksum(byte[] checksum) {
            this.checksum = requireNonNull(checksum, "checksum").clone();
            return this;
        }

        /**
         * Sets the Base64-encoded SHA-256 checksum for this library.
         *
         * @param checksum Base64-encoded SHA-256 checksum
         * @return this builder
         * @throws IllegalArgumentException if the checksum is not valid Base64
         */
        public Builder checksum(String checksum) {
            return checksum(Base64.getDecoder().decode(requireNonNull(checksum, "checksum")));
        }

        /**
         * Adds a jar relocation to apply to this library.
         *
         * @param relocation jar relocation to apply
         * @return this builder
         */
        public Builder relocate(Relocation relocation) {
            relocations.add(requireNonNull(relocation, "relocation"));
            return this;
        }

        /**
         * Adds a jar relocation to apply to this library.
         *
         * @param pattern          search pattern
         * @param relocatedPattern replacement pattern
         * @return this builder
         */
        public Builder relocate(String pattern, String relocatedPattern) {
            return relocate(new Relocation(pattern, relocatedPattern));
        }

        /**
         * Sets whether this library's transitive dependencies are resolved
         * from its POM and loaded together with it. Disabled by default.
         * <p>
         * When enabled, the library manager downloads the library's POM (and
         * its parent POMs and imported BOMs) from its repositories and loads
         * the library's {@code compile} and {@code runtime} scoped
         * dependencies, recursively, with Maven's nearest-wins version
         * mediation. Transitive dependencies are downloaded from the
         * manager's repositories (never from this library's direct URLs), get
         * this library's relocations and are verified against the
         * repository's checksum files. See
         * {@link gg.moonrise.libby.resolver.DependencyResolver} for the
         * supported POM features.
         *
         * @param resolveTransitiveDependencies true to resolve transitive dependencies
         * @return this builder
         * @see LibraryManager#resolveTransitiveLibraries(Library)
         * @see #excludeTransitiveDependency(String, String)
         */
        public Builder resolveTransitiveDependencies(boolean resolveTransitiveDependencies) {
            this.resolveTransitiveDependencies = resolveTransitiveDependencies;
            return this;
        }

        /**
         * Excludes a transitive dependency, and with it everything that only
         * it depends on, when transitive dependencies are resolved. Either
         * value may be {@code *} to match anything, and {@code {}} is
         * accepted in place of {@code .} in the group ID.
         *
         * @param groupId    Maven group ID or {@code *}
         * @param artifactId Maven artifact ID or {@code *}
         * @return this builder
         * @throws IllegalArgumentException if a value is neither {@code *} nor a valid coordinate
         * @see #resolveTransitiveDependencies(boolean)
         */
        public Builder excludeTransitiveDependency(String groupId, String artifactId) {
            return excludeTransitiveDependency(new ExcludedDependency(groupId, artifactId));
        }

        /**
         * Excludes a transitive dependency, and with it everything that only
         * it depends on, when transitive dependencies are resolved.
         *
         * @param excludedDependency the dependency to exclude
         * @return this builder
         * @see #excludeTransitiveDependency(String, String)
         */
        public Builder excludeTransitiveDependency(ExcludedDependency excludedDependency) {
            excludedTransitiveDependencies.add(requireNonNull(excludedDependency, "excludedDependency"));
            return this;
        }

        /**
         * Creates a new library using this builder's configuration.
         *
         * @return new library
         * @throws NullPointerException     if the group ID, artifact ID or version is missing
         * @throws IllegalArgumentException if a coordinate or the checksum is invalid
         */
        public Library build() {
            return new Library(urls, groupId, artifactId, version, classifier, checksum, relocations,
                resolveTransitiveDependencies, excludedTransitiveDependencies);
        }
    }
}

package net.byteflux.libby;

import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/**
 * A transitive dependency that must not be downloaded when a library's
 * transitive dependencies are resolved, identified by group ID and artifact
 * ID like a Maven {@code <exclusion>}.
 * <p>
 * Either part may be {@code *} to match any value, so {@code ("com.example", "*")}
 * excludes every artifact of the group and {@code ("*", "*")} excludes every
 * transitive dependency. As with {@link Library.Builder#groupId(String)},
 * {@code {}} is accepted in place of {@code .} in the group ID so that
 * build-time relocation doesn't rewrite the string.
 *
 * @param groupId    Maven group ID or {@code *}
 * @param artifactId Maven artifact ID or {@code *}
 * @see Library.Builder#excludeTransitiveDependency(String, String)
 */
public record ExcludedDependency(String groupId, String artifactId) {
    /**
     * Wildcard matching any group ID or artifact ID
     */
    public static final String WILDCARD = "*";

    /**
     * Characters allowed in Maven coordinates
     */
    private static final Pattern COORDINATE = Pattern.compile("[A-Za-z0-9_.+\\-]+");

    /**
     * Creates a new excluded dependency.
     *
     * @param groupId    Maven group ID or {@code *}, with {@code {}} accepted for {@code .}
     * @param artifactId Maven artifact ID or {@code *}
     * @throws IllegalArgumentException if a value is neither {@code *} nor a valid coordinate
     */
    public ExcludedDependency {
        groupId = requirePattern("groupId", requireNonNull(groupId, "groupId").replace("{}", "."));
        artifactId = requirePattern("artifactId", requireNonNull(artifactId, "artifactId"));
    }

    /**
     * Checks whether this exclusion matches a dependency.
     *
     * @param groupId    the dependency's group ID
     * @param artifactId the dependency's artifact ID
     * @return true if the dependency is excluded
     */
    public boolean matches(String groupId, String artifactId) {
        return (WILDCARD.equals(this.groupId) || this.groupId.equals(groupId))
            && (WILDCARD.equals(this.artifactId) || this.artifactId.equals(artifactId));
    }

    /**
     * Gets this exclusion as {@code groupId:artifactId}.
     *
     * @return string representation
     */
    @Override
    public String toString() {
        return groupId + ':' + artifactId;
    }

    /**
     * Validates an exclusion value.
     *
     * @param name  value name used in error messages
     * @param value the value
     * @return the value
     */
    private static String requirePattern(String name, String value) {
        if (!WILDCARD.equals(value) && !COORDINATE.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " '" + value + "' must be '*' or only contain letters, digits and _ . + -");
        }

        return value;
    }
}

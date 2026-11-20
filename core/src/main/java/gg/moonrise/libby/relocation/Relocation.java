package gg.moonrise.libby.relocation;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Relocations are used to describe a search and replace pattern for renaming
 * packages in a library jar for the purpose of preventing namespace conflicts
 * with other plugins that bundle their own version of the same library.
 * <p>
 * Relocations are immutable. As with {@link gg.moonrise.libby.Library}
 * group IDs, {@code {}} may be used in place of {@code .} in the patterns to
 * stop build-time shading tools from rewriting the string literals.
 */
public class Relocation {
    /**
     * Search pattern
     */
    private final String pattern;

    /**
     * Replacement pattern
     */
    private final String relocatedPattern;

    /**
     * Classes and resources to include
     */
    private final Collection<String> includes;

    /**
     * Classes and resources to exclude
     */
    private final Collection<String> excludes;

    /**
     * Creates a new relocation.
     *
     * @param pattern          search pattern
     * @param relocatedPattern replacement pattern
     * @param includes         classes and resources to include or null
     * @param excludes         classes and resources to exclude or null
     */
    public Relocation(@Nullable String pattern,
                      @Nullable String relocatedPattern,
                      @Nullable Collection<String> includes,
                      @Nullable Collection<String> excludes) {

        this.pattern = requireNonNull(pattern, "pattern").replace("{}", ".");
        this.relocatedPattern = requireNonNull(relocatedPattern, "relocatedPattern").replace("{}", ".");
        this.includes = includes != null ? List.copyOf(includes) : List.of();
        this.excludes = excludes != null ? List.copyOf(excludes) : List.of();
    }

    /**
     * Creates a new relocation with empty includes and excludes.
     *
     * @param pattern          search pattern
     * @param relocatedPattern replacement pattern
     */
    public Relocation(String pattern, String relocatedPattern) {
        this(pattern, relocatedPattern, null, null);
    }

    /**
     * Gets the search pattern.
     *
     * @return pattern to search
     */
    public String getPattern() {
        return pattern;
    }

    /**
     * Gets the replacement pattern.
     *
     * @return pattern to replace with
     */
    public String getRelocatedPattern() {
        return relocatedPattern;
    }

    /**
     * Gets included classes and resources.
     *
     * @return classes and resources to include
     */
    public Collection<String> getIncludes() {
        return includes;
    }

    /**
     * Gets excluded classes and resources.
     *
     * @return classes and resources to exclude
     */
    public Collection<String> getExcludes() {
        return excludes;
    }

    /**
     * Compares this relocation with another object. Two relocations are equal
     * when their patterns, includes and excludes are equal.
     *
     * @param o the object to compare with
     * @return true if the object is an equal relocation
     */
    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Relocation)) {
            return false;
        }

        Relocation other = (Relocation) o;
        return pattern.equals(other.pattern)
            && relocatedPattern.equals(other.relocatedPattern)
            && includes.equals(other.includes)
            && excludes.equals(other.excludes);
    }

    /**
     * Gets a hash code consistent with {@link #equals(Object)}.
     *
     * @return hash code
     */
    @Override
    public int hashCode() {
        return Objects.hash(pattern, relocatedPattern, includes, excludes);
    }

    /**
     * Gets a human-readable string representation of this relocation.
     *
     * @return string representation
     */
    @Override
    public String toString() {
        return pattern + " -> " + relocatedPattern + " (includes=" + includes + ", excludes=" + excludes + ')';
    }

    /**
     * Creates a new relocation builder.
     *
     * @return new relocation builder
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Provides an alternative method of creating a {@link Relocation}. This
     * builder may be more intuitive for configuring relocations that also have
     * any includes or excludes.
     */
    public static class Builder {
        /**
         * Search pattern
         */
        private @Nullable String pattern;

        /**
         * Replacement pattern
         */
        private @Nullable String relocatedPattern;

        /**
         * Classes and resources to include
         */
        private final List<String> includes = new ArrayList<>();

        /**
         * Classes and resources to exclude
         */
        private final List<String> excludes = new ArrayList<>();

        /**
         * Sets the search pattern.
         *
         * @param pattern pattern to search
         * @return this builder
         */
        public Builder pattern(String pattern) {
            this.pattern = requireNonNull(pattern, "pattern");
            return this;
        }

        /**
         * Sets the replacement pattern.
         *
         * @param relocatedPattern pattern to replace with
         * @return this builder
         */
        public Builder relocatedPattern(String relocatedPattern) {
            this.relocatedPattern = requireNonNull(relocatedPattern, "relocatedPattern");
            return this;
        }

        /**
         * Adds a class or resource to be included.
         *
         * @param include class or resource to include
         * @return this builder
         */
        public Builder include(String include) {
            includes.add(requireNonNull(include, "include"));
            return this;
        }

        /**
         * Adds a class or resource to be excluded.
         *
         * @param exclude class or resource to exclude
         * @return this builder
         */
        public Builder exclude(String exclude) {
            excludes.add(requireNonNull(exclude, "exclude"));
            return this;
        }

        /**
         * Creates a new relocation using this builder's configuration.
         *
         * @return new relocation
         */
        public Relocation build() {
            return new Relocation(pattern, relocatedPattern, includes, excludes);
        }
    }
}

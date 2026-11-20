package net.byteflux.libby.resolver;

import net.byteflux.libby.ExcludedDependency;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * A POM after inheritance, interpolation, BOM imports and dependency
 * management injection.
 * <p>
 * Values that contained a property that couldn't be resolved keep the
 * {@code ${...}} expression, so that callers can report it if the value is
 * actually needed.
 *
 * @param groupId      effective group ID
 * @param artifactId   artifact ID
 * @param version      effective version
 * @param packaging    packaging, {@code jar} by default
 * @param management   effective dependency management by
 *                     {@link Dependency#managementKey() management key}
 * @param dependencies effective dependencies in declaration order, children
 *                     before inherited ones
 * @param relocation   the relocation target with missing parts filled in from
 *                     this POM's coordinates, or null if not relocated
 */
record EffectiveModel(String groupId,
                      String artifactId,
                      String version,
                      String packaging,
                      Map<String, Dependency> management,
                      List<Dependency> dependencies,
                      @Nullable Gav relocation) {

    /**
     * Complete artifact coordinates.
     *
     * @param groupId    group ID
     * @param artifactId artifact ID
     * @param version    version
     */
    record Gav(String groupId, String artifactId, String version) {
        /**
         * Gets these coordinates as {@code groupId:artifactId:version}.
         *
         * @return string representation
         */
        @Override
        public String toString() {
            return groupId + ':' + artifactId + ':' + version;
        }
    }

    /**
     * An interpolated dependency or managed dependency.
     *
     * @param groupId    group ID, or null if missing
     * @param artifactId artifact ID, or null if missing
     * @param version    version, or null if missing
     * @param type       type, never null ({@code jar} by default)
     * @param classifier classifier or null
     * @param scope      scope, or null if not declared
     * @param optional   optional flag as written, or null if not declared
     * @param exclusions exclusions
     */
    record Dependency(@Nullable String groupId,
                      @Nullable String artifactId,
                      @Nullable String version,
                      String type,
                      @Nullable String classifier,
                      @Nullable String scope,
                      @Nullable String optional,
                      List<ExcludedDependency> exclusions) {

        /**
         * Gets the key that dependency management entries are matched by.
         *
         * @return {@code groupId:artifactId:type:classifier}
         */
        String managementKey() {
            return groupId + ":" + artifactId + ':' + type + ':' + (classifier != null ? classifier : "");
        }

        /**
         * Gets whether this dependency is declared optional.
         *
         * @return true if optional
         */
        boolean isOptional() {
            return "true".equalsIgnoreCase(optional);
        }
    }
}

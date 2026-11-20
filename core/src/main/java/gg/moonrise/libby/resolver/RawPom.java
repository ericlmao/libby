package gg.moonrise.libby.resolver;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * The parts of a single POM document the resolver uses, exactly as written
 * (not interpolated and without inheritance). Active-by-default profiles have
 * already been merged in.
 *
 * @param parent               the {@code <parent>} coordinates, or null
 * @param groupId              the {@code <groupId>}, or null if inherited
 * @param artifactId           the {@code <artifactId>}, or null
 * @param version              the {@code <version>}, or null if inherited
 * @param packaging            the {@code <packaging>}, or null
 * @param properties           the {@code <properties>}
 * @param dependencyManagement the {@code <dependencyManagement>} dependencies
 * @param dependencies         the {@code <dependencies>}
 * @param relocation           the {@code <distributionManagement><relocation>}, or null
 */
record RawPom(@Nullable Coordinates parent,
              @Nullable String groupId,
              @Nullable String artifactId,
              @Nullable String version,
              @Nullable String packaging,
              Map<String, String> properties,
              List<RawDependency> dependencyManagement,
              List<RawDependency> dependencies,
              @Nullable Coordinates relocation) {

    /**
     * Possibly incomplete {@code groupId}, {@code artifactId} and
     * {@code version} elements.
     *
     * @param groupId    group ID or null
     * @param artifactId artifact ID or null
     * @param version    version or null
     */
    record Coordinates(@Nullable String groupId, @Nullable String artifactId, @Nullable String version) {
    }

    /**
     * A {@code <dependency>} element.
     *
     * @param groupId    group ID or null
     * @param artifactId artifact ID or null
     * @param version    version or null
     * @param type       type or null
     * @param classifier classifier or null
     * @param scope      scope or null
     * @param optional   optional flag or null
     * @param exclusions the {@code <exclusions>}
     */
    record RawDependency(@Nullable String groupId,
                         @Nullable String artifactId,
                         @Nullable String version,
                         @Nullable String type,
                         @Nullable String classifier,
                         @Nullable String scope,
                         @Nullable String optional,
                         List<Coordinates> exclusions) {

        /**
         * Gets the key identifying this dependency within one list, before
         * interpolation.
         *
         * @return {@code groupId:artifactId:type:classifier}
         */
        String key() {
            return groupId + ":" + artifactId + ':' + (type != null ? type : "jar") + ':' + (classifier != null ? classifier : "");
        }
    }
}

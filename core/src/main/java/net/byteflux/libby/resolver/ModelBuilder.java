package net.byteflux.libby.resolver;

import net.byteflux.libby.ExcludedDependency;
import net.byteflux.libby.Library;
import net.byteflux.libby.LibraryDownloadException;
import net.byteflux.libby.resolver.EffectiveModel.Dependency;
import net.byteflux.libby.resolver.EffectiveModel.Gav;
import net.byteflux.libby.resolver.RawPom.Coordinates;
import net.byteflux.libby.resolver.RawPom.RawDependency;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds {@linkplain EffectiveModel effective models} the way Maven does:
 * parent inheritance first, then property interpolation, then BOM imports
 * and finally dependency management injection.
 * <p>
 * Instances cache downloaded POMs and built models and are meant to be used
 * for a single resolution by a single thread.
 */
final class ModelBuilder {
    /**
     * Maximum number of POM documents in one parent and BOM import chain
     */
    static final int MAX_CHAIN_LENGTH = 64;

    /**
     * Maximum number of POM documents loaded in one resolution
     */
    static final int MAX_DOCUMENTS = 1024;

    /**
     * Maximum nesting of property references
     */
    static final int MAX_INTERPOLATION_DEPTH = 16;

    /**
     * Maximum length of an interpolated value
     */
    static final int MAX_INTERPOLATED_LENGTH = 4096;

    /**
     * Maximum number of property lookups while interpolating one value
     */
    static final int MAX_INTERPOLATION_LOOKUPS = 1024;

    /**
     * Downloads POM files
     */
    private final PomSource source;

    /**
     * Parsed POMs by {@code groupId:artifactId:version}
     */
    private final Map<String, RawPom> documents = new HashMap<>();

    /**
     * Built models by {@code groupId:artifactId:version}
     */
    private final Map<String, EffectiveModel> models = new HashMap<>();

    /**
     * The POMs of the parent and import chain currently being built,
     * innermost first
     */
    private final Deque<String> chain = new ArrayDeque<>();

    /**
     * Models currently being built, to detect BOM import cycles
     */
    private final Set<String> inProgress = new HashSet<>();

    /**
     * Creates a new model builder.
     *
     * @param source downloads POM files
     */
    ModelBuilder(PomSource source) {
        this.source = source;
    }

    /**
     * Builds the effective model of an artifact.
     *
     * @param gav the artifact's coordinates
     * @return the effective model
     * @throws ModelException if the model can't be built
     */
    EffectiveModel build(Gav gav) throws ModelException {
        String key = gav.toString();
        EffectiveModel cached = models.get(key);
        if (cached != null) {
            return cached;
        }

        if (!inProgress.add(key)) {
            throw new ModelException("BOM import cycle " + describeChain() + " -> " + key, null);
        }

        int depth = chain.size();
        try {
            enter(key);
            Set<String> lineageKeys = new HashSet<>();
            lineageKeys.add(key);
            List<RawPom> lineage = new ArrayList<>();
            RawPom current = load(gav, "POM");
            lineage.add(current);
            Gav currentGav = gav;
            while (current.parent() != null) {
                Gav parentGav = parentCoordinates(current, currentGav);
                if (!lineageKeys.add(parentGav.toString())) {
                    throw new ModelException("parent cycle " + describeChain() + " -> " + parentGav, null);
                }
                enter(parentGav.toString());
                current = load(parentGav, "parent POM");
                lineage.add(current);
                currentGav = parentGav;
            }

            EffectiveModel model = assemble(gav, lineage);
            models.put(key, model);
            return model;
        } finally {
            while (chain.size() > depth) {
                chain.pop();
            }
            inProgress.remove(key);
        }
    }

    /**
     * Adds a POM to the current chain.
     *
     * @param key the POM's coordinates
     * @throws ModelException if the chain is too long
     */
    private void enter(String key) throws ModelException {
        if (chain.size() >= MAX_CHAIN_LENGTH) {
            throw new ModelException("parent and BOM import chain is longer than " + MAX_CHAIN_LENGTH + " POMs", null);
        }

        chain.push(key);
    }

    /**
     * Describes the current chain, outermost first.
     *
     * @return the chain joined with arrows
     */
    private String describeChain() {
        List<String> outermostFirst = new ArrayList<>(chain);
        Collections.reverse(outermostFirst);
        return String.join(" -> ", outermostFirst);
    }

    /**
     * Downloads and parses a POM, or returns it from the cache.
     *
     * @param gav  the POM's coordinates
     * @param role what the POM is, for messages
     * @return the parsed POM
     * @throws ModelException if the POM can't be downloaded or parsed
     */
    private RawPom load(Gav gav, String role) throws ModelException {
        String key = gav.toString();
        RawPom cached = documents.get(key);
        if (cached != null) {
            return cached;
        }
        if (documents.size() >= MAX_DOCUMENTS) {
            throw new ModelException("more than " + MAX_DOCUMENTS + " POM documents are needed", null);
        }

        try {
            Library.builder().groupId(gav.groupId()).artifactId(gav.artifactId()).version(gav.version()).build();
        } catch (IllegalArgumentException e) {
            throw new ModelException(role + " " + key + " has invalid coordinates: " + e.getMessage(), e);
        }

        Path file;
        try {
            file = source.download(gav.groupId(), gav.artifactId(), gav.version());
        } catch (LibraryDownloadException e) {
            throw new ModelException(role + " " + key + " couldn't be downloaded", e);
        }

        RawPom pom;
        try {
            pom = PomParser.parse(file);
        } catch (IOException e) {
            throw new ModelException(role + " " + key + " is invalid: " + e.getMessage(), e);
        }

        documents.put(key, pom);
        return pom;
    }

    /**
     * Gets the coordinates of a POM's parent. They are interpolated with the
     * POM's own properties, although published POMs normally use constants.
     *
     * @param pom the POM, which must have a parent
     * @param gav the POM's coordinates
     * @return the parent's coordinates
     * @throws ModelException if a parent coordinate is missing or can't be resolved
     */
    private static Gav parentCoordinates(RawPom pom, Gav gav) throws ModelException {
        Coordinates parent = pom.parent();
        if (parent == null) {
            throw new IllegalStateException("no parent");
        }

        Interpolator interpolator = new Interpolator(pom.properties(), Map.of());
        String groupId = interpolator.interpolate(parent.groupId());
        String artifactId = interpolator.interpolate(parent.artifactId());
        String version = interpolator.interpolate(parent.version());
        if (!isResolved(groupId) || !isResolved(artifactId) || !isResolved(version)) {
            throw new ModelException("the parent of " + gav + " has missing or unresolved coordinates "
                + parent.groupId() + ':' + parent.artifactId() + ':' + parent.version(), null);
        }

        return new Gav(groupId, artifactId, version);
    }

    /**
     * Builds the effective model from a POM and its ancestors.
     *
     * @param gav     the requested coordinates
     * @param lineage the POM followed by its parent, grandparent and so on
     * @return the effective model
     * @throws ModelException if an imported BOM can't be built
     */
    private EffectiveModel assemble(Gav gav, List<RawPom> lineage) throws ModelException {
        RawPom pom = lineage.get(0);

        // Inheritance: properties of descendants override those of ancestors,
        // while dependencies are merged by key with descendants first.
        Map<String, String> properties = new HashMap<>();
        for (int i = lineage.size() - 1; i >= 0; i--) {
            properties.putAll(lineage.get(i).properties());
        }

        Map<String, RawDependency> rawManagement = new LinkedHashMap<>();
        Map<String, RawDependency> rawDependencies = new LinkedHashMap<>();
        for (RawPom ancestor : lineage) {
            for (RawDependency dependency : ancestor.dependencyManagement()) {
                rawManagement.putIfAbsent(dependency.key(), dependency);
            }
            for (RawDependency dependency : ancestor.dependencies()) {
                rawDependencies.putIfAbsent(dependency.key(), dependency);
            }
        }

        // Interpolation
        Coordinates parent = pom.parent();
        Map<String, String> project = new HashMap<>();
        if (parent != null) {
            Interpolator parentInterpolator = new Interpolator(properties, Map.of());
            putProjectValue(project, "parent.groupId", parentInterpolator.interpolate(parent.groupId()));
            putProjectValue(project, "parent.artifactId", parentInterpolator.interpolate(parent.artifactId()));
            putProjectValue(project, "parent.version", parentInterpolator.interpolate(parent.version()));
        }

        Interpolator coordinateInterpolator = new Interpolator(properties, project);
        String groupId = firstNonNull(
            coordinateInterpolator.interpolate(pom.groupId()),
            parent != null ? coordinateInterpolator.interpolate(parent.groupId()) : null,
            gav.groupId());
        String artifactId = firstNonNull(coordinateInterpolator.interpolate(pom.artifactId()), gav.artifactId(), gav.artifactId());
        String version = firstNonNull(
            coordinateInterpolator.interpolate(pom.version()),
            parent != null ? coordinateInterpolator.interpolate(parent.version()) : null,
            gav.version());
        putProjectValue(project, "groupId", groupId);
        putProjectValue(project, "artifactId", artifactId);
        putProjectValue(project, "version", version);
        String packaging = pom.packaging() != null ? pom.packaging() : "jar";
        putProjectValue(project, "packaging", packaging);

        Interpolator interpolator = new Interpolator(properties, project);

        // Dependency management, with BOMs imported after the declared entries
        Map<String, Dependency> management = new LinkedHashMap<>();
        List<Dependency> imports = new ArrayList<>();
        for (RawDependency raw : rawManagement.values()) {
            Dependency managed = interpolate(raw, interpolator);
            if ("import".equals(managed.scope())) {
                if ("pom".equals(managed.type())) {
                    imports.add(managed);
                }
                continue;
            }
            management.putIfAbsent(managed.managementKey(), managed);
        }

        for (Dependency bom : imports) {
            String bomVersion = bom.version();
            if (!isResolved(bom.groupId()) || !isResolved(bom.artifactId()) || !isResolved(bomVersion)) {
                throw new ModelException("BOM " + bom.groupId() + ':' + bom.artifactId() + ':' + bomVersion
                    + " imported by " + gav + " has missing or unresolved coordinates", null);
            }
            String exact = Versions.exact(bomVersion);
            if (exact == null) {
                throw new ModelException("BOM " + bom.groupId() + ':' + bom.artifactId() + " imported by " + gav
                    + " uses the version range " + bomVersion, null);
            }

            EffectiveModel imported = build(new Gav(bom.groupId(), bom.artifactId(), exact));
            for (Map.Entry<String, Dependency> entry : imported.management().entrySet()) {
                management.putIfAbsent(entry.getKey(), entry.getValue());
            }
        }

        // Dependencies with dependency management injected
        List<Dependency> dependencies = new ArrayList<>();
        for (RawDependency raw : rawDependencies.values()) {
            Dependency dependency = interpolate(raw, interpolator);
            Dependency managed = management.get(dependency.managementKey());
            if (managed != null) {
                dependency = applyManagement(dependency, managed);
            }
            dependencies.add(dependency);
        }

        Gav relocation = null;
        Coordinates relocationElement = pom.relocation();
        if (relocationElement != null) {
            Gav target = new Gav(
                firstNonNull(interpolator.interpolate(relocationElement.groupId()), groupId, groupId),
                firstNonNull(interpolator.interpolate(relocationElement.artifactId()), artifactId, artifactId),
                firstNonNull(interpolator.interpolate(relocationElement.version()), version, version));
            if (!target.equals(new Gav(groupId, artifactId, version))) {
                relocation = target;
            }
        }

        return new EffectiveModel(groupId, artifactId, version, packaging, Map.copyOf(management), List.copyOf(dependencies), relocation);
    }

    /**
     * Applies a dependency management entry to a dependency, filling in
     * undeclared values only.
     *
     * @param dependency the dependency
     * @param managed    the dependency management entry
     * @return the managed dependency
     */
    private static Dependency applyManagement(Dependency dependency, Dependency managed) {
        String version = dependency.version() != null ? dependency.version() : managed.version();
        String scope = dependency.scope() != null ? dependency.scope() : managed.scope();
        String optional = dependency.optional() != null ? dependency.optional() : managed.optional();

        Set<ExcludedDependency> exclusions = new LinkedHashSet<>(dependency.exclusions());
        exclusions.addAll(managed.exclusions());

        return new Dependency(dependency.groupId(), dependency.artifactId(), version, dependency.type(),
            dependency.classifier(), scope, optional, List.copyOf(exclusions));
    }

    /**
     * Interpolates a dependency.
     *
     * @param raw          the dependency as written
     * @param interpolator the interpolator to use
     * @return the interpolated dependency
     * @throws ModelException if a value expands beyond the interpolation limits
     */
    private static Dependency interpolate(RawDependency raw, Interpolator interpolator) throws ModelException {
        List<ExcludedDependency> exclusions = new ArrayList<>();
        for (Coordinates exclusion : raw.exclusions()) {
            String groupId = interpolator.interpolate(exclusion.groupId());
            String artifactId = interpolator.interpolate(exclusion.artifactId());
            if (groupId == null || artifactId == null) {
                continue;
            }
            try {
                exclusions.add(new ExcludedDependency(groupId, artifactId));
            } catch (IllegalArgumentException e) {
                // A malformed or unresolved exclusion can't match a valid coordinate.
            }
        }

        String type = interpolator.interpolate(raw.type());
        return new Dependency(
            interpolator.interpolate(raw.groupId()),
            interpolator.interpolate(raw.artifactId()),
            interpolator.interpolate(raw.version()),
            type != null ? type : "jar",
            interpolator.interpolate(raw.classifier()),
            interpolator.interpolate(raw.scope()),
            interpolator.interpolate(raw.optional()),
            List.copyOf(exclusions)
        );
    }

    /**
     * Adds a model value under its {@code project.}, {@code pom.} and, for
     * parent values, bare expression names.
     *
     * @param project expression values to add to
     * @param name    the value's name relative to the project, such as
     *                {@code version} or {@code parent.version}
     * @param value   the value, or null to add nothing
     */
    private static void putProjectValue(Map<String, String> project, String name, @Nullable String value) {
        if (value == null) {
            return;
        }

        project.put("project." + name, value);
        project.put("pom." + name, value);
        if (name.startsWith("parent.")) {
            project.put(name, value);
        }
    }

    /**
     * Gets whether a value is present and contains no unresolved property.
     *
     * @param value the value
     * @return true if the value is usable
     */
    static boolean isResolved(@Nullable String value) {
        return value != null && !value.contains("${");
    }

    /**
     * Gets the first non-null value.
     *
     * @param first    the preferred value
     * @param second   the second choice
     * @param fallback the value used if both are null
     * @return the first non-null value
     */
    private static String firstNonNull(@Nullable String first, @Nullable String second, String fallback) {
        return first != null ? first : second != null ? second : fallback;
    }

    /**
     * Resolves {@code ${...}} expressions against model values and
     * properties. Unknown expressions, including environment variables and
     * system properties, are left unchanged, as are self-referencing
     * properties.
     * <p>
     * Expansion is bounded so that a small POM can't make it run for a long
     * time or build huge strings: properties may be nested up to
     * {@value #MAX_INTERPOLATION_DEPTH} levels deep, interpolating one value
     * may look up at most {@value #MAX_INTERPOLATION_LOOKUPS} expressions and
     * the result may be at most {@value #MAX_INTERPOLATED_LENGTH} characters
     * long.
     */
    private static final class Interpolator {
        /**
         * The effective properties
         */
        private final Map<String, String> properties;

        /**
         * Values of {@code project.*}, {@code pom.*} and {@code parent.*}
         * expressions
         */
        private final Map<String, String> project;

        /**
         * Properties currently being expanded, to detect cycles
         */
        private final Set<String> visiting = new HashSet<>();

        /**
         * Expressions looked up for the value currently being interpolated
         */
        private int lookups;

        /**
         * Creates a new interpolator.
         *
         * @param properties the effective properties
         * @param project    model expression values
         */
        private Interpolator(Map<String, String> properties, Map<String, String> project) {
            this.properties = properties;
            this.project = project;
        }

        /**
         * Interpolates a value.
         *
         * @param value the value, or null
         * @return the interpolated value, or null if {@code value} is null
         * @throws ModelException if the value expands beyond the limits
         */
        private @Nullable String interpolate(@Nullable String value) throws ModelException {
            if (value == null) {
                return null;
            }

            lookups = 0;
            visiting.clear();
            return interpolate(value, value);
        }

        /**
         * Interpolates a value.
         *
         * @param value    the value
         * @param original the value being interpolated at the top level, for messages
         * @return the interpolated value
         * @throws ModelException if the value expands beyond the limits
         */
        private String interpolate(String value, String original) throws ModelException {
            int start = value.indexOf("${");
            if (start < 0) {
                return value;
            }

            StringBuilder result = new StringBuilder();
            int index = 0;
            while (start >= 0) {
                int end = value.indexOf('}', start + 2);
                if (end < 0) {
                    break;
                }

                result.append(value, index, start);
                String replacement = lookup(value.substring(start + 2, end), original);
                if (replacement != null) {
                    result.append(replacement);
                } else {
                    result.append(value, start, end + 1);
                }
                requireLength(result, original);
                index = end + 1;
                start = value.indexOf("${", index);
            }

            result.append(value, index, value.length());
            requireLength(result, original);
            return result.toString();
        }

        /**
         * Looks up the value of an expression.
         *
         * @param name     the expression without {@code ${}} and {@code }}
         * @param original the value being interpolated at the top level, for messages
         * @return the value, or null if unknown, too deeply nested or self-referencing
         * @throws ModelException if the value expands beyond the limits
         */
        private @Nullable String lookup(String name, String original) throws ModelException {
            if (++lookups > MAX_INTERPOLATION_LOOKUPS) {
                throw new ModelException("interpolating '" + abbreviate(original) + "' needs more than "
                    + MAX_INTERPOLATION_LOOKUPS + " property lookups", null);
            }

            String value = project.get(name);
            if (value != null) {
                return value;
            }

            value = properties.get(name);
            if (value == null || visiting.size() >= MAX_INTERPOLATION_DEPTH || !visiting.add(name)) {
                return null;
            }

            try {
                return interpolate(value, original);
            } finally {
                visiting.remove(name);
            }
        }

        /**
         * Checks that an interpolated value isn't too long.
         *
         * @param result   the value so far
         * @param original the value being interpolated at the top level, for messages
         * @throws ModelException if the value is too long
         */
        private static void requireLength(StringBuilder result, String original) throws ModelException {
            if (result.length() > MAX_INTERPOLATED_LENGTH) {
                throw new ModelException("interpolating '" + abbreviate(original) + "' produces more than "
                    + MAX_INTERPOLATED_LENGTH + " characters", null);
            }
        }

        /**
         * Shortens a value for messages.
         *
         * @param value the value
         * @return the value, cut to at most 64 characters
         */
        private static String abbreviate(String value) {
            return value.length() <= 64 ? value : value.substring(0, 61) + "...";
        }
    }

    /**
     * Signals that an effective model couldn't be built.
     */
    static final class ModelException extends Exception {
        /**
         * Creates a new model exception.
         *
         * @param message what went wrong
         * @param cause   the cause, or null
         */
        ModelException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}

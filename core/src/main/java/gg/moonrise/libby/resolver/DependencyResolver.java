package gg.moonrise.libby.resolver;

import gg.moonrise.libby.ExcludedDependency;
import gg.moonrise.libby.Library;
import gg.moonrise.libby.logging.Logger;
import gg.moonrise.libby.resolver.EffectiveModel.Dependency;
import gg.moonrise.libby.resolver.EffectiveModel.Gav;
import gg.moonrise.libby.resolver.ModelBuilder.ModelException;
import org.jspecify.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Resolves a library's transitive dependencies from Maven POM files, with
 * Maven's semantics for the commonly used POM features.
 * <p>
 * <b>Supported:</b>
 * <ul>
 *     <li>parent POMs ({@code <relativePath>} is ignored), with inheritance of
 *     the group ID, version, properties, dependency management and
 *     dependencies;</li>
 *     <li>{@code ${...}} interpolation of {@code project.groupId},
 *     {@code project.artifactId}, {@code project.version},
 *     {@code project.parent.groupId}/{@code artifactId}/{@code version} (also
 *     with the {@code pom.} prefix, and {@code parent.*}) and
 *     {@code <properties>}, which may reference each other up to
 *     {@value ModelBuilder#MAX_INTERPOLATION_DEPTH} levels deep. Environment
 *     variables and system properties are not supported;</li>
 *     <li>dependency management, including BOMs imported with
 *     {@code <scope>import</scope>} and {@code <type>pom</type>}, applied to
 *     undeclared versions, scopes, optional flags and exclusions of each
 *     POM's own dependencies. The requested library's dependency management
 *     only supplies versions that transitive dependencies leave undeclared;
 *     it never overrides a declared version or scope, so the resolved tree
 *     matches what Gradle and Maven put on the compile classpath of a project
 *     that depends on the library;</li>
 *     <li>{@code compile} and {@code runtime} dependencies, with Maven's
 *     transitive scope rules. {@code test}, {@code provided}, {@code system}
 *     and optional dependencies are skipped, as are dependencies whose type
 *     isn't {@code jar}, {@code bundle} or {@code pom};</li>
 *     <li>dependencies of type {@code pom}, and dependencies on artifacts
 *     with {@code pom} packaging (aggregators such as
 *     {@code club.minnced:opus-java}, which Gradle-generated POMs reference
 *     without a type): their dependencies are followed, but there is no jar
 *     to load for them;</li>
 *     <li>classifiers, and exclusions (with {@code *} wildcards), which apply
 *     to the whole subtree below the dependency that declares them;</li>
 *     <li>nearest-wins version mediation: the version found closest to the
 *     requested library wins, ties are broken by declaration order;</li>
 *     <li>one hop of {@code <distributionManagement><relocation>};</li>
 *     <li>profiles that are {@code <activeByDefault>}. Profiles activated by
 *     JDK, OS, property or file conditions are ignored.</li>
 * </ul>
 * <p>
 * <b>Not supported</b>, reported with a {@link TransitiveDependencyException}
 * naming the dependency path: version ranges other than a single exact
 * version such as {@code [1.2.3]}, dependencies whose version is missing or
 * contains an unresolved property, {@code SNAPSHOT} versions of transitive
 * dependencies and POMs that can't be downloaded or parsed. Dependency
 * cycles are not an error.
 * <p>
 * To stay safe with untrusted POMs, a resolution is limited to a depth of
 * {@value #MAX_DEPTH}, {@value #MAX_ARTIFACTS} artifacts,
 * {@value ModelBuilder#MAX_CHAIN_LENGTH} POMs per parent and BOM import chain
 * and {@value ModelBuilder#MAX_DOCUMENTS} POMs in total. Interpolating a value
 * may take at most {@value ModelBuilder#MAX_INTERPOLATION_LOOKUPS} property
 * lookups and produce at most {@value ModelBuilder#MAX_INTERPOLATED_LENGTH}
 * characters. POMs are parsed with a hardened XML parser that rejects document
 * type declarations and elements nested more than
 * {@value PomParser#MAX_ELEMENT_DEPTH} levels deep.
 * <p>
 * This is an internal class and not part of the stable API; use
 * {@link gg.moonrise.libby.LibraryManager#resolveTransitiveLibraries(Library)}.
 */
public final class DependencyResolver {
    /**
     * Maximum depth of a transitive dependency below the requested library
     */
    public static final int MAX_DEPTH = 16;

    /**
     * Maximum number of transitive dependencies of one library
     */
    public static final int MAX_ARTIFACTS = 256;

    /**
     * Maximum size of a POM file in bytes
     */
    public static final int MAX_POM_SIZE = PomParser.MAX_POM_SIZE;

    /**
     * Logger used for resolution messages
     */
    private final Logger logger;

    /**
     * Downloads POM files
     */
    private final PomSource source;

    /**
     * Creates a new dependency resolver.
     *
     * @param logger logger used for resolution messages
     * @param source downloads POM files
     */
    public DependencyResolver(Logger logger, PomSource source) {
        this.logger = requireNonNull(logger, "logger");
        this.source = requireNonNull(source, "source");
    }

    /**
     * Resolves the transitive dependencies of a library.
     * <p>
     * The returned libraries have no direct download URLs and no pinned
     * checksum, carry the requested library's relocations and don't resolve
     * transitive dependencies themselves. They are ordered so that a
     * library's dependencies come before it.
     *
     * @param root the library to resolve the dependencies of
     * @return the transitive dependencies, without {@code root}
     * @throws TransitiveDependencyException if the dependencies can't be resolved
     */
    public List<Library> resolve(Library root) {
        requireNonNull(root, "root");
        ModelBuilder models = new ModelBuilder(source);

        Node rootNode = new Node(root.getGroupId(), root.getArtifactId(), root.getVersion(), root.getClassifier(),
            "compile", root.getExcludedTransitiveDependencies(), null);
        rootNode.model = buildModel(models, rootNode, root.getGroupId(), root.getArtifactId());
        Gav rootRelocation = rootNode.model.relocation();
        if (rootRelocation != null) {
            logger.warn(root + " has been relocated to " + rootRelocation + ", using the relocated POM's dependencies");
            rootNode.model = buildModel(models, rootNode, root.getGroupId(), root.getArtifactId(), rootRelocation);
        }
        Map<String, Dependency> rootManagement = rootNode.model.management();

        Map<String, Node> selected = new HashMap<>();
        selected.put(rootNode.key(), rootNode);
        int count = 0;

        Deque<Node> queue = new ArrayDeque<>();
        queue.add(rootNode);
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            EffectiveModel model = requireNonNull(node.model, "model");
            for (Dependency declared : model.dependencies()) {
                Dependency dependency = declared;
                if (node != rootNode && dependency.version() == null) {
                    // Like Gradle and Maven when compiling against the library, the
                    // root's dependency management never overrides a version or scope
                    // declared (or managed) deeper in the tree; it only fills in a
                    // version that would otherwise be missing.
                    Dependency managed = rootManagement.get(dependency.managementKey());
                    if (managed != null && managed.version() != null) {
                        dependency = dependency.withVersion(managed.version());
                    }
                }

                Node child = select(models, node, dependency, selected);
                if (child == null) {
                    continue;
                }

                if (++count > MAX_ARTIFACTS) {
                    throw failure(child, "more than " + MAX_ARTIFACTS + " transitive dependencies", null);
                }
                selected.put(child.key(), child);
                node.children.add(child);
                queue.add(child);
            }
        }

        List<Library> libraries = new ArrayList<>();
        collect(rootNode, root, libraries);
        logger.info("Resolved " + libraries.size() + " transitive dependencies for " + root);
        if (!rootNode.children.isEmpty()) {
            StringBuilder tree = new StringBuilder("Dependency tree of ").append(root).append(':');
            appendTree(tree, rootNode, 1);
            logger.debug(tree.toString());
        }

        return List.copyOf(libraries);
    }

    /**
     * Decides whether a dependency of a node is followed and creates its node.
     *
     * @param models   the model builder
     * @param node     the node declaring the dependency
     * @param dependency the dependency, with dependency management applied
     * @param selected nodes selected so far by {@link Node#key() key}
     * @return the new node, or null if the dependency isn't followed
     * @throws TransitiveDependencyException if the dependency can't be resolved
     */
    private @Nullable Node select(ModelBuilder models, Node node, Dependency dependency, Map<String, Node> selected) {
        String groupId = dependency.groupId();
        String artifactId = dependency.artifactId();
        String name = groupId + ':' + artifactId + (dependency.version() != null ? ":" + dependency.version() : "");

        String type = dependency.type();
        if (!"jar".equals(type) && !"bundle".equals(type) && !"pom".equals(type)) {
            logger.debug("Skipping " + name + " of type " + type + " required by " + node);
            return null;
        }

        String scope = transitiveScope(node.scope, dependency.scope() != null ? dependency.scope() : "compile");
        if (scope == null) {
            logger.debug("Skipping " + name + " (" + dependency.scope() + ") required by " + node);
            return null;
        }
        if (dependency.isOptional()) {
            logger.debug("Skipping optional " + name + " required by " + node);
            return null;
        }

        if (!ModelBuilder.isResolved(groupId) || !ModelBuilder.isResolved(artifactId)) {
            throw failure(node, name, groupId, artifactId,
                "dependency " + name + " of " + node + " has a missing or unresolved group or artifact ID", null);
        }

        for (ExcludedDependency exclusion : node.exclusions) {
            if (exclusion.matches(groupId, artifactId)) {
                logger.debug("Excluding " + name + " required by " + node + " (" + exclusion + ')');
                return null;
            }
        }

        String classifier = dependency.classifier();
        if (classifier != null && !ModelBuilder.isResolved(classifier)) {
            throw failure(node, name, groupId, artifactId,
                "dependency " + name + " of " + node + " has the unresolved classifier " + classifier, null);
        }
        Node existing = selected.get(Node.key(groupId, artifactId, classifier));
        if (existing != null) {
            if (!existing.version.equals(dependency.version())) {
                logger.debug("Omitting " + name + " required by " + node + ", using " + existing.version + " (nearest wins)");
            }
            return null;
        }

        String version = requireVersion(node, name, groupId, artifactId, dependency.version());
        Set<ExcludedDependency> exclusions = new LinkedHashSet<>(node.exclusions);
        exclusions.addAll(dependency.exclusions());
        Node child = new Node(groupId, artifactId, version, classifier, scope, List.copyOf(exclusions), node);
        if (child.depth > MAX_DEPTH) {
            throw failure(child, "the dependency tree is deeper than " + MAX_DEPTH + " levels", null);
        }

        try {
            Library.Builder builder = Library.builder().groupId(groupId).artifactId(artifactId).version(version);
            if (classifier != null) {
                builder.classifier(classifier);
            }
            builder.build();
        } catch (IllegalArgumentException e) {
            throw failure(child, "invalid coordinates: " + e.getMessage(), e);
        }

        child.model = buildModel(models, child, groupId, artifactId);
        child.hasArtifact = !"pom".equals(type) && !"pom".equals(child.model.packaging());
        Gav relocation = child.model.relocation();
        if (relocation != null) {
            logger.warn(child.gav() + " required by " + node + " has been relocated to " + relocation + ", using " + relocation);
            Node relocated = new Node(relocation.groupId(), relocation.artifactId(), relocation.version(), classifier, scope, child.exclusions, node);
            relocated.model = buildModel(models, relocated, groupId, artifactId, relocation);
            if (relocated.model.relocation() != null) {
                throw failure(relocated, child.gav() + " is relocated more than once (" + relocation + " -> " + relocated.model.relocation() + ')', null);
            }
            if (Versions.isSnapshot(relocation.version())) {
                throw failure(relocated, "relocated to the SNAPSHOT version " + relocation, null);
            }
            if (selected.containsKey(relocated.key())) {
                return null;
            }
            relocated.hasArtifact = !"pom".equals(type) && !"pom".equals(relocated.model.packaging());
            child = relocated;
        }

        return child;
    }

    /**
     * Checks that a dependency's version can be used and returns it.
     *
     * @param node       the node declaring the dependency
     * @param name       the dependency's name for messages
     * @param groupId    the dependency's group ID
     * @param artifactId the dependency's artifact ID
     * @param version    the declared or managed version
     * @return the exact version
     * @throws TransitiveDependencyException if the version can't be used
     */
    private static String requireVersion(Node node, String name, String groupId, String artifactId, @Nullable String version) {
        if (version == null) {
            throw failure(node, name, groupId, artifactId,
                "dependency " + name + " of " + node + " has no version, not even from dependency management", null);
        }
        if (!ModelBuilder.isResolved(version)) {
            throw failure(node, name, groupId, artifactId,
                "dependency " + name + " of " + node + " has a version with an unresolved property", null);
        }

        String exact = Versions.exact(version);
        if (exact == null) {
            throw failure(node, name, groupId, artifactId,
                "dependency " + name + " of " + node + " uses a version range, which isn't supported", null);
        }
        if (Versions.isSnapshot(exact)) {
            throw failure(node, name, groupId, artifactId,
                "dependency " + name + " of " + node + " is a SNAPSHOT version, which isn't supported for transitive dependencies", null);
        }

        return exact;
    }

    /**
     * Builds the effective model of a node.
     *
     * @param models     the model builder
     * @param node       the node
     * @param groupId    group ID suggested for exclusion on failure
     * @param artifactId artifact ID suggested for exclusion on failure
     * @return the effective model
     * @throws TransitiveDependencyException if the model can't be built
     */
    private static EffectiveModel buildModel(ModelBuilder models, Node node, String groupId, String artifactId) {
        return buildModel(models, node, groupId, artifactId, node.gav());
    }

    /**
     * Builds an effective model for a node.
     *
     * @param models     the model builder
     * @param node       the node
     * @param groupId    group ID suggested for exclusion on failure
     * @param artifactId artifact ID suggested for exclusion on failure
     * @param gav        coordinates of the model to build
     * @return the effective model
     * @throws TransitiveDependencyException if the model can't be built
     */
    private static EffectiveModel buildModel(ModelBuilder models, Node node, String groupId, String artifactId, Gav gav) {
        try {
            return models.build(gav);
        } catch (ModelException e) {
            throw new TransitiveDependencyException(e.getMessage(), node.path(), groupId, artifactId, e.getCause());
        }
    }

    /**
     * Gets the scope of a transitive dependency.
     *
     * @param parentScope the scope of the depending artifact
     * @param scope       the declared scope of the dependency
     * @return the resulting scope, or null if the dependency isn't needed at runtime
     */
    private static @Nullable String transitiveScope(String parentScope, String scope) {
        if (!"compile".equals(scope) && !"runtime".equals(scope)) {
            return null;
        }

        return "compile".equals(parentScope) ? scope : "runtime";
    }

    /**
     * Adds the libraries below a node in dependency-first order.
     *
     * @param node      the node
     * @param root      the requested library
     * @param libraries the list to add to
     */
    private static void collect(Node node, Library root, List<Library> libraries) {
        for (Node child : node.children) {
            collect(child, root, libraries);
            if (!child.hasArtifact) {
                continue;
            }

            Library.Builder builder = Library.builder()
                                             .groupId(child.groupId)
                                             .artifactId(child.artifactId)
                                             .version(child.version);
            if (child.classifier != null) {
                builder.classifier(child.classifier);
            }
            root.getRelocations().forEach(builder::relocate);
            libraries.add(builder.build());
        }
    }

    /**
     * Appends the subtree below a node to a dependency tree description.
     *
     * @param tree  the description
     * @param node  the node
     * @param depth the depth of the node's children
     */
    private static void appendTree(StringBuilder tree, Node node, int depth) {
        for (Node child : node.children) {
            tree.append(System.lineSeparator()).append("  ".repeat(depth)).append("+- ").append(child)
                .append(" (").append(child.scope).append(child.hasArtifact ? ")" : ", pom only)");
            appendTree(tree, child, depth + 1);
        }
    }

    /**
     * Creates an exception for a problem with a node.
     *
     * @param node   the node
     * @param reason what went wrong
     * @param cause  the cause, or null
     * @return the exception
     */
    private static TransitiveDependencyException failure(Node node, String reason, @Nullable Throwable cause) {
        return new TransitiveDependencyException(reason, node.path(), node.groupId, node.artifactId, cause);
    }

    /**
     * Creates an exception for a problem with a dependency that has no node.
     *
     * @param parent     the node declaring the dependency
     * @param name       the dependency's name
     * @param groupId    the dependency's group ID, or null
     * @param artifactId the dependency's artifact ID, or null
     * @param reason     what went wrong
     * @param cause      the cause, or null
     * @return the exception
     */
    private static TransitiveDependencyException failure(Node parent, String name, @Nullable String groupId, @Nullable String artifactId,
                                                         String reason, @Nullable Throwable cause) {
        List<String> path = new ArrayList<>(parent.path());
        path.add(name);
        return new TransitiveDependencyException(reason, path, groupId, artifactId, cause);
    }

    /**
     * An artifact in the dependency tree.
     */
    private static final class Node {
        /**
         * Group ID
         */
        private final String groupId;

        /**
         * Artifact ID
         */
        private final String artifactId;

        /**
         * Version
         */
        private final String version;

        /**
         * Classifier or null
         */
        private final @Nullable String classifier;

        /**
         * Effective scope
         */
        private final String scope;

        /**
         * Exclusions applying to this node's dependencies
         */
        private final List<ExcludedDependency> exclusions;

        /**
         * The node that introduced this node, or null for the root
         */
        private final @Nullable Node parent;

        /**
         * Depth below the root
         */
        private final int depth;

        /**
         * Dependencies selected through this node, in declaration order
         */
        private final List<Node> children = new ArrayList<>();

        /**
         * Whether this node has a jar to load, false for POM-only artifacts
         */
        private boolean hasArtifact = true;

        /**
         * Effective model, set once built
         */
        private @Nullable EffectiveModel model;

        /**
         * Creates a new node.
         *
         * @param groupId    group ID
         * @param artifactId artifact ID
         * @param version    version
         * @param classifier classifier or null
         * @param scope      effective scope
         * @param exclusions exclusions applying to this node's dependencies
         * @param parent     the node that introduced this node, or null for the root
         */
        private Node(String groupId, String artifactId, String version, @Nullable String classifier,
                     String scope, List<ExcludedDependency> exclusions, @Nullable Node parent) {
            this.groupId = groupId;
            this.artifactId = artifactId;
            this.version = version;
            this.classifier = classifier;
            this.scope = scope;
            this.exclusions = exclusions;
            this.parent = parent;
            this.depth = parent != null ? parent.depth + 1 : 0;
        }

        /**
         * Gets the key that version mediation uses.
         *
         * @return {@code groupId:artifactId:classifier}
         */
        private String key() {
            return key(groupId, artifactId, classifier);
        }

        /**
         * Gets the key that version mediation uses.
         *
         * @param groupId    group ID
         * @param artifactId artifact ID
         * @param classifier classifier or null
         * @return {@code groupId:artifactId:classifier}
         */
        private static String key(String groupId, String artifactId, @Nullable String classifier) {
            return groupId + ':' + artifactId + ':' + (classifier != null ? classifier : "");
        }

        /**
         * Gets this node's coordinates, without the classifier.
         *
         * @return coordinates
         */
        private Gav gav() {
            return new Gav(groupId, artifactId, version);
        }

        /**
         * Gets the dependency path from the root to this node.
         *
         * @return the path as strings
         */
        private List<String> path() {
            List<String> path = new ArrayList<>();
            for (Node node = this; node != null; node = node.parent) {
                path.add(0, node.toString());
            }
            return path;
        }

        /**
         * Gets this node as {@code groupId:artifactId:version[:classifier]}.
         *
         * @return string representation
         */
        @Override
        public String toString() {
            String name = groupId + ':' + artifactId + ':' + version;
            return classifier != null ? name + ':' + classifier : name;
        }
    }
}

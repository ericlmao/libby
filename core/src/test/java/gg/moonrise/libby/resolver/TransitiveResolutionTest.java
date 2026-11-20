package gg.moonrise.libby.resolver;

import gg.moonrise.libby.Library;
import gg.moonrise.libby.LibraryDownloadException;
import gg.moonrise.libby.TestHttpRepository;
import gg.moonrise.libby.TestJars;
import gg.moonrise.libby.TestLibraryManager;
import gg.moonrise.libby.classloader.IsolatedClassLoader;
import gg.moonrise.libby.logging.LogLevel;
import gg.moonrise.libby.relocation.Relocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static gg.moonrise.libby.resolver.FixtureRepository.dependencies;
import static gg.moonrise.libby.resolver.FixtureRepository.dependency;
import static gg.moonrise.libby.resolver.FixtureRepository.exclusion;
import static gg.moonrise.libby.resolver.FixtureRepository.management;
import static gg.moonrise.libby.resolver.FixtureRepository.parent;
import static gg.moonrise.libby.resolver.FixtureRepository.scope;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TransitiveResolutionTest {
    @TempDir
    private Path dataDirectory;

    @TempDir
    private Path repositoryDirectory;

    @TempDir
    private Path work;

    private FixtureRepository repo;
    private TestLibraryManager manager;

    @BeforeEach
    public void setUp() {
        repo = new FixtureRepository(repositoryDirectory);
        manager = TestLibraryManager.create(dataDirectory);
        manager.addRepository(repo.url());
    }

    private static Library.Builder library(String coordinates) {
        String[] parts = coordinates.split(":");
        return Library.builder().groupId(parts[0]).artifactId(parts[1]).version(parts[2]);
    }

    private List<String> resolve(String coordinates) {
        return resolve(library(coordinates).build());
    }

    private List<String> resolve(Library library) {
        return manager.resolveTransitiveLibraries(library).stream().map(Library::toString).collect(Collectors.toList());
    }

    private TransitiveDependencyException resolveFails(String coordinates) {
        return assertThrows(TransitiveDependencyException.class, () -> resolve(coordinates));
    }

    @Test
    public void resolvesDirectDependencies() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0"), dependency("com.example:b:2.0")))
            .artifact("com.example:a:1.0")
            .artifact("com.example:b:2.0");

        assertEquals(List.of("com.example:a:1.0", "com.example:b:2.0"), resolve("com.example:root:1.0"));
        assertEquals(1, manager.log().count(LogLevel.INFO, "Resolved 2 transitive dependencies for com.example:root:1.0"));
    }

    @Test
    public void followsCompileAndRuntimeScopesOnly() {
        repo.artifact("com.example:root:1.0", dependencies(
                dependency("com.example:compile:1.0", scope("compile")),
                dependency("com.example:runtime:1.0", scope("runtime")),
                dependency("com.example:test:1.0", scope("test")),
                dependency("com.example:provided:1.0", scope("provided")),
                dependency("com.example:system:1.0", scope("system"), "<systemPath>/opt/x.jar</systemPath>"),
                dependency("com.example:import:1.0", scope("import"))))
            .artifact("com.example:compile:1.0")
            .artifact("com.example:runtime:1.0");

        assertEquals(List.of("com.example:compile:1.0", "com.example:runtime:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void skipsOptionalDependencies() {
        repo.artifact("com.example:root:1.0", dependencies(
                dependency("com.example:direct-optional:1.0", "<optional>true</optional>"),
                dependency("com.example:a:1.0", "<optional>false</optional>")))
            .artifact("com.example:a:1.0", dependencies(dependency("com.example:transitive-optional:1.0", "<optional>true</optional>")));

        assertEquals(List.of("com.example:a:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void appliesTransitiveScopeRules() {
        repo.artifact("com.example:root:1.0", dependencies(
                dependency("com.example:runtime:1.0", scope("runtime")),
                dependency("com.example:compile:1.0")))
            .artifact("com.example:runtime:1.0", dependencies(dependency("com.example:runtime-child:1.0", scope("compile"))))
            .artifact("com.example:runtime-child:1.0")
            .artifact("com.example:compile:1.0", dependencies(
                dependency("com.example:compile-runtime:1.0", scope("runtime")),
                dependency("com.example:compile-test:1.0", scope("test")),
                dependency("com.example:compile-provided:1.0", scope("provided"))))
            .artifact("com.example:compile-runtime:1.0");

        assertEquals(List.of("com.example:runtime-child:1.0", "com.example:runtime:1.0", "com.example:compile-runtime:1.0", "com.example:compile:1.0"),
            resolve("com.example:root:1.0"));
        assertEquals(1, manager.log().count(LogLevel.DEBUG, "+- com.example:runtime-child:1.0 (runtime)"));
    }

    @Test
    public void inheritsFromParent() {
        repo.pom("com.example:parent:3", "<groupId>com.example</groupId><artifactId>parent</artifactId><version>3</version><packaging>pom</packaging>"
                + "<properties><lib.version>1.5</lib.version><other.version>9</other.version></properties>"
                + management(dependency("com.example:managed:${lib.version}"))
                + dependencies(dependency("com.example:inherited:${other.version}")))
            .artifact("com.example:root:1.0", parent("com.example:parent:3")
                + "<properties><other.version>2.0</other.version></properties>"
                + dependencies(dependency("com.example:managed")))
            .artifact("com.example:managed:1.5")
            .artifact("com.example:inherited:2.0")
            .artifact("com.example:inherited:9");

        // The child POM leaves out its group ID and version: both are inherited, as
        // are the parent's dependencies (after the child's own ones).
        repo.pom("com.example:child:3", parent("com.example:parent:3") + "<artifactId>child</artifactId>"
            + dependencies(dependency("com.example:sibling:${project.version}")))
            .artifact("com.example:sibling:3");

        assertEquals(List.of("com.example:managed:1.5", "com.example:inherited:2.0"), resolve("com.example:root:1.0"));
        assertEquals(List.of("com.example:sibling:3", "com.example:inherited:9"), resolve("com.example:child:3"));
    }

    @Test
    public void interpolatesProjectParentAndNestedProperties() {
        repo.pom("com.example:parent:7", "<groupId>com.example</groupId><artifactId>parent</artifactId><version>7</version>")
            .artifact("com.example:root:1.0", parent("com.example:parent:7")
                + "<properties><outer>${middle}</outer><middle>${inner}-x</middle><inner>4.2</inner></properties>"
                + dependencies(
                    dependency("${project.groupId}:same-version:${project.version}"),
                    dependency("${pom.groupId}:parent-version:${parent.version}"),
                    dependency("com.example:project-parent-version:${project.parent.version}"),
                    dependency("com.example:nested:${outer}")))
            .artifact("com.example:same-version:1.0")
            .artifact("com.example:parent-version:7")
            .artifact("com.example:project-parent-version:7")
            .artifact("com.example:nested:4.2-x");

        assertEquals(List.of("com.example:same-version:1.0", "com.example:parent-version:7", "com.example:project-parent-version:7", "com.example:nested:4.2-x"),
            resolve("com.example:root:1.0"));
    }

    @Test
    public void unresolvedPropertyThrows() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:${undefined.version}")));

        TransitiveDependencyException e = resolveFails("com.example:root:1.0");
        assertTrue(e.getMessage().contains("unresolved property"), e.getMessage());
        assertTrue(e.getMessage().contains("excludeTransitiveDependency(\"com.example\", \"a\")"), e.getMessage());
    }

    @Test
    public void environmentAndSystemPropertiesAreNotInterpolated() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:${env.HOME}")));
        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("unresolved property"));

        repo.artifact("com.example:root:2.0", dependencies(dependency("com.example:a:${java.version}")));
        assertTrue(resolveFails("com.example:root:2.0").getMessage().contains("unresolved property"));
    }

    @Test
    public void recursivePropertiesThrow() {
        repo.artifact("com.example:root:1.0", "<properties><a>${b}</a><b>${a}</b></properties>" + dependencies(dependency("com.example:a:${a}")));

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("unresolved property"));
    }

    @Test
    public void exponentialPropertyExpansionFailsFast() {
        repo.artifact("com.example:root:1.0", fanOutProperties("x", 16) + dependencies(dependency("com.example:a:${p16}")));

        TransitiveDependencyException e = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> resolveFails("com.example:root:1.0"));
        assertTrue(e.getMessage().contains("interpolating"), e.getMessage());
    }

    @Test
    public void overlongInterpolatedValueThrows() {
        repo.artifact("com.example:root:1.0", "<properties><p0>" + "x".repeat(1000) + "</p0><p1>" + "${p0}".repeat(5) + "</p1></properties>"
            + dependencies(dependency("com.example:a:${p1}")));

        TransitiveDependencyException e = resolveFails("com.example:root:1.0");
        assertTrue(e.getMessage().contains("more than 4096 characters"), e.getMessage());
    }

    @Test
    public void exponentialExpansionOfEmptyPropertiesFailsFast() {
        // 15 levels so that every reference resolves within the nesting limit
        repo.artifact("com.example:root:1.0", fanOutProperties("", 15) + dependencies(dependency("com.example:a:1${p15}")));

        TransitiveDependencyException e = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> resolveFails("com.example:root:1.0"));
        assertTrue(e.getMessage().contains("property lookups"), e.getMessage());
    }

    /**
     * Properties p1 to p{@code levels} that each reference the previous one
     * 50 times, so that {@code ${p16}} would expand to 50^16 copies of
     * {@code p0}.
     */
    private static String fanOutProperties(String p0, int levels) {
        StringBuilder properties = new StringBuilder("<properties><p0>").append(p0).append("</p0>");
        for (int i = 1; i <= levels; i++) {
            properties.append("<p").append(i).append('>').append(("${p" + (i - 1) + '}').repeat(50)).append("</p").append(i).append('>');
        }
        return properties.append("</properties>").toString();
    }

    @Test
    public void unresolvedPropertyOfSkippedDependencyIsIgnored() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:test:${undefined}", scope("test"))));

        assertEquals(List.of(), resolve("com.example:root:1.0"));
    }

    @Test
    public void missingVersionThrows() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:unmanaged")));

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("has no version"));
    }

    @Test
    public void importsBomsLikeJackson() {
        // jackson-databind -> parent jackson-base -> imports jackson-bom (which has its own
        // parent and properties) -> managed versions for jackson-core and jackson-annotations.
        repo.pom("com.fasterxml:oss-parent:58", "<groupId>com.fasterxml</groupId><artifactId>oss-parent</artifactId><version>58</version><packaging>pom</packaging>"
                + management(dependency("org.junit:junit-bom:5.10.1", "<type>pom</type>", scope("import"))))
            .pom("org.junit:junit-bom:5.10.1", "<groupId>org.junit</groupId><artifactId>junit-bom</artifactId><version>5.10.1</version><packaging>pom</packaging>"
                + management(dependency("org.junit.jupiter:junit-jupiter:5.10.1")))
            .pom("com.fasterxml.jackson:jackson-parent:2.17", parent("com.fasterxml:oss-parent:58")
                + "<groupId>com.fasterxml.jackson</groupId><artifactId>jackson-parent</artifactId><version>2.17</version><packaging>pom</packaging>")
            .pom("com.fasterxml.jackson:jackson-bom:2.17.0", parent("com.fasterxml.jackson:jackson-parent:2.17")
                + "<artifactId>jackson-bom</artifactId><version>2.17.0</version><packaging>pom</packaging>"
                + "<properties><jackson.version>2.17.0</jackson.version>"
                + "<jackson.version.annotations>${jackson.version}</jackson.version.annotations>"
                + "<jackson.version.core>${jackson.version}</jackson.version.core></properties>"
                + management(
                    dependency("com.fasterxml.jackson.core:jackson-annotations:${jackson.version.annotations}"),
                    dependency("com.fasterxml.jackson.core:jackson-core:${jackson.version.core}"),
                    dependency("com.fasterxml.jackson.core:jackson-databind:${jackson.version}")))
            .pom("com.fasterxml.jackson:jackson-base:2.17.0", parent("com.fasterxml.jackson:jackson-bom:2.17.0")
                + "<artifactId>jackson-base</artifactId><packaging>pom</packaging>"
                + management(dependency("com.fasterxml.jackson:jackson-bom:${jackson.version}", "<type>pom</type>", scope("import")))
                + dependencies(dependency("org.junit.jupiter:junit-jupiter", scope("test"))))
            .artifact("com.fasterxml.jackson.core:jackson-databind:2.17.0", parent("com.fasterxml.jackson:jackson-base:2.17.0")
                + "<packaging>bundle</packaging>"
                + dependencies(
                    dependency("com.fasterxml.jackson.core:jackson-annotations"),
                    dependency("com.fasterxml.jackson.core:jackson-core")))
            .artifact("com.fasterxml.jackson.core:jackson-annotations:2.17.0")
            .artifact("com.fasterxml.jackson.core:jackson-core:2.17.0");

        assertEquals(List.of("com.fasterxml.jackson.core:jackson-annotations:2.17.0", "com.fasterxml.jackson.core:jackson-core:2.17.0"),
            resolve("com.fasterxml.jackson.core:jackson-databind:2.17.0"));
    }

    @Test
    public void managedScopeAndExclusionsAreApplied() {
        repo.artifact("com.example:root:1.0", management(
                    dependency("com.example:a:1.0", exclusion("com.example", "hidden")),
                    dependency("com.example:test-only:1.0", scope("test")))
                + dependencies(dependency("com.example:a"), dependency("com.example:test-only")))
            .artifact("com.example:a:1.0", dependencies(dependency("com.example:hidden:1.0"), dependency("com.example:visible:1.0")))
            .artifact("com.example:visible:1.0");

        assertEquals(List.of("com.example:visible:1.0", "com.example:a:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void builderExclusionsWithWildcards() {
        repo.artifact("com.example:root:1.0", dependencies(
                dependency("com.example:a:1.0"),
                dependency("com.example.excluded:x:1.0"),
                dependency("org.other:y:1.0")))
            .artifact("com.example:a:1.0", dependencies(dependency("com.example:b:1.0"), dependency("org.other:z:1.0")))
            .artifact("com.example:b:1.0", dependencies(dependency("com.example.excluded:deep:1.0")));

        Library library = library("com.example:root:1.0")
            .excludeTransitiveDependency("com{}example{}excluded", "*")
            .excludeTransitiveDependency("*", "y")
            .excludeTransitiveDependency("org.other", "z")
            .build();
        assertEquals(List.of("com.example:b:1.0", "com.example:a:1.0"), resolve(library));

        Library everything = library("com.example:root:1.0").excludeTransitiveDependency("*", "*").build();
        assertEquals(List.of(), resolve(everything));
    }

    @Test
    public void builderExclusionRemovesSubtree() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0"), dependency("com.example:c:1.0")))
            .artifact("com.example:a:1.0", dependencies(dependency("com.example:only-through-a:1.0")))
            .artifact("com.example:c:1.0");

        assertEquals(List.of("com.example:c:1.0"), resolve(library("com.example:root:1.0").excludeTransitiveDependency("com.example", "a").build()));
    }

    @Test
    public void pomExclusionsAreInheritedDownThePath() {
        repo.artifact("com.example:root:1.0", dependencies(
                dependency("com.example:a:1.0", exclusion("com.example", "d")),
                dependency("com.example:wild:1.0", exclusion("*", "*"))))
            .artifact("com.example:a:1.0", dependencies(dependency("com.example:b:1.0")))
            .artifact("com.example:b:1.0", dependencies(dependency("com.example:c:1.0")))
            .artifact("com.example:c:1.0", dependencies(dependency("com.example:d:1.0"), dependency("com.example:e:1.0")))
            .artifact("com.example:e:1.0")
            .artifact("com.example:wild:1.0", dependencies(dependency("org.any:thing:1.0")));

        assertEquals(List.of("com.example:e:1.0", "com.example:c:1.0", "com.example:b:1.0", "com.example:a:1.0", "com.example:wild:1.0"),
            resolve("com.example:root:1.0"));
    }

    @Test
    public void nearestVersionWins() {
        // root -> d -> e -> c:2 is declared first but root -> b -> c:1 is nearer.
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:d:1.0"), dependency("com.example:b:1.0")))
            .artifact("com.example:d:1.0", dependencies(dependency("com.example:e:1.0")))
            .artifact("com.example:e:1.0", dependencies(dependency("com.example:c:2.0")))
            .artifact("com.example:b:1.0", dependencies(dependency("com.example:c:1.0")))
            .artifact("com.example:c:1.0");

        List<String> resolved = resolve("com.example:root:1.0");
        assertEquals(List.of("com.example:e:1.0", "com.example:d:1.0", "com.example:c:1.0", "com.example:b:1.0"), resolved);
    }

    @Test
    public void firstDeclarationWinsAtTheSameDepth() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0"), dependency("com.example:b:1.0")))
            .artifact("com.example:a:1.0", dependencies(dependency("com.example:c:1.0")))
            .artifact("com.example:b:1.0", dependencies(dependency("com.example:c:2.0")))
            .artifact("com.example:c:1.0");

        assertEquals(List.of("com.example:c:1.0", "com.example:a:1.0", "com.example:b:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void rootDependencyManagementDoesNotOverrideTransitiveVersionsOrScopes() {
        repo.artifact("com.example:root:1.0", management(dependency("com.example:c:3.0"), dependency("com.example:d:3.0", scope("test")))
                + dependencies(dependency("com.example:a:1.0")))
            .artifact("com.example:a:1.0", management(dependency("com.example:c:2.0"))
                + dependencies(dependency("com.example:c:1.0"), dependency("com.example:d:1.0")))
            .artifact("com.example:c:1.0")
            .artifact("com.example:d:1.0");

        assertEquals(List.of("com.example:c:1.0", "com.example:d:1.0", "com.example:a:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void rootDependencyManagementFillsMissingTransitiveVersions() {
        repo.artifact("com.example:root:1.0", management(dependency("com.example:c:3.0"), dependency("com.example:d:3.0"))
                + dependencies(dependency("com.example:a:1.0")))
            .artifact("com.example:a:1.0", management(dependency("com.example:d:2.0"))
                + dependencies(dependency("com.example:c"), dependency("com.example:d")))
            .artifact("com.example:c:3.0")
            .artifact("com.example:d:2.0");

        // The transitive POM's own management comes first, the root's only fills gaps.
        assertEquals(List.of("com.example:c:3.0", "com.example:d:2.0", "com.example:a:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void honorsClassifiers() {
        repo.artifact("com.example:root:1.0", dependencies(
                dependency("com.example:native:1.0"),
                dependency("com.example:native:1.0", "<classifier>linux-x86_64</classifier>"),
                dependency("com.example:tests:1.0", "<type>test-jar</type>"),
                dependency("com.example:android:1.0", "<type>aar</type>")))
            .artifact("com.example:native:1.0")
            .jar("com.example:native:1.0:linux-x86_64", TestJars.simpleJar("linux"));

        List<Library> resolved = manager.resolveTransitiveLibraries(library("com.example:root:1.0").build());
        assertEquals(List.of("com.example:native:1.0", "com.example:native:1.0:linux-x86_64"),
            resolved.stream().map(Library::toString).collect(Collectors.toList()));
        assertEquals("linux-x86_64", resolved.get(1).getClassifier());
        assertTrue(resolved.get(1).getPath().endsWith("native-1.0-linux-x86_64.jar"));
    }

    @Test
    public void followsOneRelocation() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:old:1.0")))
            .pom("com.example:old:1.0", "<groupId>com.example</groupId><artifactId>old</artifactId><version>1.0</version>"
                + "<distributionManagement><relocation><groupId>com.example.moved</groupId><artifactId>new</artifactId>"
                + "<message>moved</message></relocation></distributionManagement>")
            .artifact("com.example.moved:new:1.0", dependencies(dependency("com.example:after-move:1.0")))
            .artifact("com.example:after-move:1.0");

        assertEquals(List.of("com.example:after-move:1.0", "com.example.moved:new:1.0"), resolve("com.example:root:1.0"));
        assertEquals(1, manager.log().count(LogLevel.WARN, "com.example:old:1.0 required by com.example:root:1.0 has been relocated to com.example.moved:new:1.0"));
    }

    @Test
    public void relocationChainThrows() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:old:1.0")))
            .pom("com.example:old:1.0", "<groupId>com.example</groupId><artifactId>old</artifactId><version>1.0</version>"
                + "<distributionManagement><relocation><artifactId>newer</artifactId></relocation></distributionManagement>")
            .pom("com.example:newer:1.0", "<groupId>com.example</groupId><artifactId>newer</artifactId><version>1.0</version>"
                + "<distributionManagement><relocation><artifactId>newest</artifactId></relocation></distributionManagement>");

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("relocated more than once"));
    }

    @Test
    public void cyclesTerminate() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0")))
            .artifact("com.example:a:1.0", dependencies(dependency("com.example:root:1.0"), dependency("com.example:b:1.0")))
            .artifact("com.example:b:1.0", dependencies(dependency("com.example:a:1.0")));

        assertEquals(List.of("com.example:b:1.0", "com.example:a:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void parentCycleThrows() {
        repo.pom("com.example:p1:1", parent("com.example:p2:1") + "<artifactId>p1</artifactId>")
            .pom("com.example:p2:1", parent("com.example:p1:1") + "<artifactId>p2</artifactId>")
            .artifact("com.example:root:1.0", parent("com.example:p1:1"));

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("cycle"));
    }

    @Test
    public void bomImportCycleThrows() {
        repo.pom("com.example:bom-a:1", "<groupId>com.example</groupId><artifactId>bom-a</artifactId><version>1</version>"
                + management(dependency("com.example:bom-b:1", "<type>pom</type>", scope("import"))))
            .pom("com.example:bom-b:1", "<groupId>com.example</groupId><artifactId>bom-b</artifactId><version>1</version>"
                + management(dependency("com.example:bom-a:1", "<type>pom</type>", scope("import"))))
            .artifact("com.example:root:1.0", management(dependency("com.example:bom-a:1", "<type>pom</type>", scope("import"))));

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("BOM import cycle"));
    }

    @Test
    public void depthLimitThrows() {
        String previous = "com.example:root:1.0";
        for (int i = 1; i <= DependencyResolver.MAX_DEPTH + 1; i++) {
            String next = "com.example:level" + i + ":1.0";
            repo.artifact(previous, dependencies(dependency(next)));
            previous = next;
        }
        repo.artifact(previous);

        TransitiveDependencyException e = resolveFails("com.example:root:1.0");
        assertTrue(e.getMessage().contains("deeper than " + DependencyResolver.MAX_DEPTH), e.getMessage());
        assertEquals(DependencyResolver.MAX_DEPTH + 2, e.getDependencyPath().size());
    }

    @Test
    public void artifactLimitThrows() {
        List<String> dependencies = new ArrayList<>();
        for (int i = 0; i <= DependencyResolver.MAX_ARTIFACTS; i++) {
            dependencies.add(dependency("com.example:dep" + i + ":1.0"));
            repo.artifact("com.example:dep" + i + ":1.0");
        }
        repo.artifact("com.example:root:1.0", dependencies(dependencies.toArray(new String[0])));

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("more than " + DependencyResolver.MAX_ARTIFACTS));
    }

    @Test
    public void parentChainLimitThrows() {
        repo.artifact("com.example:root:1.0", parent("com.example:p0:1"));
        for (int i = 0; i < ModelBuilder.MAX_CHAIN_LENGTH; i++) {
            repo.pom("com.example:p" + i + ":1", parent("com.example:p" + (i + 1) + ":1") + "<artifactId>p" + i + "</artifactId>");
        }
        repo.pom("com.example:p" + ModelBuilder.MAX_CHAIN_LENGTH + ":1", "<groupId>com.example</groupId><artifactId>last</artifactId><version>1</version>");

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("longer than " + ModelBuilder.MAX_CHAIN_LENGTH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[1.0,2.0)", "(,1.0]", "[1.0,)", "[1.0,1.5],[2.0,3.0)"})
    public void versionRangeThrowsActionableMessage(String range) {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0")))
            .artifact("com.example:a:1.0", dependencies(dependency("com.example:ranged:" + range)));

        TransitiveDependencyException e = resolveFails("com.example:root:1.0");
        assertTrue(e.getMessage().contains("version range"), e.getMessage());
        assertTrue(e.getMessage().contains("Library.Builder#excludeTransitiveDependency(\"com.example\", \"ranged\")"), e.getMessage());
        assertEquals("com.example:root:1.0", e.getDependencyPath().get(0));
        assertEquals("com.example:a:1.0", e.getDependencyPath().get(1));
        assertTrue(e.getDependencyPath().get(2).startsWith("com.example:ranged:"));
    }

    @Test
    public void exactVersionRangeIsAccepted() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:[1.0]")))
            .artifact("com.example:a:1.0");

        assertEquals(List.of("com.example:a:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void excludedRangeIsNotAnError() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:ranged:[1,2)")));

        assertEquals(List.of(), resolve(library("com.example:root:1.0").excludeTransitiveDependency("com.example", "ranged").build()));
    }

    @Test
    public void snapshotTransitiveDependencyThrows() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.1-SNAPSHOT")));

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("SNAPSHOT"));
    }

    @Test
    public void missingPomOfDependencyThrows() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:missing:1.0")));

        TransitiveDependencyException e = resolveFails("com.example:root:1.0");
        assertTrue(e.getMessage().contains("com.example:missing:1.0 couldn't be downloaded"), e.getMessage());
        assertInstanceOf(LibraryDownloadException.class, e.getCause());
        assertEquals(List.of("com.example:root:1.0", "com.example:missing:1.0"), e.getDependencyPath());
    }

    @Test
    public void missingRootPomThrows() {
        TransitiveDependencyException e = resolveFails("com.example:nothing:1.0");
        assertTrue(e.getMessage().contains("resolveTransitiveDependencies"), e.getMessage());
    }

    @Test
    public void rejectsDoctypeWithoutFetchingExternalEntities() throws IOException {
        Path secret = work.resolve("secret.txt");
        Files.writeString(secret, "TOP-SECRET-VALUE");
        try (TestHttpRepository http = new TestHttpRepository()) {
            repo.rawPom("com.example:root:1.0", "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE project [\n"
                + "  <!ENTITY file SYSTEM \"" + secret.toUri() + "\">\n"
                + "  <!ENTITY remote SYSTEM \"" + http.url() + "entity\">\n"
                + "  <!ENTITY % dtd SYSTEM \"" + http.url() + "dtd\"> %dtd;\n"
                + "]>\n"
                + "<project><modelVersion>4.0.0</modelVersion><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version>"
                + "<properties><leak>&file;&remote;</leak></properties>"
                + "<dependencies><dependency><groupId>com.example</groupId><artifactId>a</artifactId><version>${leak}</version></dependency></dependencies>"
                + "</project>");

            TransitiveDependencyException e = resolveFails("com.example:root:1.0");
            assertTrue(e.getMessage().contains("DOCTYPE"), e.getMessage());
            assertFalse(e.getMessage().contains("TOP-SECRET-VALUE"));
            assertEquals(0, http.totalRequests());
        }
    }

    @Test
    public void rejectsEntityExpansionBombs() {
        repo.rawPom("com.example:root:1.0", "<?xml version=\"1.0\"?>\n"
            + "<!DOCTYPE lolz [<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">]>\n"
            + "<project><artifactId>&lol2;</artifactId></project>");

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("DOCTYPE"));
    }

    @Test
    public void rejectsDeeplyNestedPoms() {
        repo.artifact("com.example:root:1.0", "<properties><deep>" + "<a>".repeat(10_000) + "x" + "</a>".repeat(10_000) + "</deep></properties>"
            + dependencies(dependency("com.example:a:${deep}")));

        TransitiveDependencyException e = resolveFails("com.example:root:1.0");
        assertTrue(e.getMessage().contains("is invalid"), e.getMessage());
        assertTrue(e.getMessage().contains("maxElementDepth"), e.getMessage());
    }

    @Test
    public void rejectsOversizedPoms() {
        repo.pom("com.example:root:1.0", "<groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version>"
            + "<!--" + "x".repeat(DependencyResolver.MAX_POM_SIZE) + "-->");

        TransitiveDependencyException e = resolveFails("com.example:root:1.0");
        LibraryDownloadException cause = assertInstanceOf(LibraryDownloadException.class, e.getCause());
        assertTrue(cause.getAttempts().get(0).reason().contains("maximum download size"), cause.getMessage());
    }

    @Test
    public void rejectsNonPomXml() {
        repo.rawPom("com.example:root:1.0", "<html><body>Not a POM</body></html>");

        assertTrue(resolveFails("com.example:root:1.0").getMessage().contains("not a POM"));
    }

    @Test
    public void acceptsPomsWithoutNamespaceAndWithPrefix() {
        repo.rawPom("com.example:root:1.0", "<project><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version>"
                + dependencies(dependency("com.example:a:1.0")) + "</project>")
            .rawPom("com.example:a:1.0", "<m:project xmlns:m=\"http://maven.apache.org/POM/4.0.0\"><m:groupId>com.example</m:groupId>"
                + "<m:artifactId>a</m:artifactId><m:version>1.0</m:version><m:dependencies><m:dependency><m:groupId>com.example</m:groupId>"
                + "<m:artifactId>b</m:artifactId><m:version>1.0</m:version></m:dependency></m:dependencies></m:project>")
            .artifact("com.example:b:1.0");

        assertEquals(List.of("com.example:b:1.0", "com.example:a:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void ignoresBuildPluginDependencies() {
        repo.artifact("com.example:root:1.0", "<build><plugins><plugin><artifactId>maven-shade-plugin</artifactId>"
            + dependencies(dependency("com.example:plugin-dep:1.0")) + "</plugin></plugins></build>");

        assertEquals(List.of(), resolve("com.example:root:1.0"));
    }

    @Test
    public void appliesOnlyActiveByDefaultProfiles() {
        repo.artifact("com.example:root:1.0", "<properties><dep.version>1.0</dep.version></properties>"
                + "<profiles>"
                + "<profile><id>default</id><activation><activeByDefault>true</activeByDefault></activation>"
                + "<properties><dep.version>2.0</dep.version></properties>"
                + dependencies(dependency("com.example:from-default-profile:${dep.version}")) + "</profile>"
                + "<profile><id>jdk</id><activation><jdk>[17,)</jdk></activation>"
                + dependencies(dependency("com.example:from-jdk-profile:1.0")) + "</profile>"
                + "<profile><id>property</id><activation><property><name>!skip</name></property></activation>"
                + dependencies(dependency("com.example:from-property-profile:1.0")) + "</profile>"
                + "</profiles>")
            .artifact("com.example:from-default-profile:2.0");

        assertEquals(List.of("com.example:from-default-profile:2.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void followsPomOnlyDependencies() {
        repo.artifact("com.example:root:1.0", dependencies(
                dependency("com.example:aggregator:1.0"),
                dependency("com.example:typed-pom:1.0", "<type>pom</type>")))
            .pom("com.example:aggregator:1.0", "<groupId>com.example</groupId><artifactId>aggregator</artifactId><version>1.0</version>"
                + "<packaging>pom</packaging>" + dependencies(dependency("com.example:api:1.0")))
            .pom("com.example:typed-pom:1.0", "<groupId>com.example</groupId><artifactId>typed-pom</artifactId><version>1.0</version>"
                + dependencies(dependency("com.example:impl:1.0")))
            .artifact("com.example:api:1.0")
            .artifact("com.example:impl:1.0");

        assertEquals(List.of("com.example:api:1.0", "com.example:impl:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void transitiveLibrariesInheritRelocationsOnly() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0")))
            .artifact("com.example:a:1.0");

        Library root = library("com.example:root:1.0")
            .relocate("com{}example", "shaded{}example")
            .checksum(new byte[32])
            .url("https://example.com/root.jar")
            .resolveTransitiveDependencies(true)
            .build();
        Library a = manager.resolveTransitiveLibraries(root).get(0);

        assertEquals(List.of(new Relocation("com.example", "shaded.example")), List.copyOf(a.getRelocations()));
        assertFalse(a.hasChecksum());
        assertTrue(a.getUrls().isEmpty());
        assertFalse(a.isResolveTransitiveDependencies());
    }

    @Test
    public void cachesPomsWithRecordedChecksums() throws IOException {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0")))
            .artifact("com.example:a:1.0");

        assertEquals(List.of("com.example:a:1.0"), resolve("com.example:root:1.0"));
        Path cached = manager.saveDirectory().resolve("com/example/root/1.0/root-1.0.pom");
        assertTrue(Files.isRegularFile(cached));
        assertTrue(Files.isRegularFile(cached.resolveSibling("root-1.0.pom.sha256")));

        // Resolves offline from the cache.
        Files.delete(repo.file("com.example:root:1.0", "pom"));
        Files.delete(repo.file("com.example:a:1.0", "pom"));
        assertEquals(List.of("com.example:a:1.0"), resolve("com.example:root:1.0"));

        // A tampered cached POM is detected and downloaded again (and fails here).
        Files.writeString(cached, "<project/>");
        assertThrows(TransitiveDependencyException.class, () -> resolve("com.example:root:1.0"));
        assertEquals(1, manager.log().count(LogLevel.WARN, "doesn't match its recorded checksum"));
    }

    @Test
    public void verifiesPomsAgainstRepositoryChecksums() throws IOException {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0")));
        Path pom = repo.file("com.example:root:1.0", "pom");
        Files.writeString(pom.resolveSibling(pom.getFileName() + ".sha1"), "0000000000000000000000000000000000000000");

        TransitiveDependencyException e = resolveFails("com.example:root:1.0");
        LibraryDownloadException cause = assertInstanceOf(LibraryDownloadException.class, e.getCause());
        assertTrue(cause.getAttempts().get(0).reason().startsWith("checksum mismatch"), cause.getMessage());

        Files.writeString(pom.resolveSibling(pom.getFileName() + ".sha1"), TestJars.hex(TestJars.digest("SHA-1", Files.readAllBytes(pom))));
        repo.artifact("com.example:a:1.0");
        assertEquals(List.of("com.example:a:1.0"), resolve("com.example:root:1.0"));
    }

    @Test
    public void resolvesPomsOverHttp() {
        try (TestHttpRepository http = new TestHttpRepository()) {
            String pom = "<project><groupId>com.example</groupId><artifactId>root</artifactId><version>1.0</version>"
                + dependencies(dependency("com.example:a:1.0")) + "</project>";
            http.put("com/example/root/1.0/root-1.0.pom", pom.getBytes(StandardCharsets.UTF_8));
            http.put("com/example/a/1.0/a-1.0.pom", "<project><groupId>com.example</groupId><artifactId>a</artifactId><version>1.0</version></project>"
                .getBytes(StandardCharsets.UTF_8));

            TestLibraryManager remote = TestLibraryManager.create(work.resolve("remote"));
            remote.setAllowInsecureHttp(true);
            remote.addRepository(http.url());

            assertEquals(List.of("com.example:a:1.0"),
                remote.resolveTransitiveLibraries(library("com.example:root:1.0").build()).stream().map(Library::toString).collect(Collectors.toList()));
            assertEquals(0, remote.log().count(LogLevel.WARN, "No checksum available"));
        }
    }

    @Test
    public void loadLibraryLoadsTransitiveDependenciesFirst() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0")))
            .artifact("com.example:a:1.0", dependencies(dependency("com.example:b:1.0")))
            .artifact("com.example:b:1.0");

        manager.loadLibrary(library("com.example:root:1.0").resolveTransitiveDependencies(true).build());

        Path save = manager.saveDirectory();
        assertEquals(List.of(
            save.resolve("com/example/b/1.0/b-1.0.jar"),
            save.resolve("com/example/a/1.0/a-1.0.jar"),
            save.resolve("com/example/root/1.0/root-1.0.jar")
        ), manager.classpath());
    }

    @Test
    public void loadLibraryWithoutResolutionLoadsOnlyTheLibrary() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0")))
            .artifact("com.example:a:1.0");

        manager.loadLibrary(library("com.example:root:1.0").build());

        assertEquals(List.of(manager.saveDirectory().resolve("com/example/root/1.0/root-1.0.jar")), manager.classpath());
        assertFalse(Files.exists(manager.saveDirectory().resolve("com/example/root/1.0/root-1.0.pom")));
    }

    @Test
    public void explicitlyLoadedVersionWins() {
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:a:1.0")))
            .artifact("com.example:a:1.0")
            .artifact("com.example:a:2.0");

        manager.loadLibrary(library("com.example:a:2.0").build());
        manager.loadLibrary(library("com.example:root:1.0").resolveTransitiveDependencies(true).build());

        Path save = manager.saveDirectory();
        assertEquals(List.of(save.resolve("com/example/a/2.0/a-2.0.jar"), save.resolve("com/example/root/1.0/root-1.0.jar")), manager.classpath());
        assertEquals(1, manager.log().count(LogLevel.WARN, "Not loading com.example:a:1.0 because version 2.0 of com.example:a is already loaded"));
    }

    @Test
    public void loadsTransitiveClassesIntoIsolatedClassLoader() throws Exception {
        byte[] jar = TestJars.compiledJar(work, Map.of(
            "com.example.transitive.Greeter",
            "package com.example.transitive; public class Greeter { public static String greet() { return \"transitive\"; } }"
        ));
        repo.artifact("com.example:root:1.0", dependencies(dependency("com.example:middle:1.0")))
            .artifact("com.example:middle:1.0", dependencies(dependency("com.example:greeter:1.0", scope("runtime"))))
            .pom("com.example:greeter:1.0", "<groupId>com.example</groupId><artifactId>greeter</artifactId><version>1.0</version>")
            .jar("com.example:greeter:1.0", jar);

        try (IsolatedClassLoader loader = new IsolatedClassLoader()) {
            manager.loadLibrary(library("com.example:root:1.0").resolveTransitiveDependencies(true).build(), loader);

            assertEquals(3, loader.getURLs().length);
            assertEquals("transitive", loader.loadClass("com.example.transitive.Greeter").getMethod("greet").invoke(null));
            assertEquals(List.of(), manager.classpath());
        }
    }

    @Test
    public void resolvesJdaLikeGradlePom() {
        publishJdaFixture();

        List<String> all = resolve("net.dv8tion:JDA:5.0.0");
        assertEquals(List.of(
            "org.slf4j:slf4j-api:2.0.13",
            "com.neovisionaries:nv-websocket-client:2.14",
            "com.squareup.okio:okio-jvm:3.6.0",
            "org.jetbrains:annotations:13.0",
            "org.jetbrains.kotlin:kotlin-stdlib:1.8.21",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.21",
            "org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21",
            "com.squareup.okhttp3:okhttp:4.12.0",
            "club.minnced:opus-java-api:1.1.1",
            "club.minnced:opus-java-natives:1.1.1",
            "org.apache.commons:commons-collections4:4.4",
            "net.sf.trove4j:core:3.1.0",
            "com.fasterxml.jackson.core:jackson-core:2.17.0"
        ), all);

        List<String> withoutOpus = resolve(library("net.dv8tion:JDA:5.0.0").excludeTransitiveDependency("club{}minnced", "opus-java").build());
        assertFalse(withoutOpus.stream().anyMatch(name -> name.startsWith("club.minnced:")), withoutOpus.toString());
        assertEquals(all.size() - 2, withoutOpus.size());
    }

    /**
     * Hand-written fixture modeled on the Gradle-generated POMs of JDA 5 and
     * its dependencies: explicit compile and runtime scopes, Gradle's
     * exclusion style, a POM-packaged aggregator (opus-java) and okio's
     * Kotlin multiplatform redirection to okio-jvm.
     */
    private void publishJdaFixture() {
        String gradleHeader = "<!-- This module was also published with a richer model, Gradle metadata,  -->\n"
            + "<!-- which should be used instead. Do not delete the following line which  -->\n"
            + "<!-- is to indicate to Gradle or any Gradle module metadata file consumer  -->\n"
            + "<!-- that they should prefer consuming it instead. -->\n"
            + "<!-- do_not_remove: published-with-gradle-metadata -->\n";

        repo.artifact("net.dv8tion:JDA:5.0.0", gradleHeader
            + "<name>JDA</name><description>Java wrapper for the popular chat &amp; VOIP service</description>"
            + "<url>https://github.com/discord-jda/JDA</url>"
            + "<licenses><license><name>The Apache Software License, Version 2.0</name></license></licenses>"
            + "<dependencies>"
            + "<dependency><groupId>org.slf4j</groupId><artifactId>slf4j-api</artifactId><version>2.0.13</version><scope>compile</scope></dependency>"
            + "<dependency><groupId>com.neovisionaries</groupId><artifactId>nv-websocket-client</artifactId><version>2.14</version><scope>compile</scope></dependency>"
            + "<dependency><groupId>com.squareup.okhttp3</groupId><artifactId>okhttp</artifactId><version>4.12.0</version><scope>compile</scope></dependency>"
            + "<dependency><groupId>club.minnced</groupId><artifactId>opus-java</artifactId><version>1.1.1</version><scope>runtime</scope></dependency>"
            + "<dependency><groupId>org.apache.commons</groupId><artifactId>commons-collections4</artifactId><version>4.4</version><scope>runtime</scope></dependency>"
            + "<dependency><groupId>net.sf.trove4j</groupId><artifactId>core</artifactId><version>3.1.0</version><scope>runtime</scope></dependency>"
            + "<dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-core</artifactId><version>2.17.0</version><scope>runtime</scope>"
            + "<exclusions><exclusion><artifactId>*</artifactId><groupId>org.junit</groupId></exclusion></exclusions></dependency>"
            + "<dependency><groupId>com.google.crypto.tink</groupId><artifactId>tink</artifactId><version>1.14.1</version><scope>compile</scope><optional>true</optional></dependency>"
            + "</dependencies>");

        repo.artifact("org.slf4j:slf4j-api:2.0.13", parent("org.slf4j:slf4j-parent:2.0.13") + "<packaging>bundle</packaging>"
                + dependencies(dependency("junit:junit", scope("test"))))
            .pom("org.slf4j:slf4j-parent:2.0.13", parent("org.slf4j:slf4j-bom:2.0.13") + "<artifactId>slf4j-parent</artifactId><packaging>pom</packaging>"
                + "<properties><junit.version>4.13.1</junit.version></properties>"
                + management(dependency("junit:junit:${junit.version}")))
            .pom("org.slf4j:slf4j-bom:2.0.13", "<groupId>org.slf4j</groupId><artifactId>slf4j-bom</artifactId><version>2.0.13</version><packaging>pom</packaging>");

        repo.artifact("com.neovisionaries:nv-websocket-client:2.14", "<packaging>bundle</packaging>");

        repo.artifact("com.squareup.okhttp3:okhttp:4.12.0", gradleHeader + dependencies(
                "<dependency><groupId>com.squareup.okio</groupId><artifactId>okio</artifactId><version>3.6.0</version><scope>compile</scope></dependency>",
                "<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib-jdk8</artifactId><version>1.8.21</version><scope>compile</scope></dependency>"))
            .pom("com.squareup.okio:okio:3.6.0", gradleHeader + "<groupId>com.squareup.okio</groupId><artifactId>okio</artifactId><version>3.6.0</version>"
                + "<packaging>pom</packaging>"
                + dependencies("<dependency><groupId>com.squareup.okio</groupId><artifactId>okio-jvm</artifactId><version>3.6.0</version><scope>compile</scope></dependency>"))
            .artifact("com.squareup.okio:okio-jvm:3.6.0", gradleHeader + dependencies(
                "<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>1.9.10</version><scope>compile</scope></dependency>"))
            // okio-jvm's kotlin-stdlib 1.9.10 is one level deeper than kotlin-stdlib-jdk8's
            // kotlin-stdlib 1.8.21, so 1.8.21 wins (as it does with Maven).
            .artifact("org.jetbrains.kotlin:kotlin-stdlib-jdk8:1.8.21", dependencies(
                "<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>1.8.21</version><scope>compile</scope></dependency>",
                "<dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib-jdk7</artifactId><version>1.8.21</version><scope>compile</scope></dependency>"))
            .artifact("org.jetbrains.kotlin:kotlin-stdlib:1.8.21", dependencies(
                "<dependency><groupId>org.jetbrains</groupId><artifactId>annotations</artifactId><version>13.0</version><scope>compile</scope></dependency>"))
            .artifact("org.jetbrains.kotlin:kotlin-stdlib-jdk7:1.8.21")
            .artifact("org.jetbrains:annotations:13.0");

        repo.pom("club.minnced:opus-java:1.1.1", "<groupId>club.minnced</groupId><artifactId>opus-java</artifactId><version>1.1.1</version><packaging>pom</packaging>"
                + dependencies(
                    "<dependency><groupId>club.minnced</groupId><artifactId>opus-java-api</artifactId><version>1.1.1</version><scope>compile</scope></dependency>",
                    "<dependency><groupId>club.minnced</groupId><artifactId>opus-java-natives</artifactId><version>1.1.1</version><scope>compile</scope></dependency>"))
            .artifact("club.minnced:opus-java-api:1.1.1", dependencies(
                "<dependency><groupId>net.java.dev.jna</groupId><artifactId>jna</artifactId><version>4.4.0</version><scope>provided</scope></dependency>"))
            .artifact("club.minnced:opus-java-natives:1.1.1");

        repo.artifact("org.apache.commons:commons-collections4:4.4", parent("org.apache.commons:commons-parent:48")
                + dependencies(dependency("junit:junit", scope("test")), dependency("org.easymock:easymock:4.0.2", scope("test"))))
            .pom("org.apache.commons:commons-parent:48", parent("org.apache:apache:21") + "<groupId>org.apache.commons</groupId>"
                + "<artifactId>commons-parent</artifactId><version>48</version><packaging>pom</packaging>"
                + management(dependency("junit:junit:4.12")))
            .pom("org.apache:apache:21", "<groupId>org.apache</groupId><artifactId>apache</artifactId><version>21</version><packaging>pom</packaging>");

        repo.artifact("net.sf.trove4j:core:3.1.0");

        repo.artifact("com.fasterxml.jackson.core:jackson-core:2.17.0",
            dependencies(dependency("org.junit.jupiter:junit-jupiter:5.10.1", scope("test"))));
    }
}

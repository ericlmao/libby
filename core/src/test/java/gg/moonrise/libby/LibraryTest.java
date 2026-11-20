package gg.moonrise.libby;

import gg.moonrise.libby.relocation.Relocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class LibraryTest {
    private static final byte[] CHECKSUM = new byte[32];

    private static Library.Builder valid() {
        return Library.builder().groupId("com.example").artifactId("lib").version("1.0.0");
    }

    @Test
    public void buildsMavenPathFromCoordinates() {
        Library library = valid().classifier("all").build();

        assertEquals("com/example/lib/1.0.0/lib-1.0.0-all.jar", library.getPath());
        assertEquals("com.example:lib:1.0.0:all", library.toString());
    }

    @Test
    public void replacesBracesInGroupId() {
        Library library = valid().groupId("com{}example").build();

        assertEquals("com.example", library.getGroupId());
    }

    @Test
    public void acceptsCommonVersionFormats() {
        assertEquals("1.0.0+build.5", valid().version("1.0.0+build.5").build().getVersion());
        assertEquals("2.0-SNAPSHOT", valid().version("2.0-SNAPSHOT").build().getVersion());
        assertEquals("1.21-R0.1_beta", valid().version("1.21-R0.1_beta").build().getVersion());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "..", ".", ".hidden", "trailing.", "a..b", "a/b", "a\\b", "a b", "a:b", "{}{}", "%2e%2e"})
    public void rejectsUnsafeGroupIds(String groupId) {
        assertThrows(IllegalArgumentException.class, () -> valid().groupId(groupId).build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "..", "../../evil", "a/b", ".x", "x\u0000"})
    public void rejectsUnsafeArtifactIdsVersionsAndClassifiers(String value) {
        assertThrows(IllegalArgumentException.class, () -> valid().artifactId(value).build());
        assertThrows(IllegalArgumentException.class, () -> valid().version(value).build());
        assertThrows(IllegalArgumentException.class, () -> valid().classifier(value).build());
    }

    @Test
    public void rejectsMissingCoordinates() {
        assertThrows(NullPointerException.class, () -> Library.builder().artifactId("lib").version("1").build());
        assertThrows(NullPointerException.class, () -> Library.builder().groupId("g").version("1").build());
        assertThrows(NullPointerException.class, () -> Library.builder().groupId("g").artifactId("lib").build());
    }

    @Test
    public void rejectsChecksumsOfTheWrongLength() {
        assertThrows(IllegalArgumentException.class, () -> valid().checksum(new byte[20]).build());
        assertThrows(IllegalArgumentException.class, () -> valid().checksum("not base64!").build());
    }

    @Test
    public void copiesChecksumOnSetAndGet() {
        byte[] input = CHECKSUM.clone();
        Library.Builder builder = valid().checksum(input);
        input[0] = 1;
        Library library = builder.build();

        byte[] checksum = library.getChecksum();
        assertNotNull(checksum);
        assertEquals(0, checksum[0]);
        checksum[1] = 1;
        assertArrayEquals(CHECKSUM, library.getChecksum());
        assertNotSame(library.getChecksum(), library.getChecksum());
    }

    @Test
    public void decodesBase64Checksum() {
        Library library = valid().checksum(Base64.getEncoder().encodeToString(CHECKSUM)).build();

        assertTrue(library.hasChecksum());
        assertArrayEquals(CHECKSUM, library.getChecksum());
    }

    @Test
    public void collectionsAreImmutableSnapshots() {
        Library.Builder builder = valid().url("https://example.com/lib.jar").relocate("com{}example", "shaded{}example");
        Library library = builder.build();
        builder.url("https://example.com/other.jar").relocate("a", "b");

        assertEquals(List.of("https://example.com/lib.jar"), new ArrayList<>(library.getUrls()));
        assertEquals(1, library.getRelocations().size());
        assertThrows(UnsupportedOperationException.class, () -> library.getUrls().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> library.getRelocations().clear());
    }

    @Test
    public void relocatedPathDependsOnRules() {
        Library plain = valid().build();
        Library relocated = valid().relocate("com{}example", "shaded").build();
        Library sameRules = valid().relocate(new Relocation("com.example", "shaded")).build();
        Library otherRules = valid().relocate("com{}example", "other").build();
        Library reorderedIncludes = valid().relocate(new Relocation("com.example", "shaded", List.of("b", "a"), null)).build();
        Library sortedIncludes = valid().relocate(new Relocation("com.example", "shaded", List.of("a", "b"), null)).build();

        assertNull(plain.getRelocatedPath());
        assertNotNull(relocated.getRelocatedPath());
        assertTrue(relocated.getRelocatedPath().matches("\\.relocated/com/example/lib/1\\.0\\.0/lib-1\\.0\\.0-relocated-[0-9a-f]{8}\\.jar"),
            relocated.getRelocatedPath());
        assertEquals(relocated.getRelocatedPath(), sameRules.getRelocatedPath());
        assertNotEquals(relocated.getRelocatedPath(), otherRules.getRelocatedPath());
        assertEquals(sortedIncludes.getRelocatedPath(), reorderedIncludes.getRelocatedPath());
    }

    @Test
    public void relocatedPathCantCollideWithAnArtifactPath() {
        Library relocated = valid().relocate("com{}example", "shaded").build();
        String fingerprint = relocated.getRelocationFingerprint();
        assertNotNull(fingerprint);

        Library sameName = valid().classifier("relocated-" + fingerprint).build();
        assertNotEquals(relocated.getRelocatedPath(), sameName.getPath());
        assertTrue(sameName.getPath().endsWith("/lib-1.0.0-relocated-" + fingerprint + ".jar"), sameName.getPath());
        assertThrows(IllegalArgumentException.class, () -> valid().groupId(".relocated.com.example").build());
    }

    @Test
    public void equalsAndHashCodeUseAllFields() {
        Library a = valid().checksum(CHECKSUM).relocate("a", "b").build();
        Library b = valid().checksum(CHECKSUM.clone()).relocate(new Relocation("a", "b")).build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, valid().relocate("a", "b").build());
        assertNotEquals(a, valid().version("1.0.1").checksum(CHECKSUM).relocate("a", "b").build());
        assertNotEquals(a, valid().classifier("x").checksum(CHECKSUM).relocate("a", "b").build());
    }

    @Test
    public void transitiveDependencySettings() {
        Library plain = valid().build();
        Library resolving = valid()
            .resolveTransitiveDependencies(true)
            .excludeTransitiveDependency("club{}minnced", "opus-java")
            .excludeTransitiveDependency(new ExcludedDependency("*", "*"))
            .build();

        assertFalse(plain.isResolveTransitiveDependencies());
        assertEquals(List.of(), plain.getExcludedTransitiveDependencies());
        assertTrue(resolving.isResolveTransitiveDependencies());
        assertEquals(List.of(new ExcludedDependency("club.minnced", "opus-java"), new ExcludedDependency("*", "*")),
            resolving.getExcludedTransitiveDependencies());
        assertThrows(UnsupportedOperationException.class, () -> resolving.getExcludedTransitiveDependencies().clear());
        assertNotEquals(plain, valid().resolveTransitiveDependencies(true).build());
        assertNotEquals(valid().excludeTransitiveDependency("a", "b").build(), plain);
    }

    @Test
    public void excludedDependencyMatchesWildcards() {
        assertTrue(new ExcludedDependency("com.example", "lib").matches("com.example", "lib"));
        assertFalse(new ExcludedDependency("com.example", "lib").matches("com.example", "other"));
        assertTrue(new ExcludedDependency("com.example", "*").matches("com.example", "anything"));
        assertFalse(new ExcludedDependency("com.example", "*").matches("com.example.sub", "anything"));
        assertTrue(new ExcludedDependency("*", "lib").matches("org.any", "lib"));
        assertTrue(new ExcludedDependency("*", "*").matches("org.any", "thing"));
        assertEquals("com.example:lib", new ExcludedDependency("com{}example", "lib").toString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "com/example", "com.*", "${group}", " "})
    public void rejectsInvalidExclusions(String value) {
        assertThrows(IllegalArgumentException.class, () -> new ExcludedDependency(value, "lib"));
        assertThrows(IllegalArgumentException.class, () -> new ExcludedDependency("com.example", value));
    }
}

package net.byteflux.libby.relocation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class RelocationTest {
    @Test
    public void replacesBracesInPatterns() {
        Relocation relocation = new Relocation("com{}example", "shaded{}com{}example");

        assertEquals("com.example", relocation.getPattern());
        assertEquals("shaded.com.example", relocation.getRelocatedPattern());
        assertTrue(relocation.getIncludes().isEmpty());
        assertTrue(relocation.getExcludes().isEmpty());
    }

    @Test
    public void copiesIncludesAndExcludes() {
        List<String> includes = new ArrayList<>(List.of("com.example.A"));
        Relocation relocation = new Relocation("com.example", "shaded", includes, null);
        includes.add("com.example.B");

        assertEquals(List.of("com.example.A"), new ArrayList<>(relocation.getIncludes()));
        assertThrows(UnsupportedOperationException.class, () -> relocation.getIncludes().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> relocation.getExcludes().add("x"));
    }

    @Test
    public void builderSnapshotsState() {
        Relocation.Builder builder = Relocation.builder().pattern("a").relocatedPattern("b").include("a.X").exclude("a.Y");
        Relocation relocation = builder.build();
        builder.include("a.Z");

        assertEquals(List.of("a.X"), new ArrayList<>(relocation.getIncludes()));
        assertEquals(List.of("a.Y"), new ArrayList<>(relocation.getExcludes()));
    }

    @Test
    public void equalsAndHashCode() {
        Relocation a = new Relocation("a", "b", List.of("a.X"), List.of());
        Relocation b = Relocation.builder().pattern("a").relocatedPattern("b").include("a.X").build();

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new Relocation("a", "c"));
    }

    @Test
    public void rejectsMissingPatterns() {
        assertThrows(NullPointerException.class, () -> Relocation.builder().pattern("a").build());
    }
}

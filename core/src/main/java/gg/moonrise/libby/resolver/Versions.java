package gg.moonrise.libby.resolver;

import org.jspecify.annotations.Nullable;

/**
 * Helpers for Maven version strings.
 */
final class Versions {
    private Versions() {
    }

    /**
     * Gets the exact version a version requirement refers to. A plain version
     * such as {@code 1.2} (a "soft" requirement) and a single-version range
     * such as {@code [1.2]} are exact, other ranges such as {@code [1,2)} or
     * {@code (,1]} are not.
     *
     * @param version the version requirement
     * @return the exact version, or null if the requirement is a range
     */
    static @Nullable String exact(String version) {
        String trimmed = version.strip();
        if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.indexOf(',') < 0) {
            String inner = trimmed.substring(1, trimmed.length() - 1).strip();
            return inner.isEmpty() || isRange(inner) ? null : inner;
        }

        return isRange(trimmed) ? null : trimmed;
    }

    /**
     * Gets whether a version is a snapshot version.
     *
     * @param version the version
     * @return true if the version is a {@code SNAPSHOT}
     */
    static boolean isSnapshot(String version) {
        return version.endsWith("-SNAPSHOT") || version.equals("SNAPSHOT");
    }

    /**
     * Gets whether a version requirement contains range syntax.
     *
     * @param version the version requirement
     * @return true if it contains a bracket, parenthesis or comma
     */
    private static boolean isRange(String version) {
        for (int i = 0; i < version.length(); i++) {
            char c = version.charAt(i);
            if (c == '[' || c == ']' || c == '(' || c == ')' || c == ',') {
                return true;
            }
        }

        return false;
    }
}

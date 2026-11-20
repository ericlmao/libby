package gg.moonrise.libby.resolver;

import gg.moonrise.libby.TestJars;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes POMs and jars into a Maven repository layout on disk, to be used
 * as a {@code file:} repository.
 */
final class FixtureRepository {
    private final Path root;

    FixtureRepository(Path root) {
        this.root = root;
    }

    String url() {
        return root.toUri().toString();
    }

    Path root() {
        return root;
    }

    /**
     * Publishes an artifact with a jar and a POM containing its coordinates
     * followed by {@code body}.
     */
    FixtureRepository artifact(String coordinates, String body) {
        String[] parts = coordinates.split(":");
        pom(coordinates, "<groupId>" + parts[0] + "</groupId><artifactId>" + parts[1] + "</artifactId><version>" + parts[2] + "</version>" + body);
        return jar(coordinates, TestJars.simpleJar(coordinates));
    }

    /**
     * Publishes an artifact with a jar and a POM without dependencies.
     */
    FixtureRepository artifact(String coordinates) {
        return artifact(coordinates, "");
    }

    /**
     * Publishes a POM whose {@code <project>} element contains {@code body}.
     */
    FixtureRepository pom(String coordinates, String body) {
        return rawPom(coordinates, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
            + " xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">\n"
            + "<modelVersion>4.0.0</modelVersion>\n" + body + "\n</project>\n");
    }

    /**
     * Publishes a POM file with exactly the given contents.
     */
    FixtureRepository rawPom(String coordinates, String xml) {
        write(file(coordinates, "pom"), xml.getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Publishes a jar, using the optional fourth coordinate as classifier.
     */
    FixtureRepository jar(String coordinates, byte[] jar) {
        write(file(coordinates, "jar"), jar);
        return this;
    }

    /**
     * Gets the path of an artifact file in the repository.
     */
    Path file(String coordinates, String extension) {
        String[] parts = coordinates.split(":");
        String name = parts[1] + '-' + parts[2] + (parts.length > 3 ? '-' + parts[3] : "") + '.' + extension;
        return root.resolve(parts[0].replace('.', '/') + '/' + parts[1] + '/' + parts[2] + '/' + name);
    }

    static String dependency(String coordinates, String... extra) {
        String[] parts = coordinates.split(":");
        StringBuilder xml = new StringBuilder("<dependency><groupId>").append(parts[0]).append("</groupId><artifactId>").append(parts[1]).append("</artifactId>");
        if (parts.length > 2 && !parts[2].isEmpty()) {
            xml.append("<version>").append(parts[2]).append("</version>");
        }
        for (String element : extra) {
            xml.append(element);
        }
        return xml.append("</dependency>").toString();
    }

    static String dependencies(String... dependencies) {
        return "<dependencies>" + String.join("", dependencies) + "</dependencies>";
    }

    static String management(String... dependencies) {
        return "<dependencyManagement>" + dependencies(dependencies) + "</dependencyManagement>";
    }

    static String scope(String scope) {
        return "<scope>" + scope + "</scope>";
    }

    static String exclusion(String groupId, String artifactId) {
        return "<exclusions><exclusion><groupId>" + groupId + "</groupId><artifactId>" + artifactId + "</artifactId></exclusion></exclusions>";
    }

    static String parent(String coordinates) {
        String[] parts = coordinates.split(":");
        return "<parent><groupId>" + parts[0] + "</groupId><artifactId>" + parts[1] + "</artifactId><version>" + parts[2]
            + "</version><relativePath>../parent/pom.xml</relativePath></parent>";
    }

    private static void write(Path file, byte[] bytes) {
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package gg.moonrise.libby;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

/**
 * Builds small jars for tests.
 */
public final class TestJars {
    private TestJars() {
    }

    /**
     * Builds a jar with a single text entry, unique per name.
     *
     * @param name content marker
     * @return jar bytes
     */
    public static byte[] simpleJar(String name) {
        return jar(Map.of("marker.txt", name.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Builds a jar containing the given entries.
     *
     * @param entries entry name to contents
     * @return jar bytes
     */
    public static byte[] jar(Map<String, byte[]> entries) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream out = new JarOutputStream(bytes)) {
            for (Map.Entry<String, byte[]> entry : new TreeMap<>(entries).entrySet()) {
                JarEntry jarEntry = new JarEntry(entry.getKey());
                jarEntry.setTime(0);
                out.putNextEntry(jarEntry);
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * Compiles Java sources targeting Java 17 and packages the classes in a jar.
     *
     * @param workDirectory scratch directory
     * @param sources       binary class name to source code
     * @return jar bytes
     */
    public static byte[] compiledJar(Path workDirectory, Map<String, String> sources) {
        try {
            Path sourceRoot = Files.createDirectories(workDirectory.resolve("src"));
            Path classRoot = Files.createDirectories(workDirectory.resolve("classes"));
            String[] args = new String[sources.size() + 4];
            args[0] = "--release";
            args[1] = "17";
            args[2] = "-d";
            args[3] = classRoot.toString();
            int i = 4;
            for (Map.Entry<String, String> source : sources.entrySet()) {
                Path file = sourceRoot.resolve(source.getKey().replace('.', '/') + ".java");
                Files.createDirectories(file.getParent());
                Files.writeString(file, source.getValue());
                args[i++] = file.toString();
            }

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            if (compiler.run(null, null, null, args) != 0) {
                throw new IllegalStateException("Compilation failed");
            }

            Map<String, byte[]> entries = new TreeMap<>();
            try (Stream<Path> files = Files.walk(classRoot)) {
                for (Path file : (Iterable<Path>) files.filter(Files::isRegularFile)::iterator) {
                    entries.put(classRoot.relativize(file).toString().replace('\\', '/'), Files.readAllBytes(file));
                }
            }
            return jar(entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] digest(String algorithm, byte[] bytes) {
        try {
            return MessageDigest.getInstance(algorithm).digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder();
        for (byte b : bytes) {
            builder.append(String.format("%02x", b));
        }
        return builder.toString();
    }
}

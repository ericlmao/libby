package net.byteflux.libby.transport;

import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * Checksum helpers, including the SHA-256 sidecar files the library manager
 * records next to every file it installs.
 * <p>
 * This is an internal class and not part of the stable API.
 */
public final class Checksums {
    /**
     * Suffix of the sidecar file holding a file's recorded SHA-256 checksum
     */
    public static final String RECORDED_SUFFIX = ".sha256";

    /**
     * Name of the SHA-256 message digest algorithm
     */
    public static final String SHA_256 = "SHA-256";

    /**
     * Length in bytes of a SHA-256 checksum
     */
    private static final int SHA_256_LENGTH = 32;

    /**
     * Hexadecimal digits
     */
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Checksums() {
    }

    /**
     * Creates a new message digest.
     *
     * @param algorithm digest algorithm name
     * @return new message digest
     * @throws IllegalStateException if the JVM doesn't support the algorithm
     */
    public static MessageDigest newDigest(String algorithm) {
        try {
            return MessageDigest.getInstance(requireNonNull(algorithm, "algorithm"));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("Digest algorithm " + algorithm + " is not available", e);
        }
    }

    /**
     * Computes the digest of a file.
     *
     * @param file      the file to digest
     * @param algorithm digest algorithm name
     * @return the digest
     * @throws IOException if the file can't be read
     */
    public static byte[] digest(Path file, String algorithm) throws IOException {
        MessageDigest digest = newDigest(algorithm);
        try (InputStream in = Files.newInputStream(requireNonNull(file, "file"))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }

        return digest.digest();
    }

    /**
     * Encodes bytes as lowercase hexadecimal.
     *
     * @param bytes the bytes to encode
     * @return hexadecimal string
     */
    public static String toHex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            chars[i * 2] = HEX[(bytes[i] >> 4) & 0xF];
            chars[i * 2 + 1] = HEX[bytes[i] & 0xF];
        }

        return new String(chars);
    }

    /**
     * Parses the first whitespace-separated token of a checksum file as a
     * case-insensitive hexadecimal digest of the expected length. Checksum
     * files commonly contain either just the digest or the digest followed by
     * a file name.
     *
     * @param text   checksum file contents
     * @param length expected digest length in bytes
     * @return the digest or null if the text doesn't start with one
     */
    public static byte @Nullable [] parseHex(String text, int length) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return null;
        }

        String token = trimmed.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        if (token.length() != length * 2) {
            return null;
        }

        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            int high = Character.digit(token.charAt(i * 2), 16);
            int low = Character.digit(token.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return null;
            }
            bytes[i] = (byte) ((high << 4) | low);
        }

        return bytes;
    }

    /**
     * Gets the sidecar file holding the recorded checksum of a file.
     *
     * @param file the file
     * @return the sidecar file path
     */
    public static Path recordedChecksumFile(Path file) {
        return file.resolveSibling(file.getFileName() + RECORDED_SUFFIX);
    }

    /**
     * Records the SHA-256 checksum of a file in its sidecar file.
     *
     * @param file   the file
     * @param sha256 the file's SHA-256 checksum
     * @throws IOException if the sidecar file can't be written
     */
    public static void record(Path file, byte[] sha256) throws IOException {
        Files.write(recordedChecksumFile(file), (toHex(sha256) + '\n').getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * Reads the recorded SHA-256 checksum of a file.
     *
     * @param file the file
     * @return the recorded checksum, or null if none is recorded or the sidecar is malformed
     * @throws IOException if the sidecar file exists but can't be read
     */
    public static byte @Nullable [] readRecorded(Path file) throws IOException {
        Path sidecar = recordedChecksumFile(file);
        if (!Files.isRegularFile(sidecar) || Files.size(sidecar) > 1024) {
            return null;
        }

        return parseHex(new String(Files.readAllBytes(sidecar), StandardCharsets.US_ASCII), SHA_256_LENGTH);
    }

    /**
     * Deletes a file and its recorded checksum sidecar, if they exist.
     *
     * @param file the file
     * @throws IOException if a file can't be deleted
     */
    public static void deleteWithRecord(Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.deleteIfExists(recordedChecksumFile(file));
    }
}

package net.byteflux.libby.resolver;

import java.nio.file.Path;

/**
 * Downloads POM files for the {@link DependencyResolver}.
 * <p>
 * This is an internal interface and not part of the stable API.
 */
@FunctionalInterface
public interface PomSource {
    /**
     * Downloads the POM of an artifact, or returns a verified cached copy,
     * and returns the local file. The coordinates have already been validated
     * like {@link net.byteflux.libby.Library} coordinates.
     *
     * @param groupId    Maven group ID
     * @param artifactId Maven artifact ID
     * @param version    artifact version
     * @return local path to the POM file
     * @throws net.byteflux.libby.LibraryDownloadException if the POM couldn't be downloaded
     */
    Path download(String groupId, String artifactId, String version);
}

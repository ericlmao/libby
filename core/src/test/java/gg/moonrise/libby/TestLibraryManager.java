package gg.moonrise.libby;

import gg.moonrise.libby.logging.LogLevel;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Library manager that records classpath additions instead of modifying a
 * class loader.
 */
public final class TestLibraryManager extends LibraryManager {
    private final RecordingLogAdapter log;
    private final List<Path> classpath = new CopyOnWriteArrayList<>();
    private final List<Library> dependencies;

    private TestLibraryManager(RecordingLogAdapter log, Path dataDirectory, List<Library> dependencies) {
        super(log, dataDirectory);
        this.log = log;
        this.dependencies = dependencies;
    }

    public static TestLibraryManager create(Path dataDirectory) {
        return withDependencies(dataDirectory, List.of());
    }

    /**
     * Creates a manager whose {@link #collectLibraries(Library)} loads the
     * given dependencies before every requested library.
     */
    public static TestLibraryManager withDependencies(Path dataDirectory, List<Library> dependencies) {
        TestLibraryManager manager = new TestLibraryManager(new RecordingLogAdapter(), dataDirectory, List.copyOf(dependencies));
        manager.setLogLevel(LogLevel.DEBUG);
        return manager;
    }

    @Override
    protected List<Library> collectLibraries(Library root) {
        List<Library> libraries = new ArrayList<>(dependencies);
        libraries.addAll(super.collectLibraries(root));
        return libraries;
    }

    @Override
    protected void addToClasspath(Path file) {
        classpath.add(file);
    }

    public RecordingLogAdapter log() {
        return log;
    }

    public List<Path> classpath() {
        return List.copyOf(classpath);
    }

    public Path saveDirectory() {
        return saveDirectory;
    }
}

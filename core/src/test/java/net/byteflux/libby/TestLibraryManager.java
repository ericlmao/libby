package net.byteflux.libby;

import net.byteflux.libby.logging.LogLevel;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Library manager that records classpath additions instead of modifying a
 * class loader.
 */
public final class TestLibraryManager extends LibraryManager {
    private final RecordingLogAdapter log;
    private final List<Path> classpath = new CopyOnWriteArrayList<>();

    private TestLibraryManager(RecordingLogAdapter log, Path dataDirectory) {
        super(log, dataDirectory);
        this.log = log;
    }

    public static TestLibraryManager create(Path dataDirectory) {
        TestLibraryManager manager = new TestLibraryManager(new RecordingLogAdapter(), dataDirectory);
        manager.setLogLevel(LogLevel.DEBUG);
        return manager;
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

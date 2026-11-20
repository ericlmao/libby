package gg.moonrise.libby;

import gg.moonrise.libby.logging.LogLevel;
import gg.moonrise.libby.logging.adapters.LogAdapter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Log adapter that records every message as {@code LEVEL message}.
 */
public final class RecordingLogAdapter implements LogAdapter {
    private final List<String> messages = new CopyOnWriteArrayList<>();

    @Override
    public void log(LogLevel level, String message) {
        messages.add(level + " " + message);
    }

    @Override
    public void log(LogLevel level, String message, Throwable throwable) {
        messages.add(level + " " + message + " " + throwable);
    }

    public List<String> messages() {
        return List.copyOf(messages);
    }

    public List<String> messages(LogLevel level) {
        return messages.stream().filter(message -> message.startsWith(level + " ")).collect(Collectors.toList());
    }

    public long count(LogLevel level, String fragment) {
        return messages(level).stream().filter(message -> message.contains(fragment)).count();
    }
}

package net.byteflux.libby.transport;

import org.jspecify.annotations.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * A deadline for a whole transfer, enforced by closing the transfer's
 * stream from a watchdog thread once it passes. Closing the stream makes a
 * read that is blocked on a stalled connection fail, which a timeout on the
 * request alone doesn't do.
 * <p>
 * Every deadline shares one daemon thread named
 * {@value #THREAD_NAME}, which is only started while a deadline is pending
 * and stops again after {@value #KEEP_ALIVE_SECONDS} idle seconds.
 * Deadlines are meant to be used by the thread doing the transfer.
 */
final class TransferDeadline {
    /**
     * Name of the watchdog thread
     */
    static final String THREAD_NAME = "libby-download-watchdog";

    /**
     * Seconds an idle watchdog thread waits for new deadlines before it stops
     */
    private static final long KEEP_ALIVE_SECONDS = 30;

    /**
     * The time limit of the transfer
     */
    private final Duration timeout;

    /**
     * {@link System#nanoTime()} at which the deadline passes
     */
    private final long deadlineNanos;

    /**
     * Whether the watchdog closed the stream because the deadline passed
     */
    private volatile boolean expired;

    /**
     * The pending watchdog task, or null if no stream is watched
     */
    private @Nullable ScheduledFuture<?> task;

    /**
     * Creates a new deadline.
     *
     * @param timeout the time limit, starting now
     */
    private TransferDeadline(Duration timeout) {
        this.timeout = timeout;
        this.deadlineNanos = System.nanoTime() + timeout.toNanos();
    }

    /**
     * Starts a deadline.
     *
     * @param timeout the time limit, starting now
     * @return the deadline
     */
    static TransferDeadline start(Duration timeout) {
        return new TransferDeadline(timeout);
    }

    /**
     * Gets the time limit of the transfer.
     *
     * @return the time limit
     */
    Duration timeout() {
        return timeout;
    }

    /**
     * Closes a stream when the deadline passes, unless {@link #cancel()} is
     * called first. Only one stream can be watched.
     *
     * @param stream the stream to close
     */
    void watch(Closeable stream) {
        if (task != null) {
            throw new IllegalStateException("a stream is already watched");
        }

        long remaining = Math.max(0, deadlineNanos - System.nanoTime());
        task = Watchdog.EXECUTOR.schedule(() -> expire(stream), remaining, TimeUnit.NANOSECONDS);
    }

    /**
     * Gets whether the deadline passed while a stream was watched, in which
     * case the stream has been closed.
     *
     * @return true if the transfer ran out of time
     */
    boolean isExpired() {
        return expired;
    }

    /**
     * Stops watching the stream.
     */
    void cancel() {
        ScheduledFuture<?> pending = task;
        if (pending != null) {
            pending.cancel(false);
        }
    }

    /**
     * Marks the deadline as passed and closes the stream.
     *
     * @param stream the watched stream
     */
    private void expire(Closeable stream) {
        expired = true;
        try {
            stream.close();
        } catch (IOException | RuntimeException e) {
            // The reading thread fails either way.
        }
    }

    /**
     * Holds the watchdog executor, created on first use.
     */
    private static final class Watchdog {
        /**
         * Runs the watchdog tasks of every deadline
         */
        private static final ScheduledThreadPoolExecutor EXECUTOR = createExecutor();

        private Watchdog() {
        }

        /**
         * Creates the watchdog executor: one daemon thread that stops when
         * idle, and cancelled tasks removed right away.
         *
         * @return the executor
         */
        private static ScheduledThreadPoolExecutor createExecutor() {
            ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread thread = new Thread(runnable, THREAD_NAME);
                thread.setDaemon(true);
                thread.setContextClassLoader(null);
                return thread;
            });
            executor.setKeepAliveTime(KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
            executor.allowCoreThreadTimeOut(true);
            executor.setRemoveOnCancelPolicy(true);
            return executor;
        }
    }
}

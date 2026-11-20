package net.byteflux.libby;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A tiny in-memory HTTP Maven repository on localhost. Unknown paths answer
 * 404 with a body, so tests can make sure error pages are never cached.
 */
public final class TestHttpRepository implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService executor = Executors.newFixedThreadPool(8);
    private final Map<String, Response> responses = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> requests = new ConcurrentHashMap<>();
    private volatile long delayMillis;

    public TestHttpRepository() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    /**
     * Gets the repository URL, ending with a slash.
     *
     * @return repository URL
     */
    public String url() {
        return "http://" + server.getAddress().getHostString() + ':' + server.getAddress().getPort() + "/repo/";
    }

    public void put(String path, byte[] body) {
        responses.put("/repo/" + path, new Response(200, body, false, false));
    }

    public void putChunked(String path, byte[] body) {
        responses.put("/repo/" + path, new Response(200, body, true, false));
    }

    public void putStatus(String path, int status, byte[] body) {
        responses.put("/repo/" + path, new Response(status, body, false, false));
    }

    /**
     * Answers with the headers and the first bytes of a longer body, then
     * stalls until the server is closed.
     */
    public void putStalled(String path, byte[] body) {
        responses.put("/repo/" + path, new Response(200, body, false, true));
    }

    public void setDelayMillis(long delayMillis) {
        this.delayMillis = delayMillis;
    }

    public int requests(String path) {
        AtomicInteger count = requests.get("/repo/" + path);
        return count != null ? count.get() : 0;
    }

    public int totalRequests() {
        return requests.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        requests.computeIfAbsent(path, key -> new AtomicInteger()).incrementAndGet();
        try {
            if (delayMillis > 0) {
                Thread.sleep(delayMillis);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Response response = responses.getOrDefault(path, new Response(404, "<html>Not Found</html>".getBytes(), false, false));
        if (response.stall) {
            exchange.sendResponseHeaders(response.status, response.body.length + 1024L);
            OutputStream out = exchange.getResponseBody();
            out.write(response.body);
            out.flush();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
            return;
        }

        exchange.sendResponseHeaders(response.status, response.chunked ? 0 : response.body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(response.body);
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }

    private record Response(int status, byte[] body, boolean chunked, boolean stall) {
    }
}

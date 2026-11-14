package dev.mcspb.api;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import dev.mcspb.core.scan.ServerPackBuilder;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Executors;

/** Loopback-only API for the Windows desktop application. */
public final class ApiServerMain {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_REQUEST_BYTES = 16_384;
    private ApiServerMain() { }

    public static void main(String[] args) throws IOException {
        int port = args.length == 0 ? 38241 : Integer.parseInt(args[0]);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        BuildJobs jobs = new BuildJobs();
        server.createContext("/api/health", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, Map.of("error", "Method not allowed")); return; }
            send(exchange, 200, Map.of("status", "ok"));
        });
        server.createContext("/api/scan", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, Map.of("error", "Method not allowed")); return; }
            String type = exchange.getRequestHeaders().getFirst("Content-Type");
            if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
                send(exchange, 415, Map.of("error", "Content-Type must be application/json")); return;
            }
            try {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                if (body.length > MAX_REQUEST_BYTES) { send(exchange, 413, Map.of("error", "Request too large")); return; }
                JsonNode request = JSON.readTree(body);
                String path = request == null ? "" : request.path("path").asText("");
                if (path.isBlank()) { send(exchange, 400, Map.of("error", "Missing path")); return; }
                send(exchange, 200, new ScanService().scan(Path.of(path)));
            } catch (IOException | IllegalArgumentException e) {
                send(exchange, 400, Map.of("error", e.getMessage() == null ? "Invalid request" : e.getMessage()));
            } catch (Exception e) {
                send(exchange, 500, Map.of("error", "Scan failed"));
            }
        });
        server.createContext("/api/build", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, Map.of("error", "Method not allowed")); return; }
            try {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                if (body.length > MAX_REQUEST_BYTES) { send(exchange, 413, Map.of("error", "Request too large")); return; }
                JsonNode request = JSON.readTree(body);
                String path = request == null ? "" : request.path("path").asText("");
                String output = request == null ? "" : request.path("output").asText("");
                if (path.isBlank() || output.isBlank()) { send(exchange, 400, Map.of("error", "Missing path or output")); return; }
                send(exchange, 200, new ServerPackBuilder().build(Path.of(path), Path.of(output)));
            } catch (IOException | IllegalArgumentException e) {
                send(exchange, 400, Map.of("error", e.getMessage() == null ? "Build failed" : e.getMessage()));
            } catch (Exception e) {
                send(exchange, 500, Map.of("error", "Build failed"));
            }
        });
        server.createContext("/api/build/start", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, Map.of("error", "Method not allowed")); return; }
            try {
                byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
                if (body.length > MAX_REQUEST_BYTES) { send(exchange, 413, Map.of("error", "Request too large")); return; }
                JsonNode request = JSON.readTree(body);
                String path = request == null ? "" : request.path("path").asText("");
                String output = request == null ? "" : request.path("output").asText("");
                if (path.isBlank() || output.isBlank()) { send(exchange, 400, Map.of("error", "Missing path or output")); return; }
                send(exchange, 200, jobs.start(Path.of(path), Path.of(output)));
            } catch (IOException | IllegalArgumentException e) {
                send(exchange, 400, Map.of("error", e.getMessage() == null ? "Invalid request" : e.getMessage()));
            } catch (IllegalStateException e) { send(exchange, 409, Map.of("error", e.getMessage())); }
        });
        server.createContext("/api/build/status", exchange -> {
            if (!"GET".equals(exchange.getRequestMethod())) { send(exchange, 405, Map.of("error", "Method not allowed")); return; }
            String id = queryId(exchange);
            BuildJobs.Snapshot status = jobs.status(id);
            if (status == null) send(exchange, 404, Map.of("error", "Unknown build job"));
            else send(exchange, 200, status);
        });
        server.createContext("/api/build/cancel", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) { send(exchange, 405, Map.of("error", "Method not allowed")); return; }
            String id = queryId(exchange);
            BuildJobs.Snapshot status = jobs.cancel(id);
            if (status == null) send(exchange, 404, Map.of("error", "Unknown build job"));
            else send(exchange, 200, status);
        });
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("MC Server Pack Builder API listening on " + server.getAddress());
    }

    private static String queryId(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return query != null && query.startsWith("id=") ? query.substring(3) : "";
    }

    private static void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
    }
}

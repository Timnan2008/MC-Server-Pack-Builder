package dev.mcspb.core.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcspb.core.model.ModSide;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/** Looks up public project-side metadata using IDs already present in Modrinth CDN URLs. */
final class ModrinthProjectLookup implements PackScanner.ProjectLookup {
    private static final int BATCH_SIZE = 25;
    private static final int MAX_RESPONSE_BYTES = 8_000_000;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    ModrinthProjectLookup() {
        // The Windows desktop may use a system proxy that Java ignores by default.
        if (System.getProperty("java.net.useSystemProxies") == null)
            System.setProperty("java.net.useSystemProxies", "true");
        client = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    static String projectId(JsonNode downloads) {
        if (!downloads.isArray()) return null;
        String found = null;
        for (JsonNode download : downloads) {
            URI uri;
            try { uri = URI.create(download.asText()); }
            catch (IllegalArgumentException e) { return null; }
            if (!"https".equalsIgnoreCase(uri.getScheme()) ||
                    !"cdn.modrinth.com".equalsIgnoreCase(uri.getHost())) continue;
            String[] parts = uri.getPath().split("/");
            if (parts.length < 5 || !"data".equals(parts[1]) ||
                    !"versions".equals(parts[3]) || !parts[2].matches("[A-Za-z0-9]{8}")) continue;
            if (found != null && !found.equals(parts[2])) return null;
            found = parts[2];
        }
        return found;
    }

    @Override public Map<String, PackScanner.ProjectSide> lookup(Set<String> ids) throws IOException {
        List<String> ordered = ids.stream().filter(id -> id.matches("[A-Za-z0-9]{8}"))
                .sorted().toList();
        Map<String, PackScanner.ProjectSide> result = new HashMap<>();
        for (int start = 0; start < ordered.size(); start += BATCH_SIZE) {
            List<String> batch = ordered.subList(start, Math.min(start + BATCH_SIZE, ordered.size()));
            String json = mapper.writeValueAsString(batch);
            URI uri = URI.create("https://api.modrinth.com/v2/projects?ids=" +
                    URLEncoder.encode(json, StandardCharsets.UTF_8));
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "MCSPB/0.1 (mod side classification)").GET().build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream body = response.body()) {
                    if (response.statusCode() != 200)
                        throw new IOException("Modrinth returned HTTP " + response.statusCode());
                    byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                    if (bytes.length > MAX_RESPONSE_BYTES) throw new IOException("Modrinth response exceeds limit");
                    JsonNode projects = mapper.readTree(bytes);
                    if (projects == null || !projects.isArray()) throw new IOException("Unexpected Modrinth response");
                    for (JsonNode project : projects) {
                        String id = project.path("id").asText();
                        if (batch.contains(id)) result.put(id, classify(project));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Modrinth lookup interrupted", e);
            }
        }
        return result;
    }

    static PackScanner.ProjectSide classify(JsonNode project) {
        Set<String> environments = new HashSet<>();
        JsonNode values = project.path("environment");
        if (values.isArray()) values.forEach(v -> environments.add(v.asText()));
        String legacy = project.path("server_side").asText("unknown");
        if (environments.equals(Set.of("client_only")) &&
                !legacy.equals("required") && !legacy.equals("optional"))
            return new PackScanner.ProjectSide(ModSide.CLIENT_ONLY,
                    "Modrinth project: client only; dedicated server unsupported");
        if (environments.isEmpty() && legacy.equals("unsupported"))
            return new PackScanner.ProjectSide(ModSide.CLIENT_ONLY,
                    "Modrinth project: server unsupported");
        if (environments.stream().anyMatch(ModrinthProjectLookup::supportsServer)) {
            if (legacy.equals("unsupported"))
                return new PackScanner.ProjectSide(ModSide.UNKNOWN,
                        "Modrinth project side declarations conflict");
            if (environments.contains("client_only"))
                return new PackScanner.ProjectSide(ModSide.KEEP_ON_SERVER,
                        "Modrinth project versions differ in server support; keep this version pending review");
            return new PackScanner.ProjectSide(ModSide.SERVER_COMPATIBLE,
                    "Modrinth project reports dedicated-server support");
        }
        if (environments.isEmpty() && (legacy.equals("required") || legacy.equals("optional")))
            return new PackScanner.ProjectSide(ModSide.SERVER_COMPATIBLE,
                    "Modrinth project reports server=" + legacy);
        return new PackScanner.ProjectSide(ModSide.UNKNOWN,
                "Modrinth project side is unspecified or ambiguous");
    }

    private static boolean supportsServer(String environment) {
        return switch (environment) {
            case "client_and_server", "client_only_server_optional", "server_only",
                 "server_only_client_optional", "dedicated_server_only", "client_or_server",
                 "client_or_server_prefers_both" -> true;
            default -> false;
        };
    }
}

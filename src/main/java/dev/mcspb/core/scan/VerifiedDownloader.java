package dev.mcspb.core.scan;

import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;

/** Streams files from known mod and NeoForge hosts and checks the pack's SHA-512. */
final class VerifiedDownloader implements PackFileSource {
    private static final Set<String> HOSTS = Set.of("cdn.modrinth.com", "edge.forgecdn.net",
            "mediafilez.forgecdn.net", "maven.neoforged.net");
    private static final long MAX_FILE_BYTES = 1_000_000_000L;
    private final HttpClient client;

    VerifiedDownloader() {
        if (System.getProperty("java.net.useSystemProxies") == null)
            System.setProperty("java.net.useSystemProxies", "true");
        client = HttpClient.newBuilder().proxy(ProxySelector.getDefault())
                .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public void download(List<String> urls, Path target, String sha512, long expectedBytes) throws IOException {
        if (urls.isEmpty() || sha512 == null || !sha512.matches("(?i)[0-9a-f]{128}"))
            throw new IOException("Missing download URL or SHA-512 hash");
        if (expectedBytes < -1 || expectedBytes > MAX_FILE_BYTES)
            throw new IOException("File size exceeds limit");
        IOException failure = null;
        for (String url : urls) {
            try {
                fetch(URI.create(url), target, sha512, expectedBytes);
                return;
            } catch (IOException | IllegalArgumentException e) {
                failure = e instanceof IOException io ? io : new IOException("Invalid download URL", e);
            }
        }
        throw new IOException("All download sources failed: " +
                (failure == null ? "no usable URL" : failure.getMessage()));
    }

    @Override public void verifyLocal(Path file, String sha512, long expectedBytes) throws IOException {
        if (sha512 == null || !sha512.matches("(?i)[0-9a-f]{128}") ||
                expectedBytes < 0 || expectedBytes > MAX_FILE_BYTES || Files.size(file) != expectedBytes)
            throw new IOException("Embedded mod size or SHA-512 is invalid");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            try (InputStream in = Files.newInputStream(file)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha512))
                throw new IOException("Embedded mod SHA-512 differs from pack index");
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    @Override public byte[] getSmall(URI uri, int maxBytes) throws IOException {
        try (InputStream body = open(uri)) {
            byte[] data = body.readNBytes(maxBytes + 1);
            if (data.length > maxBytes) throw new IOException("Response exceeds limit");
            return data;
        }
    }

    private void fetch(URI uri, Path target, String sha512, long expectedBytes) throws IOException {
        Files.createDirectories(target.getParent());
        Path part = target.resolveSibling(target.getFileName() + ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            long count = 0;
            try (InputStream body = open(uri); OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = body.read(buffer)) != -1) {
                    count += n;
                    if ((expectedBytes >= 0 && count > expectedBytes) || count > MAX_FILE_BYTES)
                        throw new IOException("Downloaded file exceeds declared size");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (expectedBytes >= 0 && count != expectedBytes)
                throw new IOException("Downloaded size differs from pack index");
            if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(sha512))
                throw new IOException("Downloaded SHA-512 differs from pack index");
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private InputStream open(URI original) throws IOException {
        URI uri = original;
        for (int redirects = 0; redirects <= 4; redirects++) {
            validate(uri);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "MCSPB/0.1 (server pack builder)").GET().build();
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();
                if (status == 200) return response.body();
                response.body().close();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = response.headers().firstValue("Location")
                            .orElseThrow(() -> new IOException("Redirect without Location"));
                    uri = uri.resolve(location);
                    continue;
                }
                throw new IOException("Download returned HTTP " + status);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            }
        }
        throw new IOException("Too many download redirects");
    }

    private static void validate(URI uri) throws IOException {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null ||
                uri.getHost() == null || !HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)))
            throw new IOException("Download host is not permitted: " + uri.getHost());
    }
}

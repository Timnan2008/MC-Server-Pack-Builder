package dev.mcspb.core.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcspb.core.model.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/** Reads mod entries in a Modrinth pack or a ZIP without changing the source archive. */
public final class PackScanner {
    private static final int MAX_ENTRIES = 50_000;
    private static final long MAX_JAR_BYTES = 1_000_000_000L;
    private static final long MAX_TOTAL_JAR_BYTES = 2_000_000_000L;
    private static final int MAX_INDEX_BYTES = 2_000_000;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectLookup projectLookup;

    @FunctionalInterface
    interface ProjectLookup {
        Map<String, ProjectSide> lookup(Set<String> ids) throws IOException;
    }

    record ProjectSide(ModSide side, String reason) { }

    public PackScanner() { this(new ModrinthProjectLookup()); }

    PackScanner(ProjectLookup projectLookup) { this.projectLookup = Objects.requireNonNull(projectLookup); }

    public PackScanReport scan(Path source) throws IOException {
        Path file = source.toAbsolutePath().normalize();
        String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!Files.isRegularFile(file) || !(lower.endsWith(".mrpack") || lower.endsWith(".zip")))
            throw new IOException("Not an mrpack or ZIP file: " + file);
        Map<String, PackModResult> mods = new TreeMap<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        try (ZipFile zip = new ZipFile(file.toFile())) {
            List<? extends ZipEntry> entries = Collections.list(zip.entries());
            if (entries.size() > MAX_ENTRIES) throw new IOException("Pack entry count exceeds limit");
            ZipEntry index = null;
            ZipEntry curseforgeManifest = null;
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (!safe(name)) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "UNSAFE_ENTRY", "Unsafe ZIP entry: " + name));
                    continue;
                }
                if (name.equals("modrinth.index.json")) index = entry;
                if (name.equals("manifest.json")) curseforgeManifest = entry;
            }
            if (lower.endsWith(".mrpack") && index == null)
                diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "NO_INDEX", "Missing modrinth.index.json"));
            if (index != null) readIndex(zip, index, mods, diagnostics);
            if (curseforgeManifest != null) readCurseforgeManifest(zip, curseforgeManifest, mods, diagnostics);
            long total = 0;
            for (ZipEntry entry : entries) {
                String name = entry.getName();
                if (entry.isDirectory() || !safe(name) || !isModJar(name)) continue;
                if (index != null && name.startsWith("client-overrides/")) {
                    mods.put(name, new PackModResult(name, ModSide.CLIENT_ONLY, "Modrinth client-only override"));
                    continue;
                }
                if (index != null && name.startsWith("server-overrides/")) {
                    mods.put(name, new PackModResult(name, ModSide.SERVER_COMPATIBLE, "Modrinth server override"));
                    continue;
                }
                if (entry.getSize() > MAX_JAR_BYTES) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "JAR_SIZE", "JAR exceeds size limit: " + name));
                    mods.putIfAbsent(name, new PackModResult(name, ModSide.UNKNOWN, "JAR exceeds size limit"));
                    continue;
                }
                Path staged = Files.createTempFile("mcspb-pack-", ".jar");
                try {
                    long size = 0;
                    try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(staged)) {
                        byte[] buffer = new byte[64 * 1024];
                        int n;
                        while ((n = in.read(buffer)) != -1) {
                            size += n;
                            if (size > MAX_JAR_BYTES || total + size > MAX_TOTAL_JAR_BYTES)
                                throw new IOException("Pack JAR data exceeds size limit");
                            out.write(buffer, 0, n);
                        }
                    }
                    total += size;
                    ArtifactResult artifact = new JarScanner().scanFile(staged);
                    PackModResult fromJar = ModSideClassifier.classify(name, artifact);
                    mods.merge(name, fromJar, (indexed, scanned) -> combine(name, indexed, scanned));
                } catch (IOException e) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "JAR_READ", name + ": " + e.getMessage()));
                    mods.putIfAbsent(name, new PackModResult(name, ModSide.UNKNOWN, "Could not scan JAR"));
                } finally {
                    Files.deleteIfExists(staged);
                }
            }
        }
        return new PackScanReport(file.toString(), new ArrayList<>(mods.values()), diagnostics);
    }

    static PackModResult combine(String path, PackModResult indexed, PackModResult scanned) {
        if (indexed.side() == ModSide.UNKNOWN) return scanned;
        if (indexed.side() == ModSide.KEEP_ON_SERVER && scanned.side() != ModSide.UNKNOWN) return scanned;
        if (scanned.side() == ModSide.KEEP_ON_SERVER || scanned.side() == ModSide.UNKNOWN) return indexed;
        if (indexed.side() != scanned.side())
            return new PackModResult(path, ModSide.UNKNOWN, "Index and downloaded JAR evidence disagree; keep for review");
        return indexed;
    }

    private void readIndex(ZipFile zip, ZipEntry entry, Map<String, PackModResult> mods,
                           List<Diagnostic> diagnostics) {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_INDEX_BYTES + 1);
            if (bytes.length > MAX_INDEX_BYTES) throw new IOException("Index exceeds size limit");
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.path("files").isArray()) throw new IOException("Missing files array");
            Map<String, String> projectByPath = new HashMap<>();
            for (JsonNode item : root.path("files")) {
                String path = item.path("path").asText("");
                if (!safe(path)) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "UNSAFE_ENTRY", "Unsafe index path: " + path));
                    continue;
                }
                if (!isModJar(path)) continue;
                String server = item.path("env").path("server").asText("");
                ModSide side = switch (server) {
                    case "unsupported" -> ModSide.CLIENT_ONLY;
                    case "required", "optional" -> ModSide.SERVER_COMPATIBLE;
                    case "" -> ModSide.KEEP_ON_SERVER;
                    default -> ModSide.UNKNOWN;
                };
                mods.put(path, new PackModResult(path, side,
                        side == ModSide.KEEP_ON_SERVER ? "Modrinth index omits optional env.server; keep by default" :
                        side == ModSide.UNKNOWN ? "Unrecognized env.server value" : "Modrinth index: server=" + server));
                if (side == ModSide.KEEP_ON_SERVER) {
                    String projectId = ModrinthProjectLookup.projectId(item.path("downloads"));
                    if (projectId != null) projectByPath.put(path, projectId);
                }
            }
            if (!projectByPath.isEmpty()) {
                try {
                    Map<String, ProjectSide> projects = projectLookup.lookup(new HashSet<>(projectByPath.values()));
                    projectByPath.forEach((path, id) -> {
                        ProjectSide info = projects.get(id);
                        if (info != null)
                            mods.put(path, new PackModResult(path,
                                    info.side() == ModSide.UNKNOWN ? ModSide.KEEP_ON_SERVER : info.side(),
                                    info.reason()));
                    });
                } catch (IOException e) {
                    diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING, "MODRINTH_LOOKUP",
                            "Modrinth project lookup unavailable; keeping indexed mods: " + e.getMessage()));
                }
            }
        } catch (Exception e) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "INDEX_PARSE", e.getMessage()));
        }
    }

    private void readCurseforgeManifest(ZipFile zip, ZipEntry entry, Map<String, PackModResult> mods,
                                        List<Diagnostic> diagnostics) {
        try (InputStream in = zip.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(MAX_INDEX_BYTES + 1);
            if (bytes.length > MAX_INDEX_BYTES) throw new IOException("Manifest exceeds size limit");
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.path("files").isArray()) return;
            for (JsonNode item : root.path("files")) {
                JsonNode project = item.path("projectID");
                JsonNode file = item.path("fileID");
                if (!project.canConvertToLong() || !file.canConvertToLong()) continue;
                String path = "curseforge:" + project.asLong() + "/" + file.asLong();
                mods.putIfAbsent(path, new PackModResult(path, ModSide.UNKNOWN,
                        "CurseForge manifest has IDs only; mod side cannot be determined offline"));
            }
        } catch (Exception e) {
            diagnostics.add(new Diagnostic(Diagnostic.Severity.ERROR, "MANIFEST_PARSE", e.getMessage()));
        }
    }

    private static boolean isModJar(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jar") && (!lower.contains("/") || lower.startsWith("mods/") || lower.contains("/mods/"));
    }

    static boolean safe(String name) {
        return !name.isBlank() && !name.startsWith("/") && !name.startsWith("\\") &&
                !name.contains("\\") && !name.matches("(?i)^[a-z]:.*") &&
                !Arrays.asList(name.split("/", -1)).contains("..");
    }
}

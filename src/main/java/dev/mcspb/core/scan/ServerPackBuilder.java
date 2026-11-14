package dev.mcspb.core.scan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcspb.core.model.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;
import java.util.concurrent.atomic.AtomicLong;

/** Downloads verified mod JARs, checks their side, and writes a NeoForge server bootstrap ZIP. */
public final class ServerPackBuilder {
    private static final int MAX_INDEX_BYTES = 2_000_000;
    private static final long MAX_OVERRIDE_BYTES = 2_000_000_000L;
    private final ObjectMapper mapper = new ObjectMapper();
    private final PackFileSource downloader;
    private final CoreInstaller coreInstaller;

    public ServerPackBuilder() { this(new VerifiedDownloader(), new NeoForgeCoreInstaller()); }

    ServerPackBuilder(PackFileSource downloader, CoreInstaller coreInstaller) {
        this.downloader = Objects.requireNonNull(downloader);
        this.coreInstaller = Objects.requireNonNull(coreInstaller);
    }

    public record Result(String output, String minecraftVersion, String neoforgeVersion,
                         int downloadedMods, int keptMods, int excludedMods, List<String> warnings) { }

    private record DownloadSpec(String path, List<String> urls, String sha512, long size) { }

    @FunctionalInterface public interface Progress {
        void update(String stage, long downloadedBytes, long totalBytes, String currentFile);
    }

    public Result build(Path source, Path destination) throws IOException {
        return build(source, destination, (stage, downloaded, total, current) -> { });
    }

    public Result build(Path source, Path destination, Progress progress) throws IOException {
        Path pack = source.toAbsolutePath().normalize();
        Path output = destination.toAbsolutePath().normalize();
        if (!Files.isRegularFile(pack) || !(pack.toString().toLowerCase(Locale.ROOT).endsWith(".mrpack") ||
                pack.toString().toLowerCase(Locale.ROOT).endsWith(".zip")))
            throw new IOException("Choose an .mrpack or .zip file");
        if (pack.equals(output) || !output.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
            throw new IOException("Output must be a different .zip path");
        Path temp = Files.createTempDirectory("mcspb-build-");
        try {
            progress.update("preparing", 0, 0, "Reading pack index");
            Path downloads = Files.createDirectory(temp.resolve("downloads"));
            Path server = Files.createDirectory(temp.resolve("server"));
            List<DownloadSpec> specs = new ArrayList<>();
            Set<String> indexedExtras = new HashSet<>();
            Set<String> indexedPaths = new HashSet<>();
            int downloadedMods = 0;
            String minecraft;
            String neoforge;
            try (ZipFile zip = new ZipFile(pack.toFile())) {
                ZipEntry index = zip.getEntry("modrinth.index.json");
                if (index == null) throw new IOException("ZIP has no modrinth.index.json; a CurseForge ID-only export cannot supply mod files offline");
                JsonNode root;
                try (InputStream in = zip.getInputStream(index)) {
                    byte[] bytes = in.readNBytes(MAX_INDEX_BYTES + 1);
                    if (bytes.length > MAX_INDEX_BYTES) throw new IOException("Pack index exceeds size limit");
                    root = mapper.readTree(bytes);
                }
                if (root == null || !root.path("files").isArray()) throw new IOException("Invalid Modrinth index");
                minecraft = root.path("dependencies").path("minecraft").asText("");
                neoforge = root.path("dependencies").path("neoforge").asText("");
                if (!minecraft.matches("[0-9A-Za-z._-]+") || !neoforge.matches("[0-9A-Za-z._-]+"))
                    throw new IOException("This builder needs a valid Minecraft and NeoForge version in the pack index");
                for (JsonNode item : root.path("files")) {
                    String path = item.path("path").asText("");
                    if (!safeFilePath(path)) throw new IOException("Unsafe pack path: " + path);
                    if (!indexedPaths.add(path)) throw new IOException("Duplicate pack path: " + path);
                    boolean mod = isModJar(path);
                    if (!mod && ("unsupported".equals(item.path("env").path("server").asText()) ||
                            !keepServerFile(path))) continue;
                    if (!mod) indexedExtras.add(path);
                    ZipEntry embedded = zip.getEntry(path);
                    if (embedded != null) {
                        Path staged = resolveInside(downloads, path);
                        Files.createDirectories(staged.getParent());
                        try (InputStream in = zip.getInputStream(embedded)) { copyLimited(in, staged, 1_000_000_000L); }
                        downloader.verifyLocal(staged, item.path("hashes").path("sha512").asText(""),
                                item.path("fileSize").asLong(-1));
                    } else {
                        List<String> urls = new ArrayList<>();
                        item.path("downloads").forEach(url -> urls.add(url.asText()));
                        specs.add(new DownloadSpec(path, urls, item.path("hashes").path("sha512").asText(""),
                                item.path("fileSize").asLong(-1)));
                        if (mod) downloadedMods++;
                    }
                }
                downloadAll(downloads, specs, progress);
                checkCancelled();
                Enumeration<? extends ZipEntry> embeddedEntries = zip.entries();
                while (embeddedEntries.hasMoreElements()) {
                    ZipEntry entry = embeddedEntries.nextElement();
                    if (entry.isDirectory() || !isModJar(entry.getName()) || indexedPaths.contains(entry.getName())) continue;
                    if (entry.getSize() > 1_000_000_000L) throw new IOException("Embedded mod exceeds size limit");
                    Path staged = resolveInside(downloads, entry.getName());
                    Files.createDirectories(staged.getParent());
                    try (InputStream in = zip.getInputStream(entry)) { copyLimited(in, staged, 1_000_000_000L); }
                }
            }
            progress.update("scanning", 0, 0, "Checking downloaded mods");
            checkCancelled();
            PackScanReport indexReport = new PackScanner().scan(pack);
            checkCancelled();
            if (indexReport.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR))
                throw new IOException("Pack index or embedded JAR has scan errors");
            Map<String, PackModResult> decisions = new HashMap<>();
            indexReport.mods().forEach(mod -> decisions.put(mod.path(), mod));
            Map<String, ArtifactResult> artifacts = new HashMap<>();
            try (var files = Files.walk(downloads)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    checkCancelled();
                    String path = downloads.relativize(file).toString().replace('\\', '/');
                    if (!isModJar(path)) continue;
                    ArtifactResult artifact = new JarScanner().scanFile(file);
                    artifacts.put(path, artifact);
                    PackModResult scanned = ModSideClassifier.classify(path, artifact);
                    decisions.merge(path, scanned, (indexed, local) -> PackScanner.combine(path, indexed, local));
                }
            }
            Set<String> keep = new HashSet<>();
            decisions.forEach((path, decision) -> {
                if (artifacts.containsKey(path) && decision.side() != ModSide.CLIENT_ONLY) keep.add(path);
            });
            retainRequiredDependencies(artifacts, keep);
            int excluded = 0;
            for (String path : artifacts.keySet()) {
                if (keep.contains(path)) {
                    Path target = resolveInside(server, path);
                    Files.createDirectories(target.getParent());
                    Files.copy(resolveInside(downloads, path), target, StandardCopyOption.REPLACE_EXISTING);
                } else excluded++;
            }
            for (String path : indexedExtras) {
                Path target = resolveInside(server, path);
                Files.createDirectories(target.getParent());
                Files.copy(resolveInside(downloads, path), target, StandardCopyOption.REPLACE_EXISTING);
            }
            try (ZipFile zip = new ZipFile(pack.toFile())) { copyOverrides(zip, server, decisions, indexedPaths); }
            checkCancelled();
            progress.update("installer-download", 0, 0, "Downloading NeoForge installer");
            String installer = addNeoForgeInstaller(server, neoforge, progress);
            checkCancelled();
            progress.update("installing", 0, 0, "Installing NeoForge server core");
            coreInstaller.install(server, installer);
            checkCancelled();
            addLaunchFiles(server, minecraft, neoforge);
            List<String> warnings = new ArrayList<>();
            indexReport.diagnostics().stream().filter(d -> d.severity() != Diagnostic.Severity.ERROR)
                    .forEach(d -> warnings.add(d.code() + ": " + d.message()));
            if (decisions.values().stream().anyMatch(d -> d.side() == ModSide.UNKNOWN))
                warnings.add("Some mod side declarations conflict; those JARs were retained");
            progress.update("packaging", 0, 0, "Writing server ZIP");
            writeZip(server, output);
            progress.update("completed", 0, 0, "Server ZIP ready");
            return new Result(output.toString(), minecraft, neoforge, downloadedMods, keep.size(), excluded, warnings);
        } finally {
            deleteTemporaryTree(temp);
        }
    }

    private void downloadAll(Path downloads, List<DownloadSpec> specs, Progress progress) throws IOException {
        long total = specs.stream().mapToLong(spec -> Math.max(0, spec.size())).sum();
        AtomicLong downloaded = new AtomicLong();
        progress.update("downloading", 0, total, "Starting pack downloads");
        try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
            List<Future<?>> tasks = new ArrayList<>();
            for (DownloadSpec spec : specs) tasks.add(pool.submit(() -> {
                try {
                    if (spec.size() < 0) throw new IOException("Missing fileSize in pack index");
                    downloader.download(spec.urls(), resolveInside(downloads, spec.path()),
                            spec.sha512(), spec.size(), delta ->
                                    progress.update("downloading", downloaded.addAndGet(delta), total, spec.path()));
                } catch (IOException e) { throw new UncheckedIOException(spec.path() + ": " + e.getMessage(), e); }
            }));
            for (Future<?> task : tasks) {
                try { task.get(); }
                catch (InterruptedException e) {
                    pool.shutdownNow(); Thread.currentThread().interrupt();
                    throw new IOException("Download interrupted", e);
                } catch (ExecutionException e) {
                    pool.shutdownNow();
                    Throwable cause = e.getCause();
                    if (cause instanceof UncheckedIOException io) throw io.getCause();
                    throw new IOException("Download failed", cause);
                }
            }
        }
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new IOException("Build cancelled");
    }

    private static void retainRequiredDependencies(Map<String, ArtifactResult> artifacts, Set<String> keep) {
        Map<String, Set<String>> providers = new HashMap<>();
        artifacts.forEach((path, artifact) -> artifact.mods().forEach(mod ->
                providers.computeIfAbsent(mod.id(), ignored -> new HashSet<>()).add(path)));
        boolean changed;
        do {
            changed = false;
            for (String path : List.copyOf(keep)) {
                for (ModDeclaration mod : artifacts.get(path).mods()) {
                    for (Dependency dependency : mod.dependencies()) {
                        if (dependency.required())
                            for (String provider : providers.getOrDefault(dependency.modId(), Set.of()))
                                changed |= keep.add(provider);
                    }
                }
            }
        } while (changed);
    }

    private String addNeoForgeInstaller(Path server, String version, Progress progress) throws IOException {
        String name = "neoforge-" + version + "-installer.jar";
        String base = "https://maven.neoforged.net/releases/net/neoforged/neoforge/" + version + "/" + name;
        String checksum = new String(downloader.getSmall(URI.create(base + ".sha512"), 1024),
                StandardCharsets.UTF_8).trim().split("\\s+")[0];
        if (!checksum.matches("(?i)[0-9a-f]{128}")) throw new IOException("Invalid NeoForge installer checksum");
        AtomicLong received = new AtomicLong();
        downloader.download(List.of(base), server.resolve(name), checksum, -1,
                (delta, total) -> progress.update("installer-download", received.addAndGet(delta), total, name));
        return name;
    }

    private static void addLaunchFiles(Path server, String minecraft, String neoforge)
            throws IOException {
        String bat = "@echo off\r\ncd /d \"%~dp0\"\r\ncall run.bat\r\n";
        String sh = "#!/bin/sh\nset -eu\ncd \"$(dirname \"$0\")\"\n" +
                "exec sh ./run.sh\n";
        String readme = "Minecraft " + minecraft + " / NeoForge " + neoforge + " server pack\r\n" +
                "The NeoForge server core and libraries are already installed. Run start-server.bat " +
                "(Windows) or sh start-server.sh (Linux).\r\n" +
                "Use a Java version supported by this Minecraft release. The first server run will create " +
                "eula.txt; read and accept it yourself before starting again.\r\n";
        Files.writeString(server.resolve("start-server.bat"), bat, StandardCharsets.UTF_8);
        Files.writeString(server.resolve("start-server.sh"), sh, StandardCharsets.UTF_8);
        Files.writeString(server.resolve("README-server.txt"), readme, StandardCharsets.UTF_8);
    }

    private static void copyOverrides(ZipFile zip, Path server,
                                      Map<String, PackModResult> decisions, Set<String> indexedPaths) throws IOException {
        long total = 0;
        for (String prefix : List.of("", "overrides/", "server-overrides/")) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            String name = entry.getName();
            if (entry.isDirectory() || !name.startsWith(prefix)) continue;
            if (prefix.isEmpty() && (name.equals("modrinth.index.json") || name.equals("manifest.json") ||
                    name.startsWith("overrides/") || name.startsWith("server-overrides/") ||
                    name.startsWith("client-overrides/") || isModJar(name) || indexedPaths.contains(name))) continue;
            String relative = name.substring(prefix.length());
            if (!safeFilePath(relative) || !keepServerFile(relative)) continue;
            PackModResult decision = decisions.get(name);
            if (decision != null && decision.side() == ModSide.CLIENT_ONLY) continue;
            if (entry.getSize() > MAX_OVERRIDE_BYTES) throw new IOException("Override entry exceeds size limit");
            Path target = resolveInside(server, relative);
            Files.createDirectories(target.getParent());
            try (InputStream in = zip.getInputStream(entry); OutputStream out = Files.newOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    total += n;
                    if (total > MAX_OVERRIDE_BYTES) throw new IOException("Overrides exceed size limit");
                    out.write(buffer, 0, n);
                }
            }
            }
        }
    }

    private static boolean keepServerFile(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return !lower.startsWith("client-overrides/") && !lower.startsWith("resourcepacks/") &&
                !lower.startsWith("shaderpacks/") && !lower.startsWith("screenshots/") &&
                !lower.startsWith("saves/") && !lower.equals("options.txt") &&
                !lower.equals("servers.dat") && !lower.equals("usercache.json");
    }

    private static void writeZip(Path server, Path output) throws IOException {
        Files.createDirectories(output.getParent());
        Path part = output.resolveSibling(output.getFileName() + ".part");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(part))) {
                zip.setLevel(1);
                try (var files = Files.walk(server)) {
                    for (Path file : files.filter(Files::isRegularFile).toList()) {
                        checkCancelled();
                        String name = server.relativize(file).toString().replace('\\', '/');
                        zip.putNextEntry(new ZipEntry(name));
                        Files.copy(file, zip);
                        zip.closeEntry();
                    }
                }
            }
            checkCancelled();
            try { Files.move(part, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(part, output, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(part); }
    }

    private static Path resolveInside(Path root, String relative) throws IOException {
        if (!safeFilePath(relative)) throw new IOException("Unsafe path: " + relative);
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) throw new IOException("Path escapes staging directory");
        return resolved;
    }

    private static boolean safeFilePath(String path) {
        return PackScanner.safe(path) && Arrays.stream(path.split("/", -1))
                .noneMatch(part -> part.isEmpty() || part.equals("."));
    }

    private static void copyLimited(InputStream in, Path target, long limit) throws IOException {
        try (OutputStream out = Files.newOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) != -1) {
                total += n;
                if (total > limit) throw new IOException("Embedded mod exceeds size limit");
                out.write(buffer, 0, n);
            }
        }
    }

    private static boolean isModJar(String path) {
        return path.startsWith("mods/") && path.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    private static void deleteTemporaryTree(Path temp) throws IOException {
        Path real = temp.toAbsolutePath().normalize();
        Path parent = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        if (!real.startsWith(parent) || !real.getFileName().toString().startsWith("mcspb-build-"))
            throw new IOException("Refusing to remove an unexpected staging directory");
        try (var files = Files.walk(real)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
        }
    }
}

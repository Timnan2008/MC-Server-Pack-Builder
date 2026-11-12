package dev.mcspb.core.scan;

import dev.mcspb.core.metadata.*;
import dev.mcspb.core.model.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.*;

public final class JarScanner {
    private static final long MAX_JAR_BYTES = 1_000_000_000L;
    private static final long MAX_ENTRY_BYTES = 2_000_000L;
    private static final long MAX_DECLARED_UNCOMPRESSED = 2_000_000_000L;
    private static final int MAX_ENTRIES = 20_000;
    private final List<MetadataParser> parsers = List.of(
            new TomlMetadataParser(Loader.FORGE), new TomlMetadataParser(Loader.NEOFORGE),
            new FabricMetadataParser());

    public CompletableFuture<ScanReport> scanAsync(Path folder, Executor executor) {
        return CompletableFuture.supplyAsync(() -> {
            try { return scan(folder); }
            catch (IOException e) { throw new CompletionException(e); }
        }, executor);
    }

    public ScanReport scan(Path folder) throws IOException {
        Path root = folder.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) throw new IOException("Not a directory: " + root);
        List<ArtifactResult> artifacts = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        try (var files = Files.list(root)) {
            List<Path> jars = files.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            for (Path jar : jars) artifacts.add(scanJar(root, jar));
        }
        return new ScanReport("1", root.toString(), artifacts, diagnostics);
    }

    public ArtifactResult scanFile(Path jar) throws IOException {
        Path file = jar.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file) || !file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
            throw new IOException("Not a JAR file: " + file);
        return scanJar(file.getParent(), file);
    }

    private ArtifactResult scanJar(Path root, Path jar) {
        String relative = root.relativize(jar).toString();
        List<Loader> loaders = new ArrayList<>();
        List<ModDeclaration> mods = new ArrayList<>();
        List<Diagnostic> diagnostics = new ArrayList<>();
        BytecodeAnalysisResult bytecode = new BytecodeAnalysisResult(List.of(), true);
        String hash = null;
        long bytes = -1;
        try {
            bytes = Files.size(jar);
            if (bytes > MAX_JAR_BYTES) throw new IOException("JAR exceeds size limit");
            hash = sha256(jar);
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                Enumeration<? extends ZipEntry> entries = zip.entries();
                Map<String, ZipEntry> metadata = new HashMap<>();
                long declared = 0;
                int count = 0;
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (++count > MAX_ENTRIES) throw new IOException("Archive entry count exceeds limit");
                    String name = entry.getName();
                    if (name.startsWith("/") || name.startsWith("\\") || name.contains("\\") ||
                            Arrays.asList(name.split("/", -1)).contains("..")) {
                        diagnostics.add(error("UNSAFE_ENTRY", "Unsafe ZIP entry: " + name));
                        continue;
                    }
                    if (entry.getSize() > 0) {
                        declared += entry.getSize();
                        if (declared > MAX_DECLARED_UNCOMPRESSED) throw new IOException("Archive declared size exceeds limit");
                    }
                    for (MetadataParser parser : parsers) {
                        if (name.equals(parser.entryName())) metadata.putIfAbsent(name, entry);
                    }
                }
                for (MetadataParser parser : parsers) {
                    ZipEntry entry = metadata.get(parser.entryName());
                    if (entry == null) continue;
                    loaders.add(parser.loader());
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] contents = in.readNBytes((int) MAX_ENTRY_BYTES + 1);
                        if (contents.length > MAX_ENTRY_BYTES) throw new IOException("Metadata exceeds size limit");
                        mods.addAll(parser.parse(contents));
                    } catch (Exception e) {
                        diagnostics.add(error("METADATA_PARSE", parser.loader() + ": " + e.getMessage()));
                    }
                }
                if (loaders.contains(Loader.NEOFORGE)) {
                    bytecode = NeoForgeEntryPointAnalyzer.analyze(zip);
                    if (!bytecode.complete()) diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING,
                            "BYTECODE_INCOMPLETE", "Some NeoForge class annotations could not be analyzed"));
                }
                if (loaders.isEmpty()) diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING,
                        "NO_METADATA", "No supported loader metadata found"));
                if (loaders.size() > 1) diagnostics.add(new Diagnostic(Diagnostic.Severity.WARNING,
                        "MULTIPLE_LOADERS", "Metadata for multiple loaders found; inspect artifact manually"));
            }
        } catch (IOException | SecurityException e) {
            diagnostics.add(error("JAR_READ", e.getMessage()));
        }
        return new ArtifactResult(relative, hash, bytes, loaders, mods, bytecode, diagnostics);
    }

    private static Diagnostic error(String code, String message) {
        return new Diagnostic(Diagnostic.Severity.ERROR, code, message == null ? "Unknown error" : message);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int n;
                while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}

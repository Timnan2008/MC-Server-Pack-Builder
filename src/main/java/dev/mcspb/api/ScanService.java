package dev.mcspb.api;

import dev.mcspb.core.model.*;
import dev.mcspb.core.scan.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** One response shape for a JAR, a mods folder, and a pack archive. */
public final class ScanService {
    public record Entry(String path, ModSide side, String reason, List<Diagnostic> diagnostics) { }
    public record Result(String source, String kind, List<Entry> entries, List<Diagnostic> diagnostics) { }

    public Result scan(Path source) throws IOException {
        Path path = source.toAbsolutePath().normalize();
        if (Files.isDirectory(path)) {
            ScanReport report = new JarScanner().scan(path);
            return new Result(report.source(), "folder", report.artifacts().stream().map(this::entry).toList(),
                    report.diagnostics());
        }
        if (!Files.isRegularFile(path)) throw new IOException("Path does not exist: " + path);
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar")) {
            ArtifactResult artifact = new JarScanner().scanFile(path);
            return new Result(path.toString(), "jar", List.of(entry(artifact)), List.of());
        }
        if (name.endsWith(".mrpack") || name.endsWith(".zip")) {
            PackScanReport report = new PackScanner().scan(path);
            return new Result(report.source(), "pack", report.mods().stream()
                    .map(m -> new Entry(m.path(), m.side(), m.reason(), List.of())).toList(), report.diagnostics());
        }
        throw new IOException("Choose a JAR, mods folder, .mrpack, or .zip file");
    }

    private Entry entry(ArtifactResult artifact) {
        PackModResult side = ModSideClassifier.classify(artifact.path(), artifact);
        return new Entry(artifact.path(), side.side(), side.reason(), artifact.diagnostics());
    }
}

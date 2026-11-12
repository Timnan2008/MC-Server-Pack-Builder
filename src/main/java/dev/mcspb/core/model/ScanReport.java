package dev.mcspb.core.model;

import java.util.List;

public record ScanReport(String schemaVersion, String source, List<ArtifactResult> artifacts,
                         List<Diagnostic> diagnostics) {
    public ScanReport {
        artifacts = List.copyOf(artifacts);
        diagnostics = List.copyOf(diagnostics);
    }
}

package dev.mcspb.core.model;

import java.util.List;

public record PackScanReport(String source, List<PackModResult> mods, List<Diagnostic> diagnostics) {
    public PackScanReport {
        mods = List.copyOf(mods);
        diagnostics = List.copyOf(diagnostics);
    }
}

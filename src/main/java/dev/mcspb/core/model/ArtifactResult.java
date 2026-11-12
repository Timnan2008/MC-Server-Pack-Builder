package dev.mcspb.core.model;

import java.util.List;

public record ArtifactResult(String path, String sha256, long bytes, List<Loader> loaders,
                             List<ModDeclaration> mods, BytecodeAnalysisResult bytecode,
                             List<Diagnostic> diagnostics) {
    public ArtifactResult {
        loaders = List.copyOf(loaders);
        mods = List.copyOf(mods);
        diagnostics = List.copyOf(diagnostics);
    }
}

package dev.mcspb.core.model;

import java.util.List;

public record BytecodeAnalysisResult(List<ModEntryPoint> entryPoints, boolean complete) {
    public BytecodeAnalysisResult { entryPoints = List.copyOf(entryPoints); }
}

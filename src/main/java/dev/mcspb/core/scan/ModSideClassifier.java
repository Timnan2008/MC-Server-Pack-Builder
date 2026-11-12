package dev.mcspb.core.scan;

import dev.mcspb.core.model.*;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Metadata-based classification; UNKNOWN must be reviewed before removal. */
public final class ModSideClassifier {
    private ModSideClassifier() { }

    public static PackModResult classify(String path, ArtifactResult artifact) {
        if (artifact.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR))
            return new PackModResult(path, ModSide.UNKNOWN, "JAR scan has errors");
        if (ClientModClassifier.isClientOnly(artifact))
            return new PackModResult(path, ModSide.CLIENT_ONLY, "Fabric environment=client");
        if (!artifact.mods().isEmpty() && artifact.loaders().equals(List.of(Loader.FABRIC)) &&
                artifact.mods().stream().allMatch(m -> m.declaredEnvironment() == null ||
                        "*".equals(m.declaredEnvironment()) ||
                        "server".equalsIgnoreCase(m.declaredEnvironment())))
            return new PackModResult(path, ModSide.SERVER_COMPATIBLE, "Fabric environment allows server");
        if (!artifact.mods().isEmpty() && artifact.loaders().equals(List.of(Loader.NEOFORGE))) {
            BytecodeAnalysisResult bytecode = artifact.bytecode();
            if (!bytecode.complete()) return new PackModResult(path, ModSide.UNKNOWN,
                    "NeoForge entry point analysis incomplete; keep for review");
            if (!bytecode.entryPoints().isEmpty()) {
                Set<String> declared = artifact.mods().stream().map(ModDeclaration::id).collect(Collectors.toSet());
                Set<String> discovered = bytecode.entryPoints().stream()
                        .map(ModEntryPoint::modId).collect(Collectors.toSet());
                if (!declared.equals(discovered)) return new PackModResult(path, ModSide.KEEP_ON_SERVER,
                        "NeoForge entry points do not cover every declared mod ID; keep on server");
                if (bytecode.entryPoints().stream().allMatch(e -> e.explicitSideRestriction() &&
                        e.sides().equals(Set.of(PhysicalSide.CLIENT))))
                    return new PackModResult(path, ModSide.CLIENT_ONLY,
                            "Every declared NeoForge mod entry point is restricted to CLIENT");
                return new PackModResult(path, ModSide.SERVER_COMPATIBLE,
                        "NeoForge has an entry point available on the dedicated server; startup not verified");
            }
        }
        if (!artifact.mods().isEmpty() && artifact.loaders().size() == 1 &&
                (artifact.loaders().getFirst() == Loader.FORGE || artifact.loaders().getFirst() == Loader.NEOFORGE))
            return new PackModResult(path, ModSide.KEEP_ON_SERVER,
                    artifact.loaders().getFirst() + " metadata parsed; keep unless a server crash identifies this JAR");
        return new PackModResult(path, ModSide.UNKNOWN, "JAR metadata does not establish server support");
    }
}

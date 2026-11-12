package dev.mcspb.core.scan;

import dev.mcspb.core.model.*;
import java.io.IOException;
import java.nio.file.Path;

/** Conservative, metadata-based client-only classification for a physical JAR. */
public final class ClientModClassifier {
    private ClientModClassifier() { }

    public static boolean isClientOnly(Path jar) throws IOException {
        return isClientOnly(new JarScanner().scanFile(jar));
    }

    public static boolean isClientOnly(ArtifactResult artifact) {
        if (artifact.mods().isEmpty() || artifact.loaders().size() != 1 ||
                artifact.diagnostics().stream().anyMatch(d -> d.severity() == Diagnostic.Severity.ERROR)) return false;
        return artifact.mods().stream().allMatch(mod -> switch (mod.loader()) {
            case FABRIC -> "client".equalsIgnoreCase(mod.declaredEnvironment());
            // displayTest controls network compatibility, not whether the JAR can run on a server.
            case FORGE, NEOFORGE -> false;
        });
    }
}

package dev.mcspb.core.scan;

import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
interface CoreInstaller {
    void install(Path server, String installerName) throws IOException;
}

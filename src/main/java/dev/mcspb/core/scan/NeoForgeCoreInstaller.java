package dev.mcspb.core.scan;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Runs only the checksum-verified official NeoForge installer in an isolated staging directory. */
final class NeoForgeCoreInstaller implements CoreInstaller {
    @Override public void install(Path server, String installerName) throws IOException {
        String javaName = System.getProperty("os.name", "").toLowerCase().contains("win") ? "java.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", javaName);
        Path log = server.resolve("installer.log");
        for (int attempt = 0; attempt < 2; attempt++) {
            ProcessBuilder builder = new ProcessBuilder(java.toString(), "-Djava.net.useSystemProxies=true",
                    "-jar", installerName, "--installServer")
                    .directory(server.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            Process process = builder.start();
            try {
                if (!process.waitFor(20, TimeUnit.MINUTES)) {
                    process.destroyForcibly();
                    throw new IOException("NeoForge installer timed out");
                }
                if (process.exitValue() == 0 && Files.isRegularFile(server.resolve("run.bat")) &&
                        Files.isRegularFile(server.resolve("run.sh")) && Files.isDirectory(server.resolve("libraries"))) {
                    Files.deleteIfExists(log);
                    Files.deleteIfExists(server.resolve(installerName + ".log"));
                    return;
                }
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IOException("NeoForge installer interrupted", e);
            }
        }
        String detail = Files.exists(log) ? Files.readString(log, StandardCharsets.UTF_8) : "No installer log";
        throw new IOException("NeoForge server core installation failed: " +
                detail.substring(Math.max(0, detail.length() - 2000)));
    }
}

package dev.mcspb.core.scan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerRetentionClassifierTest {
    @TempDir Path temp;

    @Test void removesOnlyJarExplicitlyNamedInDedicatedServerCrash() throws Exception {
        Path failed = Files.createFile(temp.resolve("ruok-forge_1.20.1-1.7.2.jar"));
        Path other = Files.createFile(temp.resolve("freecam.jar"));
        Path report = temp.resolve("crash.txt");
        Files.writeString(report, "Description: Mod loading error has occurred\n" +
                "-- MOD ruokmod --\nMod File: /home/container/mods/ruok-forge_1.20.1-1.7.2.jar\n" +
                "Failure message: RuOK Mod has class loading errors\n" +
                "Exception message: Attempted to load class RuOKModForge for invalid dist DEDICATED_SERVER\n" +
                "-- System Details --\n");
        assertFalse(ServerRetentionClassifier.shouldKeep(failed, report));
        assertTrue(ServerRetentionClassifier.shouldKeep(other, report));
        Files.writeString(report, "[ERROR] Attempted to load class net/minecraft/client/MouseHandler " +
                "for invalid dist DEDICATED_SERVER\nDone (8.326s)!\n");
        assertTrue(ServerRetentionClassifier.shouldKeep(failed, report));
    }
}

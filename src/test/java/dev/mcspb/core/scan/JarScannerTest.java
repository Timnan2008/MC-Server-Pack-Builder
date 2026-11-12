package dev.mcspb.core.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcspb.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class JarScannerTest {
    @TempDir Path temp;

    @Test void scansFixtureJarsAndKeepsPhysicalArtifactsSeparate() throws Exception {
        for (String name : List.of("forge-multi.jar", "neoforge.jar", "fabric.jar")) {
            try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
                assertNotNull(in);
                Files.copy(in, temp.resolve(name));
            }
        }
        ScanReport report = new JarScanner().scan(temp);
        assertEquals(3, report.artifacts().size());
        ArtifactResult forge = artifact(report, "forge-multi.jar");
        assertEquals(List.of(Loader.FORGE), forge.loaders());
        assertEquals(2, forge.mods().size());
        assertEquals("alpha", forge.mods().get(0).id());
        assertEquals("beta", forge.mods().get(1).id());
        assertEquals("library", forge.mods().get(0).dependencies().getFirst().modId());
        assertTrue(forge.mods().get(0).dependencies().getFirst().required());
        assertEquals(64, forge.sha256().length());

        ArtifactResult neo = artifact(report, "neoforge.jar");
        assertEquals(Loader.NEOFORGE, neo.mods().getFirst().loader());
        assertTrue(neo.mods().getFirst().dependencies().getFirst().required());

        ArtifactResult fabric = artifact(report, "fabric.jar");
        assertEquals("client", fabric.mods().getFirst().declaredEnvironment());
        assertTrue(ClientModClassifier.isClientOnly(fabric));
        assertFalse(ClientModClassifier.isClientOnly(forge));
        assertEquals(2, fabric.mods().getFirst().dependencies().size());
        assertTrue(fabric.mods().getFirst().dependencies().get(0).required());
        assertFalse(fabric.mods().getFirst().dependencies().get(1).required());
        assertTrue(new ObjectMapper().writeValueAsString(report).contains("forge-multi.jar"));
    }

    @Test void forgeDisplayTestDoesNotProveClientOnly() throws Exception {
        Path jar = temp.resolve("client-forge.jar");
        String toml = "[[mods]]\nmodId=\"one\"\ndisplayTest=\"IGNORE_ALL_VERSION\"\n" +
                "[[mods]]\nmodId=\"two\"\ndisplayTest=\"IGNORE_ALL_VERSION\"\n";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("META-INF/mods.toml"));
            zip.write(toml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        assertFalse(ClientModClassifier.isClientOnly(new JarScanner().scanFile(jar)));
    }

    @Test void malformedAndUnsafeArtifactsProduceDiagnostics() throws Exception {
        for (String name : List.of("bad.jar", "traversal.jar", "invalid-fabric.jar")) {
            try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
                assertNotNull(in);
                Files.copy(in, temp.resolve(name));
            }
        }
        ScanReport report = new JarScanner().scan(temp);
        assertTrue(artifact(report, "bad.jar").diagnostics().stream().anyMatch(d -> d.code().equals("JAR_READ")));
        assertTrue(artifact(report, "traversal.jar").diagnostics().stream().anyMatch(d -> d.code().equals("UNSAFE_ENTRY")));
        assertTrue(artifact(report, "invalid-fabric.jar").diagnostics().stream().anyMatch(d -> d.code().equals("METADATA_PARSE")));
    }

    private static ArtifactResult artifact(ScanReport report, String name) {
        return report.artifacts().stream().filter(a -> a.path().equals(name)).findFirst().orElseThrow();
    }
}

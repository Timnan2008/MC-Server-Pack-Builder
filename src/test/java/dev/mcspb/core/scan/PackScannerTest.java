package dev.mcspb.core.scan;

import dev.mcspb.core.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

class PackScannerTest {
    @TempDir Path temp;

    @Test void mrpackUsesIndexForDownloadedModsAndScansEmbeddedOverrides() throws Exception {
        Path pack = temp.resolve("client.mrpack");
        String index = """
                {"formatVersion":1,"files":[
                  {"path":"mods/client.jar","env":{"client":"required","server":"unsupported"}},
                  {"path":"mods/common.jar","env":{"client":"required","server":"required"}},
                  {"path":"mods/unspecified.jar"}
                ]}
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            put(zip, "modrinth.index.json", index.getBytes(StandardCharsets.UTF_8));
            put(zip, "overrides/mods/fabric.jar", fixture("fabric.jar"));
            put(zip, "client-overrides/mods/client-only.jar", fixture("forge-multi.jar"));
            put(zip, "server-overrides/mods/server.jar", fixture("forge-multi.jar"));
            put(zip, "overrides/config/example.txt", new byte[]{1});
        }
        PackScanReport result = new PackScanner().scan(pack);
        assertEquals(6, result.mods().size());
        assertEquals(ModSide.CLIENT_ONLY, side(result, "mods/client.jar"));
        assertEquals(ModSide.SERVER_COMPATIBLE, side(result, "mods/common.jar"));
        assertEquals(ModSide.KEEP_ON_SERVER, side(result, "mods/unspecified.jar"));
        assertEquals(ModSide.CLIENT_ONLY, side(result, "overrides/mods/fabric.jar"));
        assertEquals(ModSide.CLIENT_ONLY, side(result, "client-overrides/mods/client-only.jar"));
        assertEquals(ModSide.SERVER_COMPATIBLE, side(result, "server-overrides/mods/server.jar"));
        assertTrue(result.diagnostics().isEmpty());
    }

    @Test void zipScansJarsAndReportsUnsafePaths() throws Exception {
        Path pack = temp.resolve("client.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            put(zip, "instance/mods/fabric.jar", fixture("fabric.jar"));
            put(zip, "instance/mods/forge.jar", fixture("forge-multi.jar"));
            put(zip, "instance/mods/neoforge.jar", fixture("neoforge.jar"));
            put(zip, "../mods/evil.jar", fixture("fabric.jar"));
        }
        PackScanReport result = new PackScanner().scan(pack);
        assertEquals(ModSide.CLIENT_ONLY, side(result, "instance/mods/fabric.jar"));
        assertEquals(ModSide.KEEP_ON_SERVER, side(result, "instance/mods/forge.jar"));
        assertEquals(ModSide.KEEP_ON_SERVER, side(result, "instance/mods/neoforge.jar"));
        assertTrue(result.mods().stream().noneMatch(m -> m.path().contains("evil")));
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.code().equals("UNSAFE_ENTRY")));
    }

    @Test void curseforgeExportListsReferencedModsAsUnknown() throws Exception {
        Path pack = temp.resolve("curseforge.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            put(zip, "manifest.json", "{\"files\":[{\"projectID\":123,\"fileID\":456}]}"
                    .getBytes(StandardCharsets.UTF_8));
        }
        PackScanReport result = new PackScanner().scan(pack);
        assertEquals(1, result.mods().size());
        assertEquals(ModSide.UNKNOWN, side(result, "curseforge:123/456"));
    }

    @Test void embeddedJarEvidenceOverridesMissingIndexEnvironment() throws Exception {
        Path pack = temp.resolve("embedded.mrpack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            put(zip, "modrinth.index.json", "{\"files\":[{\"path\":\"mods/client.jar\"}]}"
                    .getBytes(StandardCharsets.UTF_8));
            put(zip, "mods/client.jar", fixture("fabric.jar"));
        }
        assertEquals(ModSide.CLIENT_ONLY, side(new PackScanner().scan(pack), "mods/client.jar"));
    }

    @Test void projectIdsClassifyDownloadedModsWithoutJarBytes() throws Exception {
        Path pack = temp.resolve("download-only.mrpack");
        String index = """
                {"files":[
                  {"path":"mods/sodium.jar","downloads":["https://cdn.modrinth.com/data/AANobbMI/versions/zg4YQ9EL/sodium.jar"]},
                  {"path":"mods/ae2.jar","downloads":["https://cdn.modrinth.com/data/XxWD5pD3/versions/nVSxZcom/ae2.jar"]},
                  {"path":"mods/explicit.jar","env":{"server":"unsupported"},"downloads":["https://cdn.modrinth.com/data/XxWD5pD3/versions/nVSxZcom/ae2.jar"]}
                ]}
                """;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            put(zip, "modrinth.index.json", index.getBytes(StandardCharsets.UTF_8));
        }
        PackScanner scanner = new PackScanner(ids -> {
            assertEquals(Set.of("AANobbMI", "XxWD5pD3"), ids);
            return Map.of("AANobbMI", new PackScanner.ProjectSide(ModSide.CLIENT_ONLY, "client only"),
                    "XxWD5pD3", new PackScanner.ProjectSide(ModSide.SERVER_COMPATIBLE, "server supported"));
        });
        PackScanReport report = scanner.scan(pack);
        assertEquals(ModSide.CLIENT_ONLY, side(report, "mods/sodium.jar"));
        assertEquals(ModSide.SERVER_COMPATIBLE, side(report, "mods/ae2.jar"));
        assertEquals(ModSide.CLIENT_ONLY, side(report, "mods/explicit.jar"));
    }

    @Test void lookupFailureKeepsUndeclaredMods() throws Exception {
        Path pack = temp.resolve("offline.mrpack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            put(zip, "modrinth.index.json", """
                    {"files":[{"path":"mods/example.jar","downloads":["https://cdn.modrinth.com/data/AANobbMI/versions/zg4YQ9EL/example.jar"]}]}
                    """.getBytes(StandardCharsets.UTF_8));
        }
        PackScanReport report = new PackScanner(ids -> { throw new java.io.IOException("offline"); }).scan(pack);
        assertEquals(ModSide.KEEP_ON_SERVER, side(report, "mods/example.jar"));
        assertTrue(report.diagnostics().stream().anyMatch(d -> d.code().equals("MODRINTH_LOOKUP")));
    }

    @Test void conflictingIndexAndEmbeddedJarRequireReview() throws Exception {
        Path jar = temp.resolve("server.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(zip, "fabric.mod.json", """
                    {"schemaVersion":1,"id":"servermod","version":"1","environment":"server"}
                    """.getBytes(StandardCharsets.UTF_8));
        }
        Path pack = temp.resolve("conflict.mrpack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            put(zip, "modrinth.index.json", """
                    {"files":[{"path":"mods/server.jar","env":{"server":"unsupported"}}]}
                    """.getBytes(StandardCharsets.UTF_8));
            put(zip, "mods/server.jar", Files.readAllBytes(jar));
        }
        assertEquals(ModSide.UNKNOWN, side(new PackScanner().scan(pack), "mods/server.jar"));
    }

    private byte[] fixture(String name) throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    private static ModSide side(PackScanReport report, String path) {
        return report.mods().stream().filter(m -> m.path().equals(path)).findFirst().orElseThrow().side();
    }
}

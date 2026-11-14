package dev.mcspb.core.scan;

import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ServerPackBuilderTest {
    @TempDir Path temp;

    @Test void downloadsBeforeClassifyingAndPackagesServerFiles() throws Exception {
        byte[] client = fixture("fabric.jar");
        byte[] server = fixture("neoforge.jar");
        byte[] installer = "test installer".getBytes(StandardCharsets.UTF_8);
        byte[] script = "onEvent('server.load', event => {})".getBytes(StandardCharsets.UTF_8);
        byte[] properties = "motd=Test server".getBytes(StandardCharsets.UTF_8);
        String index = """
                {"dependencies":{"minecraft":"26.1.2","neoforge":"26.1.2.114"},"files":[
                  {"path":"mods/client.jar","hashes":{"sha512":"%s"},"fileSize":%d,
                   "downloads":["https://cdn.modrinth.com/data/AANobbMI/versions/abcdefgh/client.jar"],
                   "env":{"server":"unsupported"}},
                  {"path":"mods/server.jar","hashes":{"sha512":"%s"},"fileSize":%d,
                   "downloads":["https://cdn.modrinth.com/data/XxWD5pD3/versions/abcdefgh/server.jar"],
                   "env":{"server":"required"}},
                  {"path":"kubejs/server_scripts/init.js","hashes":{"sha512":"%s"},"fileSize":%d,
                   "downloads":["https://cdn.modrinth.com/data/extra/versions/abcdefgh/init.js"]},
                  {"path":"server.properties","hashes":{"sha512":"%s"},"fileSize":%d},
                  {"path":"config/client-only.cfg","env":{"server":"unsupported"}},
                  {"path":"resourcepacks/client.zip","env":{"server":"unsupported"}}]}
                """.formatted(hash(client), client.length, hash(server), server.length,
                hash(script), script.length, hash(properties), properties.length);
        Path pack = temp.resolve("input.mrpack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            put(zip, "modrinth.index.json", index.getBytes(StandardCharsets.UTF_8));
            put(zip, "server-overrides/config/server.txt", "server".getBytes(StandardCharsets.UTF_8));
            put(zip, "overrides/defaultconfigs/common.toml", "common".getBytes(StandardCharsets.UTF_8));
            put(zip, "overrides/resourcepacks/client.zip", "client".getBytes(StandardCharsets.UTF_8));
            put(zip, "kubejs/startup_scripts/root.js", "root".getBytes(StandardCharsets.UTF_8));
            put(zip, "server.properties", properties);
            put(zip, "config/client-only.cfg", "client".getBytes(StandardCharsets.UTF_8));
            put(zip, "client-overrides/config/client.txt", "client".getBytes(StandardCharsets.UTF_8));
        }
        PackFileSource source = new PackFileSource() {
            @Override public void download(List<String> urls, Path target, String sha512, long expectedBytes)
                    throws IOException {
                byte[] bytes = urls.getFirst().contains("client.jar") ? client :
                        urls.getFirst().contains("server.jar") ? server :
                        urls.getFirst().contains("init.js") ? script : installer;
                assertEquals(hash(bytes), sha512);
                if (expectedBytes >= 0) assertEquals(bytes.length, expectedBytes);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            }
            @Override public void verifyLocal(Path file, String sha512, long expectedBytes) throws IOException {
                assertEquals(hash(Files.readAllBytes(file)), sha512);
                assertEquals(Files.size(file), expectedBytes);
            }
            @Override public byte[] getSmall(URI uri, int maxBytes) { return hash(installer).getBytes(StandardCharsets.UTF_8); }
        };
        Path output = temp.resolve("server.zip");
        CoreInstaller fakeInstaller = (directory, name) -> {
            Files.createDirectories(directory.resolve("libraries"));
            Files.writeString(directory.resolve("run.bat"), "@echo off\r\n");
            Files.writeString(directory.resolve("run.sh"), "#!/bin/sh\n");
            Files.writeString(directory.resolve("libraries/core.jar"), "core");
        };
        List<String> stages = Collections.synchronizedList(new ArrayList<>());
        List<Long> downloadCounts = Collections.synchronizedList(new ArrayList<>());
        ServerPackBuilder.Result result = new ServerPackBuilder(source, fakeInstaller).build(pack, output,
                (stage, downloaded, total, current) -> {
                    stages.add(stage);
                    if (stage.equals("downloading")) {
                        assertEquals(client.length + server.length + script.length, total);
                        downloadCounts.add(downloaded);
                    }
                });
        assertTrue(stages.contains("downloading"));
        assertTrue(stages.contains("installing"));
        assertTrue(stages.contains("packaging"));
        assertTrue(downloadCounts.contains((long) client.length + server.length + script.length));
        assertEquals(2, result.downloadedMods());
        assertEquals(1, result.keptMods());
        assertEquals(1, result.excludedMods());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertNotNull(zip.getEntry("mods/server.jar"));
            assertNull(zip.getEntry("mods/client.jar"));
            assertNotNull(zip.getEntry("config/server.txt"));
            assertNotNull(zip.getEntry("defaultconfigs/common.toml"));
            assertNotNull(zip.getEntry("kubejs/server_scripts/init.js"));
            assertNotNull(zip.getEntry("kubejs/startup_scripts/root.js"));
            assertNotNull(zip.getEntry("server.properties"));
            assertNull(zip.getEntry("config/client-only.cfg"));
            assertNull(zip.getEntry("resourcepacks/client.zip"));
            assertNull(zip.getEntry("config/client.txt"));
            assertNotNull(zip.getEntry("neoforge-26.1.2.114-installer.jar"));
            assertNotNull(zip.getEntry("start-server.bat"));
            assertNotNull(zip.getEntry("run.bat"));
            assertNotNull(zip.getEntry("libraries/core.jar"));
        }
    }

    private byte[] fixture(String name) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(in);
            return in.readAllBytes();
        }
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes)); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}

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
        String index = """
                {"dependencies":{"minecraft":"26.1.2","neoforge":"26.1.2.114"},"files":[
                  {"path":"mods/client.jar","hashes":{"sha512":"%s"},"fileSize":%d,
                   "downloads":["https://cdn.modrinth.com/data/AANobbMI/versions/abcdefgh/client.jar"],
                   "env":{"server":"unsupported"}},
                  {"path":"mods/server.jar","hashes":{"sha512":"%s"},"fileSize":%d,
                   "downloads":["https://cdn.modrinth.com/data/XxWD5pD3/versions/abcdefgh/server.jar"],
                   "env":{"server":"required"}}]}
                """.formatted(hash(client), client.length, hash(server), server.length);
        Path pack = temp.resolve("input.mrpack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            put(zip, "modrinth.index.json", index.getBytes(StandardCharsets.UTF_8));
            put(zip, "server-overrides/config/server.txt", "server".getBytes(StandardCharsets.UTF_8));
            put(zip, "client-overrides/config/client.txt", "client".getBytes(StandardCharsets.UTF_8));
        }
        PackFileSource source = new PackFileSource() {
            @Override public void download(List<String> urls, Path target, String sha512, long expectedBytes)
                    throws IOException {
                byte[] bytes = urls.getFirst().contains("client.jar") ? client :
                        urls.getFirst().contains("server.jar") ? server : installer;
                assertEquals(hash(bytes), sha512);
                if (expectedBytes >= 0) assertEquals(bytes.length, expectedBytes);
                Files.createDirectories(target.getParent());
                Files.write(target, bytes);
            }
            @Override public void verifyLocal(Path file, String sha512, long expectedBytes) { fail("Not embedded"); }
            @Override public byte[] getSmall(URI uri, int maxBytes) { return hash(installer).getBytes(StandardCharsets.UTF_8); }
        };
        Path output = temp.resolve("server.zip");
        CoreInstaller fakeInstaller = (directory, name) -> {
            Files.createDirectories(directory.resolve("libraries"));
            Files.writeString(directory.resolve("run.bat"), "@echo off\r\n");
            Files.writeString(directory.resolve("run.sh"), "#!/bin/sh\n");
            Files.writeString(directory.resolve("libraries/core.jar"), "core");
        };
        ServerPackBuilder.Result result = new ServerPackBuilder(source, fakeInstaller).build(pack, output);
        assertEquals(1, result.keptMods());
        assertEquals(1, result.excludedMods());
        try (ZipFile zip = new ZipFile(output.toFile())) {
            assertNotNull(zip.getEntry("mods/server.jar"));
            assertNull(zip.getEntry("mods/client.jar"));
            assertNotNull(zip.getEntry("config/server.txt"));
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

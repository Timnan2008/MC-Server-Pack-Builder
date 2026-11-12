package dev.mcspb.cli;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.file.*;
import java.util.zip.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @TempDir Path temp;
    @Test void noArgumentsShowHelpWithoutFailingIdeRun() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int status = Main.run(new String[0], new PrintStream(output), System.err);
        assertEquals(0, status);
        assertTrue(output.toString().contains("Program arguments"));
    }

    @Test void malformedArgumentsStillFailClearly() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int status = Main.run(new String[]{"mods", "--output"}, System.out, new PrintStream(errors));
        assertEquals(2, status);
        assertTrue(errors.toString().contains("Invalid arguments"));
    }

    @Test void singleJarPrintsOnlyBooleanAndFolderDoesNotWriteJson() throws Exception {
        try (var in = getClass().getResourceAsStream("/fixtures/fabric.jar")) {
            Files.copy(in, temp.resolve("fabric.jar"));
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{temp.resolve("fabric.jar").toString()},
                new PrintStream(output), System.err));
        assertEquals("true", output.toString().trim());
        output.reset();
        assertEquals(0, Main.run(new String[]{temp.toString()}, new PrintStream(output), System.err));
        assertEquals("fabric.jar\ttrue", output.toString().trim());
        assertFalse(Files.exists(temp.resolve("scan-report.json")));
    }

    @Test void crashReportChangesOnlyTheNamedJarToRemove() throws Exception {
        Path jar = Files.createFile(temp.resolve("ruok.jar"));
        Path report = temp.resolve("crash.txt");
        Files.writeString(report, "Description: Mod loading error has occurred\n" +
                "-- MOD ruokmod --\nMod File: /home/container/mods/ruok.jar\n" +
                "Failure message: class loading errors\n" +
                "Exception message: invalid dist DEDICATED_SERVER\n");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{jar.toString(), "--crash-report", report.toString()},
                new PrintStream(output), System.err));
        assertEquals("false", output.toString().trim());
    }

    @Test void packInputPrintsSidesAndCanWriteJson() throws Exception {
        Path pack = temp.resolve("client.mrpack");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(pack))) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json"));
            zip.write("{\"files\":[{\"path\":\"mods/client.jar\",\"env\":{\"server\":\"unsupported\"}}]}".getBytes());
            zip.closeEntry();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{pack.toString()}, new PrintStream(output), System.err));
        assertTrue(output.toString().contains("mods/client.jar\tCLIENT_ONLY"));
        Path json = temp.resolve("result.json");
        assertEquals(0, Main.run(new String[]{pack.toString(), "--output", json.toString()},
                new PrintStream(output), System.err));
        assertTrue(Files.readString(json).contains("CLIENT_ONLY"));
    }
}

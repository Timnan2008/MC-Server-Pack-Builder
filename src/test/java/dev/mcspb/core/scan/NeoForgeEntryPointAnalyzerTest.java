package dev.mcspb.core.scan;

import dev.mcspb.core.model.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.*;
import static org.junit.jupiter.api.Assertions.*;

class NeoForgeEntryPointAnalyzerTest {
    @TempDir Path temp;
    private static final String MOD = "Lnet/neoforged/fml/common/Mod;";
    private static final String DIST = "Lnet/neoforged/api/distmarker/Dist;";

    @Test void onlyClientRestrictedEntryPointsCanBeExcluded() throws Exception {
        Path jar = jar(List.of("sample"), Map.of("Client", entry("sample", "CLIENT")));
        ArtifactResult artifact = new JarScanner().scanFile(jar);
        assertEquals(ModSide.CLIENT_ONLY, ModSideClassifier.classify("sample.jar", artifact).side());
        assertEquals(Set.of(PhysicalSide.CLIENT), artifact.bytecode().entryPoints().getFirst().sides());
    }

    @Test void commonAndServerEntryPointsCauseRetention() throws Exception {
        Path common = jar(List.of("sample"), Map.of("Common", entry("sample")));
        assertEquals(ModSide.SERVER_COMPATIBLE,
                ModSideClassifier.classify("common.jar", new JarScanner().scanFile(common)).side());
        Path mixed = jar(List.of("sample"), Map.of(
                "Client", entry("sample", "CLIENT"),
                "Server", entry("sample", "DEDICATED_SERVER")));
        assertEquals(ModSide.SERVER_COMPATIBLE,
                ModSideClassifier.classify("mixed.jar", new JarScanner().scanFile(mixed)).side());
    }

    @Test void everyDeclaredModInJarMustBeClientOnly() throws Exception {
        Path jar = jar(List.of("one", "two"), Map.of("One", entry("one", "CLIENT")));
        assertEquals(ModSide.KEEP_ON_SERVER,
                ModSideClassifier.classify("two-mods.jar", new JarScanner().scanFile(jar)).side());
    }

    @Test void unknownAnnotationSidePreventsClientOnlyDecision() throws Exception {
        Path jar = jar(List.of("sample"), Map.of("Bad", entry("sample", "SOME_FUTURE_SIDE")));
        ArtifactResult artifact = new JarScanner().scanFile(jar);
        assertFalse(artifact.bytecode().complete());
        assertEquals(ModSide.UNKNOWN, ModSideClassifier.classify("bad.jar", artifact).side());
    }

    @Test void noEntryPointKeepsJar() throws Exception {
        Path jar = jar(List.of("sample"), Map.of());
        assertEquals(ModSide.KEEP_ON_SERVER,
                ModSideClassifier.classify("no-entry.jar", new JarScanner().scanFile(jar)).side());
    }

    private Path jar(List<String> ids, Map<String, byte[]> classes) throws Exception {
        Path jar = Files.createTempFile(temp, "neo-", ".jar");
        StringBuilder toml = new StringBuilder("modLoader=\"javafml\"\nloaderVersion=\"[1,)\"\n");
        ids.forEach(id -> toml.append("[[mods]]\nmodId=\"").append(id).append("\"\nversion=\"1\"\n"));
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            put(zip, "META-INF/neoforge.mods.toml", toml.toString().getBytes(StandardCharsets.UTF_8));
            for (var item : classes.entrySet()) put(zip, item.getKey() + ".class", item.getValue());
        }
        return jar;
    }

    private static byte[] entry(String modId, String... sides) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, "Example" + UUID.randomUUID().toString().replace("-", ""),
                null, "java/lang/Object", null);
        AnnotationVisitor mod = writer.visitAnnotation(MOD, true);
        mod.visit("value", modId);
        if (sides.length > 0) {
            AnnotationVisitor dist = mod.visitArray("dist");
            for (String side : sides) dist.visitEnum(null, DIST, side);
            dist.visitEnd();
        }
        mod.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }
}

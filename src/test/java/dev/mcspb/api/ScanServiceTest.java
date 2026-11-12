package dev.mcspb.api;

import dev.mcspb.core.model.ModSide;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ScanServiceTest {
    @TempDir Path temp;

    @Test void scansSingleJarAndFolderWithSameClassification() throws Exception {
        Path jar = temp.resolve("fabric.jar");
        try (var in = getClass().getResourceAsStream("/fixtures/fabric.jar")) {
            assertNotNull(in);
            Files.copy(in, jar);
        }
        ScanService service = new ScanService();
        var single = service.scan(jar);
        var folder = service.scan(temp);
        assertEquals("jar", single.kind());
        assertEquals("folder", folder.kind());
        assertEquals(1, single.entries().size());
        assertEquals(ModSide.CLIENT_ONLY, single.entries().getFirst().side());
        assertEquals(single.entries().getFirst().side(), folder.entries().getFirst().side());
    }

    @Test void neoforgeJarIsKeptByDefault() throws Exception {
        Path jar = temp.resolve("neoforge.jar");
        try (var in = getClass().getResourceAsStream("/fixtures/neoforge.jar")) {
            assertNotNull(in);
            Files.copy(in, jar);
        }
        var result = new ScanService().scan(jar);
        assertEquals(ModSide.KEEP_ON_SERVER, result.entries().getFirst().side());
    }
}

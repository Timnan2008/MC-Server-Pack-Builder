package dev.mcspb.core.scan;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.mcspb.core.model.ModSide;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModrinthProjectLookupTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void recognizesPublicProjectSideDeclarations() throws Exception {
        assertEquals(ModSide.CLIENT_ONLY, side("client_only", "unsupported"));
        assertEquals(ModSide.SERVER_COMPATIBLE, side("client_and_server", "required"));
        assertEquals(ModSide.SERVER_COMPATIBLE, side("client_only_server_optional", "optional"));
        assertEquals(ModSide.KEEP_ON_SERVER,
                ModrinthProjectLookup.classify(mapper.readTree("""
                        {"environment":["client_only","client_and_server"],"server_side":"optional"}
                        """)).side());
        assertEquals(ModSide.UNKNOWN, side("unknown", "unknown"));
        assertEquals(ModSide.UNKNOWN, side("client_only", "required"));
        assertEquals(ModSide.UNKNOWN, side("client_and_server", "unsupported"));
    }

    @Test void onlyExtractsIdsFromExpectedCdnUrls() throws Exception {
        assertEquals("AANobbMI", ModrinthProjectLookup.projectId(mapper.readTree("""
                ["https://cdn.modrinth.com/data/AANobbMI/versions/zg4YQ9EL/sodium.jar"]
                """)));
        assertNull(ModrinthProjectLookup.projectId(mapper.readTree("""
                ["https://example.com/data/AANobbMI/versions/zg4YQ9EL/sodium.jar"]
                """)));
    }

    private ModSide side(String environment, String server) throws Exception {
        return ModrinthProjectLookup.classify(mapper.readTree("""
                {"environment":["%s"],"server_side":"%s"}
                """.formatted(environment, server))).side();
    }
}

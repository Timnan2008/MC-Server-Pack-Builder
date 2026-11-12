package dev.mcspb.core.metadata;

import com.fasterxml.jackson.databind.*;
import dev.mcspb.core.model.*;
import java.io.IOException;
import java.util.*;

public final class FabricMetadataParser implements MetadataParser {
    private final ObjectMapper mapper = new ObjectMapper();
    public Loader loader() { return Loader.FABRIC; }
    public String entryName() { return "fabric.mod.json"; }

    public List<ModDeclaration> parse(byte[] metadata) throws IOException {
        JsonNode root = mapper.readTree(metadata);
        if (root == null || !root.isObject()) throw new IOException("Fabric metadata must be an object");
        String id = string(root, "id");
        if (id == null || id.isBlank()) throw new IOException("Missing Fabric mod id");
        List<Dependency> deps = new ArrayList<>();
        collect(root.path("depends"), true, deps);
        collect(root.path("recommends"), false, deps);
        collect(root.path("suggests"), false, deps);
        return List.of(new ModDeclaration(id, string(root, "version"), string(root, "name"),
                Loader.FABRIC, string(root, "environment"), null, deps));
    }

    private static void collect(JsonNode node, boolean required, List<Dependency> deps) {
        if (!node.isObject()) return;
        node.properties().forEach(entry -> {
            JsonNode range = entry.getValue();
            deps.add(new Dependency(entry.getKey(), range.isTextual() ? range.asText() : range.toString(), required));
        });
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }
}

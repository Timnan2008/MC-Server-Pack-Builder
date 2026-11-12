package dev.mcspb.core.metadata;

import dev.mcspb.core.model.*;
import org.tomlj.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class TomlMetadataParser implements MetadataParser {
    private final Loader loader;

    public TomlMetadataParser(Loader loader) {
        if (loader == Loader.FABRIC) throw new IllegalArgumentException("Fabric uses JSON");
        this.loader = loader;
    }

    public Loader loader() { return loader; }
    public String entryName() {
        return loader == Loader.FORGE ? "META-INF/mods.toml" : "META-INF/neoforge.mods.toml";
    }

    public List<ModDeclaration> parse(byte[] metadata) throws IOException {
        TomlParseResult root = Toml.parse(new String(metadata, StandardCharsets.UTF_8));
        if (root.hasErrors()) throw new IOException("Invalid TOML: " + root.errors().getFirst());
        TomlArray mods = root.getArray("mods");
        if (mods == null || mods.isEmpty()) throw new IOException("Missing [[mods]] declarations");
        List<ModDeclaration> result = new ArrayList<>();
        for (int i = 0; i < mods.size(); i++) {
            if (!(mods.get(i) instanceof TomlTable mod)) throw new IOException("Invalid [[mods]] declaration");
            String id = value(mod, "modId");
            if (id == null || id.isBlank()) throw new IOException("Missing modId in [[mods]]");
            List<Dependency> deps = new ArrayList<>();
            TomlTable dependencyGroups = root.getTable("dependencies");
            if (dependencyGroups != null) {
                // Dependency tables are grouped under the declaring mod ID, not the JAR name.
                TomlArray group = dependencyGroups.getArray(id);
                if (group != null) for (int j = 0; j < group.size(); j++) {
                    if (!(group.get(j) instanceof TomlTable dep)) continue;
                    String depId = value(dep, "modId");
                    if (depId != null && !depId.isBlank()) {
                        Boolean mandatory = dep.getBoolean("mandatory"); // Forge
                        String type = value(dep, "type"); // NeoForge
                        boolean required = mandatory != null ? mandatory : "required".equalsIgnoreCase(type);
                        deps.add(new Dependency(depId, value(dep, "versionRange"), required));
                    }
                }
            }
            result.add(new ModDeclaration(id, value(mod, "version"), value(mod, "displayName"),
                    loader, null, value(mod, "displayTest"), deps));
        }
        return result;
    }

    private static String value(TomlTable table, String key) {
        Object value = table.get(key);
        return value == null ? null : value.toString();
    }
}

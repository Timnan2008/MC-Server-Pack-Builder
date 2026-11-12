package dev.mcspb.core.model;

import java.util.List;

public record ModDeclaration(String id, String version, String name, Loader loader,
                             String declaredEnvironment, String displayTest, List<Dependency> dependencies) {
    public ModDeclaration { dependencies = List.copyOf(dependencies); }
}

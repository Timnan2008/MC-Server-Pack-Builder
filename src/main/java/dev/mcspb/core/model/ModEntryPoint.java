package dev.mcspb.core.model;

import java.util.Set;

public record ModEntryPoint(String modId, String className, Set<PhysicalSide> sides,
                            boolean explicitSideRestriction) {
    public ModEntryPoint { sides = Set.copyOf(sides); }
}

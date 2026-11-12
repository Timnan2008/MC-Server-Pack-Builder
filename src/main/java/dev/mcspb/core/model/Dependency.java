package dev.mcspb.core.model;

public record Dependency(String modId, String versionRange, boolean required) { }

package dev.mcspb.core.model;

public record Diagnostic(Severity severity, String code, String message) {
    public enum Severity { WARNING, ERROR }
}

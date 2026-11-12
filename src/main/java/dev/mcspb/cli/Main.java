package dev.mcspb.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.mcspb.core.scan.JarScanner;
import dev.mcspb.core.scan.PackScanner;
import dev.mcspb.core.scan.ServerRetentionClassifier;
import dev.mcspb.core.scan.ServerPackBuilder;
import java.io.PrintStream;
import java.nio.file.*;
import java.util.concurrent.*;

public final class Main {
    private Main() { }

    public static void main(String[] args) {
        int status = run(args, System.out, System.err);
        if (status != 0) System.exit(status);
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
            out.println("MC Server Pack Builder — server retention decision");
            out.println("No mods folder was supplied. In your IDE, set Program arguments to:");
            out.println("  <mod.jar> or <mods-folder>");
            out.println("  <client-pack.mrpack> or <client-pack.zip> [--output <report.json>]");
            out.println("  <client-pack.mrpack> --server-zip <server-pack.zip>");
            out.println("  <mod.jar> --crash-report <forge-crash-report.txt>");
            out.println("Optional: <mods-folder> --output <report.json> for a detailed report");
            out.println("This milestone has no graphical interface yet.");
            return 0;
        }
        if (args.length != 1 && (args.length != 3 ||
                (!"--output".equals(args[1]) && !"--crash-report".equals(args[1]) &&
                 !"--server-zip".equals(args[1])))) {
            err.println("Invalid arguments. Run with --help for usage.");
            return 2;
        }
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Path source = Path.of(args[0]);
            Path crashReport = args.length == 3 && "--crash-report".equals(args[1]) ? Path.of(args[2]) : null;
            String filename = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
            if (Files.isRegularFile(source) && (filename.endsWith(".mrpack") || filename.endsWith(".zip"))) {
                if (crashReport != null) {
                    err.println("--crash-report applies only to a JAR or mods folder.");
                    return 2;
                }
                if (args.length == 3 && "--server-zip".equals(args[1])) {
                    var built = new ServerPackBuilder().build(source, Path.of(args[2]));
                    out.printf("Built %s: %d kept, %d client-only excluded%n",
                            built.output(), built.keptMods(), built.excludedMods());
                    return 0;
                }
                var pack = new PackScanner().scan(source);
                if (args.length == 3) {
                    Path target = Path.of(args[2]).toAbsolutePath().normalize();
                    if (target.equals(source.toAbsolutePath().normalize())) {
                        err.println("Report path must differ from source pack.");
                        return 2;
                    }
                    if (target.getParent() != null) Files.createDirectories(target.getParent());
                    new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(target.toFile(), pack);
                    out.printf("Scanned %d mod(s). Report: %s%n", pack.mods().size(), target);
                } else {
                    for (var mod : pack.mods()) out.printf("%s\t%s\t%s%n", mod.path(), mod.side(), mod.reason());
                    for (var diagnostic : pack.diagnostics())
                        err.printf("%s: %s%n", diagnostic.code(), diagnostic.message());
                }
                return pack.diagnostics().stream().anyMatch(d -> d.severity() == dev.mcspb.core.model.Diagnostic.Severity.ERROR) ? 1 : 0;
            }
            if (args.length == 1 && Files.isRegularFile(source)) {
                out.println(true); // No confirmed startup failure was supplied.
                return 0;
            }
            if (crashReport != null && Files.isRegularFile(source)) {
                out.println(ServerRetentionClassifier.shouldKeep(source, crashReport));
                return 0;
            }
            var report = new JarScanner().scanAsync(source, executor).get();
            if (args.length == 3 && "--output".equals(args[1])) {
                Path target = Path.of(args[2]).toAbsolutePath().normalize();
                if (target.getParent() != null) Files.createDirectories(target.getParent());
                new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(target.toFile(), report);
                out.printf("Scanned %d JAR(s). Report: %s%n", report.artifacts().size(), target);
            } else {
                for (var artifact : report.artifacts())
                    out.printf("%s\t%s%n", artifact.path(), crashReport == null ||
                            ServerRetentionClassifier.shouldKeep(source.resolve(artifact.path()), crashReport));
            }
            return 0;
        } catch (Exception e) {
            err.println("Scan failed: " + e.getMessage());
            return 1;
        }
    }
}

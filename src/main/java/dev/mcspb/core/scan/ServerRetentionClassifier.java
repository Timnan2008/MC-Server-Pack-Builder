package dev.mcspb.core.scan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps a JAR unless a Forge crash report explicitly identifies it as failing on a dedicated server. */
public final class ServerRetentionClassifier {
    private static final long MAX_REPORT_BYTES = 8_000_000;
    private static final Pattern MOD_SECTION = Pattern.compile("(?m)^-- MOD [^\\r\\n]+ --\\s*$");
    private static final Pattern MOD_FILE = Pattern.compile("(?m)^\\s*Mod File:\s*(.+\\.jar)\\s*$");

    private ServerRetentionClassifier() { }

    public static boolean shouldKeep(Path jar, Path crashReport) throws IOException {
        if (!Files.isRegularFile(jar)) throw new IOException("Not a file: " + jar);
        if (Files.size(crashReport) > MAX_REPORT_BYTES) throw new IOException("Crash report exceeds size limit");
        String report = Files.readString(crashReport);
        if (!report.contains("Description: Mod loading error has occurred")) return true;
        Matcher sections = MOD_SECTION.matcher(report);
        while (sections.find()) {
            int start = sections.end();
            int end = sections.find() ? sections.start() : report.length();
            String section = report.substring(start, end);
            Matcher file = MOD_FILE.matcher(section);
            if (file.find() && Path.of(file.group(1).trim()).getFileName().toString()
                    .equalsIgnoreCase(jar.getFileName().toString()) &&
                    section.contains("invalid dist DEDICATED_SERVER")) return false;
            if (end == report.length()) break;
            sections.region(end, report.length());
        }
        return true;
    }
}

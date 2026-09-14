package dev.jvmd.core;

import java.nio.file.Files;
import java.nio.file.Path;

/** Implements 4.10: report runtime auto-mode cache acceptance and rejection reasons. */
public final class AotStatus {
    private AotStatus() { }
    public static String runtime(Path log) {
        if (java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments().stream()
                .noneMatch(arg -> arg.startsWith("-XX:AOTCache="))) return "rejected: no cache configured for this JVM";
        return read(log);
    }
    public static String read(Path log) {
        if (log == null || !Files.isRegularFile(log)) return "rejected: no AOT log configured";
        try {
            var lines = Files.readAllLines(log);
            for (String line : lines) {
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("unable") || lower.contains("cannot") || lower.contains("failed")
                        || lower.contains("mismatch") || lower.contains("does not match")) return "rejected: " + line;
            }
            if (lines.stream().anyMatch(line -> line.contains("Opened archive") || line.contains("Mapped static"))) return "used";
            return "rejected: cache mapping not confirmed in log";
        } catch (java.io.IOException e) { return "rejected: " + e.getMessage(); }
    }
}

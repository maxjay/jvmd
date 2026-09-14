package dev.jvmd.resolver;

import dev.jvmd.core.Config;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Implements 4.3: Maven settings, major alignment and offline-first sessions. */
public final class MavenEnvironment {
    private final Config config;
    private final Path settingsFile;
    public MavenEnvironment(Config config) { this(config, Path.of(System.getProperty("user.home"), ".m2/settings.xml")); }
    public MavenEnvironment(Config config, Path settingsFile) {
        this.config = config; this.settingsFile = settingsFile;
        // Maven initializes the default timezone while building a model. Do it before cache keys.
        java.util.TimeZone.getDefault();
    }
    public Path settingsFile() { return settingsFile; }
    public Properties systemProperties() {
        var props = new Properties(); props.putAll(System.getProperties());
        System.getenv().forEach((key, value) -> props.setProperty("env." + key, value));
        return props;
    }
    public Path globalSettingsFile() {
        String home = System.getProperty("maven.home", System.getenv("MAVEN_HOME"));
        return home == null ? null : Path.of(home, "conf/settings.xml");
    }
    public List<Path> settingsInputs(List<Path> roots) {
        var files = new ArrayList<Path>(); files.add(settingsFile);
        Path global = globalSettingsFile(); if (global != null) files.add(global);
        Path home = Path.of(System.getProperty("user.home"), ".m2");
        files.add(home.resolve("settings-security.xml")); files.add(home.resolve("settings-security4.xml"));
        for (Path root : roots) files.add(root.resolve(".mvn/settings.xml"));
        return List.copyOf(files);
    }
    public List<String> versionWarnings(Path root) {
        var warnings = new ArrayList<String>(); String version = null;
        Path wrapper = root.resolve(".mvn/wrapper/maven-wrapper.properties");
        try {
            if (Files.isRegularFile(wrapper)) {
                var properties = new Properties();
                try (var input = Files.newInputStream(wrapper)) { properties.load(input); }
                var match = java.util.regex.Pattern.compile("apache-maven-(\\d+)\\.").matcher(properties.getProperty("distributionUrl", ""));
                if (match.find()) version = match.group(1);
            }
            if (version == null) {
                var process = new ProcessBuilder("mvn", "-v").directory(root.toFile()).redirectErrorStream(true).start();
                var output = new java.io.ByteArrayOutputStream();
                Thread reader = Thread.ofVirtual().start(() -> { try { process.getInputStream().transferTo(output); } catch (java.io.IOException ignored) { } });
                if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); warnings.add("Maven version detection timed out"); }
                reader.join(1000);
                var match = java.util.regex.Pattern.compile("Apache Maven (\\d+)\\.").matcher(output.toString(java.nio.charset.StandardCharsets.UTF_8));
                if (match.find()) version = match.group(1);
            }
        } catch (Exception e) { warnings.add("Maven version detection unavailable: " + e.getClass().getSimpleName()); }
        if (version != null && Integer.parseInt(version) != config.mavenMajor()) warnings.add("Maven major mismatch: build=" + version + ", resolver=" + config.mavenMajor());
        return warnings;
    }
}

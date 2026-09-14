package dev.jvmd.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Implements 4.1: machine-scoped configuration and private runtime paths. */
public record Config(Path jdkHome, Path jbrHome, Path m2Repo, int mavenMajor,
                     Duration idleTimeout, int heapCeilingMb, boolean indexOnStart,
                     Path stateDir, Path socket) {
    public Config {
        if (mavenMajor != 3 && mavenMajor != 4) throw new IllegalArgumentException("maven_major must be 3 or 4");
        if (heapCeilingMb < 64 || idleTimeout.isNegative() || idleTimeout.isZero())
            throw new IllegalArgumentException("Invalid resource limits");
    }
    public static Config load() throws IOException {
        Path home = Path.of(System.getProperty("user.home"));
        Path configPath = Path.of(System.getProperty("jvmd.config", home.resolve(".config/jvmd/config.json").toString()));
        JsonNode config = Files.isRegularFile(configPath) ? Json.MAPPER.readTree(configPath.toFile()) : Json.MAPPER.createObjectNode();
        Path state = Path.of(System.getProperty("jvmd.state", home.resolve(".cache/jvmd").toString()));
        String runtime = System.getenv("XDG_RUNTIME_DIR");
        long uid = ((Number) Files.getAttribute(home, "unix:uid")).longValue();
        Path runtimeDir = runtime == null ? Path.of(System.getProperty("java.io.tmpdir"), "jvmd-" + uid) : Path.of(runtime);
        Files.createDirectories(runtimeDir);
        if (runtime == null) Files.setPosixFilePermissions(runtimeDir, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        Path socket = Path.of(System.getProperty("jvmd.socket", runtimeDir.resolve("jvmd-" + uid + ".sock").toString()));
        return new Config(Path.of(config.path("jdk_home").asText(System.getProperty("java.home"))),
                config.hasNonNull("jbr_home") ? Path.of(config.get("jbr_home").asText()) : null,
                Path.of(config.path("m2_repo").asText(home.resolve(".m2/repository").toString())),
                config.path("maven_major").asInt(3), Duration.ofSeconds(config.path("idle_timeout").asLong(14400)),
                config.path("heap_ceiling_mb").asInt(1024), config.path("index_on_start").asBoolean(true), state, socket);
    }
}

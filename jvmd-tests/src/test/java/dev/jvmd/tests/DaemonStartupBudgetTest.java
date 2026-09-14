package dev.jvmd.tests;

import dev.jvmd.core.*;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 exit: strict AOT startup <600 ms and 2k-line overview <50 ms. */
@Tag("phase-1") @Tag("perf")
class DaemonStartupBudgetTest {
    @TempDir Path temp;
    @Test void strictAotStartupAndOverviewBudgets() throws Exception {
        Path image = TestSupport.repo().resolve("jvmd-dist/target/image");
        Path fixture = temp.resolve("Large.java");
        var text = new StringBuilder("class Large {\n");
        for (int i = 0; i < 1998; i++) text.append("  int value").append(i).append(";\n");
        Files.writeString(fixture, text.append("}\n").toString());
        Path socket = temp.resolve("perf.sock"), log = temp.resolve("perf.log"), aotLog = temp.resolve("aot.log");
        var command = new ArrayList<>(java.util.List.of(image.resolve("bin/java").toString(),
                "-XX:AOTCache=" + image.resolve("lib/jvmd/jvmd.aot"), "-XX:AOTMode=on",
                "-Xlog:aot=info:file=" + aotLog, "-Djvmd.aot.log=" + aotLog,
                "-Djvmd.socket=" + socket, "-Djvmd.state=" + temp.resolve("state"),
                "-cp", image.resolve("lib/jvmd/*").toString(), "dev.jvmd.dist.Application"));
        long started = System.nanoTime();
        var process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try (var channel = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            while (!Files.exists(socket) && process.isAlive() && System.nanoTime() - started < TimeUnit.SECONDS.toNanos(10)) Thread.sleep(2);
            assertThat(Files.exists(socket)).withFailMessage(Files.readString(log)).isTrue();
            channel.connect(UnixDomainSocketAddress.of(socket));
            var in = Channels.newInputStream(channel); var out = Channels.newOutputStream(channel);
            Framing.write(out, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"daemon.status\"}".getBytes());
            var status = Json.MAPPER.readTree(Framing.read(in));
            double startupMs = (System.nanoTime() - started) / 1e6;
            assertThat(status.path("result").path("result").path("aot_cache").asText()).isEqualTo("used");
            var request = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 2).put("method", "session.open");
            request.putObject("params").put("root", temp.toString());
            Framing.write(out, Json.MAPPER.writeValueAsBytes(request));
            String session = Json.MAPPER.readTree(Framing.read(in)).path("result").path("result").path("session").asText();
            request.put("method", "symbol.overview");
            request.putObject("params").put("session", session).put("path", fixture.toString()).put("depth", 1).put("limit", 20);
            double[] times = new double[15];
            for (int i = -5; i < times.length; i++) {
                long before = System.nanoTime();
                Framing.write(out, Json.MAPPER.writeValueAsBytes(request));
                var reply = Json.MAPPER.readTree(Framing.read(in));
                assertThat(reply.path("result").path("result").path("symbols").size()).isEqualTo(20);
                if (i >= 0) times[i] = (System.nanoTime() - before) / 1e6;
            }
            java.util.Arrays.sort(times);
            var measures = java.util.Map.of("startup_ms", startupMs, "overview_p50_ms", times[7], "overview_p95_ms", times[14]);
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/phase-1-perf.json").toFile(), measures);
            assertThat(startupMs).as("strict AOT cold startup ms").isLessThan(600);
            assertThat(times[14]).as("2k-line overview p95 ms").isLessThan(50);
        } finally { process.destroy(); if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly(); }
    }
}

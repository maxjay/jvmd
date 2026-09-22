package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: JSON-RPC 2.0 server with Content-Length framing. */
@Tag("phase-1")
class JsonRpcServerTest {
    @TempDir Path temp;
    @Test void fragmentedUtf8AndConsecutiveFrames() throws Exception {
        var output = new ByteArrayOutputStream();
        byte[] payload = "名字😀".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Framing.write(output, payload); Framing.write(output, new byte[0]);
        var input = new ByteArrayInputStream(output.toByteArray()) {
            @Override public synchronized int read(byte[] b, int off, int len) { return super.read(b, off, Math.min(len, 1)); }
        };
        assertThat(Framing.read(input)).isEqualTo(payload);
        assertThat(Framing.read(input)).isEmpty();
        assertThat(Framing.read(input)).isNull();
        assertThatThrownBy(() -> Framing.read(new ByteArrayInputStream("Content-Length: -1\r\n\r\n".getBytes()))).isInstanceOf(java.io.IOException.class);
    }
    @Test void idleExpiryStartsOnlyAfterReady() throws Exception {
        var config = TestSupport.config(temp, Duration.ofMillis(50));
        var app = new Application(config);
        try (var server = new UnixServer(config, app.dispatcher(), app)) {
            server.start();
            Thread.sleep(150);
            assertThat(server.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)).isFalse();
            assertThat(java.nio.file.Files.exists(config.socket())).isTrue();

            server.ready();
            assertThat(server.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }
    @Test void concurrentCloseWaitsForResourceCleanup() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var sessions = new Sessions()) {
            var server = new UnixServer(config, new Dispatcher(sessions, new Metrics()), () -> {
                entered.countDown();
                release.await(5, java.util.concurrent.TimeUnit.SECONDS);
            });
            server.start();
            try (var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var first = workers.submit(server::close);
                assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var second = workers.submit(server::close);
                Thread.sleep(50);
                assertThat(second.isDone()).isFalse();
                release.countDown();
                first.get(2, java.util.concurrent.TimeUnit.SECONDS);
                second.get(2, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(server.await(10, java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();
            } finally {
                release.countDown();
                server.close();
            }
        }
    }
    @Test void actualUnixSocketAndMalformedJsonIsolation() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        var app = new Application(config);
        try (var server = new UnixServer(config, app.dispatcher(), app)) {
            server.start();
            try (var client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                client.connect(UnixDomainSocketAddress.of(config.socket()));
                var in = Channels.newInputStream(client); var out = Channels.newOutputStream(client);
                Framing.write(out, "{".getBytes());
                assertThat(Json.MAPPER.readTree(Framing.read(in)).path("error").path("code").asInt()).isEqualTo(-32700);
                Framing.write(out, "{\"jsonrpc\":\"2.0\",\"id\":42,\"method\":\"daemon.status\"}".getBytes());
                var reply = Json.MAPPER.readTree(Framing.read(in));
                assertThat(reply.path("id").asInt()).isEqualTo(42);
                assertThat(reply.path("result").path("tier").asInt(-1)).isEqualTo(2);
            }
        }
    }
}

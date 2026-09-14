package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 1 checkpoint: envelope enforcement across every dispatcher method. */
@Tag("phase-1")
class EnvelopeTest {
    @TempDir Path temp;
    @Test void malformedRequestsGetErrorsEvenWithoutAnId() throws Exception {
        try (var sessions = new Sessions()) {
            var dispatcher = new Dispatcher(sessions, new Metrics());
            var malformed = Json.MAPPER.createObjectNode().put("method", "daemon.status");
            assertThat(dispatcher.dispatch(malformed).path("error").path("code").asInt()).isEqualTo(-32600);
            assertThat(dispatcher.dispatch(null).path("error").path("code").asInt()).isEqualTo(-32600);
            var notification = malformed.put("jsonrpc", "2.0");
            assertThat(dispatcher.dispatch(notification)).isNull();
        }
    }
    @Test void allMethodsAndFaultsCarryRequiredFields() throws Exception {
        try (var app = new Application(TestSupport.config(temp, Duration.ofHours(4)))) {
            String session = TestSupport.open(app, temp);
            var methods = new java.util.ArrayList<>(app.dispatcher().methods());
            methods.remove("daemon.shutdown"); methods.remove("session.close");
            for (String method : methods) assertEnvelope(TestSupport.request(app.dispatcher(), method, Map.of("session", session, "root", temp.toString())));
            app.dispatcher().register("test.missing", (_, _) -> null);
            var response = TestSupport.request(app.dispatcher(), "test.missing", Map.of("session", session));
            assertEnvelope(response);
            assertThat(response.path("result").path("warnings").toString()).contains("Handler omitted envelope");
            app.dispatcher().register("test.crash", (_, _) -> { throw new AssertionError("fixture"); });
            assertEnvelope(TestSupport.request(app.dispatcher(), "test.crash", Map.of("session", session)));
            assertEnvelope(TestSupport.request(app.dispatcher(), "unknown", Map.of()));
            assertEnvelope(TestSupport.request(app.dispatcher(), "session.close", Map.of("session", session)));
            assertEnvelope(TestSupport.request(app.dispatcher(), "daemon.shutdown", Map.of()));
        }
    }
    private void assertEnvelope(com.fasterxml.jackson.databind.JsonNode response) {
        var envelope = response.has("result") ? response.get("result") : response.path("error").path("data");
        for (String field : java.util.List.of("tier", "source", "truncated", "cursor", "warnings"))
            assertThat(envelope.has(field)).as(field + " in " + response).isTrue();
        assertThat(envelope.path("tier").asInt()).isBetween(0, 2);
    }
}

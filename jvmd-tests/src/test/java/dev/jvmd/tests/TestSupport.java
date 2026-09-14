package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.Path;
import java.time.Duration;

/** Implements 12.4: isolated machine state and real dispatcher requests for checkpoint tests. */
public final class TestSupport {
    private TestSupport() { }
    public static Path repo() { return Path.of(System.getProperty("basedir")).getParent(); }
    public static Config config(Path temp, Duration idle) {
        return new Config(Path.of(System.getProperty("java.home")), null, temp.resolve("repository"), 3,
                idle, 512, false, temp.resolve("state"), temp.resolve("daemon.sock"));
    }
    public static JsonNode request(Dispatcher dispatcher, String method, Object params) {
        var request = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", 1).put("method", method);
        request.set("params", Json.MAPPER.valueToTree(params));
        return dispatcher.dispatch(request);
    }
    public static String open(Application app, Path root) {
        return request(app.dispatcher(), "session.open", java.util.Map.of("root", root.toString())).path("result").path("result").path("session").asText();
    }
}

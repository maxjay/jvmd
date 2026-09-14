package dev.jvmd.tests;

import dev.jvmd.core.Config;
import dev.jvmd.core.Json;
import dev.jvmd.resolver.MavenResolver;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/** Implements 12.4: resolver budget measurements in a strict-AOT child JVM. */
public final class ResolverPerformanceProbe {
    public static void main(String[] args) throws Exception {
        var config = new Config(Path.of(System.getProperty("java.home")), null,
                Path.of(System.getProperty("user.home"), ".m2/repository"), Integer.parseInt(args[3]),
                Duration.ofHours(4), 512, false, Path.of(args[1]), Path.of(args[1], "unused.sock"));
        try (var resolver = new MavenResolver(config)) {
            long started = System.nanoTime();
            var graph = resolver.resolve(Path.of(args[0]));
            double cold = (System.nanoTime() - started) / 1e6;
            double[] times = new double[30];
            for (int i = -5; i < times.length; i++) {
                long before = System.nanoTime();
                var cached = resolver.resolve(Path.of(args[0]));
                if (!cached.cached()) throw new AssertionError("Expected a graph-cache hit");
                if (i >= 0) times[i] = (System.nanoTime() - before) / 1e6;
            }
            java.util.Arrays.sort(times);
            var measurements = Map.of("maven_major",Integer.parseInt(args[3]),"cold_ms", cold, "warm_p50_ms", times[14], "warm_p95_ms", times[28], "nodes", graph.nodes().size());
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of(args[2]).toFile(), measurements);
            System.out.println("phase-2-perf " + Json.MAPPER.writeValueAsString(measurements));
            if (cold >= 1000 || times[28] >= 5) throw new AssertionError("Resolver budget exceeded: " + measurements);
        }catch(dev.jvmd.core.RpcException error){System.err.println(Json.MAPPER.writeValueAsString(error.data()));throw error;}
    }
}

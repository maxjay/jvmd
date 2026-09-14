package dev.jvmd.smoke;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import java.nio.file.Path;
import java.util.List;

/** Implements section 9.3: actual embedded offline Spring Boot resolution, before phase 2. */
public final class ResolverOfflineSmoke {
    public static void main(String[] args) throws Exception {
        long start = System.nanoTime();
        var system = new RepositorySystemSupplier().get();
        try {
            // Maven 3's confirmed factory. SessionBuilderSupplier exists only on Resolver 2.
            var session = MavenRepositorySystemUtils.newSession();
            session.setOffline(true);
            session.setConfigProperty("aether.conflictResolver.verbose", true);
            session.setConfigProperty("aether.dependencyCollector.impl", "bf");
            session.setLocalRepositoryManager(system.newLocalRepositoryManager(session,
                    new LocalRepository(Path.of(args[0]).toFile(), "simple")));
            var collect = new CollectRequest(new Dependency(new DefaultArtifact(
                    "org.springframework.boot:spring-boot-starter:4.1.0"), "compile"),
                    List.of(new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2").build()));
            var graph = system.resolveDependencies(session, new DependencyRequest(collect,
                    (node, parents) -> node.getData().get(org.eclipse.aether.util.graph.transformer.ConflictResolver.NODE_DATA_WINNER) == null));
            if (graph.getArtifactResults().isEmpty() || graph.getArtifactResults().stream().anyMatch(r -> !r.isResolved()))
                throw new AssertionError("Spring Boot graph was not completely resolved offline");
            System.out.printf("PASS Spring Boot offline: %d artifacts, cold_ms=%.3f%n", graph.getArtifactResults().size(), (System.nanoTime() - start) / 1e6);
        } finally { system.shutdown(); }
    }
}

package dev.jvmd.resolver.engine;

import java.nio.file.Path;
import java.util.*;
import org.apache.maven.model.Model;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.*;
import org.eclipse.aether.repository.RemoteRepository;

/** Implements 4.3: each bundle supplies its own native model builder and resolver session semantics. */
public interface Models extends AutoCloseable {
    /** Implements 4.3: config-independent native services, transferred to exactly one bundle owner. */
    interface Bootstrap extends AutoCloseable {
        Models create(dev.jvmd.core.Config config,dev.jvmd.resolver.MavenEnvironment environment);
        @Override void close();
    }
    record Built(Model model, List<Path> inputs, List<String> children) { }
    RepositorySystem system();
    String mavenVersion();
    String resolverVersion();
    String modelBuilder();
    default Map<String,Double> timings(){return Map.of();}
    Settings settings(Path root) throws Exception;
    DefaultRepositorySystemSession session(Settings settings, boolean offline) throws Exception;
    Built build(Path pom, DefaultRepositorySystemSession session, Settings settings, Properties properties,
                List<String> active, List<String> inactive, List<RemoteRepository> repositories) throws Exception;
    Model read(Path pom) throws Exception;
    default List<String> children(Model model) { return model.getModules(); }
    default void associate(DefaultRepositorySystemSession session, Settings settings, Path root,
                           Map<String,String> properties, List<RemoteRepository> repositories) { }
    default void closeSession(DefaultRepositorySystemSession session) { }
    boolean isResolutionFailure(Exception error);
    @Override void close();
}

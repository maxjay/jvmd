package dev.jvmd.resolver.engine;

import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import org.apache.maven.api.*;
import org.apache.maven.api.services.*;
import org.apache.maven.api.settings.Settings;
import org.apache.maven.api.toolchain.ToolchainModel;
import org.apache.maven.impl.AbstractSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;

/** Implements 4.3: native Maven 4 session with real settings, properties and reactor root, without launching Maven. */
final class NativeSession extends AbstractSession {
    private final Settings settings;
    private final Path root;
    private final Map<String,String> properties;
    private final Instant started=Instant.now();
    NativeSession(RepositorySystemSession session,RepositorySystem system,Lookup lookup,Settings settings,Path root,
                  Map<String,String> properties,List<org.eclipse.aether.repository.RemoteRepository> repositories) {
        super(session,system,null,repositories,lookup);
        this.settings=settings;this.root=root;this.properties=Map.copyOf(properties);
    }
    @Override protected Session newSession(RepositorySystemSession session,List<RemoteRepository> repositories) {
        return new NativeSession(session,repositorySystem,lookup,settings,root,properties,toRepositories(repositories));
    }
    @Override public Settings getSettings() { return settings; }
    @Override public Collection<ToolchainModel> getToolchains() { return List.of(); }
    @Override public Map<String,String> getUserProperties() { return properties; }
    @Override public Map<String,String> getSystemProperties() { return session.getSystemProperties(); }
    @Override public Map<String,String> getEffectiveProperties(Project project) {
        var result=new HashMap<>(getSystemProperties());if(project!=null)result.putAll(project.getModel().getProperties());result.putAll(properties);return result;
    }
    @Override public Version getMavenVersion() { return parseVersion("4.0.0-rc-6"); }
    @Override public int getDegreeOfConcurrency() { return 1; }
    @Override public Instant getStartTime() { return started; }
    @Override public Path getTopDirectory() { return root; }
    @Override public Path getRootDirectory() { return root; }
    @Override public List<Project> getProjects() { return List.of(); }
    @Override public Map<String,Object> getPluginContext(Project project) { throw new UnsupportedOperationException("Build plugins execute only in verified builds"); }
}

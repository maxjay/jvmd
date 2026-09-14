package dev.jvmd.resolver.engine;

import dev.jvmd.core.Config;
import dev.jvmd.resolver.MavenEnvironment;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.*;
import org.apache.maven.model.*;
import org.apache.maven.model.building.*;
import org.apache.maven.project.*;
import org.apache.maven.settings.SettingsUtils;
import org.eclipse.aether.*;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.util.repository.*;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import java.nio.file.*;
import java.util.*;

/** Implements 4.3: Maven 3.9 native model building and Resolver 1.9 bootstrap, isolated from Maven 4. */
public final class VersionModels implements Models {
    private static final class Supply extends RepositorySystemSupplier {
        private org.eclipse.aether.impl.RemoteRepositoryManager repositories;
        @Override protected org.eclipse.aether.impl.RemoteRepositoryManager getRemoteRepositoryManager(
                org.eclipse.aether.impl.UpdatePolicyAnalyzer policy,
                org.eclipse.aether.spi.connector.checksum.ChecksumPolicyProvider checksum) {
            return repositories = super.getRemoteRepositoryManager(policy, checksum);
        }
    }
    private final Config config;
    private final MavenEnvironment environment;
    private final Supply supply;
    private final RepositorySystem system;
    private record Startup(Supply supply,RepositorySystem system) implements Models.Bootstrap {
        @Override public Models create(Config config,MavenEnvironment environment){return new VersionModels(config,environment,this);}
        @Override public void close(){system.shutdown();}
    }
    public static Models.Bootstrap bootstrap(){var supply=new Supply();return new Startup(supply,supply.get());}
    public VersionModels(Config config,MavenEnvironment environment){this(config,environment,(Startup)bootstrap());}
    private VersionModels(Config config,MavenEnvironment environment,Startup startup){
        this.config=config;this.environment=environment;this.supply=startup.supply();this.system=startup.system();
    }
    @Override public RepositorySystem system() { return system; }
    @Override public String mavenVersion() { return "3.9.16"; }
    @Override public String resolverVersion() { return "1.9.27"; }
    @Override public String modelBuilder() { return DefaultModelBuilderFactory.class.getName(); }
    public Settings settings(Path root) throws Exception {
        var request = new DefaultSettingsBuildingRequest().setSystemProperties(environment.systemProperties()).setUserProperties(new Properties());
        if (Files.isRegularFile(environment.settingsFile())) request.setUserSettingsFile(environment.settingsFile().toFile());
        Path global = environment.globalSettingsFile();
        if (global != null) request.setGlobalSettingsFile(global.toFile());
        var settings = new DefaultSettingsBuilderFactory().newInstance().build(request).getEffectiveSettings();
        var security = new org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher(new org.sonatype.plexus.components.cipher.DefaultPlexusCipher());
        security.setConfigurationFile(Path.of(System.getProperty("user.home"), ".m2/settings-security.xml").toString());
        var decrypted = new org.apache.maven.settings.crypto.DefaultSettingsDecrypter(security)
                .decrypt(new org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest(settings));
        if (!decrypted.getProblems().isEmpty()) throw new IllegalStateException("Maven settings credentials could not be decrypted");
        settings.setServers(decrypted.getServers()); settings.setProxies(decrypted.getProxies());
        return settings;
    }
    public DefaultRepositorySystemSession session(Settings settings, boolean offline) throws Exception {
        var session = MavenRepositorySystemUtils.newSession();
        session.setSystemProperties(environment.systemProperties());
        session.setOffline(offline || settings.isOffline());
        session.setConfigProperty("aether.conflictResolver.verbose", true);
        session.setConfigProperty("aether.dependencyCollector.impl", "bf");
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session, new LocalRepository(config.m2Repo().toFile(), "simple")));
        var mirrors = new DefaultMirrorSelector();
        for (var m : settings.getMirrors()) mirrors.add(m.getId(), m.getUrl(), m.getLayout(), false, m.isBlocked(), m.getMirrorOf(), m.getMirrorOfLayouts());
        session.setMirrorSelector(mirrors);
        var auth = new DefaultAuthenticationSelector();
        for (var s : settings.getServers()) auth.add(s.getId(), new AuthenticationBuilder().addUsername(s.getUsername()).addPassword(s.getPassword()).addPrivateKey(s.getPrivateKey(), s.getPassphrase()).build());
        session.setAuthenticationSelector(auth);
        var proxies = new DefaultProxySelector();
        for (var p : settings.getProxies()) if (p.isActive()) proxies.add(new org.eclipse.aether.repository.Proxy(p.getProtocol(), p.getHost(), p.getPort(),
                new AuthenticationBuilder().addUsername(p.getUsername()).addPassword(p.getPassword()).build()), p.getNonProxyHosts());
        session.setProxySelector(proxies);
        return session;
    }

    @Override public Built build(Path pom, DefaultRepositorySystemSession session, Settings settings, Properties properties,
                                List<String> active, List<String> inactive, List<RemoteRepository> repositories) throws Exception {
        var resolver = new ProjectModelResolver(session, null, system, supply.repositories, repositories,
                ProjectBuildingRequest.RepositoryMerging.REQUEST_DOMINANT, null);
        var request = new DefaultModelBuildingRequest().setPomFile(pom.toFile()).setProcessPlugins(true)
                .setSystemProperties(environment.systemProperties()).setUserProperties(properties)
                .setActiveProfileIds(active).setInactiveProfileIds(inactive)
                .setProfiles(settings.getProfiles().stream().map(SettingsUtils::convertFromSettingsProfile).toList()).setModelResolver(resolver);
        var result = new DefaultModelBuilderFactory().newInstance().build(request);
        var inputs = new ArrayList<Path>();
        for (String id : result.getModelIds()) {
            var raw=result.getRawModel(id);
            if(raw!=null && raw.getPomFile()!=null) inputs.add(raw.getPomFile().toPath().toAbsolutePath().normalize());
        }
        return new Built(result.getEffectiveModel(), List.copyOf(inputs), result.getEffectiveModel().getModules());
    }
    @Override public Model read(Path pom) throws Exception { return new org.apache.maven.model.io.DefaultModelReader().read(pom.toFile(),Map.of()); }
    @Override public boolean isResolutionFailure(Exception error) { return error instanceof ModelBuildingException || error instanceof RepositoryException; }
    @Override public void close() { system.shutdown(); }
}

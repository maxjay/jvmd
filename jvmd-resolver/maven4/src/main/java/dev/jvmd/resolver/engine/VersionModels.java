package dev.jvmd.resolver.engine;

import dev.jvmd.core.Config;
import dev.jvmd.resolver.MavenEnvironment;
import java.nio.file.*;
import java.util.*;
import org.apache.maven.api.di.*;
import org.apache.maven.api.services.*;
import org.apache.maven.api.services.xml.ModelXmlFactory;
import org.apache.maven.di.Injector;
import org.apache.maven.impl.InternalSession;
import org.apache.maven.impl.di.SessionScope;
import org.apache.maven.impl.resolver.MavenSessionBuilderSupplier;
import org.apache.maven.impl.standalone.ApiRunner;
import org.apache.maven.model.Model;
import org.apache.maven.settings.Settings;
import org.eclipse.aether.*;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.spi.connector.transport.http.ChecksumExtractor;
import org.eclipse.aether.spi.io.PathProcessor;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.jdk.JdkTransporterFactory;
import org.eclipse.aether.util.repository.*;

/** Implements 4.3: Maven 4's native immutable model builder and Resolver 2 session personality, with JDK HTTP transport. */
public final class VersionModels implements Models {
    private final Config config;
    private final MavenEnvironment environment;
    private final Injector injector;
    private final Lookup lookup;
    private final RepositorySystem system;
    private final Map<DefaultRepositorySystemSession,RepositorySystemSession.CloseableSession> sessions=new IdentityHashMap<>();
    private final Map<DefaultRepositorySystemSession,Map<Path,Built>> built=new IdentityHashMap<>();
    private Path root;
    public VersionModels(Config config,MavenEnvironment environment) {
        this.config=config;this.environment=environment;
        injector=Injector.create();injector.bindInstance(Injector.class,injector);
        // ApiRunner provides service registries only. Its test-only default Session is never requested.
        injector.bindImplicit(ApiRunner.class);
        injector.bindImplicit(org.apache.maven.impl.standalone.RepositorySystemSupplier.class);
        injector.bindImplicit(Transport.class);
        injector.bindScope(SessionScoped.class,new SessionScope());
        injector.discover(VersionModels.class.getClassLoader());
        lookup=injector.getInstance(Lookup.class);
        system=injector.getInstance(RepositorySystem.class);
    }
    public static final class Transport {
        @Provides @Named("file") @Singleton public static TransporterFactory file() { return new FileTransporterFactory(); }
        @Provides @Named("jdk") @Singleton public static TransporterFactory jdk(ChecksumExtractor checksums,PathProcessor paths) { return new JdkTransporterFactory(checksums,paths); }
    }
    @Override public RepositorySystem system() { return system; }
    @Override public String mavenVersion() { return "4.0.0-rc-6"; }
    @Override public String resolverVersion() { return "2.0.21"; }
    @Override public String modelBuilder() { return lookup.lookup(ModelBuilder.class).getClass().getName(); }
    private static Map<String,String> map(Properties properties) {
        var result=new LinkedHashMap<String,String>();properties.forEach((k,v)->result.put(k.toString(),v.toString()));return result;
    }
    private RepositorySystemSession.CloseableSession base(boolean offline) {
        return new MavenSessionBuilderSupplier(system,false).get().setSystemProperties(environment.systemProperties())
                .setOffline(offline).setConfigProperty("aether.conflictResolver.verbose",true)
                .setConfigProperty("aether.dependencyCollector.impl","bf").setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_NEVER)
                .withLocalRepositories(new LocalRepository(config.m2Repo(),"simple")).build();
    }
    @Override public Settings settings(Path root) throws Exception {
        this.root=root;
        try(var resolverSession=base(true)) {
            var session=new NativeSession(resolverSession,system,lookup,org.apache.maven.api.settings.Settings.newInstance(),root,Map.of(),List.of());
            var result=lookup.lookup(SettingsBuilder.class).build(session,environment.globalSettingsFile(),root.resolve(".mvn/settings.xml"),environment.settingsFile());
            return new Settings(result.getEffectiveSettings());
        }
    }
    @Override public DefaultRepositorySystemSession session(Settings settings,boolean offline) {
        var owner=base(offline || settings.isOffline());
        var session=new DefaultRepositorySystemSession(owner);
        var mirrors=new DefaultMirrorSelector();
        for(var m:settings.getMirrors())mirrors.add(m.getId(),m.getUrl(),m.getLayout(),false,m.isBlocked(),m.getMirrorOf(),m.getMirrorOfLayouts());
        session.setMirrorSelector(mirrors);
        var auth=new DefaultAuthenticationSelector();
        for(var s:settings.getServers())auth.add(s.getId(),new AuthenticationBuilder().addUsername(s.getUsername()).addPassword(s.getPassword()).addPrivateKey(s.getPrivateKey(),s.getPassphrase()).build());
        session.setAuthenticationSelector(auth);
        var proxies=new DefaultProxySelector();
        for(var p:settings.getProxies())if(p.isActive())proxies.add(new Proxy(p.getProtocol(),p.getHost(),p.getPort(),new AuthenticationBuilder().addUsername(p.getUsername()).addPassword(p.getPassword()).build()),p.getNonProxyHosts());
        session.setProxySelector(proxies);sessions.put(session,owner);built.put(session,new LinkedHashMap<>());return session;
    }
    private NativeSession nativeSession(DefaultRepositorySystemSession session,Settings settings,Path directory,Map<String,String> properties,List<RemoteRepository> repositories) {
        Path top=directory;
        for(Path current=directory;current!=null;current=current.getParent()) {
            if(Files.isDirectory(current.resolve(".mvn"))) { top=current;break; }
            if(current.equals(root)) { top=current;break; }
        }
        var result=new NativeSession(session,system,lookup,settings.getDelegate(),top,properties,repositories);
        // The same Aether session collects each module sequentially with its own Maven request properties.
        session.getData().set(InternalSession.class,result);
        return result;
    }
    @Override public void associate(DefaultRepositorySystemSession session,Settings settings,Path directory,Map<String,String> properties,List<RemoteRepository> repositories) {
        nativeSession(session,settings,directory,properties,repositories);
    }
    @Override public Built build(Path pom,DefaultRepositorySystemSession session,Settings settings,Properties properties,
                                 List<String> active,List<String> inactive,List<RemoteRepository> repositories) throws Exception {
        Path file=pom.toAbsolutePath().normalize();
        var cached=built.get(session).get(file);if(cached!=null)return cached;
        var nativeSession=nativeSession(session,settings,pom.getParent(),map(properties),repositories);
        var profiles=new ArrayList<org.apache.maven.api.model.Profile>();
        var settingsBuilder=lookup.lookup(SettingsBuilder.class);
        for(var profile:settings.getDelegate().getProfiles())profiles.add(settingsBuilder.convert(profile));
        // Maven 4 permits repositories directly in settings, outside named profiles.
        profiles.add(settingsBuilder.convert(org.apache.maven.api.settings.Profile.newBuilder().id("jvmd-settings-repositories")
                .repositories(settings.getDelegate().getRepositories()).pluginRepositories(settings.getDelegate().getPluginRepositories()).build()));
        var selected=new ArrayList<>(active);selected.add("jvmd-settings-repositories");
        var request=ModelBuilderRequest.builder().session(nativeSession).source(Sources.buildSource(file))
                .requestType(ModelBuilderRequest.RequestType.BUILD_PROJECT).recursive(true).locationTracking(true)
                .profiles(profiles).activeProfileIds(selected).inactiveProfileIds(inactive)
                .systemProperties(map(environment.systemProperties())).userProperties(map(properties))
                .repositories(nativeSession.getRemoteRepositories()).repositoryMerging(ModelBuilderRequest.RepositoryMerging.REQUEST_DOMINANT).build();
        var result=lookup.lookup(ModelBuilder.class).newSession().build(request);
        var inputs=new LinkedHashSet<Path>();collectInputs(result,inputs);
        for(Path input:List.copyOf(inputs))parentInputs(input,inputs);
        collectResults(result,inputs,built.get(session));
        var answer=built.get(session).get(file);
        if(answer==null)throw new IllegalStateException("Maven 4 did not return a model for "+file);
        return answer;
    }
    private void collectInputs(ModelBuilderResult result,Set<Path> inputs) {
        if(result.getSource().getPath()!=null)inputs.add(result.getSource().getPath().toAbsolutePath().normalize());
        for(var model:Arrays.asList(result.getFileModel(),result.getRawModel(),result.getParentModel(),result.getEffectiveModel()))
            if(model!=null && model.getPomFile()!=null)inputs.add(model.getPomFile().toAbsolutePath().normalize());
        for(var child:result.getChildren())collectInputs(child,inputs);
    }
    private void parentInputs(Path file,Set<Path> inputs) throws Exception {
        var seen=new HashSet<Path>();
        while(Files.isRegularFile(file) && seen.add(file)) {
            var parent=read(file).getParent();if(parent==null)break;
            String relative=parent.getRelativePath();if(relative==null)relative="../pom.xml";if(relative.isEmpty())break;
            Path next=file.getParent().resolve(relative).normalize();if(Files.isDirectory(next))next=next.resolve("pom.xml");
            inputs.add(next);file=next;
        }
    }
    private void collectResults(ModelBuilderResult result,Set<Path> inputs,Map<Path,Built> cache) {
        if(result.getEffectiveModel()!=null) {
            var model=new Model(result.getEffectiveModel());
            cache.put(result.getSource().getPath().toAbsolutePath().normalize(),new Built(model,List.copyOf(inputs),children(model)));
        }
        for(var child:result.getChildren())collectResults(child,inputs,cache);
    }
    @Override public Model read(Path pom) { return new Model(lookup.lookup(ModelXmlFactory.class).read(pom)); }
    @Override public List<String> children(Model model) {
        var subprojects=model.getDelegate().getSubprojects();return subprojects.isEmpty()?model.getModules():subprojects;
    }
    @Override public boolean isResolutionFailure(Exception error) { return error instanceof ModelBuilderException || error instanceof RepositoryException; }
    @Override public void closeSession(DefaultRepositorySystemSession session) {
        built.remove(session);var owner=sessions.remove(session);if(owner!=null)owner.close();
    }
    @Override public void close() { for(var owner:sessions.values())owner.close();sessions.clear();built.clear();system.shutdown(); }
}

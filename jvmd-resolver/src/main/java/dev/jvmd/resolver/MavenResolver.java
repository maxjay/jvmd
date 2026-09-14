package dev.jvmd.resolver;

import dev.jvmd.core.Config;
import dev.jvmd.core.Hashing;
import dev.jvmd.core.Json;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.DefaultModelBuilderFactory;
import org.apache.maven.model.building.DefaultModelBuildingRequest;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectModelResolver;
import org.apache.maven.settings.SettingsUtils;
import org.eclipse.aether.AbstractRepositoryListener;
import org.eclipse.aether.RepositoryEvent;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.DependencyNode;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.supplier.RepositorySystemSupplier;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/** Implements 4.3: Maven's own models and mediation, offline-first and graph-only caching. */
public final class MavenResolver implements AutoCloseable {
    private static final class Supply extends RepositorySystemSupplier {
        private org.eclipse.aether.impl.RemoteRepositoryManager repositories;
        @Override protected org.eclipse.aether.impl.RemoteRepositoryManager getRemoteRepositoryManager(
                org.eclipse.aether.impl.UpdatePolicyAnalyzer policy,
                org.eclipse.aether.spi.connector.checksum.ChecksumPolicyProvider checksum) {
            return repositories = super.getRemoteRepositoryManager(policy, checksum);
        }
    }
    /** Implements 4.3: graph inputs, with content checks for root/parent/settings files. */
    public record Input(String path, long size, long modified, String hash, boolean strong) { }
    /** Implements 4.3: serialized graph cache without effective models. */
    public record Cached(String environment, List<Input> inputs, Resolution graph) { }
    private final Config config;
    private final MavenEnvironment environment;
    private final Supply supply = new Supply();
    private final RepositorySystem system = supply.get();
    private final Map<Path, Cached> memory = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Path, List<String>> versions = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicLong collections = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong cacheHits = new java.util.concurrent.atomic.AtomicLong();
    public MavenResolver(Config config) { this(config, new MavenEnvironment(config)); }
    public MavenResolver(Config config, MavenEnvironment environment) { this.config = config; this.environment = environment; }
    public Map<String, Object> status() { return Map.of("maven_major", 3, "resolver_version", "1.9.27", "collections", collections.get(), "cache_hits", cacheHits.get()); }
    public synchronized Resolution resolve(Path root) throws Exception {
        return resolve(root, null);
    }
    public synchronized Resolution resolve(Path root, org.eclipse.aether.repository.WorkspaceReader workspace) throws Exception {
        root = root.toRealPath();
        Path pom = root.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) throw new IllegalArgumentException("No Maven pom.xml in " + root);
        Path directory = config.stateDir().resolve("graphs"); Files.createDirectories(directory);
        Path cacheFile = directory.resolve(Hashing.sha256(root.toString().getBytes(StandardCharsets.UTF_8)) + ".json");
        String context = contextFingerprint();
        Cached prior = memory.get(root);
        if (prior == null && Files.isRegularFile(cacheFile)) {
            try { prior = Json.MAPPER.readValue(cacheFile.toFile(), Cached.class); }
            catch (java.io.IOException ignored) { }
        }
        if (workspace == null && prior != null && prior.environment().equals(context) && unchanged(prior.inputs())) {
            memory.put(root, prior); cacheHits.incrementAndGet(); return prior.graph().cachedCopy();
        }
        var settings = environment.settings();
        versions.remove(root);
        List<String> warnings = versions.computeIfAbsent(root, environment::versionWarnings);
        if (warnings.stream().anyMatch(w -> w.startsWith("Maven major mismatch")))
            throw new dev.jvmd.core.RpcException(-32003, "unsupported_capability", Map.of("capability", "matching Maven resolver", "warnings", warnings));
        Build build;
        try { build = build(root, settings, true, workspace, warnings); }
        catch (org.apache.maven.model.building.ModelBuildingException | org.eclipse.aether.RepositoryException offlineMiss) {
            if (settings.isOffline()) throw offlineMiss;
            // Local artifacts have updatePolicy=never; this single online pass transfers misses only.
            build(root, settings, false, workspace, warnings);
            build = build(root, settings, true, workspace, warnings);
            build.warnings.add("offline_miss: completed one online fill pass");
        }
        var inputs = new ArrayList<Input>();
        for (Path path : build.inputs.stream().sorted().toList()) inputs.add(input(path, build.strong.contains(path)));
        String fingerprint = Hashing.sha256(Json.MAPPER.writeValueAsBytes(inputs));
        var graph = new Resolution(root.toString(), List.copyOf(build.modules), List.copyOf(build.nodes.values()),
                List.copyOf(build.edges), Map.copyOf(build.classpaths), List.copyOf(build.warnings), fingerprint, true, false);
        Cached cached = new Cached(context, inputs, graph);
        if (workspace == null) {
            Path temp = Files.createTempFile(directory, "graph-", ".tmp");
            try {
                Json.MAPPER.writeValue(temp.toFile(), cached);
                Files.move(temp, cacheFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally { Files.deleteIfExists(temp); }
            memory.put(root, cached);
        }
        return graph;
    }
    private static final class Build {
        final Set<Path> inputs = java.util.concurrent.ConcurrentHashMap.newKeySet();
        final Set<Path> strong = new LinkedHashSet<>();
        final List<Resolution.Module> modules = new ArrayList<>();
        final Map<String, Resolution.Node> nodes = new LinkedHashMap<>();
        final Set<Resolution.Edge> edges = new LinkedHashSet<>();
        final Map<String, List<String>> classpaths = new LinkedHashMap<>();
        final List<String> warnings = new ArrayList<>();
    }
    private Build build(Path root, org.apache.maven.settings.Settings settings, boolean offline,
                        org.eclipse.aether.repository.WorkspaceReader workspace, List<String> versionWarnings) throws Exception {
        var build = new Build(); build.warnings.addAll(versionWarnings);
        var session = environment.session(system, settings, offline);
        if (workspace != null) session.setWorkspaceReader(workspace);
        session.setRepositoryListener(new AbstractRepositoryListener() {
            @Override public void artifactResolved(RepositoryEvent event) {
                if (event.getArtifact() != null && event.getArtifact().getExtension().equals("pom") && event.getFile() != null)
                    build.inputs.add(event.getFile().toPath().toAbsolutePath().normalize());
            }
        });
        var repositories = List.of(new RemoteRepository.Builder("central", "default", "https://repo.maven.apache.org/maven2")
                .setPolicy(new RepositoryPolicy(true, RepositoryPolicy.UPDATE_POLICY_NEVER, RepositoryPolicy.CHECKSUM_POLICY_FAIL)).build());
        repositories = system.newResolutionRepositories(session, repositories);
        build.inputs.add(environment.settingsFile()); build.strong.add(environment.settingsFile());
        Path configFile = root.resolve(".mvn/maven.config"); build.inputs.add(configFile); build.strong.add(configFile);
        Path wrapper = root.resolve(".mvn/wrapper/maven-wrapper.properties"); build.inputs.add(wrapper); build.strong.add(wrapper);
        var properties = new Properties();
        var active = new ArrayList<>(settings.getActiveProfiles()); var inactive = new ArrayList<String>();
        if (Files.isRegularFile(configFile)) for (String line : Files.readAllLines(configFile)) {
            String argument = line.trim();
            if (argument.startsWith("-D")) { String[] pair = argument.substring(2).split("=", 2); properties.setProperty(pair[0], pair.length == 1 ? "true" : pair[1]); }
            else if (argument.startsWith("-P")) for (String profile : argument.substring(2).split(",")) {
                if (profile.startsWith("!") || profile.startsWith("-")) inactive.add(profile.substring(1)); else active.add(profile);
            }
            else if (!argument.isEmpty() && !argument.startsWith("#")) build.warnings.add("unapplied_maven_config: " + argument);
        }
        session.setUserProperties(properties);
        var queue = new java.util.ArrayDeque<Path>(); queue.add(root);
        var visited = new LinkedHashSet<Path>();
        while (!queue.isEmpty()) {
            Path moduleDir = queue.removeFirst().toRealPath();
            if (!visited.add(moduleDir)) { build.warnings.add("reactor_cycle_or_duplicate: " + moduleDir); continue; }
            Path pom = moduleDir.resolve("pom.xml"); build.inputs.add(pom); build.strong.add(pom);
            var modelResolver = new ProjectModelResolver(session, null, system, supply.repositories, repositories,
                    ProjectBuildingRequest.RepositoryMerging.REQUEST_DOMINANT, null);
            var request = new DefaultModelBuildingRequest().setPomFile(pom.toFile()).setProcessPlugins(true)
                    .setSystemProperties(environment.systemProperties()).setUserProperties(properties)
                    .setActiveProfileIds(active).setInactiveProfileIds(inactive)
                    .setProfiles(settings.getProfiles().stream().map(SettingsUtils::convertFromSettingsProfile).toList())
                    .setModelResolver(modelResolver);
            var models = new DefaultModelBuilderFactory().newInstance().build(request);
            Model model = models.getEffectiveModel();
            for (String id : models.getModelIds()) {
                var raw = models.getRawModel(id);
                if (raw != null && raw.getPomFile() != null) {
                    Path parent = raw.getPomFile().toPath().toAbsolutePath().normalize();
                    build.inputs.add(parent); build.strong.add(parent);
                }
            }
            var remotes = new ArrayList<RemoteRepository>(repositories);
            for (var r : model.getRepositories()) remotes.add(new RemoteRepository.Builder(r.getId(), r.getLayout(), r.getUrl())
                    .setReleasePolicy(policy(r.getReleases())).setSnapshotPolicy(policy(r.getSnapshots())).build());
            remotes = new ArrayList<>(system.newResolutionRepositories(session, remotes));
            String gav = model.getGroupId() + ":" + model.getArtifactId() + ":" + model.getVersion();
            var m = model.getBuild();
            var sources = List.of(m.getSourceDirectory(), moduleDir.resolve("target/generated-sources/annotations").toString());
            var testSources = List.of(m.getTestSourceDirectory(), moduleDir.resolve("target/generated-test-sources/test-annotations").toString());
            String release = model.getProperties().getProperty("maven.compiler.release", model.getProperties().getProperty("java.version", model.getProperties().getProperty("maven.compiler.source", "25")));
            build.modules.add(new Resolution.Module(gav, moduleDir.toString(), model.getPackaging(), sources, testSources,
                    m.getOutputDirectory(), m.getTestOutputDirectory(), release,
                    model.getDependencies().stream().map(d -> d.getGroupId() + ":" + d.getArtifactId() + ":" + d.getVersion()).toList(),CompilerSettings.options(model,false),CompilerSettings.options(model,true)));
            var collect = new CollectRequest().setRootArtifact(new DefaultArtifact(model.getGroupId(), model.getArtifactId(), "pom", model.getVersion()))
                    .setRepositories(remotes).setDependencies(model.getDependencies().stream().map(d -> dependency(d, session)).toList());
            if (model.getDependencyManagement() != null) collect.setManagedDependencies(model.getDependencyManagement().getDependencies().stream().map(d -> dependency(d, session)).toList());
            collections.incrementAndGet();
            var resolved = system.resolveDependencies(session, new DependencyRequest(collect,
                    (node, parents) -> node.getData().get(ConflictResolver.NODE_DATA_WINNER) == null));
            var main = new LinkedHashSet<String>(); var test = new LinkedHashSet<String>();
            main.add(m.getOutputDirectory()); test.add(m.getTestOutputDirectory()); test.add(m.getOutputDirectory());
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<DependencyNode, Boolean>());
            walk(resolved.getRoot(), null, gav, build, main, test, seen);
            build.classpaths.put(gav + ":main", List.copyOf(main)); build.classpaths.put(gav + ":test", List.copyOf(test));
            for (String child : model.getModules()) queue.add(moduleDir.resolve(child).normalize());
        }
        return build;
    }
    private static RepositoryPolicy policy(org.apache.maven.model.RepositoryPolicy policy) {
        return new RepositoryPolicy(policy == null || policy.isEnabled(), RepositoryPolicy.UPDATE_POLICY_NEVER,
                policy == null || policy.getChecksumPolicy() == null ? RepositoryPolicy.CHECKSUM_POLICY_FAIL : policy.getChecksumPolicy());
    }
    private static Dependency dependency(org.apache.maven.model.Dependency d, org.eclipse.aether.RepositorySystemSession session) {
        var type = session.getArtifactTypeRegistry().get(d.getType());
        var artifact = new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(),
                type == null ? d.getType() : type.getExtension(), d.getVersion(), type);
        if ("system".equals(d.getScope()) && d.getSystemPath() != null) artifact = (DefaultArtifact) artifact.setFile(Path.of(d.getSystemPath()).toFile());
        return new Dependency(artifact, d.getScope(), d.isOptional(), d.getExclusions().stream()
                .map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(), "*", "*")).toList());
    }
    private static void walk(DependencyNode node, String parent, String root, Build build,
                             Set<String> main, Set<String> test, Set<DependencyNode> seen) {
        var artifact = node.getArtifact();
        String id = root + "|" + (artifact == null ? root : artifact.toString());
        String gav = artifact == null ? root : artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
        String scope = node.getDependency() == null ? "compile" : node.getDependency().getScope();
        var winner = (DependencyNode) node.getData().get(ConflictResolver.NODE_DATA_WINNER);
        String winnerId = winner == null ? null : root + "|" + winner.getArtifact().toString();
        String path = artifact == null || artifact.getFile() == null ? null : artifact.getFile().getAbsolutePath();
        var entry = new Resolution.Node(id, gav, artifact == null ? "pom" : artifact.getExtension(),
                artifact == null ? "" : artifact.getClassifier(), scope, path, winnerId,
                winnerId == null ? "selected by Maven" : "conflict: selected " + winnerId, node.getDependency() != null && node.getDependency().isOptional());
        build.nodes.merge(id, entry, (old, current) -> old.winner() != null && current.winner() == null ? current : old);
        if (parent != null) build.edges.add(new Resolution.Edge(parent, id, scope));
        if (winner == null && artifact != null && artifact.getExtension().equals("jar") && path != null) {
            test.add(path);
            if (!scope.equals("test")) main.add(path);
        }
        if (!seen.add(node) || winner != null) return;
        for (var child : node.getChildren()) walk(child, id, root, build, main, test, seen);
    }
    private String contextFingerprint() {
        String properties = environment.systemProperties().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).sorted().collect(java.util.stream.Collectors.joining("\n"));
        return Hashing.sha256(("compiler-settings-v1\n" + config.mavenMajor() + "\n" + config.m2Repo() + "\n" + properties).getBytes(StandardCharsets.UTF_8));
    }
    private static Input input(Path path, boolean strong) throws Exception {
        path = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) return new Input(path.toString(), -1, -1, "absent", strong);
        var attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
        return new Input(path.toString(), attrs.size(), attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS), Hashing.sha256(path), strong);
    }
    private static boolean unchanged(List<Input> inputs) throws Exception {
        for (Input input : inputs) {
            Path path = Path.of(input.path());
            if (!Files.isRegularFile(path)) { if (input.size() != -1) return false; else continue; }
            var attrs = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes.class);
            if (attrs.size() != input.size() || attrs.lastModifiedTime().to(java.util.concurrent.TimeUnit.NANOSECONDS) != input.modified()) return false;
            if (input.strong() && !Hashing.sha256(path).equals(input.hash())) return false;
        }
        return true;
    }
    @Override public void close() { system.shutdown(); memory.clear(); }
}

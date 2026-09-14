package dev.jvmd.resolver;

import dev.jvmd.core.Config;
import dev.jvmd.core.RpcException;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.util.repository.AuthenticationBuilder;
import org.eclipse.aether.util.repository.DefaultAuthenticationSelector;
import org.eclipse.aether.util.repository.DefaultMirrorSelector;
import org.eclipse.aether.util.repository.DefaultProxySelector;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

/** Implements 4.3: Maven settings, major alignment and offline-first sessions. */
public final class MavenEnvironment {
    private final Config config;
    private final Path settingsFile;
    public MavenEnvironment(Config config) { this(config, Path.of(System.getProperty("user.home"), ".m2/settings.xml")); }
    public MavenEnvironment(Config config, Path settingsFile) {
        this.config = config; this.settingsFile = settingsFile;
        // Maven initializes the default timezone while building a model. Do it before cache keys.
        java.util.TimeZone.getDefault();
    }
    public Path settingsFile() { return settingsFile; }
    public Properties systemProperties() {
        var props = new Properties(); props.putAll(System.getProperties());
        System.getenv().forEach((key, value) -> props.setProperty("env." + key, value));
        return props;
    }
    public Settings settings() throws Exception {
        var request = new DefaultSettingsBuildingRequest().setSystemProperties(systemProperties()).setUserProperties(new Properties());
        if (Files.isRegularFile(settingsFile)) request.setUserSettingsFile(settingsFile.toFile());
        String mavenHome = System.getenv("MAVEN_HOME");
        if (mavenHome != null) request.setGlobalSettingsFile(Path.of(mavenHome, "conf/settings.xml").toFile());
        var settings = new DefaultSettingsBuilderFactory().newInstance().build(request).getEffectiveSettings();
        var security = new org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher(new org.sonatype.plexus.components.cipher.DefaultPlexusCipher());
        security.setConfigurationFile(Path.of(System.getProperty("user.home"), ".m2/settings-security.xml").toString());
        var decrypted = new org.apache.maven.settings.crypto.DefaultSettingsDecrypter(security)
                .decrypt(new org.apache.maven.settings.crypto.DefaultSettingsDecryptionRequest(settings));
        if (!decrypted.getProblems().isEmpty()) throw new IllegalStateException("Maven settings credentials could not be decrypted");
        settings.setServers(decrypted.getServers()); settings.setProxies(decrypted.getProxies());
        return settings;
    }
    public DefaultRepositorySystemSession session(RepositorySystem system, Settings settings, boolean offline) throws Exception {
        if (config.mavenMajor() != 3) throw new RpcException(-32003, "unsupported_capability",
                java.util.Map.of("capability", "Maven 4 resolver bundle", "reason", "This build is pinned to Maven 3; refusing a mismatched graph"));
        var session = MavenRepositorySystemUtils.newSession();
        session.setSystemProperties(systemProperties());
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
    public List<String> versionWarnings(Path root) {
        var warnings = new ArrayList<String>(); String version = null;
        Path wrapper = root.resolve(".mvn/wrapper/maven-wrapper.properties");
        try {
            if (Files.isRegularFile(wrapper)) {
                var properties = new Properties();
                try (var input = Files.newInputStream(wrapper)) { properties.load(input); }
                var match = java.util.regex.Pattern.compile("apache-maven-(\\d+)\\.").matcher(properties.getProperty("distributionUrl", ""));
                if (match.find()) version = match.group(1);
            }
            if (version == null) {
                var process = new ProcessBuilder("mvn", "-v").directory(root.toFile()).redirectErrorStream(true).start();
                var output = new java.io.ByteArrayOutputStream();
                Thread reader = Thread.ofVirtual().start(() -> { try { process.getInputStream().transferTo(output); } catch (java.io.IOException ignored) { } });
                if (!process.waitFor(5, TimeUnit.SECONDS)) { process.destroyForcibly(); warnings.add("Maven version detection timed out"); }
                reader.join(1000);
                var match = java.util.regex.Pattern.compile("Apache Maven (\\d+)\\.").matcher(output.toString(java.nio.charset.StandardCharsets.UTF_8));
                if (match.find()) version = match.group(1);
            }
        } catch (Exception e) { warnings.add("Maven version detection unavailable: " + e.getClass().getSimpleName()); }
        if (version != null && Integer.parseInt(version) != config.mavenMajor()) warnings.add("Maven major mismatch: build=" + version + ", resolver=" + config.mavenMajor());
        return warnings;
    }
}

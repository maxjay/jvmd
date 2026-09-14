package dev.jvmd.tests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Implements 12.4: small deterministic Maven repositories for resolver checkpoints. */
final class MavenFixtures {
    static Path project(Path root, String body) throws Exception {
        Files.createDirectories(root);
        Files.writeString(root.resolve("pom.xml"), pom("fixture", "app", "1", body));
        Path wrapper = Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(wrapper.resolve("maven-wrapper.properties"), "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip\n");
        return root;
    }
    static String pom(String group, String name, String version, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>" + group + "</groupId><artifactId>" + name + "</artifactId><version>" + version + "</version>" + body + "</project>";
    }
    static String dependency(String name, String version) {
        return "<dependency><groupId>fixture</groupId><artifactId>" + name + "</artifactId><version>" + version + "</version></dependency>";
    }
    static Path artifact(Path repository, String name, String version, String body) throws Exception {
        Path directory = Files.createDirectories(repository.resolve("fixture/" + name + "/" + version));
        Path pom = directory.resolve(name + "-" + version + ".pom"), jar = directory.resolve(name + "-" + version + ".jar");
        Files.writeString(pom, pom("fixture", name, version, body));
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) { }
        for (Path path : java.util.List.of(pom, jar)) Files.writeString(path.resolveSibling(path.getFileName() + ".sha1"),
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path))), StandardCharsets.US_ASCII);
        return jar;
    }
}

package dev.jvmd.tests;

import dev.jvmd.core.Json;
import dev.jvmd.resolver.MavenResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 2 exit: Spring graph matches Maven and meets strict-AOT cold/warm budgets. */
@Tag("phase-2") @Tag("perf")
class ResolvedGraphBudgetTest {
    @TempDir Path temp;
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.ValueSource(ints={3,4})
    void graphAgreesWithMavenAndMeetsColdAndCachedBudgets(int major) throws Exception {
        Path project = TestSupport.repo().resolve("jvmd-tests/smoke/resolver/warmup");
        Path baseline = project.resolve("target/dependency-tree.json");
        assertThat(baseline).as("Run smoke/run-resolver.sh before the perf gate").isRegularFile();
        if(major==4){
            Path original=project;project=Files.createDirectories(temp.resolve("maven4-project"));Files.copy(original.resolve("pom.xml"),project.resolve("pom.xml"));
            Path wrapper=Files.createDirectories(project.resolve(".mvn/wrapper"));
            Files.writeString(wrapper.resolve("maven-wrapper.properties"),"distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/4.0.0-rc-6/apache-maven-4.0.0-rc-6-bin.zip\n");
            String home=System.getenv("JVMD_MAVEN4_HOME");assertThat(home).as("CI installs the pinned native Maven 4 build").isNotBlank();
            Path buildLog=temp.resolve("maven4-tree.log");
            var build=new ProcessBuilder(Path.of(home,"bin/mvn").toString(),"-B","-f",project.resolve("pom.xml").toString(),"org.apache.maven.plugins:maven-dependency-plugin:3.8.1:tree","-DoutputType=json","-DoutputFile=target/dependency-tree.json").redirectErrorStream(true).redirectOutput(buildLog.toFile()).start();
            try{assertThat(build.waitFor(60,TimeUnit.SECONDS)).isTrue();assertThat(build.exitValue()).withFailMessage(Files.readString(buildLog)).isZero();}finally{build.destroyForcibly();}
            baseline=project.resolve("target/dependency-tree.json");assertThat(baseline).isRegularFile();
        }
        Path image = TestSupport.repo().resolve("jvmd-dist/target/image");
        Path probe = temp.resolve("resolver-probe.jar");
        String name = "dev/jvmd/tests/ResolverPerformanceProbe.class";
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(probe));
             var input = getClass().getClassLoader().getResourceAsStream(name)) {
            output.putNextEntry(new java.util.jar.JarEntry(name)); input.transferTo(output); output.closeEntry();
        }
        Path measurements = TestSupport.repo().resolve("jvmd-tests/target/phase-2-maven"+major+"-perf.json");
        Path log = temp.resolve("probe.log");
        var process = new ProcessBuilder(image.resolve("bin/java").toString(), "-XX:AOTMode=on",
                "-XX:AOTCache=" + image.resolve("lib/jvmd/jvmd.aot"), "-cp", image.resolve("lib/jvmd/*") + ":" + probe,
                "dev.jvmd.tests.ResolverPerformanceProbe", project.toString(), temp.resolve("state").toString(), measurements.toString(),Integer.toString(major))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
            System.out.println("phase-2-perf " + Files.readString(measurements));
        } finally { process.destroyForcibly(); }
        var config = new dev.jvmd.core.Config(Path.of(System.getProperty("java.home")), null,
                Path.of(System.getProperty("user.home"), ".m2/repository"), major, Duration.ofHours(4), 512, false, temp.resolve("agreement"), temp.resolve("unused.sock"));
        try (var resolver = new MavenResolver(config)) {
            var graph = resolver.resolve(project);
            var expected = new java.util.TreeSet<String>();
            collect(Json.MAPPER.readTree(baseline.toFile()), expected);
            var actual = graph.nodes().stream().filter(n -> n.winner() == null).map(n -> n.gav()).collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
            assertThat(actual).isEqualTo(expected);
        }
    }
    private void collect(com.fasterxml.jackson.databind.JsonNode node, java.util.Set<String> gavs) {
        gavs.add(node.path("groupId").asText() + ":" + node.path("artifactId").asText() + ":" + node.path("version").asText());
        for (var child : node.path("children")) collect(child, gavs);
    }
}

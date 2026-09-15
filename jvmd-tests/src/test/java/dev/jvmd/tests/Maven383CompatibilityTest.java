package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.resolver.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Maven 3.8.3 projects keep their build version while using the isolated Maven 3 resolver. */
@Tag("phase-2")
class Maven383CompatibilityTest {
    @TempDir Path temp;

    private Path reactor() throws Exception {
        Path root=MavenFixtures.project(temp.resolve("Maven 3.8.3 project"),"""
                <packaging>pom</packaging>
                <modules><module>library</module><module>app</module></modules>
                <properties><maven.compiler.release>17</maven.compiler.release></properties>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version></plugin></plugins></build>
                """);
        Files.writeString(root.resolve(".mvn/wrapper/maven-wrapper.properties"),
                "distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.8.3/apache-maven-3.8.3-bin.zip\n");
        for(String name:List.of("library","app")){
            Path directory=Files.createDirectories(root.resolve(name));
            String dependency=name.equals("app")?"<dependencies>"+MavenFixtures.dependency("library","1")+"</dependencies>":"";
            Files.writeString(directory.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture</groupId><artifactId>app</artifactId><version>1</version></parent><artifactId>"+
                    (name.equals("app")?"consumer":name)+"</artifactId>"+dependency+"</project>");
            Path source=Files.createDirectories(directory.resolve("src/main/java/p"));
            Files.writeString(source.resolve(name.equals("library")?"Library.java":"Use.java"),name.equals("library")?
                    "package p; public class Library { public static String value(){return \"maven383\";} }":
                    "package p; public class Use { String value(){return Library.value();} }");
        }
        return root;
    }

    @Test void wrapper383UsesItsRealBuildAndMatchesLiveDiagnostics() throws Exception {
        Path maven=TestSupport.repo().resolve("jvmd-tests/target/toolchains/apache-maven-3.8.3/bin/mvn");
        assertThat(maven).isRegularFile();
        Files.setPosixFilePermissions(maven,PosixFilePermissions.fromString("rwxr-xr-x"));
        var config=TestSupport.config(temp,Duration.ofHours(4));
        Path root=reactor(),wrapper=root.resolve("mvnw");
        // Delegate to the actual distribution; no mock of Maven resolution or compilation.
        Files.writeString(wrapper,"#!/bin/sh\nexec '"+maven.toString().replace("'","'\"'\"'")+"' \"$@\"\n");
        Files.setPosixFilePermissions(wrapper,PosixFilePermissions.fromString("rwx------"));
        assertThat(new MavenEnvironment(config).versionWarnings(root)).isEmpty();
        var versionCommand=Json.MAPPER.createObjectNode();
        versionCommand.putArray("verify_command").add(maven.toString()).add("-v");
        var version=new Verifier(config).verify(root,versionCommand,Duration.ofSeconds(30));
        assertThat(version.exitCode()).as(version.output()).isZero();
        assertThat(version.output()).contains("Apache Maven 3.8.3");
        try(var resolver=new MavenResolver(config)){
            var graph=resolver.resolve(root);
            assertThat(graph.offline()).isTrue();
            assertThat(graph.modules()).extracting(Resolution.Module::gav)
                    .containsExactlyInAnyOrder("fixture:app:1","fixture:library:1","fixture:consumer:1");
            var consumer=graph.modules().stream().filter(m->m.gav().equals("fixture:consumer:1")).findFirst().orElseThrow();
            assertThat(consumer.dependencies()).contains("fixture:library:1");
            assertThat(consumer.compilerOptions()).containsSubsequence("--release","17");
            assertThat(resolver.resolve(root).cached()).isTrue();
        }
        Path source=root.resolve("app/src/main/java/p/Use.java");
        try(var app=new Application(config)){
            String session=TestSupport.open(app,root);
            var clean=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session));
            assertThat(clean.has("error")).as(clean.toPrettyString()).isFalse();
            assertThat(clean.path("result").path("result").path("diagnostics").isEmpty()).isTrue();
            var verified=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"verified",true));
            assertThat(verified.has("error")).as(verified.toPrettyString()).isFalse();
            var build=verified.path("result").path("result");
            var completed=(Verifier.Result)app.sessions().get(session).state("last_verification");
            assertThat(completed.command().getFirst()).isEqualTo(wrapper.toString());
            assertThat(Files.isRegularFile(root.resolve("app/target/classes/p/Use.class"))).isTrue();
            assertThat(build.path("diagnostics").isEmpty()).isTrue();

            // Archives and some source checkouts lose executable bits. The wrapper must still win.
            Files.setPosixFilePermissions(wrapper,PosixFilePermissions.fromString("rw-------"));
            Files.writeString(source,"package p; public class Use { String value(){return Library.missing();} }");
            var live=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"paths",List.of(source.toString())))
                    .path("result").path("result").path("diagnostics");
            assertThat(live.size()).isEqualTo(1);
            var failure=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"verified",true));
            assertThat(failure.path("error").path("code").asInt()).as(failure.toPrettyString()).isEqualTo(-32004);
            var failed=failure.path("error").path("data").path("result");
            assertThat(failed.path("command").get(0).asText()).isEqualTo("sh");
            assertThat(failed.path("command").get(1).asText()).isEqualTo(wrapper.toString());
            var actual=failed.path("diagnostics");
            assertThat(actual.size()).isEqualTo(1);
            for(String field:List.of("code","file","line","character","kind"))
                assertThat(actual.get(0).path(field)).as(field).isEqualTo(live.get(0).path(field));
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/maven-3.8.3-compatibility.json").toFile(),
                    Map.of("maven_version","3.8.3","wrapper",wrapper.toString(),"clean_build",build,"live_error",live,"verified_error",actual));
        }
    }
}

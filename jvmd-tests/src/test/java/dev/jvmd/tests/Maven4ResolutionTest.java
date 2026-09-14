package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.resolver.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.3: native Maven 4.1 semantics, offline dependency mediation, cache identity and real-build agreement. */
@Tag("phase-2")
class Maven4ResolutionTest {
    @TempDir Path temp;
    private Config config() { var c=TestSupport.config(temp,Duration.ofHours(4));return new Config(c.jdkHome(),null,c.m2Repo(),4,c.idleTimeout(),c.heapCeilingMb(),false,c.stateDir(),c.socket()); }
    private static void wrapper(Path root) throws Exception {
        Path directory=Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(directory.resolve("maven-wrapper.properties"),"distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/4.0.0-rc-6/apache-maven-4.0.0-rc-6-bin.zip\n");
    }
    private Path reactor() throws Exception {
        Path root=Files.createDirectories(temp.resolve("reactor"));wrapper(root);
        Files.writeString(root.resolve("pom.xml"),"""
                <project xmlns="http://maven.apache.org/POM/4.1.0" root="true">
                <modelVersion>4.1.0</modelVersion><groupId>fixture</groupId><artifactId>parent</artifactId><version>7</version><packaging>pom</packaging>
                <properties><maven.compiler.release>25</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <subprojects><subproject>library</subproject><subproject>app</subproject></subprojects>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.16.0</version></plugin></plugins></build>
                </project>
                """);
        for(String name:List.of("library","app")) {
            Path dir=Files.createDirectories(root.resolve(name));
            String deps=name.equals("app")?"<dependencies><dependency><groupId>fixture</groupId><artifactId>library</artifactId></dependency></dependencies>":"";
            Files.writeString(dir.resolve("pom.xml"),"<project xmlns=\"http://maven.apache.org/POM/4.1.0\"><modelVersion>4.1.0</modelVersion><parent><groupId>fixture</groupId><artifactId>parent</artifactId></parent><artifactId>"+name+"</artifactId>"+deps+"</project>");
            Path source=Files.createDirectories(dir.resolve("src/main/java/p"));
            Files.writeString(source.resolve(name.equals("library")?"Library.java":"Use.java"),name.equals("library")?
                    "package p; public class Library { public static String value(){return \"native\";} }":
                    "package p; public class Use { String value(){return Library.value();} }");
        }
        return root;
    }
    @Test void model41InfersReactorVersionsAndInvalidatesOnParentBytes() throws Exception {
        var config=config();Path root=reactor();
        try(var resolver=new MavenResolver(config)) {
            assertThat(resolver.status()).containsEntry("maven_major",4).containsEntry("maven_version","4.0.0-rc-6").containsEntry("resolver_version","2.0.21")
                    .containsEntry("model_builder","org.apache.maven.impl.model.DefaultModelBuilder");
            var graph=resolver.resolve(root);
            assertThat(graph.offline()).isTrue();assertThat(graph.modules()).extracting(Resolution.Module::gav).containsExactlyInAnyOrder("fixture:parent:7","fixture:library:7","fixture:app:7");
            var app=graph.modules().stream().filter(m->m.gav().equals("fixture:app:7")).findFirst().orElseThrow();
            assertThat(app.dependencies()).contains("fixture:library:7");
            assertThat(app.compilerOptions()).containsSubsequence("--release","25");
            assertThat(new WorkspaceOverlay(graph.modules(),true).dependencies(graph,app.gav(),false)).extracting(Resolution.Module::gav).contains("fixture:library:7");
            assertThat(resolver.resolve(root).cached()).isTrue();
            Path pom=root.resolve("pom.xml");var timestamp=Files.getLastModifiedTime(pom);
            Files.writeString(pom,Files.readString(pom).replace("<version>7</version>","<version>8</version>"));Files.setLastModifiedTime(pom,timestamp);
            var changed=resolver.resolve(root);assertThat(changed.cached()).isFalse();assertThat(changed.fingerprint()).isNotEqualTo(graph.fingerprint());
            assertThat(changed.modules()).extracting(Resolution.Module::gav).contains("fixture:app:8","fixture:library:8");
        }
        try(var resolver=new MavenResolver(config)) { assertThat(resolver.resolve(root).cached()).isTrue(); }
        for(String name:List.of("org.apache.maven.api.Session","org.eclipse.aether.RepositorySystem"))
            assertThatThrownBy(()->Class.forName(name,false,MavenResolver.class.getClassLoader())).isInstanceOf(ClassNotFoundException.class);
    }
    @Test void settingsProfilesBomManagementAndConflictLosersSurviveOfflineCollection() throws Exception {
        var config=config();Path repo=config.m2Repo();
        MavenFixtures.artifact(repo,"base","1","");Path selected=MavenFixtures.artifact(repo,"base","2","");
        MavenFixtures.artifact(repo,"left","1","<dependencies>"+MavenFixtures.dependency("base","1")+"</dependencies>");
        Path bom=Files.createDirectories(repo.resolve("fixture/bom/1"));
        Files.writeString(bom.resolve("bom-1.pom"),MavenFixtures.pom("fixture","bom","1","<packaging>pom</packaging><dependencyManagement><dependencies>"+MavenFixtures.dependency("left","1")+"</dependencies></dependencyManagement>"));
        Path root=MavenFixtures.project(temp.resolve("app"),"""
                <properties><maven.compiler.release>${fixture.release}</maven.compiler.release></properties>
                <dependencyManagement><dependencies><dependency><groupId>fixture</groupId><artifactId>bom</artifactId><version>1</version><type>pom</type><scope>import</scope></dependency></dependencies></dependencyManagement>
                <dependencies><dependency><groupId>fixture</groupId><artifactId>left</artifactId></dependency><dependency><groupId>fixture</groupId><artifactId>base</artifactId><version>2</version></dependency></dependencies>
                """);wrapper(root);
        Path settings=temp.resolve("settings.xml");Files.writeString(settings,"<settings><offline>true</offline><profiles><profile><id>native</id><properties><fixture.release>21</fixture.release></properties></profile></profiles><activeProfiles><activeProfile>native</activeProfile></activeProfiles></settings>");
        Files.writeString(selected.resolveSibling("_remote.repositories"),"base-2.jar>private=\nbase-2.pom>private=\n");
        try(var resolver=new MavenResolver(config,new MavenEnvironment(config,settings))) {
            var graph=resolver.resolve(root);assertThat(graph.offline()).isTrue();assertThat(graph.classpath()).contains(selected.toString());
            assertThat(graph.modules().getFirst().compilerOptions()).containsSubsequence("--release","21");
            assertThat(graph.nodes()).anyMatch(n->n.gav().equals("fixture:base:1")&&n.winner()!=null);
            Files.writeString(settings,Files.readString(settings).replace("<fixture.release>21","<fixture.release>25"));
            var changed=resolver.resolve(root);assertThat(changed.cached()).isFalse();assertThat(changed.modules().getFirst().compilerOptions()).containsSubsequence("--release","25");
        }
    }
    @Test void daemonLiveDiagnosticsAgreeWithAnActualMaven4Build() throws Exception {
        Path home=Path.of(Objects.requireNonNull(System.getenv("JVMD_MAVEN4_HOME"),"CI must provide the pinned Maven 4 distribution"));
        Path root=reactor();var config=config();
        Path directory=Files.createDirectories(root.resolve(".jvmd"));
        var manifest=Json.MAPPER.createObjectNode();manifest.putArray("verify_command").add(home.resolve("bin/mvn").toString()).add("-B").add("-q").add("test-compile");
        Json.MAPPER.writeValue(directory.resolve("workspace.json").toFile(),manifest);
        Path file=root.resolve("app/src/main/java/p/Use.java");
        try(var app=new Application(config)) {
            String session=TestSupport.open(app,root);assertThat(session).isNotEmpty();
            var clean=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session));
            assertThat(clean.has("error")).withFailMessage(clean.toPrettyString()).isFalse();assertThat(clean.path("result").path("result").path("diagnostics").isEmpty()).isTrue();
            var verified=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"verified",true));
            assertThat(verified.has("error")).withFailMessage(verified.toPrettyString()).isFalse();
            assertThat(Files.isRegularFile(root.resolve("app/target/classes/p/Use.class"))).isTrue();
            Files.writeString(file,"package p; public class Use { String value(){return Library.missing();} }");
            var live=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"paths",List.of(file.toString()))).path("result").path("result").path("diagnostics");
            assertThat(live.size()).isEqualTo(1);
            var failure=TestSupport.request(app.dispatcher(),"diag.get",Map.of("session",session,"verified",true));
            assertThat(failure.path("error").path("code").asInt()).withFailMessage(failure.toPrettyString()).isEqualTo(-32004);
            var actual=failure.path("error").path("data").path("result").path("diagnostics");assertThat(actual.size()).isEqualTo(1);
            for(String field:List.of("code","file","line","character","kind"))assertThat(actual.get(0).path(field)).as(field).isEqualTo(live.get(0).path(field));
        }
    }
}

package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.resolver.*;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.3: both native transports honor authenticated mirrors and retain offline-first resolution. */
@Tag("phase-2")
class AuthenticatedRepositoryTest {
    @TempDir Path temp;
    @ParameterizedTest @ValueSource(ints={3,4})
    void authenticatedMirrorFillsMissesOnceAndAColdOfflineRebuildUsesTheLocalRepository(int major)throws Exception {
        var base=TestSupport.config(temp,Duration.ofHours(4));
        var config=new Config(base.jdkHome(),null,base.m2Repo(),major,base.idleTimeout(),base.heapCeilingMb(),false,base.stateDir(),base.socket());
        Path remote=Files.createDirectories(temp.resolve("remote"));MavenFixtures.artifact(remote,"lib","1","");
        Path project=MavenFixtures.project(temp.resolve("project"),"<dependencies>"+MavenFixtures.dependency("lib","1")+"</dependencies>");
        if(major==4)Files.writeString(project.resolve(".mvn/wrapper/maven-wrapper.properties"),"distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/4.0.0-rc-6/apache-maven-4.0.0-rc-6-bin.zip\n");
        var served=ConcurrentHashMap.<String>newKeySet();String expected="Basic "+Base64.getEncoder().encodeToString("fixture:fixture-password".getBytes(StandardCharsets.UTF_8));
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            try(exchange){
                if(!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))){exchange.getResponseHeaders().set("WWW-Authenticate","Basic realm=\"fixture\"");exchange.sendResponseHeaders(401,-1);return;}
                Path file=remote.resolve(exchange.getRequestURI().getPath().substring(1)).normalize();
                if(!file.startsWith(remote)||!Files.isRegularFile(file)){exchange.sendResponseHeaders(404,-1);return;}
                served.add(remote.relativize(file).toString());byte[] bytes=Files.readAllBytes(file);
                exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
            }
        });server.start();
        Path settings=temp.resolve("settings.xml");
        Files.writeString(settings,"<settings><servers><server><id>fixture-mirror</id><username>fixture</username><password>fixture-password</password></server></servers><mirrors><mirror><id>fixture-mirror</id><mirrorOf>*</mirrorOf><url>http://127.0.0.1:"+server.getAddress().getPort()+"/</url></mirror></mirrors></settings>");
        try {
            try(var resolver=new MavenResolver(config,new MavenEnvironment(config,settings))){
                var graph=resolver.resolve(project);
                assertThat(graph.offline()).isTrue();assertThat(graph.warnings()).contains("offline_miss: completed one online fill pass");
                assertThat(served).contains("fixture/lib/1/lib-1.jar","fixture/lib/1/lib-1.pom");
                assertThat(graph.classpath()).contains(config.m2Repo().resolve("fixture/lib/1/lib-1.jar").toString());
            }
            server.stop(0);
            Path pom=project.resolve("pom.xml");Files.writeString(pom,Files.readString(pom)+"\n<!-- force a cold graph after the server has stopped -->\n");
            try(var resolver=new MavenResolver(config,new MavenEnvironment(config,settings))){
                var graph=resolver.resolve(project);assertThat(graph.cached()).isFalse();assertThat(graph.offline()).isTrue();
                assertThat(graph.warnings()).doesNotContain("offline_miss: completed one online fill pass");
                assertThat(resolver.resolve(project).cached()).isTrue();
            }
        } finally {server.stop(0);}
    }
}

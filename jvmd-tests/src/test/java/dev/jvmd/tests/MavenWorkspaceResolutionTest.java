package dev.jvmd.tests;

import dev.jvmd.resolver.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 6: embedded Maven resolves reactors and independent unbuilt roots offline. */
@Tag("phase-6")
class MavenWorkspaceResolutionTest {
    @TempDir Path root;
    @Test void anUninstalledLocalDependencyResolvesWithItsOwnInvocationProperties()throws Exception{
        Path a=OverlayFixtures.project(root.resolve("a"),"app","1","<dependencies>"+MavenFixtures.dependency("library","1")+"</dependencies>");
        Path b=OverlayFixtures.project(root.resolve("b"),"library","2","<profiles><profile><id>local</id><activation><property><name>local.release</name></property></activation><properties><maven.compiler.release>24</maven.compiler.release></properties></profile></profiles>");
        Files.writeString(b.resolve(".mvn/maven.config"),"-Dlocal.release=true\n");
        OverlayFixtures.source(b,"Library","public class Library {}");
        try(var resolver=new MavenResolver(TestSupport.config(root,Duration.ofHours(4)))){
            var graph=resolver.resolveWorkspace(root,List.of(a,b),true);
            assertThat(graph.offline()).isTrue();assertThat(graph.warnings().toString()).contains("overlay_version","fixture:library:1","fixture:library:2");
            assertThat(graph.modules()).hasSize(2);assertThat(graph.modules().get(1).compilerOptions()).containsSequence("--release","24");
            assertThat(graph.nodes()).anyMatch(n->n.gav().equals("fixture:library:1")&&n.path()==null);
            assertThat(resolver.resolveWorkspace(root,List.of(a,b),true).cached()).isTrue();
        }
    }
    @Test void reactorChildrenAreDiscoveredBeforeArtifactResolution()throws Exception{
        Path reactor=OverlayFixtures.project(root.resolve("reactor"),"parent","1","<packaging>pom</packaging><modules><module>a</module><module>b</module></modules>");
        OverlayFixtures.project(reactor.resolve("a"),"app","1","<dependencies>"+MavenFixtures.dependency("library","1")+"</dependencies>");
        OverlayFixtures.project(reactor.resolve("b"),"library","1","");
        try(var resolver=new MavenResolver(TestSupport.config(root,Duration.ofHours(4)))){var graph=resolver.resolve(reactor);assertThat(graph.modules()).hasSize(3);assertThat(graph.offline()).isTrue();}
    }
}

package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 2 checkpoint: re-resolve changed POMs and publish the classpath delta. */
@Tag("phase-2")
class PomChangeClasspathDiffTest {
    @TempDir Path temp;
    @Test void dependencyEditChangesTheMaintainedGraphBeforeTheNextSemanticRead() throws Exception {
        var config = TestSupport.config(temp, Duration.ofHours(4));
        MavenFixtures.artifact(config.m2Repo(), "lib", "1", "");
        MavenFixtures.artifact(config.m2Repo(), "lib", "2", "");
        Path project = MavenFixtures.project(temp.resolve("project"),
                "<properties><maven.compiler.release>25</maven.compiler.release></properties><dependencies>"
                        + MavenFixtures.dependency("lib", "1") + "</dependencies>");
        Path source=Files.createDirectories(project.resolve("src/main/java/p")).resolve("Use.java");
        Files.writeString(source,"package p; class Use { int value(){return 1;} }");
        try (var app = new Application(config)) {
            String id = TestSupport.open(app, project);
            var session = app.sessions().get(id);
            Object old = session.state("classpath_generation");
            long beforeMutation=resolveWorkspaceCalls(app);

            Path pom=project.resolve("pom.xml");
            Files.writeString(pom, Files.readString(pom)
                    .replace(MavenFixtures.dependency("lib", "1"), MavenFixtures.dependency("lib", "2")));

            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(Objects.equals(old,session.state("classpath_generation"))&&System.nanoTime()<deadline)
                Thread.sleep(20);
            assertThat(session.state("classpath_generation")).as("project-model watcher must publish the changed graph")
                    .isNotEqualTo(old);
            assertThat(session.state("classpath_diff").toString()).contains("added=", "removed=", "lib-1.jar", "lib-2.jar");
            assertThat(((dev.jvmd.resolver.Resolution)session.state("resolution")).classpath().toString()).contains("lib-2.jar");
            assertThat(resolveWorkspaceCalls(app)).as("one project mutation causes one maintained refresh")
                    .isEqualTo(beforeMutation+1);

            long admitted=resolveWorkspaceCalls(app);
            var semantic=TestSupport.request(app.dispatcher(),"symbol.overview",Map.of(
                    "session",id,"path",source.toString(),"depth",2,"limit",100));
            assertThat(semantic.has("error")).as(semantic.toPrettyString()).isFalse();
            assertThat(resolveWorkspaceCalls(app))
                    .as("first semantic read after project admission consumes the new graph")
                    .isEqualTo(admitted);
            TestSupport.request(app.dispatcher(),"symbol.overview",Map.of(
                    "session",id,"path",source.toString(),"depth",2,"limit",100));
            assertThat(resolveWorkspaceCalls(app))
                    .as("repeated semantic reads after admission stay resolver-free")
                    .isEqualTo(admitted);

            var explicit=TestSupport.request(app.dispatcher(), "deps.graph", Map.of("session", id));
            assertThat(explicit.has("error")).as(explicit.toPrettyString()).isFalse();
            assertThat(resolveWorkspaceCalls(app))
                    .as("deps.graph retains its explicit fresh-project-model boundary")
                    .isEqualTo(admitted+1);
        }
    }

    private static long resolveWorkspaceCalls(Application app){
        var status=TestSupport.request(app.dispatcher(),"daemon.status",Map.of());
        assertThat(status.has("error")).as(status.toPrettyString()).isFalse();
        return status.path("result").path("result").path("resolver").path("resolve_workspace_calls").asLong();
    }
}

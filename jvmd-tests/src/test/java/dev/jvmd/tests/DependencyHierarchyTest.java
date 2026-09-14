package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: dependency hierarchy traversal continues beyond the first external supertype. */
@Tag("phase-8")
class DependencyHierarchyTest {
    @TempDir Path root;
    @Test void nestedDependencyHierarchyHonorsDirectionAndDepth()throws Exception{
        Path repo=Files.createDirectories(root.resolve("repository")),state=Files.createDirectories(root.resolve("state")),workspace=Files.createDirectories(root.resolve("workspace"));
        Path jar=IndexFixtures.jar(repo,"sample","package fixture; public class Sample { public static class Base {} public static class Middle extends Base {} public static class Leaf extends Middle {} }",false);
        try(var index=new IndexService(state.resolve("index.db"),repo)){index.indexJar(jar,"fixture:sample:1","jar");index.linkEdges();}
        var config=new Config(Path.of(System.getProperty("java.home")),null,repo,3,Duration.ofHours(4),512,false,state,root.resolve("daemon.sock"));
        try(var app=new Application(config)){
            String session=TestSupport.open(app,workspace);
            var up=TestSupport.request(app.dispatcher(),"symbol.hierarchy",Map.of("session",session,"ref","Sample/Leaf","direction","up","depth",2)).path("result").path("result");
            assertThat(up.path("edges").size()).isEqualTo(2);assertThat(up.path("symbols").toString()).contains("Middle","Base","Leaf");
            var down=TestSupport.request(app.dispatcher(),"symbol.hierarchy",Map.of("session",session,"ref","Sample/Base","direction","down","depth",1)).path("result").path("result");
            assertThat(down.path("edges").size()).isEqualTo(1);assertThat(down.path("symbols").toString()).contains("Middle").doesNotContain("Leaf");
            var find=TestSupport.request(app.dispatcher(),"symbol.find",Map.of("session",session,"name_path","Sample","scope","deps","depth",1)).path("result").path("result").path("matches");
            assertThat(find.toString()).contains("Sample/Base","Sample/Middle","Sample/Leaf");
        }
    }
}

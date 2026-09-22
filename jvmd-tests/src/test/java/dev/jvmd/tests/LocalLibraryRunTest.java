package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-10")
class LocalLibraryRunTest {
    @TempDir Path root;
    @Test void runCompilesAndExecutesAnUninstalledLocalLibrary() throws Exception {
        Path host=OverlayFixtures.project(root.resolve("host"),"host","1","<dependencies>"+MavenFixtures.dependency("library","1")+"</dependencies>");
        Path library=OverlayFixtures.project(root.resolve("library"),"library","1","");
        OverlayFixtures.source(library,"Library","public class Library { public static int value(){return 42;} }");
        OverlayFixtures.source(host,"Main","public class Main { public static void main(String[] args)throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),\"READY revision=B value=\"+Library.value()); } }");
        Path marker=host.resolve("observed.txt");
        assertThat(host.resolve("target/classes")).doesNotExist();
        assertThat(library.resolve("target/classes")).doesNotExist();
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))) {
            var opened=TestSupport.request(app.dispatcher(),"session.open",Map.of("root",host.toString(),"manifest",Map.of("roots",List.of(host.toString(),library.toString()),"ignore_versions",false)));
            assertThat(opened.has("error")).as(opened.toString()).isFalse();
            String session=opened.path("result").path("result").path("session").asText();
            var run=TestSupport.request(app.dispatcher(),"run.start",Map.of("session",session,"target","p.Main","args",List.of(marker.toString())));
            assertThat(run.has("error")).as(run.toPrettyString()).isFalse();
            assertThat(run.path("result").path("result").path("pid").asLong()).isPositive();
            long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();
            while(!Files.exists(marker)&&System.nanoTime()<deadline)Thread.sleep(20);
            assertThat(marker).as("Actual code must execute; successful launch acceptance is insufficient").exists();
            assertThat(Files.readString(marker)).isEqualTo("READY revision=B value=42");
        }
    }
}

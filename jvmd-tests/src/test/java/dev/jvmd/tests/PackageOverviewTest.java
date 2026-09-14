package dev.jvmd.tests;

import dev.jvmd.dist.Application;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 8: package and directory overviews share the paged declaration model. */
@Tag("phase-8")
class PackageOverviewTest {
    @TempDir Path root;
    @Test void packageAndDirectoryPagesContainOnlyTheirDeclarations()throws Exception{
        Path a=Files.createDirectories(root.resolve("a")),b=Files.createDirectories(root.resolve("b"));
        Files.writeString(a.resolve("One.java"),"package a; class One { int member; int value(){return 7;} }");
        Files.writeString(a.resolve("Two.java"),"package a; class Two {}");Files.writeString(b.resolve("Other.java"),"package b; class Other {}");
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);var params=new LinkedHashMap<String,Object>();params.put("session",session);params.put("package","a");params.put("depth",1);params.put("limit",2);
            var found=new HashSet<String>();boolean more;
            do{
                var response=TestSupport.request(app.dispatcher(),"symbol.overview",params);assertThat(response.has("error")).isFalse();var envelope=response.path("result");
                for(var symbol:envelope.path("result").path("symbols")){found.add(symbol.path("name").asText());assertThat(symbol.has("body")).isFalse();}
                more=envelope.path("truncated").asBoolean();if(more)params.put("cursor",envelope.path("cursor").asText());
            }while(more);
            assertThat(found).containsExactlyInAnyOrder("One","Two","member","value");
            var directory=TestSupport.request(app.dispatcher(),"symbol.overview",Map.of("session",session,"path",a.toString(),"depth",0)).path("result").path("result").path("symbols");
            assertThat(directory.size()).isEqualTo(2);assertThat(directory.toString()).doesNotContain("Other","member","value");
        }
    }
}

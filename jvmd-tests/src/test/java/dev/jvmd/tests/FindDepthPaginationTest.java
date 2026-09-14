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

/** Implements 4.8: kind/depth filters apply before pagination while parent discovery remains unfiltered. */
@Tag("phase-8")
class FindDepthPaginationTest {
    @TempDir Path temp;
    @Test void dependencyMethodsAreFoundThroughAFilteredParentAndPastDeeperMembers() throws Exception {
        Path repo=temp.resolve("repository");var source=new StringBuilder("package fixture; public class Sample { public static class Nested {");
        for(int i=0;i<160;i++)source.append("public void deep").append(i).append("(){} ");
        source.append("} public void alpha(){} public void beta(){} public void gamma(){} }");
        Path jar=IndexFixtures.jar(repo,"sample",source.toString(),false);
        var config=TestSupport.config(temp,Duration.ofHours(4));
        try(var index=new IndexService(config.stateDir().resolve("index.db"),repo)) { index.indexJar(jar,"fixture:sample:1","jar"); }
        Path root=Files.createDirectories(temp.resolve("empty"));
        try(var app=new Application(config)) {
            String session=TestSupport.open(app,root);var names=new ArrayList<String>();String cursor=null;
            for(int page=0;page<4;page++) {
                var args=new LinkedHashMap<String,Object>();args.put("session",session);args.put("name_path","Sample");args.put("scope","deps");args.put("depth",1);args.put("kinds",List.of("method"));args.put("limit",1);if(cursor!=null)args.put("cursor",cursor);
                var answer=TestSupport.request(app.dispatcher(),"symbol.find",args);
                assertThat(answer.has("error")).withFailMessage(answer.toPrettyString()).isFalse();var envelope=answer.path("result");
                var matches=envelope.path("result").path("matches");assertThat(matches.size()).isEqualTo(1);names.add(matches.get(0).path("name").asText());
                if(!envelope.path("truncated").asBoolean())break;
                cursor=envelope.path("cursor").asText();assertThat(cursor).isNotBlank();
            }
            assertThat(names).containsExactly("alpha","beta","gamma");
        }
    }
    @Test void workspaceDepthAndKindFiltersKeepEveryPage() throws Exception {
        Path root=Files.createDirectories(temp.resolve("source"));Files.writeString(root.resolve("Example.java"),"class Example { static class Nested { void hidden(){} } void first(){} void second(){} }");
        try(var app=new Application(TestSupport.config(temp,Duration.ofHours(4)))) {
            String session=TestSupport.open(app,root);var names=new ArrayList<String>();String cursor=null;
            for(int i=0;i<3;i++) {
                var args=new LinkedHashMap<String,Object>();args.put("session",session);args.put("name_path","Example");args.put("depth",1);args.put("kinds",List.of("method"));args.put("limit",1);if(cursor!=null)args.put("cursor",cursor);
                var envelope=TestSupport.request(app.dispatcher(),"symbol.find",args).path("result");for(var match:envelope.path("result").path("matches"))names.add(match.path("name").asText());
                if(!envelope.path("truncated").asBoolean())break;cursor=envelope.path("cursor").asText();
            }
            assertThat(names).containsExactly("first","second");
        }
    }
}

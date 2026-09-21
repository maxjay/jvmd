package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.dist.Application;
import dev.jvmd.index.IndexService;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3") @Tag("phase-8")
class DependencyFindPaginationTest {
    @TempDir Path root;
    @Test void aNewWorkspaceIndexesOnlyItsMissingDependenciesBeforeTheNextScan()throws Exception{
        var config=TestSupport.config(root,Duration.ofHours(4));
        Path first=IndexFixtures.jar(config.m2Repo().resolve("fixture/first/1"),"first-1","package first; public class Sample { public int value; }",true);
        Files.writeString(first.resolveSibling("first-1.pom"),pom("fixture","first",""));
        try(var index=new IndexService(config.stateDir().resolve("index.db"),config.m2Repo())){index.indexJar(first,"fixture:first:1","jar");}
        Path initial=Files.createDirectories(root.resolve("initial"));Files.writeString(initial.resolve("pom.xml"),pom("workspace","initial","first"));
        try(var app=new Application(config)){
            String session=TestSupport.open(app,initial);assertThat(query(app,Map.of("session",session,"scope","deps","name_path","first.Sample")).path("result").path("matches")).hasSize(1);
            Path added=IndexFixtures.jar(config.m2Repo().resolve("fixture/added/1"),"added-1","package added; public class Sample { public int added; }",true);
            Files.writeString(added.resolveSibling("added-1.pom"),pom("fixture","added",""));
            Path next=Files.createDirectories(root.resolve("next"));Files.writeString(next.resolve("pom.xml"),pom("workspace","next","first","added"));
            String nextSession=TestSupport.open(app,next);
            assertThat(query(app,Map.of("session",nextSession,"scope","deps","name_path","added.Sample")).path("result").path("matches")).hasSize(1);
            var status=TestSupport.complete(app.dispatcher(),"session.status",Map.of("session",nextSession)).path("result").path("result").path("index");
            assertThat(status.path("timings").path("scans").asLong()).isZero();
            assertThat(status.path("hashes").asLong()).isEqualTo(1);
        }
    }
    @Test void referencedDependencyRemainsInCombinedSearchAfterBindingsWarmup()throws Exception {
        var config=TestSupport.config(root,Duration.ofHours(4));
        Path jar=IndexFixtures.jar(config.m2Repo().resolve("fixture/library/1"),"library-1","package fixture; public class Sample { public int value(){return 1;} }",true);
        Files.writeString(jar.resolveSibling("library-1.pom"),pom("fixture","library",""));
        try(var index=new IndexService(config.stateDir().resolve("index.db"),config.m2Repo())){index.indexJar(jar,"fixture:library:1","jar");}
        Path workspace=Files.createDirectories(root.resolve("workspace"));Files.writeString(workspace.resolve("pom.xml"),pom("workspace","app","library"));
        Path sources=Files.createDirectories(workspace.resolve("src/main/java"));
        Files.writeString(sources.resolve("Caller.java"),"class Caller { int call(){return new fixture.Sample().value();} }");
        try(var app=new Application(config)){
            String session=TestSupport.open(app,workspace);
            var before=query(app,Map.of("session",session,"scope","all","name_path","fixture.Sample")).path("result").path("matches");
            assertThat(before).hasSize(1);
            var warm=TestSupport.complete(app.dispatcher(),"symbol.references",Map.of("session",session,"ref","Caller/call()","direction","out"));
            assertThat(warm.has("error")).as(warm.toString()).isFalse();
            assertThat(warm.path("result").path("result").path("edges").toString()).contains("Sample#value().");
            var after=query(app,Map.of("session",session,"scope","all","name_path","fixture.Sample")).path("result").path("matches");
            assertThat(after).hasSize(1);assertThat(after).isEqualTo(before);
        }
    }
    private static String pom(String group,String name,String... dependencies){
        var xml=new StringBuilder("<project><modelVersion>4.0.0</modelVersion><groupId>").append(group).append("</groupId><artifactId>").append(name).append("</artifactId><version>1</version><dependencies>");
        for(String dependency:dependencies)if(!dependency.isBlank())xml.append("<dependency><groupId>fixture</groupId><artifactId>").append(dependency).append("</artifactId><version>1</version></dependency>");
        return xml.append("</dependencies></project>").toString();
    }
    @Test void dependencyPagesContinueByIdAndKeepLegacyOffsets()throws Exception{
        var source=new StringBuilder("package fixture; public class Sample {");
        for(int i=0;i<73;i++)source.append(" public int match").append(i).append("(){return ").append(i).append(";}");
        source.append(" public int other; }");
        var config=TestSupport.config(root,Duration.ofHours(4));
        Path jar=IndexFixtures.jar(config.m2Repo(),"sample",source.toString(),true);
        try(var index=new IndexService(config.stateDir().resolve("index.db"),config.m2Repo())){index.indexJar(jar,"fixture:sample:1","jar");}
        Path workspace=Files.createDirectories(root.resolve("workspace"));
        try(var app=new Application(config)){
            String session=TestSupport.open(app,workspace);
            var params=new LinkedHashMap<String,Object>(Map.of("session",session,"scope","deps","name_path","match","substring",true,"kinds",List.of("method"),"limit",200));
            var full=query(app,params);assertThat(full.path("truncated").asBoolean()).isFalse();
            var expected=new ArrayList<JsonNode>();full.path("result").path("matches").forEach(expected::add);assertThat(expected).hasSize(73);
            params.put("limit",7);var rows=new ArrayList<JsonNode>();
            do{
                var page=query(app,params);page.path("result").path("matches").forEach(rows::add);
                if(!page.path("truncated").asBoolean())break;
                assertThat(page.path("cursor").asText()).startsWith("index:");params.put("cursor",page.path("cursor").asText());
            }while(rows.size()<100);
            assertThat(rows).containsExactlyElementsOf(expected);
            params.put("cursor","7");var legacy=new ArrayList<JsonNode>();query(app,params).path("result").path("matches").forEach(legacy::add);
            assertThat(legacy).containsExactlyElementsOf(expected.subList(7,14));
            for(String invalid:List.of("index:-1","index:0","index:broken","index:9223372036854775808")){
                params.put("cursor",invalid);assertThat(TestSupport.request(app.dispatcher(),"symbol.find",params).path("error").path("code").asInt()).isEqualTo(-32602);
            }
        }
    }
    private static JsonNode query(Application app,Map<String,Object> params)throws Exception{
        var response=TestSupport.complete(app.dispatcher(),"symbol.find",params);assertThat(response.has("error")).as(response.toString()).isFalse();
        assertThat(response.path("result").path("warnings").toString()).doesNotContain("analyzer_fault");return response.path("result");
    }
}

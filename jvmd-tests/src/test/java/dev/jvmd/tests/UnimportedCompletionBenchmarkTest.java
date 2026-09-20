package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.Json;
import dev.jvmd.dist.Application;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class UnimportedCompletionBenchmarkTest {
    @TempDir Path root;

    @Test void measuresIndexedUnimportedTypeCompletionBeforeAndAfterFeature()throws Exception{
        var config=TestSupport.config(root,Duration.ofHours(4));
        installDependency(config.m2Repo());
        Path project=MavenFixtures.project(root.resolve("project"),
                "<properties><maven.compiler.release>25</maven.compiler.release></properties><dependencies>"+
                        MavenFixtures.dependency("library","1")+"</dependencies>");
        Path sourceRoot=Files.createDirectories(project.resolve("src/main/java/app"));
        Path file=sourceRoot.resolve("Use.java");Files.writeString(file,text("Sa"));

        try(var app=new Application(config)){
            String session=TestSupport.open(app,project);
            var indexed=TestSupport.request(app.dispatcher(),"symbol.find",Map.of(
                    "session",session,"name_path","Sample","scope","deps","kinds",List.of("class"),"limit",20));
            assertThat(indexed.has("error")).as(indexed.toString()).isFalse();
            assertThat(indexed.path("result").path("result").path("matches").findValuesAsText("name")).contains("Sample");

            TestSupport.request(app.dispatcher(),"document.open",Map.of("session",session,"path",file.toString(),"version",1,"text",text("Sa")));
            long indexBefore=indexQueries(app,session);
            var samples=new ArrayList<Map<String,Object>>();int version=1;
            for(int cycle=0;cycle<5;cycle++){
                for(String prefix:List.of("Sa","Sam","Samp")){
                    String source=text(prefix);
                    if(!(cycle==0&&prefix.equals("Sa"))){
                        TestSupport.request(app.dispatcher(),"document.change",Map.of(
                                "session",session,"path",file.toString(),"version",++version,
                                "changes",List.of(Map.of("text",source))));
                    }
                    long started=System.nanoTime();
                    JsonNode response=TestSupport.request(app.dispatcher(),"symbol.completion",Map.of(
                            "session",session,"path",file.toString(),"line",0,
                            "character",source.indexOf(prefix)+prefix.length(),"limit",100));
                    double elapsed=(System.nanoTime()-started)/1_000_000.0;
                    assertThat(response.has("error")).as(response.toString()).isFalse();
                    var items=response.path("result").path("result").path("items");
                    samples.add(Map.of(
                            "cycle",cycle,"prefix",prefix,"request_ms",elapsed,"items",items.size(),
                            "has_unimported_sample",items.findValuesAsText("name").contains("Sample")));
                }
            }
            long indexAfter=indexQueries(app,session);
            long found=samples.stream().filter(sample->Boolean.TRUE.equals(sample.get("has_unimported_sample"))).count();
            var output=new LinkedHashMap<String,Object>();
            output.put("feature","index-backed-unimported-type-completion");
            output.put("indexed_type","lib.Sample");
            output.put("samples",samples.size());
            output.put("successful_type_suggestions",found);
            output.put("recall_pct",100.0*found/samples.size());
            output.put("index_query_delta",indexAfter-indexBefore);
            output.put("median_request_ms",median(samples.stream().mapToDouble(s->((Number)s.get("request_ms")).doubleValue()).toArray()));
            output.put("samples_detail",samples);
            Path report=TestSupport.repo().resolve("jvmd-tests/target/unimported-completion-perf.json");
            Files.createDirectories(report.getParent());Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),output);
            System.out.println("unimported-completion-perf "+Json.MAPPER.writeValueAsString(output));
        }
    }

    private static String text(String prefix){return "package app; class Use { "+prefix+" value; }";}

    private static long indexQueries(Application app,String session){
        var status=TestSupport.request(app.dispatcher(),"session.status",Map.of("session",session));
        return status.path("result").path("result").path("index").path("timings").path("query_calls").asLong();
    }

    private static double median(double[] values){
        Arrays.sort(values);int middle=values.length/2;
        return values.length%2==0?(values[middle-1]+values[middle])/2.0:values[middle];
    }

    private void installDependency(Path repository)throws Exception{
        Path build=Files.createDirectories(root.resolve("dependency-build"));
        Path compiled=IndexFixtures.jar(build,"library","""
                package lib;
                public class Sample {
                    public String label(){ return "sample"; }
                }
                """,true);
        Path directory=Files.createDirectories(repository.resolve("fixture/library/1"));
        Path jar=directory.resolve("library-1.jar"),pom=directory.resolve("library-1.pom");
        Files.copy(compiled,jar,StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(pom,MavenFixtures.pom("fixture","library","1",""));
        for(Path path:List.of(jar,pom)){
            byte[] digest=java.security.MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(path));
            Files.writeString(path.resolveSibling(path.getFileName()+".sha1"),java.util.HexFormat.of().formatHex(digest),StandardCharsets.US_ASCII);
        }
    }
}

package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
@Tag("perf")
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

        Path daemonRoot=Files.createDirectories(root.resolve("daemon"));
        try(var daemon=new AotDaemon(daemonRoot,Map.of("m2_repo",config.m2Repo().toString()))){
            String session=daemon.request("session.open",Map.of("root",project.toString())).path("result").path("session").asText();
            var indexed=daemon.request("symbol.find",Map.of(
                    "session",session,"name_path","Sample","scope","deps","kinds",List.of("class"),"limit",20));
            assertThat(indexed.path("result").path("matches").findValuesAsText("name")).contains("Sample");

            daemon.request("document.open",Map.of("session",session,"path",file.toString(),"version",1,"text",text("Sa")));
            long indexBefore=indexQueries(daemon,session);
            var samples=new ArrayList<Map<String,Object>>();int version=1;
            for(int cycle=0;cycle<5;cycle++){
                for(String prefix:List.of("Sa","Sam","Samp")){
                    String source=text(prefix);
                    if(!(cycle==0&&prefix.equals("Sa"))){
                        daemon.request("document.change",Map.of(
                                "session",session,"path",file.toString(),"version",++version,
                                "changes",List.of(Map.of("text",source))));
                    }
                    long started=System.nanoTime();
                    JsonNode response=daemon.request("symbol.completion",Map.of(
                            "session",session,"path",file.toString(),"line",0,
                            "character",source.indexOf(prefix)+prefix.length(),"limit",100));
                    double elapsed=(System.nanoTime()-started)/1_000_000.0;
                    var items=response.path("result").path("items");
                    assertThat(items.isArray()).as(response.toString()).isTrue();
                    samples.add(Map.of(
                            "phase",cycle==0?"prime":"warm","cycle",cycle,"prefix",prefix,"request_ms",elapsed,"items",items.size(),
                            "has_unimported_sample",items.findValuesAsText("name").contains("Sample")));
                }
            }
            long indexAfter=indexQueries(daemon,session);
            long found=samples.stream().filter(sample->Boolean.TRUE.equals(sample.get("has_unimported_sample"))).count();
            var output=new LinkedHashMap<String,Object>();
            output.put("feature","index-backed-unimported-type-completion");
            output.put("mode","warm-production-daemon");
            output.put("transport","unix-domain-socket");
            output.put("indexed_type","lib.Sample");
            var warmSamples=samples.stream().filter(sample->sample.get("phase").equals("warm")).toList();
            double[] warmTimes=warmSamples.stream().mapToDouble(sample->((Number)sample.get("request_ms")).doubleValue()).toArray();
            output.put("samples",samples.size());
            output.put("warm_samples",warmSamples.size());
            output.put("successful_type_suggestions",found);
            output.put("recall_pct",100.0*found/samples.size());
            output.put("index_query_delta",indexAfter-indexBefore);
            output.put("warm_p50_ms",percentile(warmTimes,.50));
            output.put("warm_p95_ms",percentile(warmTimes,.95));
            output.put("samples_detail",samples);
            Path report=TestSupport.repo().resolve("jvmd-tests/target/unimported-completion-perf.json");
            Files.createDirectories(report.getParent());Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(report.toFile(),output);
            System.out.println("unimported-completion-perf "+Json.MAPPER.writeValueAsString(output));
        }
    }

    private static String text(String prefix){return "package app; class Use { "+prefix+" value; }";}

    private static long indexQueries(AotDaemon daemon,String session)throws Exception{
        var status=daemon.request("session.status",Map.of("session",session));
        return status.path("result").path("index").path("timings").path("query_calls").asLong();
    }

    private static double percentile(double[] values,double percentile){
        Arrays.sort(values);int index=(int)Math.ceil(percentile*values.length)-1;
        return values[Math.max(0,Math.min(values.length-1,index))];
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

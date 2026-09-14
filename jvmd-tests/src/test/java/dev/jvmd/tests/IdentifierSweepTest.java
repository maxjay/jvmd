package dev.jvmd.tests;

import dev.jvmd.analyzer.SourceText;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.resolver.MavenResolver;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 5 and 12.4: every lexical identifier is probed through binding, description, position search and references. */
@Tag("phase-4") @Tag("corpus")
class IdentifierSweepTest {
    @TempDir Path temp;
    @Test void allFourSymbolProbesMeetTheCommittedFloor()throws Exception{
        var reports=new ArrayList<Map<String,Object>>();double floor=Json.MAPPER.readTree(TestSupport.repo().resolve("jvmd-tests/floors.json").toFile()).path("identifier_correctness").asDouble();
        long total=0,correct=0;
        for(Path root:List.of(TestSupport.repo().resolve("jvmd-tests/corpus/petclinic"),TestSupport.repo())){
            Path state=Files.createDirectories(temp.resolve(root.getFileName().toString()));
            var config=new Config(Path.of(System.getProperty("java.home")),null,Path.of(System.getProperty("user.home"),".m2/repository"),3,Duration.ofHours(4),1024,false,state,state.resolve("daemon.sock"));
            var files=new LinkedHashSet<Path>();
            try(var resolver=new MavenResolver(config)){for(var module:resolver.resolve(root).modules())for(String source:java.util.stream.Stream.concat(module.sources().stream(),module.testSources().stream()).toList())if(Files.isDirectory(Path.of(source)))try(var paths=Files.walk(Path.of(source))){paths.filter(Files::isRegularFile).filter(p->p.toString().endsWith(".java")).sorted().forEach(files::add);}}
            try(var app=new Application(config)){
                String session=TestSupport.open(app,root);assertThat(session).isNotBlank();
                for(Path file:files){
                    long count=0,matched=0;var misses=new ArrayList<Map<String,Object>>();var text=new SourceText(Files.readString(file));
                    for(var token:text.tokens()){
                        count++;var position=text.position(token.start());
                        var location=Map.of("session",session,"path",file.toString(),"line",position.line(),"character",position.character());
                        var at=TestSupport.complete(app.dispatcher(),"symbol.atPosition",location).path("result").path("result");
                        var found=TestSupport.complete(app.dispatcher(),"symbol.find",location).path("result").path("result");
                        var candidates=new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
                        if(at.path("ambiguous").asBoolean())at.path("candidates").forEach(candidates::add);else candidates.add(at);
                        var foundIds=new LinkedHashSet<String>();if(found.path("ambiguous").asBoolean())found.path("candidates").forEach(c->foundIds.add(c.path("scip").asText()));else foundIds.add(found.path("scip").asText());
                        var expectedIds=new LinkedHashSet<String>();candidates.forEach(c->expectedIds.add(c.path("scip").asText()));
                        boolean valid=!candidates.isEmpty()&&!expectedIds.contains("")&&foundIds.equals(expectedIds);
                        var evidence=new ArrayList<Object>();
                        for(var candidate:candidates){
                            String scip=candidate.path("scip").asText(),ref=scip.isEmpty()?token.text():scip;
                            var described=TestSupport.complete(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref",ref)).path("result").path("result");
                            var referenceArgs=new LinkedHashMap<String,Object>(Map.of("session",session,"ref",ref,"direction","out","depth",1,"limit",1000));
                            boolean namesRoot=false;
                            do {
                                var page=TestSupport.complete(app.dispatcher(),"symbol.references",referenceArgs).path("result");
                                for(var symbol:page.path("result").path("symbols"))if(symbol.path("scip").asText().equals(scip)&&symbol.path("name").asText().equals(token.text()))namesRoot=true;
                                if(namesRoot||!page.path("truncated").asBoolean())break;
                                referenceArgs.put("cursor",page.path("cursor").asText());
                            } while(true);
                            boolean description=described.path("scip").asText().equals(scip)&&described.path("name").asText().equals(token.text());
                            valid&=candidate.path("name").asText().equals(token.text())&&description&&namesRoot;
                            evidence.add(Map.of("scip",scip,"description_names_token",description,"references_name_token",namesRoot));
                        }
                        if(valid)matched++;else if(misses.size()<30)misses.add(Map.of("token",token.text(),"line",position.line()+1,"character",position.character(),"answer",at,"find",found,"probes",evidence));
                    }
                    total+=count;correct+=matched;reports.add(Map.of("file",file.toString(),"identifiers",count,"correct",matched,"rate",count==0?1d:(double)matched/count,"misses",misses));
                    System.out.println("sweep-file "+root.relativize(file)+" "+matched+"/"+count);
                    if(matched<count)System.out.println("sweep-misses "+root.relativize(file)+" "+Json.MAPPER.writeValueAsString(misses));
                }
            }
        }
        double rate=total==0?0:(double)correct/total;
        var result=Map.of("identifiers",total,"correct",correct,"rate",rate,"floor",floor,"files",reports);
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/sweep.json").toFile(),result);
        System.out.println("identifier-sweep "+correct+"/"+total+" rate="+rate);
        assertThat(total).isGreaterThan(1000);assertThat(rate).as("identifier correctness across binding, description, position search and references").isGreaterThanOrEqualTo(floor);
    }
}

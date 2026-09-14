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

/** Implements 5 and 12.4: every lexical identifier is probed for binding and description correctness. */
@Tag("phase-4") @Tag("corpus")
class IdentifierSweepTest {
    @TempDir Path temp;
    @Test void bindingsAndDescriptionsMeetTheCommittedFloor()throws Exception{
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
                        var at=TestSupport.request(app.dispatcher(),"symbol.atPosition",Map.of("session",session,"path",file.toString(),"line",position.line(),"character",position.character())).path("result").path("result");
                        String scip=at.path("scip").asText();boolean valid=!scip.isEmpty()&&at.path("name").asText().equals(token.text());
                        if(valid){var described=TestSupport.request(app.dispatcher(),"symbol.describe",Map.of("session",session,"ref",scip)).path("result").path("result");valid=described.path("scip").asText().equals(scip)&&described.path("name").asText().equals(token.text());}
                        if(valid)matched++;else if(misses.size()<30)misses.add(Map.of("token",token.text(),"line",position.line()+1,"character",position.character(),"answer",at));
                    }
                    total+=count;correct+=matched;reports.add(Map.of("file",file.toString(),"identifiers",count,"correct",matched,"rate",count==0?1d:(double)matched/count,"misses",misses));
                    System.out.println("sweep-file "+root.relativize(file)+" "+matched+"/"+count);
                }
            }
        }
        double rate=total==0?0:(double)correct/total;
        var result=Map.of("identifiers",total,"correct",correct,"rate",rate,"floor",floor,"files",reports);
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/sweep.json").toFile(),result);
        System.out.println("identifier-sweep "+correct+"/"+total+" rate="+rate);
        assertThat(total).isGreaterThan(1000);assertThat(rate).as("identifier binding and description correctness").isGreaterThanOrEqualTo(floor);
    }
}

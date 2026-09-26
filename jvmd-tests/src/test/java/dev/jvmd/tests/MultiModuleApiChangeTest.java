package dev.jvmd.tests;

import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** A changing base API must invalidate its consumers while preserving unrelated modules. */
@Tag("phase-4")
class MultiModuleApiChangeTest {
    @TempDir Path root;
    @Test void repeatedBaseSignatureChangesReuseIndependentModuleDiagnostics()throws Exception{
        Files.writeString(root.resolve("pom.xml"),MavenFixtures.pom("fixture","reactor","1","<packaging>pom</packaging><modules><module>core</module><module>app</module><module>independent</module></modules>"));
        Path base=null;
        for(String module:List.of("core","app","independent")){
            Path directory=Files.createDirectories(root.resolve(module));
            Files.writeString(directory.resolve("pom.xml"),MavenFixtures.pom("fixture",module,"1","<properties><maven.compiler.release>25</maven.compiler.release></properties>"+(module.equals("app")?"<dependencies>"+MavenFixtures.dependency("core","1")+"</dependencies>":"")));
            Path sources=Files.createDirectories(directory.resolve("src/main/java/fixture/"+module));
            for(int i=0;i<32;i++){
                String name=module.substring(0,1).toUpperCase()+module.substring(1)+i;
                Path file=sources.resolve(name+".java");
                Files.writeString(file,"package fixture."+module+"; public class "+name+" { public static int value(){return "+(module.equals("app")?"fixture.core.Core0.value()":"1")+";} }");
                if(module.equals("core")&&i==0)base=file;
            }
        }
        try(var app=new Application(TestSupport.config(root,Duration.ofHours(4)))){
            String session=TestSupport.open(app,root);
            assertThat(request(app,"diag.get",Map.of("session",session,"limit",1000)).path("diagnostics").isEmpty()).isTrue();
            var before=request(app,"session.status",Map.of("session",session));
            long independentQueries=independentQueries(app,session);
            assertThat(before.path("diagnostics").path("last").path("files_total").asInt())
                    .as(before.toPrettyString()).isGreaterThanOrEqualTo(96);
            for(int i=0;i<4;i++){
                boolean changed=i%2==0;String type=changed?"String":"int",value=changed?"\"changed\"":"1";
                Files.writeString(base,"package fixture.core; public class Core0 { public static "+type+" value(){return "+value+";} }");
                var result=request(app,"diag.get",Map.of("session",session,"limit",1000));
                assertThat(result.path("diagnostics").size()).isEqualTo(changed?32:0);
                var status=request(app,"session.status",Map.of("session",session));
                assertThat(independentQueries(app,session))
                        .as("independent module must receive no additional javac work after signature change %s",i)
                        .isEqualTo(independentQueries);
                assertThat(status.path("diagnostics").path("last").path("files_valid").asInt()).isGreaterThanOrEqualTo(32);
            }
        }
    }
    private static long independentQueries(Application app,String session){
        var status=request(app,"session.status",Map.of("session",session,"section","module_actors"));
        long queries=0;
        for(var entry:status.path("actor_queries").properties())
            if(entry.getKey().replace('\\','/').contains("/independent:false"))queries+=entry.getValue().asLong();
        return queries;
    }
    private static JsonNode request(Application app,String method,Map<String,?> parameters){
        var response=TestSupport.request(app.dispatcher(),method,parameters);assertThat(response.has("error")).withFailMessage(response.toPrettyString()).isFalse();
        var envelope=response.path("result");
        assertThat(envelope.path("warnings")).withFailMessage(response.toPrettyString())
                .allMatch(w->method.equals("diag.get")&&w.asText().equals("originates: fixture:core:1"));
        return envelope.path("result");
    }
}

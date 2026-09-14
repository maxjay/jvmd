package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.runtime.JavaRuntime;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 11: configured JBR adds methods, fields and signatures; stock JVM requires restart. */
@Tag("phase-11")
class EnhancedHotSwapTest {
    @TempDir Path root;
    @Test void absentOrInvalidJbrUsesTheConfiguredStockRuntime()throws Exception{
        Path jdk=Path.of(System.getProperty("java.home"));var stock=JavaRuntime.select(jdk,null);assertThat(stock.home()).isEqualTo(jdk);assertThat(stock.enhanced()).isFalse();assertThat(stock.options()).isEmpty();
        var missing=JavaRuntime.select(jdk,root.resolve("missing-jbr"));assertThat(missing.home()).isEqualTo(jdk);assertThat(missing.warnings()).anyMatch(w->w.startsWith("jbr_unavailable"));
    }
    @Test void enhancedChangesAreCallableOnExistingObjectsWithoutRestart()throws Exception{
        String configured=System.getenv("JVMD_JBR_HOME");if(System.getenv("CI")!=null)assertThat(configured).as("The phase-11 CI gate installs the pinned JBR").isNotBlank();
        Assumptions.assumeTrue(configured!=null&&!configured.isBlank(),"A configured JBR is required for enhanced redefinition");
        Path jbr=Path.of(configured);var selection=JavaRuntime.select(Path.of(System.getProperty("java.home")),jbr);assertThat(selection.enhanced()).isTrue();
        var evidence=new ArrayList<Map<String,Object>>();
        for(boolean enhanced:List.of(true,false)){
            Path workspace=Files.createDirectories(root.resolve(enhanced?"enhanced":"stock")),file=workspace.resolve("Probe.java");
            String source="""
                public class Probe {
                  static final Probe retained=new Probe(); static int count; int existing(int value){return value;}
                  static int tick(){return ++count;}
                  public static void main(String[] args)throws Exception {
                    while(true){tick();Thread.sleep(20);}
                  }
                }
                """;
            Files.writeString(file,source);var config=new Config(Path.of(System.getProperty("java.home")),enhanced?jbr:null,root.resolve("repository"),3,Duration.ofHours(4),512,false,root.resolve(enhanced?"enhanced-state":"stock-state"),root.resolve("unused.sock"));
            try(var app=new Application(config)){
                String session=TestSupport.open(app,workspace);var run=call(app,"run.start",Map.of("session",session,"target","Probe","debug",true));String id=run.path("run_session").asText();long pid=run.path("pid").asLong();
                assertThat(run.path("hotswap").asText()).isEqualTo(enhanced?"enhanced":"bodies_only");assertThat(run.path("jdi_redefinition").has("add_method")).isTrue();
                op(app,session,id,"break",Map.of("class","Probe","path",file.toString(),"line",3));awaitStop(app,session,id);
                String methods=source.replace("static int count;","static int count; int added(){return 42;}");Files.writeString(file,methods);
                var added=op(app,session,id,"hotswap",Map.of("path",file.toString()));assertThat(added.path("restart_required").asBoolean()).isEqualTo(!enhanced);
                if(!enhanced){assertThat(added.path("reason").asText()).isNotBlank();evidence.add(Map.of("runtime","stock","hotswap",added));continue;}
                fresh(app,session,id);assertThat(op(app,session,id,"eval",Map.of("expression","Probe.retained.added()")).path("value").path("value").asText()).isEqualTo("42");
                String fields=methods.replace("static int count;","static int count; int extra;");Files.writeString(file,fields);
                var field=op(app,session,id,"hotswap",Map.of("path",file.toString()));assertThat(field.path("restart_required").asBoolean()).isFalse();fresh(app,session,id);assertThat(op(app,session,id,"eval",Map.of("expression","Probe.retained.extra")).path("value").path("value").asText()).isEqualTo("0");
                String signature=fields.replace("int existing(int value){return value;}","long existing(long value){return value+3;}");Files.writeString(file,signature);
                var changed=op(app,session,id,"hotswap",Map.of("path",file.toString()));assertThat(changed.path("restart_required").asBoolean()).isFalse();fresh(app,session,id);assertThat(op(app,session,id,"eval",Map.of("expression","Probe.retained.existing(5L)")).path("value").path("value").asText()).isEqualTo("8");
                assertThat(ProcessHandle.of(pid).orElseThrow().isAlive()).isTrue();evidence.add(Map.of("runtime","jbr","selection",selection,"capabilities",run,"method",added,"field",field,"signature",changed));
            }
        }
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/enhanced-hotswap.json").toFile(),evidence);System.out.println("phase-11-hotswap "+Json.MAPPER.writeValueAsString(evidence));
    }
    private static JsonNode call(Application app,String method,Map<String,?> params){
        var response=TestSupport.request(app.dispatcher(),method,params);assertThat(response.has("error")).as(response.toPrettyString()).isFalse();assertThat(response.path("result").path("warnings").toString()).doesNotContain("analyzer_fault");return response.path("result").path("result");
    }
    private static JsonNode op(Application app,String session,String run,String op,Map<String,?> args){return call(app,"debug.op",Map.of("session",session,"run_session",run,"op",op,"args",args));}
    private static void fresh(Application app,String session,String id)throws Exception{op(app,session,id,"continue",Map.of());awaitStop(app,session,id);}
    private static void awaitStop(Application app,String session,String id)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(System.nanoTime()<deadline){for(var run:call(app,"session.status",Map.of("session",session)).path("runs"))if(run.path("run_session").asText().equals(id)&&!run.path("stopped_threads").isEmpty())return;Thread.sleep(10);}throw new AssertionError("Debugger did not stop");
    }
}

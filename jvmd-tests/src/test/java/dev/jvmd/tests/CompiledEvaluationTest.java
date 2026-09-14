package dev.jvmd.tests;

import com.fasterxml.jackson.databind.JsonNode;
import dev.jvmd.core.*;
import dev.jvmd.dist.Application;
import dev.jvmd.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 11: compiled lambdas, generic frame locals, private access, mutation and loader isolation. */
@Tag("phase-11")
class CompiledEvaluationTest {
    @TempDir Path root;
    @Test void evaluatesCompiledExpressionsAndCopiesUpdatedLocalsThroughTheProtocol()throws Exception{
        Path workspace=Files.createDirectories(root.resolve("workspace"));String source="""
            import java.util.*;
            import java.util.stream.*;
            public class Probe<T extends Number> {
              private int secret=7;
              private int hidden(int n){return secret+n;}
              private <U extends CharSequence> int tick(U suffix)throws Exception {
                List<String> names=new ArrayList<>(List.of("a","b")); int seed=5; char letter='x'; Object absent=null;
                System.out.println("STOP "+seed);
                return seed;
              }
              public static void main(String[] args)throws Exception {
                var probe=new Probe<Integer>();
                while(true){probe.tick("!");Thread.sleep(20);}
              }
            }
            """;
        Path file=workspace.resolve("Probe.java");Files.writeString(file,source);
        var config=new Config(Path.of(System.getProperty("java.home")),null,root.resolve("repository"),3,Duration.ofHours(4),512,false,root.resolve("state"),root.resolve("unused.sock"));
        try(var app=new Application(config)){
            String session=TestSupport.open(app,workspace),id=call(app,"run.start",Map.of("session",session,"target","Probe","debug",true)).path("run_session").asText();
            op(app,session,id,"break",Map.of("class","Probe","path",file.toString(),"line",RuntimeFixtures.line(source,"System.out.println(\"STOP")));awaitStop(app,session,id);
            String old=op(app,session,id,"frames",Map.of()).path("frames").get(0).path("frame").asText();
            var stream=eval(app,session,id,"names.stream().map(s -> s + suffix).collect(Collectors.joining(\",\"))");
            assertThat(value(stream)).as(stream.toPrettyString()).isEqualTo("a!,b!");assertThat(stream.path("eval_tier").asInt()).isEqualTo(2);assertThat(stream.path("compiled_cache_hit").asBoolean()).isFalse();
            var stale=TestSupport.request(app.dispatcher(),"debug.op",Map.of("session",session,"run_session",id,"op","locals","args",Map.of("frame",old)));assertThat(stale.path("error").toString()).contains("stale");
            assertThat(value(eval(app,session,id,"this.secret + hidden(seed)"))).isEqualTo("19");
            assertThat(value(eval(app,session,id,"new ArrayList<>(names).size()"))).isEqualTo("2");
            assertThat(value(eval(app,session,id,"letter == 'x' && absent == null"))).isEqualTo("true");
            var changed=eval(app,session,id,"++seed");assertThat(value(changed)).isEqualTo("6");assertThat(changed.path("updated_locals").toString()).contains("seed");
            var cached=eval(app,session,id,"++seed");assertThat(value(cached)).isEqualTo("7");assertThat(cached.path("compiled_cache_hit").asBoolean()).isTrue();assertThat(cached.path("compile_ms").asDouble()).isZero();
            assertThat(value(op(app,session,id,"eval",Map.of("expression","seed")))).isEqualTo("7");
            var thrown=eval(app,session,id,"Integer.parseInt(\"invalid\")");assertThat(thrown.path("threw").asBoolean()).isTrue();assertThat(thrown.path("exception").path("type").asText()).isEqualTo("java.lang.NumberFormatException");
            assertThat(value(eval(app,session,id,"secret + seed"))).isEqualTo("14");
            assertThat(eval(app,session,id,"names.clear()").path("value").path("kind").asText()).isEqualTo("null");
            assertThat(value(eval(app,session,id,"names.size()"))).isEqualTo("0");
            System.out.println("phase-11-compiled-eval "+Json.MAPPER.writeValueAsString(Map.of("stream",stream,"cached",cached,"exception",thrown)));
        }
    }
    @Test void definesPrivateEvaluatorsInAnIsolatedApplicationClassLoader()throws Exception{
        Path classes=Files.createDirectories(root.resolve("isolated")),source=root.resolve("Isolated.java");
        String isolated="""
            public class Isolated {
              private static int secret=31;
              public static void run()throws Exception {
                java.util.List<String> values=java.util.List.of("abc","de"); int bias=2;
                System.out.println("ISOLATED STOP "+bias);
              }
            }
            """;
        Files.writeString(source,isolated);
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-g","-d",classes.toString(),source.toString())).isZero();
        RuntimeFixtures.compile(root,"""
            public class Probe {
              public static void main(String[] args)throws Exception {
                System.out.println("READY"); System.in.read();
                try(var loader=new java.net.URLClassLoader(new java.net.URL[]{java.nio.file.Path.of(args[0]).toUri().toURL()},null)){
                  loader.loadClass("Isolated").getMethod("run").invoke(null);
                }
              }
            }
            """);
        var launch=new DebugSession.Launch(Path.of(System.getProperty("java.home")),root,List.of(root.resolve("classes")),"Probe",List.of(classes.toString()),true);
        var lookup=new SourceLookup(List.of(root));var request=new RunManager.Request(launch,lookup,launch.javaHome(),List.of(root),List.of(),root.resolve("classes"),List.of(new RunManager.Target(root,List.of(root),List.of(classes),List.of(),classes)));
        try(var debug=new DebugSession("isolated",launch,lookup)){
            debug.breakpoint("Isolated",source,RuntimeFixtures.line(isolated,"System.out.println(\"ISOLATED STOP"));debug.input("x");debug.awaitStop(Duration.ofSeconds(10));
            var compiled=new CompiledEvaluation(debug,request);
            var result=Json.MAPPER.valueToTree(compiled.evaluate("values.stream().mapToInt(String::length).sum() + secret + bias",null).result());
            assertThat(value(result)).as(result.toPrettyString()).isEqualTo("38");
            assertThat(value(Json.MAPPER.valueToTree(compiled.evaluate("++bias",null).result()))).isEqualTo("3");
            assertThat(value(Json.MAPPER.valueToTree(debug.eval("bias",null).result()))).isEqualTo("3");
        }
    }
    private static String value(JsonNode result){return result.path("value").path("value").asText();}
    private static JsonNode eval(Application app,String session,String run,String expression){return op(app,session,run,"eval",Map.of("expression",expression,"tier",2));}
    private static JsonNode call(Application app,String method,Map<String,?> params){var response=TestSupport.request(app.dispatcher(),method,params);assertThat(response.has("error")).as(response.toPrettyString()).isFalse();return response.path("result").path("result");}
    private static JsonNode op(Application app,String session,String run,String op,Map<String,?> args){return call(app,"debug.op",Map.of("session",session,"run_session",run,"op",op,"args",args));}
    private static void awaitStop(Application app,String session,String id)throws Exception{long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(System.nanoTime()<deadline){for(var run:call(app,"session.status",Map.of("session",session)).path("runs"))if(run.path("run_session").asText().equals(id)&&!run.path("stopped_threads").isEmpty())return;Thread.sleep(10);}throw new AssertionError("Debugger did not stop");}
}

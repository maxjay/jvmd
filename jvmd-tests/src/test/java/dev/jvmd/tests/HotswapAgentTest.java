package dev.jvmd.tests;

import dev.jvmd.core.Json;
import dev.jvmd.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 11: evaluate real HotswapAgent cache reloads against the pinned current JBR. */
@Tag("phase-11")
class HotswapAgentTest {
    @TempDir Path root;
    @Test void refreshesCachedJavaBeansMetadataAfterAddingAProperty()throws Exception{
        String configured=System.getenv("JVMD_JBR_HOME"),agent=System.getenv("JVMD_HOTSWAP_AGENT");
        if(System.getenv("CI")!=null){assertThat(configured).isNotBlank();assertThat(agent).isNotBlank();}
        Assumptions.assumeTrue(configured!=null&&agent!=null,"Pinned JBR and HotswapAgent fixtures are required");
        var evidence=new ArrayList<Map<String,Object>>();
        for(boolean enabled:List.of(false,true)){
            Path directory=Files.createDirectories(root.resolve(enabled?"agent":"plain"));String source="""
                public class Probe {
                  public static class Bean { public int getOriginal(){return 1;} }
                  static final Bean retained=new Bean();
                  static int count;
                  static int tick(){return ++count;}
                  static boolean added()throws Exception {
                    for(var property:java.beans.Introspector.getBeanInfo(Bean.class).getPropertyDescriptors())
                      if(property.getName().equals("added"))return true;
                    return false;
                  }
                  public static void main(String[] args)throws Exception {
                    System.out.println("READY "+added());
                    while(true){tick();Thread.sleep(20);}
                  }
                }
                """;
            Path file=RuntimeFixtures.compile(directory,source);
            var selected=JavaRuntime.select(Path.of(System.getProperty("java.home")),Path.of(configured),enabled?Path.of(agent):null);
            var launch=new DebugSession.Launch(selected.home(),directory,List.of(directory.resolve("classes")),"Probe",List.of(),true,selected.options());
            try(var debug=new DebugSession("agent",launch,new SourceLookup(List.of(directory)))){
                RuntimeFixtures.output(debug,"READY false");debug.breakpoint("Probe",file,RuntimeFixtures.line(source,"static int tick()"));debug.awaitStop(Duration.ofSeconds(10));
                assertThat(Json.MAPPER.valueToTree(debug.eval("Probe.added()",null).result()).path("value").path("value").asText()).isEqualTo("false");
                Files.writeString(file,source.replace("public int getOriginal(){return 1;}","public int getOriginal(){return 1;} public int getAdded(){return 42;}"));
                var swapped=new HotSwap(debug).apply(Path.of(System.getProperty("java.home")),List.of(file),List.of(directory),List.of(),directory.resolve("classes"));
                assertThat(Json.MAPPER.valueToTree(swapped.result()).path("restart_required").asBoolean()).isFalse();
                debug.resume(null);debug.awaitStop(Duration.ofSeconds(10));
                assertThat(Json.MAPPER.valueToTree(debug.eval("Probe.retained.getAdded()",null).result()).path("value").path("value").asText()).isEqualTo("42");
                var metadata=Json.MAPPER.valueToTree(debug.eval("Probe.added()",null).result());
                assertThat(metadata.path("value").path("value").asText()).as(debug.output()).isEqualTo(Boolean.toString(enabled));
                assertThat(debug.status().get("framework_reload")).isEqualTo(enabled?"hotswap_agent":"disabled");
                evidence.add(Map.of("agent_enabled",enabled,"metadata_refreshed",metadata,"hotswap",swapped.result(),"status",debug.status(),"output",debug.output()));
            }
        }
        Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(TestSupport.repo().resolve("jvmd-tests/target/hotswap-agent.json").toFile(),evidence);
        System.out.println("phase-11-agent "+Json.MAPPER.writeValueAsString(evidence));
    }
}

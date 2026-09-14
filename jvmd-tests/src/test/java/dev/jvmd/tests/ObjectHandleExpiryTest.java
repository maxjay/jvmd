package dev.jvmd.tests;

import dev.jvmd.core.Json;
import dev.jvmd.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 10 exit: expiry enables collection of a real debuggee object. */
@Tag("phase-10")
class ObjectHandleExpiryTest {
    @TempDir Path root;
    @Test void collectionIsDisabledOnlyForTheHandlesTtl()throws Exception{
        String source="""
            public class Probe {
              static java.lang.ref.WeakReference<Object> weak;
              static class Payload { byte[] data=new byte[100000]; }
              public static void main(String[] args)throws Exception {
                System.out.println("READY");System.in.read();
                Object doomed=new Payload();weak=new java.lang.ref.WeakReference<>(doomed);
                doomed=null;
                for(int i=0;i<5;i++){System.gc();Thread.sleep(10);}
                System.out.println(weak.get()==null?"EARLY":"PINNED");
                while(weak.get()!=null){System.gc();Thread.sleep(10);}
                System.out.println("COLLECTED");System.in.read();
              }
            }
            """;
        Path file=RuntimeFixtures.compile(root,source);var clock=new AtomicLong();var handles=new ObjectHandles("probe",Duration.ofSeconds(60),clock::get);
        try(var debug=RuntimeFixtures.start(root,handles)){
            debug.breakpoint("Probe",file,RuntimeFixtures.line(source,"doomed=null"));debug.input("x");debug.awaitStop(Duration.ofSeconds(10));
            String handle="";for(var local:Json.MAPPER.valueToTree(debug.locals(null,0,20).result()).path("locals"))if(local.path("name").asText().equals("doomed"))handle=local.path("value").path("handle").asText();assertThat(handle).isNotBlank();
            debug.resume(null);RuntimeFixtures.output(debug,"PINNED");assertThat(handles.size()).isPositive();
            clock.set(Duration.ofSeconds(61).toNanos());handles.expire();assertThat(handles.size()).isZero();RuntimeFixtures.output(debug,"COLLECTED");
            String expired=handle;assertThatThrownBy(()->handles.get(expired)).hasMessageContaining("expired");
        }
    }
}

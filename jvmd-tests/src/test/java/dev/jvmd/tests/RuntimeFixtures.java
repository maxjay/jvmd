package dev.jvmd.tests;

import dev.jvmd.runtime.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import javax.tools.ToolProvider;
import static org.assertj.core.api.Assertions.*;

/** Implements 12.4: real debuggee fixtures compiled with local-variable and line tables. */
final class RuntimeFixtures {
    static Path compile(Path root,String text)throws Exception{
        Path source=root.resolve("Probe.java"),classes=Files.createDirectories(root.resolve("classes"));Files.writeString(source,text);
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-g","-parameters","-d",classes.toString(),source.toString())).isZero();return source;
    }
    static DebugSession start(Path root,ObjectHandles handles)throws Exception{
        return new DebugSession("probe",new DebugSession.Launch(Path.of(System.getProperty("java.home")),root,List.of(root.resolve("classes")),"Probe",List.of(),true),new SourceLookup(List.of(root),Map.of(root,"fixture:probe:1")),handles);
    }
    static void output(DebugSession debug,String value)throws Exception{
        long deadline=System.nanoTime()+Duration.ofSeconds(10).toNanos();while(!debug.output().contains(value)&&System.nanoTime()<deadline)Thread.sleep(10);
        assertThat(debug.output()).contains(value);
    }
    static int line(String source,String marker){return (int)source.substring(0,source.indexOf(marker)).chars().filter(c->c=='\n').count()+1;}
}

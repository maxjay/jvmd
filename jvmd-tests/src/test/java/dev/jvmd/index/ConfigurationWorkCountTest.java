package dev.jvmd.index;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * A permanent work-count bound: the reactor root, the logical source mapper and the per-class
 * directories are derived once per configured context, independent of how many units are captured or
 * restored and how many dependency entries they hold, never again for every memo, entry or
 * {@code config:} lookup.
 */
class ConfigurationWorkCountTest {
    @TempDir Path root;
    private static final String GAV="g:app:1";
    private static final int UNITS=60;

    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private Analyzer.Context context(String generation)throws Exception{
        Path sources=sources();
        return new Analyzer.Context(GAV,"25",List.of(),List.of(sources),generation,
                Map.of(root.resolve("app").toString(),GAV,sources.toString(),GAV),List.of("--release","25"),
                Set.of(),List.of(),List.of(sources),true,"",Processing.NONE);
    }
    private Analyzer analyzer()throws Exception{
        var analyzer=new Analyzer(new FileStateRegistry());
        analyzer.configure(context("reactor:"+GAV+":main"),null,256L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));analyzer.memos(new SemanticMemoStore(root.resolve("memo")));
        return analyzer;
    }
    private static long derivations(Analyzer analyzer){
        return ((Number)((Map<?,?>)analyzer.status().get("attributed_memo")).get("config_derivations")).longValue();
    }
    private List<Path> project()throws Exception{
        var files=new ArrayList<Path>();
        // A chain: every unit depends on the previous one, so each record holds dependency entries.
        for(int i=0;i<UNITS;i++){
            String body=i==0?"public class U0 { public int v(){ return 0; } }"
                    :"public class U"+i+" { public int v(){ return new U"+(i-1)+"().v() + 1; } }";
            Path file=sources().resolve("p/U"+i+".java");Files.createDirectories(file.getParent());Files.writeString(file,"package p;\n"+body+"\n");files.add(file);
        }
        return files;
    }

    @Test void configurationIsDerivedOncePerContextNotPerMemoOrEntry()throws Exception{
        var files=project();
        try(var analyzer=analyzer()){
            for(Path file:files)analyzer.diagnostics(file,Files.readString(file));
            analyzer.awaitMemoWrites();
            assertThat(derivations(analyzer)).as("cold: "+UNITS+" captures, one context").isEqualTo(1);
        }
        try(var analyzer=analyzer()){
            for(Path file:files)analyzer.diagnostics(file,Files.readString(file));
            var memo=(Map<?,?>)analyzer.status().get("attributed_memo");
            assertThat(((Number)memo.get("restores")).longValue()).as("warm restart restores every unit").isEqualTo(UNITS);
            assertThat(derivations(analyzer)).as("restart: "+UNITS+" restores and their dependency entries, one context").isEqualTo(1);
            // A configuration change is the one invalidator.
            analyzer.configure(context("reactor:"+GAV+":main:2"),null,256L*1024*1024);
            analyzer.diagnostics(files.get(0),Files.readString(files.get(0)));
            assertThat(derivations(analyzer)).as("one derivation for the new context").isEqualTo(2);
        }
    }
}

package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Probe whether javac's Trees.getScope targeted attribution is sufficient for completion without FLOW. */
@Tag("phase-4")
class CompletionTargetedAttributionTest {
    @TempDir Path root;

    @Test void enteredMemberCompletionMatchesFullyAnalysedCompletion()throws Exception{
        Files.writeString(root.resolve("Api.java"),"""
                class Api {
                    /** pets */
                    int getPets(){ return 1; }
                    String getName(){ return ""; }
                    int other(){ return 2; }
                }
                """);
        Path use=root.resolve("Use.java");
        String source="class Use { Object call(Api api){ return api."+EditorQueries.MARKER+"(); } }";
        assertThat(names(completion(use,source,1,"get"))).isEqualTo(names(completion(use,source,2,"get")));
    }

    @Test void enteredLocalCompletionMatchesFullyAnalysedCompletion()throws Exception{
        Path use=root.resolve("Use.java");
        String source="class Use { int call(){ int getLocal=1; return "+EditorQueries.MARKER+"; } }";
        assertThat(names(completion(use,source,1,"get"))).isEqualTo(names(completion(use,source,2,"get")));
    }

    private List<Map<String,Object>> completion(Path file,String source,int tier)throws Exception{return completion(file,source,tier,"get");}
    private List<Map<String,Object>> completion(Path file,String source,int tier,String prefix)throws Exception{
        try(var pool=new CompilerPool()){
            pool.configure("targeted-"+tier,"25",List.of(),List.of(root),null,256L*1024*1024);
            var result=pool.query(file,source,tier,(task,units,actual)->EditorQueries.completion(task,units,
                    new SymbolIdentity(task,"fixture:test:1","25",ignored->null,List.of(root)),prefix));
            assertThat(result.warnings()).isEmpty();assertThat(result.result()).isNotNull();
            return result.result();
        }
    }
    private static List<String> names(List<Map<String,Object>> rows){return rows.stream().map(row->row.get("name").toString()).toList();}
}

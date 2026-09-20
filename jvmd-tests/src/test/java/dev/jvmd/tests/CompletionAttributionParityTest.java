package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class CompletionAttributionParityTest {
    @TempDir Path root;

    @Test void attributeOnlyCompletionMatchesFullFlowAcrossFlowSensitiveConstructs()throws Exception{
        Files.writeString(root.resolve("Api.java"),"""
                class Api {
                    /** Returns the visible pets. */
                    int getPets(){return 1;}
                    /** Returns the display name. */
                    String getName(){return "";}
                }
                """);
        assertParity("basic","class Use { Object call(Api api){ return api."+EditorQueries.MARKER+"(); } }","get");
        assertParity("pattern","class Use { int call(Object value){ if(value instanceof String s && s."+EditorQueries.MARKER+"()) return 1; return 0; } }","sta");
        assertParity("definite-assignment","class Use { Object call(boolean flag){ String s; if(flag) s=\"\"; return s."+EditorQueries.MARKER+"(); } }","sub");
        assertParity("switch-pattern","class Use { Object call(Object value){ return switch(value){ case String s -> s."+EditorQueries.MARKER+"(); default -> \"\"; }; } }","sub");
    }

    private void assertParity(String name,String source,String prefix)throws Exception{
        Path file=root.resolve("Use-"+name+".java");Files.writeString(file,source);
        CompilerPool.Query<List<Map<String,Object>>> completion=(task,units,tier)->
                EditorQueries.completion(task,units,new SymbolIdentity(task,"fixture:completion:1","25",ignored->null,List.of(root)),prefix);
        CompilerPool.Outcome<List<Map<String,Object>>> attributed,flow;
        try(var pool=new CompilerPool()){
            pool.configure("attr-"+name,"25",List.of(),List.of(root),null,256L*1024*1024);
            attributed=pool.attributedQuery(file,source,completion);
            assertThat(pool.status()).containsEntry("attribute_only_queries",1L);
        }
        try(var pool=new CompilerPool()){
            pool.configure("flow-"+name,"25",List.of(),List.of(root),null,256L*1024*1024);
            flow=pool.query(file,source,2,completion);
            assertThat(pool.status()).containsEntry("attribute_only_queries",0L);
        }
        assertThat(attributed.warnings()).as(name+" ATTR warnings").isEmpty();
        assertThat(flow.warnings()).as(name+" FLOW warnings").isEmpty();
        assertThat(attributed.tier()).as(name+" ATTR tier").isEqualTo(2);
        assertThat(flow.tier()).as(name+" FLOW tier").isEqualTo(2);
        assertThat(attributed.result()).as(name+" completion rows").isEqualTo(flow.result());
    }
}

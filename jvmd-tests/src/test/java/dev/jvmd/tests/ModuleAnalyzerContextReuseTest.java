package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Regression for multi-module traversal: visiting B must not destroy A's javac pool. */
@Tag("phase-4")
class ModuleAnalyzerContextReuseTest {
    @TempDir Path root;

    @Test void aToBToARetainsACompilerPool()throws Exception{
        Path a=root.resolve("A.java"),b=root.resolve("B.java");
        String aText="class A { int value() { return 1; } }",bText="class B { int value() { return 2; } }";
        Files.writeString(a,aText);Files.writeString(b,bText);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context("module:a:1","ctx-a"),null,256L*1024*1024);
            assertThat(analyzer.bindings(a,aText,null).tier()).isEqualTo(2);

            analyzer.configure(context("module:b:1","ctx-b"),null,256L*1024*1024);
            assertThat(analyzer.bindings(b,bText,null).tier()).isEqualTo(2);

            analyzer.configure(context("module:a:1","ctx-a"),null,256L*1024*1024);
            assertThat(analyzer.bindings(a,aText,null).tier()).isEqualTo(2);

            @SuppressWarnings("unchecked") var modules=(Map<String,Object>)analyzer.status().get("module_compilers");
            @SuppressWarnings("unchecked") var aStatus=(Map<String,Object>)modules.get("ctx-a");
            @SuppressWarnings("unchecked") var bStatus=(Map<String,Object>)modules.get("ctx-b");
            assertThat(aStatus).containsEntry("queries",2L).containsEntry("recycles",0L);
            assertThat(aStatus.get("pool_statistics").toString()).contains("1 reused Contexts");
            assertThat(bStatus).containsEntry("queries",1L).containsEntry("recycles",0L);
        }
    }

    private Analyzer.Context context(String gav,String generation){
        return new Analyzer.Context(gav,"25",List.of(),List.of(root),generation,Map.of(root.toUri().toString(),gav));
    }
}

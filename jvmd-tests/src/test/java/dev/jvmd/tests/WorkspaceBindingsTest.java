package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 4: identity-based navigation, references, overloads and hierarchy. */
@Tag("phase-4")
class WorkspaceBindingsTest {
    @TempDir Path root;
    @Test void anOuterFocusDoesNotCoverErasedNestedMethodBodies()throws Exception{
        String text="class Nested { int outer(){ int outside=1; class Inner { int nested(){ int inside=2; return inside; } } return outside; } }";
        Path file=root.resolve("Nested.java");Files.writeString(file,text);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            int outer=text.lastIndexOf("outside"),inner=text.indexOf("return inside")+7;
            assertThat(analyzer.bindings(file,text,outer).result().at(outer).get("name")).isEqualTo("outside");
            var result=analyzer.bindings(file,text,inner);assertThat(result.warnings()).isEmpty();assertThat(result.result().at(inner).get("name")).isEqualTo("inside");
            assertThat(((Number)analyzer.status().get("queries")).longValue()).isEqualTo(2);
        }
    }
    @Test void bindsOverloadsShadowedVariablesAndOverrides()throws Exception{
        String text="""
                interface Parent { int apply(int value); }
                class Example implements Parent {
                    int value;
                    public int apply(int value) { this.value = value; return echo(value); }
                    int echo(int input) { return input; }
                    String echo(String input) { return input; }
                    int broken() { return missing(); }
                }
                """;
        Path file=root.resolve("Example.java");Files.writeString(file,text);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            int position=text.indexOf("echo(value)");
            var snapshot=analyzer.bindings(file,text,position);
            assertThat(snapshot.tier()).isEqualTo(2);assertThat(snapshot.warnings()).isEmpty();assertThat(snapshot.diagnostics()).isEmpty();
            var target=snapshot.result().at(position);
            assertThat(target.get("scip").toString()).endsWith("Example#echo(int).");
            assertThat(target.get("signature").toString()).contains("int echo(int input)");
            var parameter=snapshot.result().at(text.indexOf("= value")+2);
            var field=snapshot.result().at(text.indexOf("this.value")+5);
            assertThat(field.get("scip")).isNotEqualTo(parameter.get("scip"));
            assertThat(snapshot.result().edges()).anyMatch(e->e.kind().equals("overrides")&&e.dst().endsWith("Parent#apply(int)."));
            assertThat(snapshot.result().edges()).anyMatch(e->e.kind().equals("calls")&&e.dst().endsWith("Example#echo(int)."));
            assertThat(snapshot.result().edges()).anyMatch(e->e.kind().equals("writes")&&e.dst().endsWith("Example#value."));
            long queries=((Number)analyzer.status().get("queries")).longValue();
            analyzer.bindings(file,text,text.indexOf("this.value")+5);
            assertThat(((Number)analyzer.status().get("queries")).longValue()).isEqualTo(queries);
            assertThat(analyzer.bindings(file,text,null).diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));
        }
    }
}

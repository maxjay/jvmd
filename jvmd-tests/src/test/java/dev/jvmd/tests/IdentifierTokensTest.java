package dev.jvmd.tests;

import dev.jvmd.analyzer.SourceText;
import com.sun.source.util.*;
import java.net.URI;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

/** Implements 5: contextual keywords are syntax; identical words used as identifiers remain probe targets. */
@Tag("phase-4")
class IdentifierTokensTest {
    private List<SourceText.Token> tokens(String file,String source)throws Exception{
        var compiler=ToolProvider.getSystemJavaCompiler();var problems=new DiagnosticCollector<JavaFileObject>();
        try(var files=compiler.getStandardFileManager(problems,null,null)){
            var input=new SimpleJavaFileObject(URI.create("string:///"+file),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return source;}};
            var task=(JavacTask)compiler.getTask(null,files,problems,List.of("-proc:none","--release","25"),null,List.of(input));
            var unit=task.parse().iterator().next();assertThat(problems.getDiagnostics()).noneMatch(d->d.getKind()==Diagnostic.Kind.ERROR);
            return new SourceText(source).identifiers(unit,Trees.instance(task).getSourcePositions());
        }
    }
    @Test void syntaxClassificationDoesNotDependOnResolution()throws Exception{
        String source="""
                record Example(int record) {
                    static int var, yield, module, requires, exports, opens, to, transitive, with, uses, provides, permits, sealed, open, non, when;
                    int yield(){return record;}
                    int read(){var value=missing; return switch(value){default -> {yield this.yield();}};}
                    void labels(){yield: for(;;){break yield;}}
                }
                sealed interface Parent permits Child {}
                non-sealed class Child implements Parent {}
                """;
        var tokens=tokens("Example.java",source);
        assertThat(tokens).extracting(SourceText.Token::text).contains("missing","record","var","yield","module","requires","exports","opens","to","transitive","with","uses","provides","permits","sealed","open","non","when");
        assertThat(tokens).extracting(SourceText.Token::start).doesNotContain(0,source.indexOf("var value"),source.indexOf("yield this"),source.indexOf("sealed interface"),source.indexOf("permits Child"),source.indexOf("non-sealed class"),source.indexOf("non-sealed class")+4);
        assertThat(tokens.stream().filter(t->t.text().equals("yield")).count()).isEqualTo(5);
    }
    @Test void moduleNamesKeepContextualComponents()throws Exception{
        String source="open module module.requires { requires transitive java.sql; exports sample.to to other.module; opens sample.open; uses sample.Service; provides sample.Service with sample.Impl; }";
        var tokens=tokens("module-info.java",source);
        assertThat(tokens).extracting(SourceText.Token::text).containsExactly("module","requires","java","sql","sample","to","other","module","sample","open","sample","Service","sample","Service","sample","Impl");
    }
}

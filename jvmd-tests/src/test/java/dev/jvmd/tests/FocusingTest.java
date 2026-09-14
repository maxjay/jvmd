package dev.jvmd.tests;
import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 4 checkpoint: line/column-preserving focusing with valid constructor chains. */
@Tag("phase-4")
class FocusingTest {
 @Test void preservesEveryOffsetAndConstructorChaining()throws Exception{
  String original="""
   class Parent { Parent(int count) {} }
   class Focus extends Parent {
     Focus(){ super(1); System.out.println("constructing"); }
     int other(){ return "a deliberately wrong type"; }
     void tiny(){}
     int selected(){ return 42; }
   }
   """;
  int cursor=original.indexOf("42");var focus=new Focusing().focus(Path.of("Focus.java"),original,cursor);
  assertThat(focus.source()).hasSameSizeAs(original).contains("super(1);").contains("int selected(){ return 42; }").doesNotContain("deliberately wrong type");
  for(int i=0;i<original.length();i++)if(original.charAt(i)=='\n')assertThat(focus.source().charAt(i)).isEqualTo('\n');
  assertThat(focus.source().indexOf("42")).isEqualTo(cursor);
  var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
  try(var manager=compiler.getStandardFileManager(diagnostics,null,null)){
   var task=(com.sun.source.util.JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","--should-stop=ifError=FLOW"),null,List.of(Parser.source(Path.of("Focus.java").toUri(),focus.source())));task.analyze();
   assertThat(diagnostics.getDiagnostics()).noneMatch(d->d.getKind()==Diagnostic.Kind.ERROR);
  }
 }
}

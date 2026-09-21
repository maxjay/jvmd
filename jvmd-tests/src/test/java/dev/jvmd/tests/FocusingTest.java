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
  int cursor=original.indexOf("42");Focusing.Result focus;
  try(var focusing=new Focusing()){focus=focusing.focus(Path.of("Focus.java"),original,cursor);}
  assertThat(focus.source()).hasSameSizeAs(original).contains("super(1);").contains("int selected(){ return 42; }").doesNotContain("deliberately wrong type");
  for(int i=0;i<original.length();i++)if(original.charAt(i)=='\n')assertThat(focus.source().charAt(i)).isEqualTo('\n');
  assertThat(focus.source().indexOf("42")).isEqualTo(cursor);
  var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
  try(var manager=compiler.getStandardFileManager(diagnostics,null,null)){
   var task=(com.sun.source.util.JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","--should-stop=ifError=FLOW"),null,List.of(Parser.source(Path.of("Focus.java").toUri(),focus.source())));task.analyze();
   assertThat(diagnostics.getDiagnostics()).noneMatch(d->d.getKind()==Diagnostic.Kind.ERROR);
  }
 }
 @Test void parserReuseAgreesWithFreshParsingAcrossChangedAndMalformedSources()throws Exception{
  var sources=List.of(
   "class A { int first(){return 10;} int chosen(){return 42;} }",
   "class A { A(){this(1); System.out.println(2);} A(int n){} int chosen(){return 42;} }",
   "record A(int n) { A { System.out.println(n); } int chosen(){return 42;} }",
   "class A { int broken(){return ; int chosen(){return 42;} }",
   "class A { int nested(){ class B { int value(){return 7;} } return 3;} int chosen(){return 42;} }",
   "class A { String text(){return \"{ }\";} int chosen(){return 42;} }");
  try(var retained=new Focusing()){
   for(int i=0;i<30;i++){
    String text=sources.get(i%sources.size())+" // version "+i;Path file=Path.of("A.java");
    try(var fresh=new Focusing()){
     assertThat(retained.focus(file,text,text.indexOf("42"))).isEqualTo(fresh.focus(file,text,text.indexOf("42")));
    }
   }
   assertThat(retained.status()).containsEntry("focus_layout_parses",30L).containsEntry("focus_layout_cache_entries",16);
   retained.close();
   String text=sources.getFirst();
   try(var fresh=new Focusing()){assertThat(retained.focus(Path.of("A.java"),text,text.indexOf("42"))).isEqualTo(fresh.focus(Path.of("A.java"),text,text.indexOf("42")));}
  }
 }
}

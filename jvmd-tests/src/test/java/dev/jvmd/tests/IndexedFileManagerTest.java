package dev.jvmd.tests;
import dev.jvmd.analyzer.*;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 4 checkpoint: indexed class bytes with bounded LRU and stale-byte detection. */
@Tag("phase-4")
class IndexedFileManagerTest {
 @TempDir Path temp;
 @Test void replacingAJarInvalidatesItsLoadedCatalogEvenWithPreservedMtime()throws Exception{
  Path jar=IndexFixtures.jar(temp,"replace","package fixture; public class Sample { public int value(){return 1;} }",true);
  var compiler=ToolProvider.getSystemJavaCompiler();
  try(var manager=new IndexedFileManager(compiler.getStandardFileManager(null,null,null),List.of(jar),List.of(),null,1024*1024)){
   var first=manager.getJavaFileForInput(StandardLocation.CLASS_PATH,"fixture.Sample",JavaFileObject.Kind.CLASS);
   assertThat(first).isNotNull();try(var in=first.openInputStream()){assertThat(in.readAllBytes()).isNotEmpty();}
   var timestamp=Files.getLastModifiedTime(jar);
   IndexFixtures.jar(temp,"replace","package fixture; public class Sample { public String value(){return \"changed\";} }",true);
   Files.setLastModifiedTime(jar,timestamp);
   assertThatThrownBy(manager::validateClasspath).isInstanceOf(java.io.UncheckedIOException.class);
  }
 }
 @Test void completesPrivateSupportTypesAndDoesNotHideDeletedJars()throws Exception{
  Path jar=IndexFixtures.jar(temp,"support","package fixture; class Parent { public int inherited; } public class Sample extends Parent { public int value; }",true);
  var compiler=ToolProvider.getSystemJavaCompiler();
  try(var index=new IndexService(temp.resolve("index.db"),temp)){
   index.indexJar(jar,"fixture:support:1","jar");
   try(var manager=new IndexedFileManager(compiler.getStandardFileManager(null,null,null),List.of(jar),List.of(),index,1024*1024)){
    var diagnostics=new DiagnosticCollector<JavaFileObject>();var task=(com.sun.source.util.JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","--should-stop=ifError=FLOW"),null,List.of(Parser.source(temp.resolve("Use.java").toUri(),"class Use { int get(fixture.Sample s){return s.inherited+s.value;} }")));
    task.analyze();assertThat(diagnostics.getDiagnostics()).noneMatch(d->d.getKind()==Diagnostic.Kind.ERROR);
    var file=manager.getJavaFileForInput(StandardLocation.CLASS_PATH,"fixture.Sample",JavaFileObject.Kind.CLASS);byte[] original;try(var in=file.openInputStream()){original=in.readAllBytes();}try(var in=file.openInputStream()){assertThat(in.readAllBytes()).isEqualTo(original);}
    assertThat(manager.status().get("class_byte_hits")).isPositive();assertThat(manager.status().get("class_bytes")).isLessThanOrEqualTo(1024*1024);
    Files.delete(jar);assertThatThrownBy(file::openInputStream).isInstanceOf(java.io.UncheckedIOException.class);
   }
  }
 }

 @Test void coarseSourceRootsStillOverlayUnsavedAndNewDocuments()throws Exception{
  Path root=Files.createDirectories(temp.resolve("sources")),a=Files.writeString(root.resolve("A.java"),"class A { static int value(){return 1;} }"),created=root.resolve("Created.java");
  var compiler=ToolProvider.getSystemJavaCompiler();
  try(var manager=new IndexedFileManager(compiler.getStandardFileManager(null,null,null),List.of(),List.of(root),null,1024*1024,false)){
   manager.documents(Map.of(a,"class A { static String value(){return \"new\";} }",created,"class Created { static int answer(){return 42;} }"));
   var listed=new LinkedHashMap<String,JavaFileObject>();
   for(var file:manager.list(StandardLocation.SOURCE_PATH,"",Set.of(JavaFileObject.Kind.SOURCE),false))listed.put(manager.inferBinaryName(StandardLocation.SOURCE_PATH,file),file);
   assertThat(listed).containsKeys("A","Created");
   assertThat(listed.get("A").getCharContent(true).toString()).contains("String value");
   assertThat(listed.get("Created").getCharContent(true).toString()).contains("answer");
   assertThat(Files.exists(created)).isFalse();
   assertThat(manager.status()).containsEntry("source_watch_reliable",0L);
  }
 }
}

package dev.jvmd.tests;
import dev.jvmd.analyzer.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 4 checkpoint: FLOW attribution binds healthy members alongside syntax/type errors. */
@Tag("phase-4")
class TolerantAttributionTest {
 @Test void keepsBindingsWhenOtherMembersAreBroken()throws Exception{
  try(var pool=new CompilerPool()){
   pool.configure("fixture","25",List.of(),List.of(),null,256*1024*1024);
   var result=pool.query(Path.of("Broken.java"),"class Broken { Missing missing; int bad(){int n=;return 0;} int good(){return 42;} int use(){return good();} }",2,(task,units,tier)->{
    var names=new ArrayList<String>();var trees=Trees.instance(task);for(var unit:units)new TreePathScanner<Void,Void>(){@Override public Void visitIdentifier(IdentifierTree tree,Void p){if(tree.getName().contentEquals("good")){var element=trees.getElement(getCurrentPath());if(element!=null)names.add(element.getSimpleName().toString());}return super.visitIdentifier(tree,p);}}.scan(unit,null);return List.copyOf(names);
   });assertThat(result.tier()).isEqualTo(2);assertThat(result.result()).contains("good");assertThat(result.diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));assertThat(result.warnings()).isEmpty();
  }
 }
}

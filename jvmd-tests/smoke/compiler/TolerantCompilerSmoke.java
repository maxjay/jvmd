import com.sun.source.tree.*;
import com.sun.source.util.*;
import javax.tools.*;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
/** Implements smoke 9.2: tolerant javac bindings and a catchable disappearing-classpath fault. */
class TolerantCompilerSmoke {
 static final class Source extends SimpleJavaFileObject {
  final String text;Source(String name,String text){super(URI.create("string:///"+name+".java"),Kind.SOURCE);this.text=text;}
  @Override public CharSequence getCharContent(boolean ignored){return text;}
 }
 public static void main(String[] args)throws Exception{
  var compiler=ToolProvider.getSystemJavaCompiler();var diagnostics=new DiagnosticCollector<JavaFileObject>();
  try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,null)){
   var task=(JavacTask)compiler.getTask(null,manager,diagnostics,List.of("-proc:none","--should-stop=ifError=FLOW"),null,List.of(new Source("Broken","class Broken { Missing missing; int broken(){int x = ; return 0;} int good(){return 42;} void use(){int value=good();} }")));
   var units=task.parse();task.analyze();var trees=Trees.instance(task);int[] resolved={0};
   for(var unit:units)new TreePathScanner<Void,Void>(){@Override public Void visitIdentifier(IdentifierTree node,Void p){if(node.getName().contentEquals("good")&&trees.getElement(getCurrentPath())!=null)resolved[0]++;return super.visitIdentifier(node,p);}}.scan(unit,null);
   if(resolved[0]!=1||diagnostics.getDiagnostics().stream().noneMatch(d->d.getCode().contains("cant.resolve")))throw new AssertionError("tolerant bindings missing");
   System.out.println("PASS tolerant attribution: good() bound despite syntax and unresolved-type errors");
  }
  Path directory=Files.createTempDirectory("jvmd-disappearing-class-");
  try {
   Path source=directory.resolve("Gone.java");Files.writeString(source,"package fixture; public class Gone { public int value; }");
   if(compiler.run(null,null,null,"-d",directory.toString(),source.toString())!=0)throw new AssertionError("fixture compile failed");
   Path binary=directory.resolve("fixture/Gone.class");
   try(var standard=compiler.getStandardFileManager(null,Locale.ROOT,null)){
    standard.setLocationFromPaths(StandardLocation.CLASS_PATH,List.of(directory));
    var disappearing=new ForwardingJavaFileManager<StandardJavaFileManager>(standard){
     @Override public Iterable<JavaFileObject> list(Location location,String pkg,Set<JavaFileObject.Kind> kinds,boolean recurse)throws java.io.IOException{
      var entries=super.list(location,pkg,kinds,recurse);
      if(location==StandardLocation.CLASS_PATH&&pkg.equals("fixture")){
       var captured=new ArrayList<JavaFileObject>();entries.forEach(captured::add);Files.deleteIfExists(binary);
       // The indexed-classpath seam must report stale bytes as a compiler fault, never serve stale cache data.
       if(!captured.isEmpty())throw new java.io.UncheckedIOException(new java.io.FileNotFoundException(binary.toString()));
      }
      return entries;
     }
    };
    boolean caught=false;
    try{var task=(JavacTask)compiler.getTask(null,disappearing,d->{},List.of("-proc:none","--should-stop=ifError=FLOW"),null,List.of(new Source("Use","class Use { fixture.Gone gone; }")));task.analyze();}
    catch(AssertionError|RuntimeException fault){caught=true;System.out.println("PASS disappearing class: caught "+fault.getClass().getSimpleName());}
    if(!caught)throw new AssertionError("classpath fault did not cross the catch boundary");
   }
  }finally{try(var paths=Files.walk(directory)){for(var path:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
 }
}

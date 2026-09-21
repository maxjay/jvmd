import dev.jvmd.index.*;
import dev.jvmd.index.rocks.*;
import java.nio.file.*;
import java.util.*;
public class SemanticBenchmark {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]); int n=1000;
  try(var store=new RocksSemanticInvalidation(root.resolve("state"))){
   for(int i=0;i<n;i++)store.observeFile("m","ctx",value(root,i,"1","a"));
   for(int i=0;i<20;i++)store.observeFile("m","ctx",value(root,0,"warm"+i,"a"));
   long[] ns=new long[100]; int affected=0;
   for(int i=0;i<100;i++){long t=System.nanoTime();var r=store.observeFile("m","ctx",value(root,0,"change"+i,"a"));ns[i]=System.nanoTime()-t;affected+=r.reanalyze().size();}
   Arrays.sort(ns);System.out.println("body p50_ms="+ns[50]/1e6+" p95_ms="+ns[95]/1e6+" affected="+affected);
   for(int i=0;i<100;i++){long t=System.nanoTime();var r=store.observeFile("m","ctx",value(root,0,"api"+i,"a"+i));ns[i]=System.nanoTime()-t;}
   Arrays.sort(ns);System.out.println("api p50_ms="+ns[50]/1e6+" p95_ms="+ns[95]/1e6);
  }
 }
 static FileSemanticContribution value(Path root,int i,String source,String api){return new FileSemanticContribution(root.resolve("F"+i+".java"),source,api,Set.of(),Set.of("p.F"+i),Set.of());}
}

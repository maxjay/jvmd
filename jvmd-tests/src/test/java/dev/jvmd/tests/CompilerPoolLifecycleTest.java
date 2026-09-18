package dev.jvmd.tests;
import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 4 checkpoint: a single compiler context, classpath/memory recycling and thread confinement. */
@Tag("phase-4")
class CompilerPoolLifecycleTest {
 @TempDir Path root;
 @Test void looseClasspathChangesInvalidateWithoutScanningEveryLoadedClassOnEachValidation()throws Exception{
  Path sourceDir=Files.createDirectories(root.resolve("dependency-source")),classes=Files.createDirectories(root.resolve("classes")),useRoot=Files.createDirectories(root.resolve("use"));
  Path dependency=sourceDir.resolve("Dependency.java"),use=useRoot.resolve("Use.java");
  Files.writeString(dependency,"public class Dependency { public int value(){return 1;} }");
  assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),dependency.toString())).isZero();
  try(var pool=new CompilerPool()){
   pool.configure("watch","25",List.of(classes),List.of(useRoot),null,256L*1024*1024);
   String text="class Use { int n=new Dependency().value(); }";
   assertThat(pool.query(use,text,2,(task,units,tier)->units.size()).diagnostics()).isEmpty();
   for(int i=0;i<10;i++)assertThat(pool.cacheValid()).isTrue();
   var before=pool.status();
   if(((Number)before.get("classpath_watch_directories")).longValue()>0)
    assertThat(before).containsEntry("classpath_full_scans",0L);

   Files.writeString(dependency,"public class Dependency { public String value(){return \"changed\";} }");
   assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),dependency.toString())).isZero();
   boolean invalidated=false;
   for(int i=0;i<200&&!invalidated;i++){invalidated=!pool.cacheValid();if(!invalidated)Thread.sleep(10);}
   assertThat(invalidated).as("loose classpath mutation observed").isTrue();
   assertThat(pool.query(use,text,2,(task,units,tier)->units.size()).diagnostics()).anyMatch(d->d.code().startsWith("compiler.err.prob.found.req"));
   assertThat(((Number)pool.status().get("classpath_watch_events")).longValue()).isGreaterThan(0);
  }
 }
 @Test void reusesThenRecyclesForClasspathAndHeapPressure()throws Exception{
  var heap=new java.util.concurrent.atomic.AtomicLong(1000);
  try(var pool=new CompilerPool(heap::get)){
   pool.configure("one","25",List.of(),List.of(),null,256*1024*1024);
   for(int i=0;i<3;i++)assertThat(pool.query(Path.of("A.java"),"class A {}",2,(task,units,tier)->units.size()).result()).isEqualTo(1);
   assertThat(pool.status().get("pool_statistics").toString()).contains("2 reused Contexts");
   assertThat(pool.status()).containsEntry("queries",3L).containsEntry("configure_calls",1L);
   assertThat((double)pool.status().get("query_ms")).isGreaterThanOrEqualTo(0d);
   assertThat((double)pool.status().get("configure_ms")).isGreaterThanOrEqualTo(0d);
   assertThat((long)pool.status().get("classpath_validations")).isGreaterThanOrEqualTo(3L);
   assertThat((double)pool.status().get("classpath_validation_ms")).isGreaterThanOrEqualTo(0d);
   long before=(long)pool.status().get("recycles");pool.configure("two","25",List.of(),List.of(),null,1);assertThat((long)pool.status().get("recycles")).isGreaterThan(before);
   assertThat(pool.status()).containsEntry("configure_calls",2L);
   long classpathRecycles=(long)pool.status().get("recycles");heap.set(2000);
   assertThat(pool.query(Path.of("A.java"),"class A {}",2,(task,units,tier)->units.size()).tier()).isEqualTo(2);
   assertThat((long)pool.status().get("recycles")).isGreaterThan(classpathRecycles);
   assertThat(pool.status()).containsEntry("queries",4L);
   var future=java.util.concurrent.CompletableFuture.supplyAsync(()->{try{return pool.query(Path.of("A.java"),"class A {}",2,(task,units,tier)->0);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}});
   assertThatThrownBy(future::join).hasCauseInstanceOf(IllegalStateException.class);
  }
 }
}

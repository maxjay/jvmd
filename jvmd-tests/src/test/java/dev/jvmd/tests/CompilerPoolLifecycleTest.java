package dev.jvmd.tests;
import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 4 checkpoint: a single compiler context, classpath/memory recycling and thread confinement. */
@Tag("phase-4")
class CompilerPoolLifecycleTest {
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

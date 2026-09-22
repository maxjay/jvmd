package dev.jvmd.tests;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 3 checkpoint: eager parallel repository passes, progress and warm reuse. */
@Tag("phase-3")
class EagerRepositoryIndexTest {
 @TempDir Path temp;
 @Test void scansEveryBinaryAndSourcesJarWithoutWaitingForWorkspaceOpen()throws Exception{
  Path repository=temp.resolve("repository");IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
  try(var index=new IndexService(temp.resolve("index.db"),repository)){index.scan();assertThat(index.status()).containsEntry("phase","ready").containsEntry("total",2L).containsEntry("scanned",2L).containsEntry("faults",0L);
   assertThat(index.find("transform",null,false,10,0).getFirst().get("doc")).isNotNull();long indexed=(long)index.status().get("indexed");index.scan();assertThat(index.status()).containsEntry("indexed",indexed);
  }
 }
 @Test void missingRepositoryCountsAsReadyInitialScan()throws Exception{
  String key="jvmd.index.scan.initial_delay_seconds",previous=System.getProperty(key);System.setProperty(key,"0");
  try{
   Path repository=temp.resolve("missing");
   try(var index=new IndexService(temp.resolve("missing.db"),repository)){
    index.start().get(5,java.util.concurrent.TimeUnit.SECONDS);
    assertThat(index.status()).containsEntry("phase","ready");
    assertThat(((Map<?,?>)index.status().get("timings")).get("scans")).isEqualTo(1L);
   }
  }finally{if(previous==null)System.clearProperty(key);else System.setProperty(key,previous);}
 }
 @Test void closeSettlesReadinessBeforeDelayedInitialScan()throws Exception{
  String key="jvmd.index.scan.initial_delay_seconds",previous=System.getProperty(key);System.setProperty(key,"60");
  try{
   var index=new IndexService(temp.resolve("closing.db"),temp.resolve("repository"));
   var readiness=index.start();
   index.close();
   assertThat(readiness).isCompletedExceptionally();
  }finally{if(previous==null)System.clearProperty(key);else System.setProperty(key,previous);}
 }

 @Test void deletingRepositoryRemovesPreviouslyIndexedArtifacts()throws Exception{
  Path repository=temp.resolve("deleted-repository");
  IndexFixtures.jar(repository.resolve("fixture/sample/1"),"sample-1",IndexFixtures.generic(),false);
  try(var index=new IndexService(temp.resolve("deleted.db"),repository)){
   index.scan();
   assertThat(index.find("transform",null,false,10,0)).isNotEmpty();
   try(var files=Files.walk(repository)){for(var path:files.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}
   index.scan();
   assertThat(index.find("transform",null,false,10,0)).isEmpty();
   assertThat(index.status()).containsEntry("phase","ready");
  }
 }

 @Test void failedCloseCanBeRetriedUntilStorageIsReleased()throws Exception{
  Path database=temp.resolve("retry-close.db"),repository=temp.resolve("retry-repository");
  var index=new IndexService(database,repository);
  var waits=new AtomicInteger();
  var replacement=new AbstractExecutorService(){
   private volatile boolean shutdown;
   @Override public void shutdown(){shutdown=true;}
   @Override public List<Runnable> shutdownNow(){shutdown=true;return List.of();}
   @Override public boolean isShutdown(){return shutdown;}
   @Override public boolean isTerminated(){return shutdown&&waits.get()>=3;}
   @Override public boolean awaitTermination(long timeout,TimeUnit unit){return waits.incrementAndGet()>=3;}
   @Override public void execute(Runnable command){throw new RejectedExecutionException();}
  };
  var field=IndexService.class.getDeclaredField("readers");field.setAccessible(true);
  ((ExecutorService)field.get(index)).shutdownNow();field.set(index,replacement);
  assertThatThrownBy(index::close).isInstanceOf(IllegalStateException.class);
  assertThatCode(index::close).doesNotThrowAnyException();
  try(var reopened=new IndexService(database,repository)){
   assertThat(reopened.status()).containsEntry("phase","idle");
  }
 }

}

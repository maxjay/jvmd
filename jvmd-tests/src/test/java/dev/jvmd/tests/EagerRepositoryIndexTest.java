package dev.jvmd.tests;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.Map;
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

}

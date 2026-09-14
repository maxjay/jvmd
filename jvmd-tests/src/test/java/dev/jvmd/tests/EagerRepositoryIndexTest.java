package dev.jvmd.tests;
import dev.jvmd.index.*;
import java.nio.file.*;
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
}

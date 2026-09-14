package dev.jvmd.tests;
import dev.jvmd.index.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 3 checkpoint: stat reuse, immutable checksums, and unconditional SNAPSHOT hashing. */
@Tag("phase-3")
class IndexInvalidationTest {
 @TempDir Path temp;
 @Test void hashesSnapshotsAndRejectsCorruptedArtifacts()throws Exception{
  Path jar=IndexFixtures.jar(temp,"sample",IndexFixtures.generic(),false);
  try(var index=new IndexService(temp.resolve("index.db"),temp)){long id=index.indexJar(jar,"fixture:sample:1","jar");index.indexJar(jar,"fixture:sample:1","jar");assertThat(index.status()).containsEntry("hashes",1L);
   index.indexJar(jar,"fixture:sample:1-SNAPSHOT","jar");assertThat(index.status()).containsEntry("hashes",2L);assertThat(index.artifact(jar).id()).isEqualTo(id);
   Files.writeString(jar.resolveSibling("sample.jar.sha1"),"0000000000000000000000000000000000000000");
   assertThatThrownBy(()->index.indexJar(jar,"fixture:sample:1-SNAPSHOT","jar")).hasMessageContaining("Checksum mismatch");
  }
 }
}

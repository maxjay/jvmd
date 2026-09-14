package dev.jvmd.tests;
import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 4 checkpoint: catch-and-degrade and recovery after an indexed jar disappears. */
@Tag("phase-4")
class AnalyzerFaultDegradeTest {
 @TempDir Path temp;
 @Test void returnsTierOneAndRecoversOnTheSameSessionCompiler()throws Exception{
  Path jar=IndexFixtures.jar(temp,"sample",IndexFixtures.generic(),true);byte[] original=Files.readAllBytes(jar);
  String text="class Use { fixture.Sample<Integer> sample; }";
  try(var pool=new CompilerPool()){
   pool.configure("same","25",List.of(jar),List.of(),null,256*1024*1024);
   assertThat(pool.query(temp.resolve("Use.java"),text,2,(task,units,tier)->units.size()).tier()).isEqualTo(2);
   Files.delete(jar);
   var missing=pool.query(temp.resolve("Use.java"),text,2,(task,units,tier)->units.size());assertThat(missing.tier()).isEqualTo(1);assertThat(missing.warnings()).anyMatch(w->w.startsWith("analyzer_fault:"));
   Files.write(jar,original);var recovered=pool.query(temp.resolve("Use.java"),text,2,(task,units,tier)->units.size());assertThat(recovered.tier()).isEqualTo(2);assertThat(recovered.warnings()).isEmpty();
  }
 }
}

package dev.jvmd.tests;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 3 checkpoint: workspace membership, dependency edges and all duplicate origins. */
@Tag("phase-3")
class WorkspaceIndexLoadTest {
 @TempDir Path temp;
 @Test void warnsAboutDuplicatesAndFiltersWorkspace()throws Exception{
  Path a=IndexFixtures.jar(temp.resolve("a"),"a",IndexFixtures.generic(),false),b=IndexFixtures.jar(temp.resolve("b"),"b",IndexFixtures.generic().replace("protected T protectedValue;","protected T protectedValue; public int duplicate;"),false);
  try(var index=new IndexService(new SqliteIndexStore(temp.resolve("index.db")),temp,ArtifactGenerationSink.none())){index.indexJar(a,"fixture:a:1","jar");index.indexJar(b,"fixture:b:1","jar");
   var warnings=index.loadWorkspace("one",List.of(new IndexService.WorkspaceArtifact(a.toString(),"compile")),List.of());assertThat(warnings).isEmpty();assertThat(index.find("Sample","one",false,10,0)).hasSize(2);
   warnings=index.loadWorkspace("both",List.of(new IndexService.WorkspaceArtifact(a.toString(),"compile"),new IndexService.WorkspaceArtifact(b.toString(),"compile")),List.of(Map.entry("fixture:a:1","fixture:b:1")));
   assertThat(warnings).anyMatch(w->w.startsWith("duplicate_class: fixture.Sample:")&&w.contains("fixture:a:1")&&w.contains("fixture:b:1"));
   assertThat(index.database().counts().get("edges")).isGreaterThan(0L);
  }
 }
}

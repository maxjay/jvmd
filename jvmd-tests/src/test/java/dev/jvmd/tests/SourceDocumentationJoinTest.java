package dev.jvmd.tests;
import dev.jvmd.index.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 3 checkpoint: parse-only docs and descriptor-joined source parameter names. */
@Tag("phase-3")
class SourceDocumentationJoinTest {
 @TempDir Path temp;
 @Test void joinsBoundedTypeVariablesAndRendersDocs()throws Exception{
  Path jar=IndexFixtures.jar(temp,"sample",IndexFixtures.generic(),false);
  try(var index=new IndexService(temp.resolve("index.db"),temp)){
   index.indexJar(jar,"fixture:sample:1","jar");index.indexSources(jar.resolveSibling("sample-sources.jar"));
   var result=index.find("transform",null,false,10,0).getFirst();assertThat(result.get("signature").toString()).contains("T input","U text");
   assertThat(result.get("doc").toString()).contains("Transform the value.","**input:**","**Returns:**");assertThat(result.get("source_file").toString()).contains("sample-sources.jar!/fixture/Sample.java");
   assertThat(index.artifact(jar).hasDocs()).isTrue();assertThat(index.status()).containsEntry("unmatched_source_members",0L);
  }
 }
}

package dev.jvmd.tests;
import dev.jvmd.index.*;
import dev.jvmd.core.Json;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 3 exit: full local Maven repository, Spring generics/docs and no lost artifacts. */
@Tag("phase-3") @Tag("corpus")
class RepositoryIndexExitTest {
 @TempDir Path temp;
 @Test void indexesTheEntireWarmRepositoryAndSpringSources()throws Exception{
  Path repository=Path.of(System.getProperty("user.home"),".m2/repository");
  try(var index=new IndexService(temp.resolve("index.db"),repository)){
   long start=System.nanoTime();index.scan();var status=index.status();
   System.out.println("phase-3-index "+Json.MAPPER.writeValueAsString(status));
   Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/phase-3-perf.json").toFile(),java.util.Map.of("elapsed_ms",(System.nanoTime()-start)/1e6,"status",status));
   assertThat(status).containsEntry("phase","ready").containsEntry("faults",0L);
   assertThat(index.find("hasText",null,false,50,0)).anyMatch(s->s.get("gav").toString().startsWith("org.springframework:spring-core:")&&s.get("doc")!=null&&s.get("parameters").toString().contains("str"));
   assertThat(index.find("getAnnotation",null,false,200,0)).anyMatch(s->s.get("signature").toString().contains("<A extends java.lang.annotation.Annotation>"));
  }
 }
}

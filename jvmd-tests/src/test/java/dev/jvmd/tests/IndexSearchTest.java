package dev.jvmd.tests;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.Set;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 3 checkpoint: simple names and FTS5 substring search. */
@Tag("phase-3")
class IndexSearchTest {
 @TempDir Path temp;
 @Test void findsTrigramsAndShortNamesWithoutTreatingInputAsSql()throws Exception{
  Path jar=IndexFixtures.jar(temp,"sample",IndexFixtures.generic(),false);
  try(var index=new IndexService(temp.resolve("index.db"),temp)){index.indexJar(jar,"fixture:sample:1","jar");
   assertThat(index.find("ansf",null,true,10,0)).anyMatch(s->s.get("name").equals("transform"));
   assertThat(index.find("Sa",null,true,10,0)).isNotEmpty();assertThat(index.find("%' OR 1=1 --",null,true,10,0)).isEmpty();
   assertThat(index.store().counts().get("simple_names")).isEqualTo(2L);
  }
 }
 @Test void typePrefixLookupIsCaseSensitiveAndDoesNotRequireSubstringSearch()throws Exception{
  Path jar=IndexFixtures.jar(temp.resolve("prefix"),"sample-prefix",IndexFixtures.generic(),false);
  try(var index=new IndexService(temp.resolve("prefix.db"),temp)){
   index.indexJar(jar,"fixture:sample:1","jar");
   assertThat(index.findNamePrefix("Sa",null,10,Set.of("class")).stream().map(s->s.get("name"))).contains("Sample");
   assertThat(index.findNamePrefix("am",null,10,Set.of("class")).stream().map(s->s.get("name"))).doesNotContain("Sample");
   assertThat(index.findNamePrefix("sa",null,10,Set.of("class")).stream().map(s->s.get("name"))).doesNotContain("Sample");
  }
 }
}

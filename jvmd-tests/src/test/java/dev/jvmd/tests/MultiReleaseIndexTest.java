package dev.jvmd.tests;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.jar.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 3 checkpoint: highest supported multi-release class, honoring manifest opt-in. */
@Tag("phase-3")
class MultiReleaseIndexTest {
 @TempDir Path temp;
 @Test void picksHighestSupportedAndIgnoresFuture()throws Exception{
  Path base=IndexFixtures.jar(temp.resolve("base"),"base","package fixture; public class Sample { public int base; }",true);
  Path selected=IndexFixtures.jar(temp.resolve("selected"),"selected","package fixture; public class Sample { public int selected; }",true);
  byte[] a,b;try(var j=new JarFile(base.toFile())){a=j.getInputStream(j.getJarEntry("fixture/Sample.class")).readAllBytes();}try(var j=new JarFile(selected.toFile())){b=j.getInputStream(j.getJarEntry("fixture/Sample.class")).readAllBytes();}
  var manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Multi-Release","true");Path jar=temp.resolve("multi.jar");
  try(var out=new JarOutputStream(Files.newOutputStream(jar),manifest)){for(var e:java.util.Map.of("fixture/Sample.class",a,"META-INF/versions/21/fixture/Sample.class",b,"META-INF/versions/26/fixture/Sample.class",a).entrySet()){out.putNextEntry(new JarEntry(e.getKey()));out.write(e.getValue());out.closeEntry();}}
  var content=new BinaryReader().read(jar,false);assertThat(content.symbols()).anyMatch(s->s.name().equals("selected")).noneMatch(s->s.name().equals("base"));assertThat(content.symbols().getFirst().entry()).startsWith("META-INF/versions/21/");
 }
}

package dev.jvmd.tests;
import dev.jvmd.index.*;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 3 checkpoint: generic signatures and MethodParameters without reading Code. */
@Tag("phase-3")
class BinarySkeletonTest {
 @TempDir Path temp;
 @Test void preservesGenericsVisibilityAndParameterNames()throws Exception{
  Path jar=IndexFixtures.jar(temp,"generic",IndexFixtures.generic(),true);var content=new BinaryReader().read(jar,false);
  var method=content.symbols().stream().filter(s->s.name().equals("transform")).findFirst().orElseThrow();
  assertThat(method.signature()).contains("<U extends java.lang.CharSequence>","java.util.List<U>","T input","U text");
  assertThat(method.parameters()).containsExactly("input","text");assertThat(method.descriptor()).isEqualTo("(Ljava/lang/Number;Ljava/lang/CharSequence;)Ljava/util/List;");
  assertThat(content.symbols()).noneMatch(s->s.name().equals("hidden"));assertThat(content.symbols()).anyMatch(s->s.name().equals("protectedValue"));
  assertThat(content.symbols()).anyMatch(s->s.fqn().equals("fixture.Sample$Nested"));
  assertThat(content.edges()).anyMatch(e->e.kind().equals("return_type")&&e.target().equals("java.util.List"));
 }

 @Test void retainsJvmOverloadsThatDifferOnlyByReturnType()throws Exception{
  var cf=java.lang.classfile.ClassFile.of();
  byte[] bytes=cf.build(java.lang.constant.ClassDesc.of("fixture.ReturnOverload"),b->b.withFlags(1)
   .withMethodBody("value",java.lang.constant.MethodTypeDesc.ofDescriptor("()Ljava/lang/Object;"),9,c->c.aconst_null().areturn())
   .withMethodBody("value",java.lang.constant.MethodTypeDesc.ofDescriptor("()V"),9,c->c.return_()));
  Path jar=temp.resolve("returns.jar");try(var output=new java.util.jar.JarOutputStream(Files.newOutputStream(jar))){output.putNextEntry(new java.util.jar.JarEntry("fixture/ReturnOverload.class"));output.write(bytes);output.closeEntry();}
  try(var index=new IndexService(temp.resolve("index.db"),temp);
      var control=new IndexService(new ReferenceIndexStorage(temp.resolve("control.db")),temp)){
   index.indexJar(jar,"fixture:returns:1","jar");control.indexJar(jar,"fixture:returns:1","jar");
   var methods=index.find("value",null,false,10,0);assertThat(methods).hasSize(2);assertThat(methods.stream().map(m->m.get("scip"))).doesNotHaveDuplicates();
   assertThat(methods).extracting(m->m.get("scip")).containsExactlyInAnyOrderElementsOf(control.find("value",null,false,10,0).stream().map(m->m.get("scip")).toList());
   long after=((Number)methods.getFirst().get("id")).longValue();assertThat(index.find("value",null,false,1,after)).extracting(m->m.get("scip")).containsExactly(methods.getLast().get("scip"));
   for(var method:methods)assertThat(index.store().byScip(method.get("scip").toString(),null).get("binary_key")).isEqualTo(method.get("binary_key"));
  }
 }
}

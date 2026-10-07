package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.nio.file.*;
import java.util.*;
import javax.lang.model.element.TypeElement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Independent native-writer feasibility check before selecting the source compiler-view representation. */
class HeaderWriterProbeTest {
    @TempDir Path directory;
    @Test void nativeHeaderWriterNeedsLoweringForHiddenConstructorParameters() throws Exception {
        var sources=Map.of(
                "p/A.java","package p; import java.lang.annotation.*; @Target({ElementType.TYPE_USE,ElementType.TYPE_PARAMETER,ElementType.FIELD,ElementType.PARAMETER,ElementType.RECORD_COMPONENT}) @Retention(RetentionPolicy.RUNTIME) public @interface A { String value() default \"x\"; }",
                "p/C.java","package p; public class C<@A T extends @A Number> { @A private String value; public C(){} public <U extends @A Object> @A String f(@A String p) throws Exception { return p; } public class Inner { public Inner(String x){} } }",
                "p/R.java","package p; public record R(@A String name) {}",
                "p/E.java","package p; public enum E { A, B; @A private int value; }");
        var bytes=Stage2Support.compile(directory.resolve("native"),sources,List.of("-parameters"),List.of());
        var root=directory.resolve("headers");Stage2Support.write(root,sources);
        var inputs=sources.keySet().stream().sorted().map(p->new HeaderCompiler.Source(p,root.resolve(p))).toList();
        try(var headers=HeaderCompiler.compile(inputs,List.of(),Path.of(System.getProperty("java.home")),Runtime.version().feature(),List.of("-parameters"),Sha256.INSTANCE)) {
            var extract=new dev.jvmd.index.layer.local.SourceFacts(Sha256.INSTANCE,headers.elements,headers.types,true);
            for(var unit:headers.units)assertThat(extract.of(unit.declared,unit.packageDeclaration,unit.packageClass).faults()).isEmpty();
            var taskField=HeaderCompiler.Compiled.class.getDeclaredField("task");taskField.setAccessible(true);
            var task=taskField.get(headers);var context=task.getClass().getMethod("getContext").invoke(task);
            var loader=ModuleLayer.boot().findLoader("jdk.compiler");
            var writerType=Class.forName("com.sun.tools.javac.jvm.ClassWriter",false,loader);
            var writer=writerType.getMethod("instance",Class.forName("com.sun.tools.javac.util.Context",false,loader)).invoke(null,context);
            var write=writerType.getMethod("writeClassFile",java.io.OutputStream.class,Class.forName("com.sun.tools.javac.code.Symbol$ClassSymbol",false,loader));
            var declarations=new ArrayDeque<TypeElement>();for(var unit:headers.units)declarations.addAll(unit.declared);
            while(!declarations.isEmpty()) {
                var type=declarations.removeFirst();
                for(var child:type.getEnclosedElements())if(child instanceof TypeElement t)declarations.add(t);
                var out=new java.io.ByteArrayOutputStream();write.invoke(writer,out,type);
                var name=headers.elements.getBinaryName(type).toString().replace('.','/');
                var nativeFacts=ClassFacts.of(Sha256.INSTANCE,bytes.get(name+".class"),name);
                if(name.equals("p/C$Inner")) {
                    // ClassWriter counts external parameters, but Lower has not populated extraParams.
                    // Adopting its raw output here would publish a malformed MethodParameters attribute.
                    assertThatThrownBy(()->ClassFacts.of(Sha256.INSTANCE,out.toByteArray(),name))
                            .isInstanceOf(ClassFacts.Fault.class).hasMessageContaining("Unreadable class file");
                    continue;
                }
                var headerFacts=ClassFacts.of(Sha256.INSTANCE,out.toByteArray(),name);
                var n=new TreeSet<>(nativeFacts.facts().stream().map(f->HexFormat.of().formatHex(f.h().bytes())).toList());
                var h=new TreeSet<>(headerFacts.facts().stream().map(f->HexFormat.of().formatHex(f.h().bytes())).toList());
                var missing=new TreeSet<>(n);missing.removeAll(h);var extra=new TreeSet<>(h);extra.removeAll(n);
                System.out.println("HEADER-WRITER "+name+" Tmissing="+missing.size()+" Textra="+extra.size()+" readerEqual="+Arrays.deepEquals(nativeFacts.reader().parameterRecipe().toArray(),headerFacts.reader().parameterRecipe().toArray()));
                assertThat(missing).as(name).isEmpty();assertThat(extra).as(name).isEmpty();
                if(name.equals("p/E"))
                    assertThat(headerFacts.reader().parameterRecipe()).usingRecursiveComparison().isNotEqualTo(nativeFacts.reader().parameterRecipe());
                else assertThat(headerFacts.reader().parameterRecipe()).usingRecursiveComparison().isEqualTo(nativeFacts.reader().parameterRecipe());
            }
        }
    }
}

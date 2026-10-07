package dev.jvmd.tests.oracle;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises the native hooks without running any JVMD extraction or attribution code. */
@Tag("read-oracle")
public class ReadOracleInstrumentationTest {
    @TempDir Path directory;
    public record Row(String path, List<String> typeKeys) { }

    @Test void anOrdinaryClassStillQueriesTheAutoCloseableTargetHeader() throws Exception {
        var source=directory.resolve("App.java");Files.writeString(source,"package p; class App {}");
        ReadOracleTrace.begin(new Row("p/App.java",List.of("p/App")));
        int code=ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",directory.toString(),source.toString());
        var trace=ReadOracleTrace.finish();assertThat(code).isZero();
        assertThat(trace.queries()).contains(new ReadOracleTrace.Missing("TYPE","java/lang/AutoCloseable",""));
        assertThat(trace.queries()).doesNotContain(new ReadOracleTrace.Missing("METHOD","java/lang/AutoCloseable","close"));
    }

    @Test void boxingMethodsAreNativeGenerationQueriesWithoutSourceCalls() throws Exception {
        var source=directory.resolve("App.java");
        Files.writeString(source,"package p; class App { Object box(int value){return value;} int unbox(Integer value){return value;} }");
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,null,null)) {
            var task=(com.sun.source.util.JavacTask)compiler.getTask(null,manager,null,
                    List.of("-proc:none","-d",directory.toString()),null,manager.getJavaFileObjects(source));
            task.parse();task.analyze();
            ReadOracleTrace.begin(new Row("p/App.java",List.of("p/App")));
            task.generate();
            var trace=ReadOracleTrace.finish();
            assertThat(trace.queries()).contains(new ReadOracleTrace.Missing("METHOD","java/lang/Integer","valueOf"),
                    new ReadOracleTrace.Missing("METHOD","java/lang/Integer","intValue"));
        }
    }

    @Test void emptyStaticImportScopesAreObservedAtTheirRequestedKindAndName() throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();var dependencies=Files.createDirectories(directory.resolve("dependencies"));
        var first=directory.resolve("First.java");var second=directory.resolve("Second.java");
        Files.writeString(first,"package q; public class First { public static Object pick(Object value){return value;} }");
        Files.writeString(second,"package q; public class Second {}");
        assertThat(compiler.run(null,null,null,"-proc:none","-d",dependencies.toString(),first.toString(),second.toString())).isZero();
        var source=directory.resolve("App.java");
        Files.writeString(source,"package p; import static q.First.*; import static q.Second.*; class App { Object value(){return pick(null);} }");
        ReadOracleTrace.begin(new Row("p/App.java",List.of("p/App")));
        assertThat(compiler.run(null,null,null,"-proc:none","-classpath",dependencies.toString(),"-d",directory.toString(),source.toString())).isZero();
        var trace=ReadOracleTrace.finish();
        assertThat(trace.queries()).contains(new ReadOracleTrace.Missing("N","q/First","Object"),
                new ReadOracleTrace.Missing("N","q/Second","Object"),new ReadOracleTrace.Missing("METHOD","q/Second","pick"));
        var previous=Files.readAllBytes(directory.resolve("p/App.class"));
        Files.writeString(second,"package q; public class Second { public static String pick(String value){return value;} }");
        assertThat(compiler.run(null,null,null,"-proc:none","-d",dependencies.toString(),second.toString())).isZero();
        assertThat(compiler.run(null,null,null,"-proc:none","-classpath",dependencies.toString(),"-d",directory.toString(),source.toString())).isZero();
        assertThat(Files.readAllBytes(directory.resolve("p/App.class"))).isNotEqualTo(previous);
    }

    @Test void nativeLexicalAndEnumQueriesDistinguishTheirActualScopes() throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();var dependencies=Files.createDirectories(directory.resolve("dependencies"));
        var base=directory.resolve("Base.java");var lib=directory.resolve("Lib.java");var enumeration=directory.resolve("E.java");
        Files.writeString(base,"package q; public class Base {}");
        Files.writeString(lib,"package q; public class Lib { public static int call(){return 1;} }");
        Files.writeString(enumeration,"package q; public enum E {A,B}");
        assertThat(compiler.run(null,null,null,"-proc:none","-d",dependencies.toString(),base.toString(),lib.toString(),enumeration.toString())).isZero();
        var source=directory.resolve("App.java");
        for(int version=0;version<3;version++) {
            Files.writeString(source,switch(version) {
                case 0 -> "package p; import q.Lib; class App extends q.Base { int value(){return Lib.call();} }";
                case 1 -> "package p; class App extends q.Base { int value(){return q.Lib.call();} }";
                default -> "package p; class App extends q.Base { int value(q.E e){return switch(e){case A -> 1; case B -> 2;};} }";
            });
            ReadOracleTrace.begin(new Row("p/App.java",List.of("p/App")));
            assertThat(compiler.run(null,null,null,"-proc:none","-classpath",dependencies.toString(),"-d",directory.toString(),source.toString())).isZero();
            var trace=ReadOracleTrace.finish();
            var inherited=new ReadOracleTrace.Missing("N","q/Base","q");
            if(version>0)assertThat(trace.queries()).contains(inherited);
            else assertThat(trace.queries()).doesNotContain(inherited);
            assertThat(trace.absent()).doesNotContain(new ReadOracleTrace.Missing("D","q",""));
            if(version==2) {
                assertThat(trace.queries()).contains(new ReadOracleTrace.Missing("FIELD","q/E",""))
                        .doesNotContain(new ReadOracleTrace.Missing("FIELD","q/Base","A"),new ReadOracleTrace.Missing("FIELD","java/lang/Object","A"));
            }
        }
    }

    @Test void changingOnlyTheImplicitBoxingFactoryChangesNativeBytes() throws Exception {
        byte[] original;
        try(var input=Integer.class.getResourceAsStream("Integer.class")) { original=input.readAllBytes(); }
        var cf=java.lang.classfile.ClassFile.of();
        var changed=cf.transformClass(cf.parse(original),(builder,element)->{
            if(element instanceof java.lang.classfile.MethodModel method && method.methodName().equalsString("valueOf")
                    && method.methodType().equalsString("(I)Ljava/lang/Integer;"))
                builder.withMethod("valueOf",java.lang.constant.MethodTypeDesc.ofDescriptor("(I)Ljava/lang/Number;"),
                        method.flags().flagsMask(),out->method.forEach(out::with));
            else builder.with(element);
        });
        var source=directory.resolve("App.java");Files.writeString(source,"package p; class App { Object box(int value){return value;} }");
        var compiler=ToolProvider.getSystemJavaCompiler();var outputs=new java.util.ArrayList<byte[]>();
        for(var bytes:List.of(original,changed)) {
            var patch=Files.createDirectories(directory.resolve("patch-"+outputs.size()));
            var integer=patch.resolve("java/lang/Integer.class");Files.createDirectories(integer.getParent());Files.write(integer,bytes);
            var output=Files.createDirectories(directory.resolve("output-"+outputs.size()));
            ReadOracleTrace.begin(new Row("p/App.java",List.of("p/App")));
            var errors=new java.io.ByteArrayOutputStream();
            int code=compiler.run(null,null,errors,"-proc:none","--patch-module","java.base="+patch,"-d",output.toString(),source.toString());
            var trace=ReadOracleTrace.finish();
            assertThat(code).as(errors.toString()).isZero();
            assertThat(trace.queries()).contains(new ReadOracleTrace.Missing("METHOD","java/lang/Integer","valueOf"));
            outputs.add(Files.readAllBytes(output.resolve("p/App.class")));
        }
        assertThat(outputs.get(1)).isNotEqualTo(outputs.get(0));
    }

    @Test void nativeDefaultGuardJustifiesOnlyTheRequestedMethodProjection() throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();
        var contract=directory.resolve("I.java");var library=directory.resolve("Lib.java");
        Files.writeString(contract,"package q; public interface I {}");
        Files.writeString(library,"package q; public class Lib implements I { public static Object pick(Object value){return value;} }");
        var dependencies=Files.createDirectories(directory.resolve("dependencies"));
        assertThat(compiler.run(null,null,null,"-proc:none","-d",dependencies.toString(),contract.toString(),library.toString())).isZero();
        var source=directory.resolve("App.java");Files.writeString(source,"package p; class App { Object value(){return q.Lib.pick(null);} }");
        for(int version=0;version<3;version++) {
            if(version>0) {
                Files.writeString(contract,version==1?"package q; public interface I { default int unrelated(){return 1;} }"
                        :"package q; public interface I { default String pick(String value){return value;} }");
                assertThat(compiler.run(null,null,null,"-proc:none","-d",dependencies.toString(),contract.toString())).isZero();
            }
            ReadOracleTrace.begin(new Row("p/App.java",List.of("p/App")));
            var errors=new java.io.ByteArrayOutputStream();
            int result=compiler.run(null,null,errors,"-proc:none","-d",directory.toString(),"-classpath",dependencies.toString(),source.toString());
            var trace=ReadOracleTrace.finish();
            var query=new ReadOracleTrace.Missing("METHOD","q/I","pick");
            if(version==0) {
                assertThat(trace.queries()).doesNotContain(query);
                assertThat(trace.closure()).contains(query).doesNotContain(new ReadOracleTrace.Missing("METHOD","q/I",""),
                        new ReadOracleTrace.Missing("METHOD","q/I","unrelated"));
            } else assertThat(trace.queries()).contains(query);
            assertThat(result).as(errors.toString()).isEqualTo(version==2?1:0);
        }
    }

    @Test void fullMethodQueriesAndIntrinsicArrayIdentityAreObservedIndependently() throws Exception {
        var compiler=ToolProvider.getSystemJavaCompiler();
        var array=directory.resolve("Array.java");
        var empty=directory.resolve("Empty.java");
        Files.writeString(array,"package q; public class Array { public static int length=3; }");
        Files.writeString(empty,"package q; public interface Empty {}");
        var dependencies=Files.createDirectories(directory.resolve("dependencies"));
        assertThat(compiler.run(null,null,null,"-proc:none","-d",dependencies.toString(),array.toString(),empty.toString())).isZero();
        var source=directory.resolve("App.java");
        Files.writeString(source,"package p; class App implements q.Empty { int size(int[] input) { return input.length+q.Array.length; } }");
        ReadOracleTrace.begin(new Row("p/App.java",List.of("p/App")));
        assertThat(compiler.run(null,null,null,"-proc:none","-d",directory.toString(),"-classpath",dependencies.toString(),source.toString())).isZero();
        var trace=ReadOracleTrace.finish();
        assertThat(trace.predefined()).contains(new ReadOracleTrace.Missing("FIELD","Array","length"));
        assertThat(trace.queries()).contains(new ReadOracleTrace.Missing("FIELD","q/Array","length"),
                new ReadOracleTrace.Missing("METHOD","q/Empty",""),new ReadOracleTrace.Missing("METHOD","java/lang/Object",""))
                .doesNotContain(new ReadOracleTrace.Missing("FIELD","Array","length"));
        assertThat(trace.scans()).anyMatch(s->s.contains("TransTypes.addBridges"));
    }

    @Test void nativeCompilerProducesBothTracesWithoutACollector() throws Exception {
        assertThat(System.getProperty("jvmd.readOracle.active")).isEqualTo("true");
        var compiler = ToolProvider.getSystemJavaCompiler();
        var base = directory.resolve("Base.java");
        Files.writeString(base, "package q; public class Base { public static int inherited(){return 1;} }");
        var child = directory.resolve("Child.java");
        Files.writeString(child, "package q; public class Child extends Base {}");
        var pick = directory.resolve("Pick.java");
        Files.writeString(pick, "package q; public class Pick {}");
        var other = directory.resolve("Other.java");
        Files.writeString(other, "package r; public class Other {}");
        var dependencies = Files.createDirectories(directory.resolve("dependencies"));
        assertThat(compiler.run(null, null, null, "-proc:none", "-d", dependencies.toString(),
                base.toString(), child.toString(), pick.toString(), other.toString())).isZero();
        var source = directory.resolve("App.java");
        Files.writeString(source, """
                package p;
                import q.*;
                import r.*;
                class App {
                    Pick pick;
                    Object field() { return q.Base.noField; }
                    Object method() { return q.Base.noMethod(); }
                    int inherited() { return q.Child.inherited(); }
                    q.Base.Nested nested;
                    q.Missing absent;
                }
                """);
        var errors = new java.io.ByteArrayOutputStream();
        ReadOracleTrace.begin(new Row("p/App.java", List.of("p/App")));
        int result = compiler.run(null, null, errors, "-proc:none", "-classpath", dependencies.toString(), source.toString());
        var trace = ReadOracleTrace.finish();
        assertThat(ReadOracleTrace.hooks()).contains("com/sun/tools/javac/jvm/ClassReader",
                "com/sun/tools/javac/code/Symbol", "com/sun/tools/javac/comp/Resolve", "com/sun/tools/javac/code/Scope$ScopeImpl");
        assertThat(result).as(errors.toString()).isNotZero();
        assertThat(trace.loaded()).contains("q/Base", "java/lang/Object").doesNotContain("p/App");
        assertThat(trace.queries()).contains(new ReadOracleTrace.Missing("METHOD","q/Base","inherited"),
                new ReadOracleTrace.Missing("TYPE","q/Base",""),new ReadOracleTrace.Missing("TYPE","q/Child",""));
        assertThat(trace.modules()).contains("java.base", "java.compiler");
        assertThat(trace.loaded()).noneMatch(name -> name.endsWith("/module-info"));
        assertThat(trace.predefined()).contains(new ReadOracleTrace.Missing("FIELD", "", "q"));
        assertThat(trace.absent()).noneMatch(m -> m.owner().isEmpty());
        assertThat(trace.absent()).contains(new ReadOracleTrace.Missing("D", "q/Missing", ""),
                new ReadOracleTrace.Missing("N", "q/Base", "Nested"),
                new ReadOracleTrace.Missing("FIELD", "q/Base", "noField"),
                new ReadOracleTrace.Missing("METHOD", "q/Base", "noMethod"),
                new ReadOracleTrace.Missing("METHOD", "q/Child", "inherited"),
                new ReadOracleTrace.Missing("D", "p/Pick", ""),
                new ReadOracleTrace.Missing("D", "r/Pick", ""),
                new ReadOracleTrace.Missing("D", "java/lang/Pick", ""))
                .doesNotContain(new ReadOracleTrace.Missing("METHOD", "q/Base", "inherited"),
                        new ReadOracleTrace.Missing("D", "q/Pick", ""));
        ReadOracleTrace.begin(new Row("p/App.java", List.of("p/App")));
        ReadOracleTrace.suspend();
        compiler.run(null, null, errors, "-proc:none", "-classpath", dependencies.toString(), source.toString());
        ReadOracleTrace.resume();
        var excluded = ReadOracleTrace.finish();
        assertThat(excluded.loaded()).isEmpty(); assertThat(excluded.absent()).isEmpty();
        assertThat(excluded.modules()).isEmpty();
        assertThat(excluded.predefined()).isEmpty();
        assertThat(excluded.queries()).isEmpty();
    }
}

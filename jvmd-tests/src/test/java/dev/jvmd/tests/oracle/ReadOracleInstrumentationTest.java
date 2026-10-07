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

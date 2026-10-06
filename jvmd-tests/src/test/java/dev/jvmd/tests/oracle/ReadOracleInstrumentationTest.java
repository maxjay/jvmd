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
    }
}

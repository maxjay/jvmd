package dev.jvmd.tests;

import dev.jvmd.analyzer.CompilerPool;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Release reuse must preserve javac's historical API and language restrictions. */
@Tag("phase-4")
class ReleasePlatformReuseTest {
    @TempDir Path root;
    @Test void reusedPlatformsAgreeWithFreshJavacAcrossReleasesAndSourceChanges()throws Exception{
        Path file=root.resolve("Example.java");
        try(var pool=new CompilerPool()){
            for(String release:List.of("8","11","17","25","8")){
                pool.configure("release-"+release,release,List.of(),List.of(root),null,512L*1024*1024);
                for(String body:List.of("return \"x\".strip();","return \"x\".indent(2);","return java.util.List.of(\"x\").getFirst();","var value=\"x\"; return value;")){
                    String text="class Example { Object value(){ "+body+" } }";
                    Files.writeString(file,text);pool.sourcesChanged();
                    var result=pool.query(file,text,2,(task,units,tier)->units.size());
                    assertThat(result.warnings()).isEmpty();assertThat(result.tier()).isEqualTo(2);
                    assertThat(result.diagnostics().stream().filter(p->p.kind().equals("ERROR")).map(CompilerPool.Problem::code).toList())
                            .as("release %s: %s",release,body).containsExactlyElementsOf(nativeErrors(file,text,release));
                }
            }
            assertThat((long)pool.status().get("release_platform_reuses")).isGreaterThanOrEqualTo(15);
            long before=(long)pool.status().get("release_platform_initializations");
            pool.recycle();String text="class Example { String value(){return \"x\".strip();} }";
            var result=pool.query(file,text,2,(task,units,tier)->units.size());
            assertThat(result.warnings()).isEmpty();assertThat(result.diagnostics()).anyMatch(p->p.kind().equals("ERROR"));
            assertThat(pool.status().get("release_platform_initializations")).isEqualTo(before+1);
        }
    }
    private static List<String> nativeErrors(Path file,String text,String release)throws Exception{
        var diagnostics=new DiagnosticCollector<JavaFileObject>();
        var source=new SimpleJavaFileObject(file.toUri(),JavaFileObject.Kind.SOURCE){@Override public CharSequence getCharContent(boolean ignored){return text;}};
        var compiler=ToolProvider.getSystemJavaCompiler();
        try(var files=compiler.getStandardFileManager(diagnostics,Locale.ROOT,null)){
            var task=(com.sun.source.util.JavacTask)compiler.getTask(null,files,diagnostics,List.of("--release",release,"-proc:none"),null,List.of(source));
            task.parse();task.analyze();
        }
        return diagnostics.getDiagnostics().stream().filter(d->d.getKind()==Diagnostic.Kind.ERROR).map(Diagnostic::getCode).toList();
    }
}

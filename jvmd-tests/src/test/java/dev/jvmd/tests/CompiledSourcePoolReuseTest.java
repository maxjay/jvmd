package dev.jvmd.tests;
import dev.jvmd.analyzer.CompilerPool;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
/** Implements phase 4 corpus regression: compiled source roots remain visible across pooled tasks. */
@Tag("phase-4")
class CompiledSourcePoolReuseTest {
    @TempDir Path root;
    @Test void queryingABaseTypeDoesNotHideItFromTheNextFile()throws Exception{
        Path sources=Files.createDirectories(root.resolve("src/fixture")),classes=Files.createDirectories(root.resolve("classes"));
        Path base=sources.resolve("Base.java"),derived=sources.resolve("Derived.java");
        Files.writeString(base,"package fixture; public class Base { public int value(){return 1;} }");
        Files.writeString(derived,"package fixture; public class Derived extends Base { int use(){return value();} }");
        assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),base.toString(),derived.toString())).isZero();
        try(var compiler=new CompilerPool()){
            compiler.configure("1","25",List.of(classes),List.of(root.resolve("src")),null,256L*1024*1024);
            for(int i=0;i<3;i++)for(Path file:List.of(base,derived)){
                var result=compiler.query(file,Files.readString(file),2,(task,units,tier)->Boolean.TRUE);
                assertThat(result.tier()).isEqualTo(2);assertThat(result.warnings()).isEmpty();assertThat(result.diagnostics()).as(file.toString()).isEmpty();
            }
            assertThat(compiler.status().get("pool_statistics").toString()).contains("5 reused Contexts");
        }
    }
}

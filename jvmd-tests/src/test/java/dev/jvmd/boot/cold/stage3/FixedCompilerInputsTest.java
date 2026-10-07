package dev.jvmd.boot.cold.stage3;

import dev.jvmd.boot.cold.stage2.ProcessorPath;
import dev.jvmd.core.hash.Digest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import java.util.zip.CRC32;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class FixedCompilerInputsTest {
    @TempDir Path directory;
    static Stream<Digest> digests() { return OutputRecoveryTest.digests(); }

    private Map<String,byte[]> compile(String folder, String name, String text, List<Path> dependencies) throws IOException {
        var root=Files.createDirectories(directory.resolve(folder));var source=root.resolve(name+".java");Files.writeString(source,text);
        var output=Files.createDirectories(root.resolve("out"));var compiler=ToolProvider.getSystemJavaCompiler();
        try(var manager=compiler.getStandardFileManager(null,null,StandardCharsets.UTF_8)) {
            manager.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT,List.of(output));
            manager.setLocationFromPaths(javax.tools.StandardLocation.CLASS_PATH,dependencies);
            assertThat(compiler.getTask(null,manager,null,List.of("-proc:none","-implicit:none"),null,manager.getJavaFileObjects(source)).call()).isTrue();
        }
        var classes=new TreeMap<String,byte[]>();
        try(var paths=Files.walk(output)) {
            for(var path:paths.filter(p->p.toString().endsWith(".class")).toList())classes.put(output.relativize(path).toString().replace('\\','/'),Files.readAllBytes(path));
        }
        return classes;
    }
    private void pack(Path jar,Map<String,byte[]> entries) throws IOException {
        try(var output=new JarOutputStream(Files.newOutputStream(jar))) {
            for(var file:entries.entrySet()) {
                var entry=new JarEntry(file.getKey());entry.setTime(0);entry.setMethod(JarEntry.STORED);
                entry.setSize(file.getValue().length);var crc=new CRC32();crc.update(file.getValue());entry.setCrc(crc.getValue());
                output.putNextEntry(entry);output.write(file.getValue());output.closeEntry();
            }
        }
    }
    private Pool.Completed<Void> run(Pool pool,ProcessorPath snapshot,String source) throws Exception {
        return run(pool,snapshot,source,new java.util.ArrayList<>());
    }
    private Pool.Completed<Void> run(Pool pool,ProcessorPath snapshot,String source,List<String> diagnostics) throws Exception {
        return pool.withTask(directory.resolve("App.java").toUri(),source.getBytes(StandardCharsets.UTF_8),d -> {
            diagnostics.add(d.getCode());
            assertThat(d.getKind()).isNotEqualTo(javax.tools.Diagnostic.Kind.ERROR);
        },task -> {
            try {task.parse();task.analyze();task.generate();return null;}catch(IOException e){throw new UncheckedIOException(e);}
        },snapshot);
    }

    @ParameterizedTest @MethodSource("digests")
    void invalidPlatformEnumMetadataCannotLoseItsWarningThroughContextReuse(Digest digest) throws Exception {
        var classes = compile("future-platform","Lib", """
                package q;
                @javax.annotation.processing.SupportedSourceVersion(javax.lang.model.SourceVersion.RELEASE_25)
                public class Lib {public static final String VALUE="OLD";}
                """,List.of());
        var bytes = classes.get("q/Lib.class").clone();
        var from = "RELEASE_25".getBytes(StandardCharsets.UTF_8); var to = "RELEASE_99".getBytes(StandardCharsets.UTF_8);
        int replacements = 0;
        for (int i=0;i<=bytes.length-from.length;i++) if(Arrays.equals(bytes,i,i+from.length,from,0,from.length)) {
            System.arraycopy(to,0,bytes,i,to.length);replacements++;
        }
        assertThat(replacements).isEqualTo(1);
        var jar = directory.resolve("future.jar");pack(jar,Map.of("q/Lib.class",bytes));
        String source="public class App {public String value(){return q.Lib.VALUE;}}";
        var expected=compile("future-native","App",source,List.of(jar)).get("App.class");
        var identity=digest.hash(digest.hash(Files.readAllBytes(jar)).view());
        var config=new Pool.Configuration(new Pool.Key(identity,identity),Files.createDirectories(directory.resolve("future-own")),List.of(jar),
                StandardCharsets.UTF_8,List.of("-proc:none","-implicit:none"),List.of());
        try(var snapshot=new ProcessorPath(List.of(jar),digest,identity);var pool=new Pool(config,1)) {
            for(int i=0;i<2;i++) {
                var diagnostics=new java.util.ArrayList<String>();var result=run(pool,snapshot,source,diagnostics);
                assertThat(result.metadataSupported()).isFalse();
                assertThat(result.classes().get("App")).isEqualTo(expected);
                assertThat(diagnostics).containsExactly("compiler.warn.unknown.enum.constant");
            }
            assertThat(pool.statistics().contexts()).isEqualTo(2);
            assertThat(pool.metadataStatistics().files()).isEqualTo(1);
            assertThat(pool.metadataStatistics().bytes()).isEqualTo(bytes.length);
        }
    }

    @ParameterizedTest @MethodSource("digests")
    void compilerReadsOnlyTheFrozenProcessorBytesEvenAfterAnInvisiblePathReplacement(Digest digest) throws Exception {
        var old=compile("old","Lib","package q; public class Lib {public static final String VALUE=\"OLD\";}",List.of());
        var next=compile("new","Lib","package q; public class Lib {public static final String VALUE=\"NEW\";}",List.of());
        var jar=directory.resolve("processor.jar");pack(jar,old);
        String source="public class App {public String value(){return q.Lib.VALUE;}}";
        var expected=compile("native-old","App",source,List.of(jar)).get("App.class");
        var identity=digest.hash(digest.hash(Files.readAllBytes(jar)).view());
        var config=new Pool.Configuration(new Pool.Key(identity,identity),Files.createDirectories(directory.resolve("own")),List.of(jar),
                StandardCharsets.UTF_8,List.of("-proc:none","-implicit:none"),List.of());
        try(var snapshot=new ProcessorPath(List.of(jar),digest,identity)) {
            var modified=Files.getLastModifiedTime(jar);long size=Files.size(jar);
            pack(jar,next);Files.setLastModifiedTime(jar,modified);
            assertThat(Files.size(jar)).isEqualTo(size);snapshot.check();
            var current=compile("native-new","App",source,List.of(jar)).get("App.class");
            assertThat(Arrays.equals(expected,current)).isFalse();
            try(var pool=new Pool(config,1)) {
                var fixed=run(pool,snapshot,source);assertThat(fixed.classes().get("App")).isEqualTo(expected);
                assertThat(fixed.metadataSupported()).isTrue();
                var metadata = pool.metadataStatistics();
                assertThat(metadata.files()).isEqualTo(1);
                assertThat(metadata.bytes()).isEqualTo(old.get("q/Lib.class").length);
                assertThat(run(pool,snapshot,source).classes().get("App")).isEqualTo(expected);
                assertThat(pool.metadataStatistics()).isEqualTo(metadata);
                assertThat(pool.statistics().contexts()).isEqualTo(1);
                // Removing the exact fixed input resets the context and cannot retain its admission or old classes.
                var ordinary=run(pool,null,source);assertThat(ordinary.classes().get("App")).isEqualTo(current);
                assertThat(ordinary.metadataSupported()).isFalse();
            }
        }
    }
}

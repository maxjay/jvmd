package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.index.layer.local.ResultRecord;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.assertThat;

/** Descriptor lint is checked against an independent native module compilation. */
class ModuleLintTest {
    @TempDir Path directory;
    private int sequence;

    @Test void automaticRequiresMatchesNativeCategoriesSuppressionAndPositions() throws Exception {
        var automatic=Stage2Support.jar(directory,"dependency.jar",Map.of("q/Api.java","package q; public class Api {}"),List.of());
        for(var options:List.of(List.<String>of(),List.of("-Xlint:all"),List.of("-Xlint:none"),
                List.of("-Xlint:requires-automatic,-requires-transitive-automatic"),
                List.of("-Xlint:-requires-automatic,-requires-transitive-automatic"))) {
            compare("module example { requires transitive dependency; }",automatic,options,Stage2Support.FEATURE);
            compare("module example { requires dependency; }",automatic,options,Stage2Support.FEATURE);
            compare("@SuppressWarnings({\"requires-automatic\",\"requires-transitive-automatic\"}) module example { requires transitive dependency; }",
                    automatic,options,Stage2Support.FEATURE);
        }
    }

    @Test void explicitAndMultiReleaseDescriptorsAreNotAutomatic() throws Exception {
        var classes=Stage2Support.compile(directory.resolve("dependency"),Map.of("module-info.java","module dependency { exports q; }",
                "q/Api.java","package q; public class Api {}"),List.of("--release","21"),List.of());
        var explicit=Stage2Support.pack(directory.resolve("explicit.jar"),classes);
        var multi=new java.util.TreeMap<>(classes);
        multi.put("META-INF/versions/21/module-info.class",multi.remove("module-info.class"));
        multi.put("META-INF/MANIFEST.MF","Manifest-Version: 1.0\r\nMulti-Release: true\r\nAutomatic-Module-Name: dependency\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var mr=Stage2Support.pack(directory.resolve("multi.jar"),multi);
        for(int release:List.of(21,Stage2Support.FEATURE))for(var jar:List.of(explicit,mr))
            compare("module example { requires transitive dependency; requires java.logging; }",jar,List.of("-Xlint:all"),release);
    }

    private void compare(String text,Path dependency,List<String> options,int release) throws Exception {
        var root=Files.createDirectories(directory.resolve("case-"+sequence++));
        var source=root.resolve("module-info.java");Files.writeString(source,text);
        var compiler=ToolProvider.getSystemJavaCompiler();
        var messages=new DiagnosticCollector<JavaFileObject>();
        var arguments=new ArrayList<>(List.of("-proc:none","-source",String.valueOf(release),"-Xlint:-options",
                "--module-path",dependency.toString(),"-d",Files.createDirectories(root.resolve("out")).toString()));
        arguments.addAll(options);
        try(var files=compiler.getStandardFileManager(messages,Locale.ROOT,null)) {
            assertThat(compiler.getTask(null,files,messages,arguments,null,files.getJavaFileObjects(source)).call()).isTrue();
        }
        var expected=messages.getDiagnostics().stream().map(d->new ResultRecord.Diagnostic(
                d.getKind()==Diagnostic.Kind.ERROR?0:d.getKind()==Diagnostic.Kind.WARNING || d.getKind()==Diagnostic.Kind.MANDATORY_WARNING?1:2,
                d.getStartPosition(),d.getEndPosition(),d.getCode(),d.getMessage(Locale.ROOT))).toList();
        try(var header=HeaderCompiler.compile(List.of(new HeaderCompiler.Source("module-info.java",source)),List.of(dependency),
                Stage2Support.JDK,release,options,Sha256.INSTANCE)) {
            assertThat(header.units.getFirst().moduleDiagnostics).as(text+" "+options+" "+dependency).isEqualTo(expected);
        }
    }
}

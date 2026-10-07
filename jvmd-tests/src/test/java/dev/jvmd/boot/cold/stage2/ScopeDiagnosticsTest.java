package dev.jvmd.boot.cold.stage2;

import dev.jvmd.boot.cold.stage3.Attribute;
import dev.jvmd.boot.cold.stage3.Diagnostics;
import dev.jvmd.boot.cold.stage3.Stage3;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.digests.Sha256;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.index.layer.local.*;
import dev.jvmd.index.layer.machine.ClassFacts;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import javax.tools.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-3")
class ScopeDiagnosticsTest {
    @TempDir Path dir;
    static Stream<Digest> digests() {return Stream.of(Sha256.INSTANCE,new Digests.Sha3());}

    @ParameterizedTest @MethodSource("digests")
    void scopeNotesAndPerFileDetailsMatchIndependentWholeScopeJavac(Digest digest) throws Exception {
        for(var options:List.of(List.<String>of(),List.of("-Xlint:deprecation"),List.of("-Xlint:unchecked"),List.of("-Xlint:all"),List.of("-nowarn"))) {
            for(boolean suppressSecond:List.of(false,true)) {
                String body="int old(){return new java.util.Date().getYear();} void raw(){java.util.List l=new java.util.ArrayList(); l.add(1);}";
                Stage2Support.write(dir,Map.of("m/src/main/java/p/A.java","package p; public class A {"+body+"}",
                        "m/src/main/java/p/B.java","package p; "+(suppressSecond?"@SuppressWarnings({\"deprecation\",\"unchecked\",\"rawtypes\"}) ":"")+"public class B {"+body+"}"));
                var model=ProjectModel.parse(Stage2Support.model(dir,new Stage2Support.Mod("m","g:m:1",List.of()).withOptions(options.toArray(String[]::new))));
                var tree=new ContentTree(digest);var store=Stage2Support.jdkOnly(digest).copy();
                new Stage2(digest,tree,Stage2Support.FEATURE,2,dir,ClassFacts::of).run(store,model);
                var descriptor=ModuleRecord.decode(store.get(LocalStore.moduleKey(Stage2.projectKey(digest,model),"m")));
                var policy=Attribute.Options.unprocessed(digest,descriptor,Stage2Support.JDK);
                var expected=nativeMessages(policy);
                var result=new Stage3(digest,tree,Stage2Support.FEATURE,2,dir).run(store,model);
                assertThat(result.scopes().get("m/main").diagnostics()).as(options+" suppressed="+suppressSecond).isEqualTo(expected);
            }
        }
    }

    private List<Diagnostics.Message> nativeMessages(Attribute.Options options) throws Exception {
        var messages=new ArrayList<Diagnostics.Message>();var compiler=ToolProvider.getSystemJavaCompiler();
        DiagnosticListener<JavaFileObject> listener=d->{
            String path=d.getSource()==null?null:dir.relativize(Path.of(d.getSource().toUri())).toString().replace('\\','/');
            messages.add(new Diagnostics.Message(path,new ResultRecord.Diagnostic(switch(d.getKind()) {
                case ERROR->0;case WARNING,MANDATORY_WARNING->1;default->2;
            },d.getStartPosition(),d.getEndPosition(),d.getCode(),d.getMessage(Locale.ROOT))));
        };
        try(var manager=compiler.getStandardFileManager(listener,Locale.ROOT,options.charset())) {
            manager.setLocationFromPaths(StandardLocation.CLASS_PATH,List.of());
            manager.setLocationFromPaths(StandardLocation.SOURCE_PATH,List.of());
            manager.setLocationFromPaths(StandardLocation.CLASS_OUTPUT,List.of(Files.createDirectories(dir.resolve("native"))));
            var inputs=new ArrayList<JavaFileObject>();
            for(var input:manager.getJavaFileObjectsFromPaths(List.of(dir.resolve("m/src/main/java/p/A.java"),dir.resolve("m/src/main/java/p/B.java"))))
                inputs.add(new ForwardingJavaFileObject<JavaFileObject>(input) {
                    @Override public String getName() {return Path.of(toUri()).getFileName().toString();}
                });
            var task=compiler.getTask(null,manager,listener,options.javac(),null,inputs);task.setLocale(Locale.ROOT);
            assertThat(task.call()).isTrue();
        }
        return messages;
    }
}

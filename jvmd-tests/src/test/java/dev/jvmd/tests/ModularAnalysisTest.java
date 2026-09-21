package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.2: named modules preserve javac visibility and in-memory source identity. */
@Tag("phase-4") @Tag("phase-6") @Tag("phase-9")
class ModularAnalysisTest {
    @TempDir Path root;
    @Test void attributesModuleDescriptorsAndUnsavedSourcesAgainstIndexedModuleJars() throws Exception {
        Path dependency=Files.createDirectories(root.resolve("dependency"));
        Path exported=Files.createDirectories(dependency.resolve("api")).resolve("Api.java");
        Path hidden=Files.createDirectories(dependency.resolve("internal")).resolve("Hidden.java");
        Path descriptor=dependency.resolve("module-info.java");
        Files.writeString(descriptor,"module fixture.dependency { exports api; }");
        Files.writeString(exported,"package api; public class Api { public static int value(){return 42;} }");
        Files.writeString(hidden,"package internal; public class Hidden {}");
        Path output=Files.createDirectories(root.resolve("classes"));
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"--release","25","-d",output.toString(),descriptor.toString(),exported.toString(),hidden.toString())).isZero();
        Path jar=root.resolve("dependency.jar");
        try(var stream=new java.util.jar.JarOutputStream(Files.newOutputStream(jar));var files=Files.walk(output)) {
            for(Path file:files.filter(Files::isRegularFile).sorted().toList()) {
                stream.putNextEntry(new java.util.jar.JarEntry(output.relativize(file).toString().replace(java.io.File.separatorChar,'/')));Files.copy(file,stream);stream.closeEntry();
            }
        }
        Path source=Files.createDirectories(root.resolve("source")),module=source.resolve("module-info.java");
        Files.writeString(module,"module fixture.application { requires fixture.dependency; }");
        Path main=Files.createDirectories(source.resolve("app")).resolve("Main.java");
        Files.writeString(main,"package app; class Main { int value(){return api.Api.value();} }");
        try(var pool=new CompilerPool()) {
            pool.configure("modules","25",List.of(jar),List.of(source),null,256L*1024*1024);
            for(int i=0;i<3;i++)for(Path file:List.of(module,main)) {
                var result=pool.query(file,Files.readString(file),2,(task,units,tier)->units.size());
                assertThat(result.tier()).isEqualTo(2);assertThat(result.warnings()).isEmpty();
                assertThat(result.diagnostics()).noneMatch(p->p.kind().equals("ERROR"));
            }
            Path unsaved=source.resolve("app/Unsaved.java");String text="package app; class Unsaved { int answer=api.Api.value(); }";
            pool.documents(Map.of(unsaved,text));
            var valid=pool.query(unsaved,text,2,(task,units,tier)->units.size());
            assertThat(valid.warnings()).isEmpty();assertThat(valid.diagnostics()).noneMatch(p->p.kind().equals("ERROR"));
            var inaccessible=pool.query(unsaved,"package app; class Unsaved { internal.Hidden hidden; }",2,(task,units,tier)->units.size());
            assertThat(inaccessible.warnings()).isEmpty();assertThat(inaccessible.diagnostics()).anyMatch(p->p.kind().equals("ERROR")&&p.code().equals("compiler.err.package.not.visible")&&p.message().contains("does not export"));
            assertThat(pool.status().get("faults")).isEqualTo(0L);
        }
    }
    @Test void unbuiltSourceModulesKeepTheirOwnIdentityAndObserveUnsavedDependencyChanges()throws Exception{
        Path library=Files.createDirectories(root.resolve("library/src/main/java"));
        Path application=Files.createDirectories(root.resolve("app/src/main/java"));
        Path api=Files.createDirectories(library.resolve("api")).resolve("Api.java");
        Path hidden=Files.createDirectories(library.resolve("internal")).resolve("Hidden.java");
        Files.writeString(library.resolve("module-info.java"),"module fixture.library { exports api; }");
        Files.writeString(application.resolve("module-info.java"),"module fixture.application { requires fixture.library; }");
        Files.writeString(api,"package api; public class Api { public static int value(){return 42;} }");
        Files.writeString(hidden,"package internal; public class Hidden {}");
        Path main=Files.createDirectories(application.resolve("app")).resolve("Main.java");
        String text="package app; class Main { int answer=api.Api.value(); }";Files.writeString(main,text);
        try(var pool=new CompilerPool()){
            pool.configure("unbuilt-modules","25",List.of(),List.of(application,library),null,256L*1024*1024);
            for(int i=0;i<3;i++){
                var result=pool.query(main,text,2,(task,units,tier)->units.size());
                assertThat(result.tier()).isEqualTo(2);assertThat(result.warnings()).isEmpty();assertThat(result.diagnostics()).noneMatch(p->p.kind().equals("ERROR"));
            }
            pool.documents(Map.of(api,"package api; public class Api { public static String value(){return \"changed\";} }"));pool.sourcesChanged();
            var changed=pool.query(main,text,2,(task,units,tier)->units.size());
            assertThat(changed.warnings()).isEmpty();assertThat(changed.diagnostics()).anyMatch(p->p.kind().equals("ERROR")&&p.code().equals("compiler.err.prob.found.req"));
            pool.documents(Map.of());pool.sourcesChanged();
            var forbidden=pool.query(main,"package app; class Main { internal.Hidden hidden; }",2,(task,units,tier)->units.size());
            assertThat(forbidden.warnings()).isEmpty();
            var expected=nativeErrors(main,"package app; class Main { internal.Hidden hidden; }",application,library);
            assertThat(expected).isNotEmpty();assertThat(forbidden.diagnostics().stream().filter(p->p.kind().equals("ERROR")).map(CompilerPool.Problem::code).toList()).containsExactlyElementsOf(expected);
            pool.documents(Map.of(library.resolve("module-info.java"),"module fixture.library { exports api; exports internal; }"));pool.sourcesChanged();
            var exported=pool.query(main,"package app; class Main { internal.Hidden hidden; }",2,(task,units,tier)->units.size());
            assertThat(exported.warnings()).isEmpty();assertThat(exported.diagnostics()).noneMatch(p->p.kind().equals("ERROR"));
            pool.documents(Map.of());pool.sourcesChanged();
            var hiddenAgain=pool.query(main,"package app; class Main { internal.Hidden hidden; }",2,(task,units,tier)->units.size());
            assertThat(hiddenAgain.warnings()).isEmpty();assertThat(hiddenAgain.diagnostics().stream().filter(p->p.kind().equals("ERROR")).map(CompilerPool.Problem::code).toList()).containsExactlyElementsOf(expected);
            assertThat(pool.status().get("faults")).isEqualTo(0L);
        }
        try(var files=Files.walk(root)){assertThat(files.filter(p->p.toString().endsWith(".class")).toList()).isEmpty();}
    }
    private static List<String> nativeErrors(Path main,String text,Path application,Path library)throws Exception{
        String original=Files.readString(main);Path output=Files.createTempDirectory("jvmd-native-module-analysis-");
        var compiler=javax.tools.ToolProvider.getSystemJavaCompiler();var diagnostics=new javax.tools.DiagnosticCollector<javax.tools.JavaFileObject>();
        try(var manager=compiler.getStandardFileManager(diagnostics,Locale.ROOT,java.nio.charset.StandardCharsets.UTF_8)){
            Files.writeString(main,text);manager.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT,List.of(output));
            manager.setLocationForModule(javax.tools.StandardLocation.MODULE_SOURCE_PATH,"fixture.application",List.of(application));
            manager.setLocationForModule(javax.tools.StandardLocation.MODULE_SOURCE_PATH,"fixture.library",List.of(library));
            var task=(com.sun.source.util.JavacTask)compiler.getTask(new java.io.StringWriter(),manager,diagnostics,List.of("--release","25","-proc:none"),null,manager.getJavaFileObjects(main));
            task.parse();task.analyze();
            return diagnostics.getDiagnostics().stream().filter(d->d.getKind()==javax.tools.Diagnostic.Kind.ERROR).map(javax.tools.Diagnostic::getCode).toList();
        }finally{Files.writeString(main,original);Files.delete(output);}
    }

}

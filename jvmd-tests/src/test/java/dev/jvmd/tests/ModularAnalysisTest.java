package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 4.2: named modules preserve javac visibility and in-memory source identity. */
@Tag("phase-4") @Tag("phase-9")
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
}

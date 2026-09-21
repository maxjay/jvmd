package dev.jvmd.tests;

import java.lang.module.ModuleDescriptor;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements 12.6: actual compiled descriptors and class references enforce the javac-internals boundary. */
@Tag("phase-1")
class ModuleArchitectureTest {
    @TempDir Path temp;
    @Test void compiledModuleRequiresMatchTheArchitecture() throws Exception {
        var expected=Map.of(
                "core",Set.of("java.base","java.management","com.fasterxml.jackson.databind"),
                "index",Set.of("java.base","dev.jvmd.core","java.compiler","jdk.compiler"),
                "analyzer",Set.of("java.base","dev.jvmd.core","dev.jvmd.index","java.compiler","jdk.compiler","java.management"),
                "resolver",Set.of("java.base","dev.jvmd.core"),
                "runtime",Set.of("java.base","dev.jvmd.core","jdk.jdi","java.compiler","jdk.compiler"),
                "mcp",Set.of("java.base","dev.jvmd.core"),
                "lsp",Set.of("java.base","dev.jvmd.core"),
                "dist",Set.of("java.base","dev.jvmd.core","dev.jvmd.index","dev.jvmd.analyzer","dev.jvmd.resolver","dev.jvmd.runtime","dev.jvmd.mcp","dev.jvmd.lsp","java.compiler","java.management"));
        for(var entry:expected.entrySet()) {
            Path classes=TestSupport.repo().resolve("jvmd-"+entry.getKey()+"/target/classes");
            ModuleDescriptor descriptor;
            try(var input=Files.newInputStream(classes.resolve("module-info.class"))) { descriptor=ModuleDescriptor.read(input); }
            assertThat(descriptor.name()).isEqualTo("dev.jvmd."+entry.getKey());assertThat(descriptor.isAutomatic()).isFalse();assertThat(descriptor.isOpen()).isFalse();
            assertThat(descriptor.requires()).extracting(ModuleDescriptor.Requires::name).containsExactlyInAnyOrderElementsOf(entry.getValue());
            assertThat(descriptor.opens()).allMatch(open->open.isQualified()&&open.targets().equals(Set.of("com.fasterxml.jackson.databind")));
            if(!entry.getKey().equals("analyzer"))try(var files=Files.walk(classes)) {
                for(Path file:files.filter(p->p.toString().endsWith(".class")).toList())
                    assertThat(new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.ISO_8859_1)).as(file.toString()).doesNotContain("com/sun/tools/javac/");
            }
        }
    }
    @Test void requiringThePublicCompilerModuleDoesNotGrantJavacInternals() throws Exception {
        Path source=Files.createDirectories(temp.resolve("source")),out=Files.createDirectories(temp.resolve("classes"));
        Path descriptor=source.resolve("module-info.java"),client=source.resolve("guard/Probe.java");Files.createDirectories(client.getParent());
        Files.writeString(descriptor,"module dev.jvmd.guard { requires jdk.compiler; }");
        Files.writeString(client,"package guard; public class Probe { com.sun.tools.javac.api.JavacTaskPool pool; }");
        var errors=new java.io.ByteArrayOutputStream();
        int result=javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,errors,"-proc:none","-d",out.toString(),descriptor.toString(),client.toString());
        assertThat(result).isNotZero();assertThat(errors.toString()).contains("does not export","com.sun.tools.javac.api");
    }
}

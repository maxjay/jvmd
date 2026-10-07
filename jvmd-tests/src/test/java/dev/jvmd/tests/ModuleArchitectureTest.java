package dev.jvmd.tests;

import java.lang.module.ModuleDescriptor;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Actual compiled descriptors and class references enforce the analyzer and cold-boot javac boundaries. */
@Tag("phase-1")
class ModuleArchitectureTest {
    @TempDir Path temp;
    @Test void compiledModuleRequiresMatchTheArchitecture() throws Exception {
        var expected=Map.of(
                "core",Set.of("java.base","java.management","jdk.jfr","jdk.management","com.fasterxml.jackson.databind"),
                "index",Set.of("java.base","dev.jvmd.core","java.compiler","jdk.compiler"),
                "index-rocks",Set.of("java.base","dev.jvmd.index","rocksdbjni"),
                "boot",Set.of("java.base","dev.jvmd.core","dev.jvmd.index","dev.jvmd.index.rocks","java.compiler","jdk.compiler","java.logging","java.instrument"),
                "analyzer",Set.of("java.base","dev.jvmd.core","dev.jvmd.index","java.compiler","jdk.compiler","java.management"),
                "resolver",Set.of("java.base","dev.jvmd.core"),
                "runtime",Set.of("java.base","dev.jvmd.core","jdk.jdi","java.compiler","jdk.compiler"),
                "mcp",Set.of("java.base","dev.jvmd.core"),
                "lsp",Set.of("java.base","dev.jvmd.core"),
                "dist",Set.of("java.base","dev.jvmd.core","dev.jvmd.index","dev.jvmd.index.rocks","dev.jvmd.boot","dev.jvmd.analyzer","dev.jvmd.resolver","dev.jvmd.runtime","dev.jvmd.mcp","dev.jvmd.lsp","java.compiler","java.management"));
        for(var entry:expected.entrySet()) {
            Path classes=TestSupport.repo().resolve("jvmd-"+entry.getKey()+"/target/classes");
            ModuleDescriptor descriptor;
            try(var input=Files.newInputStream(classes.resolve("module-info.class"))) { descriptor=ModuleDescriptor.read(input); }
            assertThat(descriptor.name()).isEqualTo("dev.jvmd."+entry.getKey().replace('-','.'));assertThat(descriptor.isAutomatic()).isFalse();assertThat(descriptor.isOpen()).isFalse();
            assertThat(descriptor.requires()).extracting(ModuleDescriptor.Requires::name).containsExactlyInAnyOrderElementsOf(entry.getValue());
            assertThat(descriptor.opens()).allMatch(open->open.isQualified()&&open.targets().equals(Set.of("com.fasterxml.jackson.databind")));
            try(var files=Files.walk(classes)) {
                for(Path file:files.filter(p->p.toString().endsWith(".class")).toList()) {
                    String type=classes.relativize(file).toString().replace('\\','/').replaceFirst("\\.class$", "");
                    if(!compilerInternalsAllowed(entry.getKey(),type))
                        assertThat(new String(Files.readAllBytes(file),java.nio.charset.StandardCharsets.ISO_8859_1)).as(file.toString()).doesNotContain("com/sun/tools/javac/");
                }
            }
        }
    }

    /** Exact compiler adapters: header entry/lint, annotation positions, named scopes, processor views and body pool. No package-wide grant. */
    static boolean compilerInternalsAllowed(String module, String type) {
        int nested=type.indexOf('$');
        String owner=nested<0?type:type.substring(0,nested);
        return module.equals("analyzer")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage2/HeaderCompiler")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage2/ModuleLint")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage2/ProcessorTrees")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage2/ProcessorSourceQueries")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage2/ProcessorSourceTypes")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage2/ProcessorSourcePackages")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage3/Pool")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage3/NativeReaderAgent")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage3/NativeReaderCapture")
                || module.equals("boot")&&owner.equals("dev/jvmd/boot/cold/stage3/NativeReaderTap")
                || module.equals("index")&&owner.equals("dev/jvmd/index/layer/local/SourceFacts")
                || module.equals("index")&&owner.equals("dev/jvmd/index/layer/local/NamedMembers");
    }

    @Test void coldBootCompilerAccessIsLimitedToTheCompilerAdapters() {
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage2/HeaderCompiler$SourceObject")).isTrue();
        assertThat(compilerInternalsAllowed("index","dev/jvmd/index/layer/local/SourceFacts")).isTrue();
        assertThat(compilerInternalsAllowed("index","dev/jvmd/index/layer/local/NamedMembers")).isTrue();
        assertThat(compilerInternalsAllowed("index","dev/jvmd/index/layer/local/BodyCollector")).isFalse();
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage2/ModuleLint")).isTrue();
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage2/ModuleLintExtra")).isFalse();
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage2/ProcessorTrees")).isTrue();
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage2/ProcessorSourceQueries")).isTrue();
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage2/ProcessorSourceTypes")).isTrue();
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage2/ProcessorSourcePackages")).isTrue();
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage2/ModuleJob")).isFalse();
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage3/Pool$Worker")).isTrue();
        assertThat(compilerInternalsAllowed("boot","dev/jvmd/boot/cold/stage3/Attribute")).isFalse();
        assertThat(compilerInternalsAllowed("index","dev/jvmd/index/layer/local/SourceFactsExtra")).isFalse();
        assertThat(compilerInternalsAllowed("core","dev/jvmd/index/layer/local/SourceFacts")).isFalse();
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

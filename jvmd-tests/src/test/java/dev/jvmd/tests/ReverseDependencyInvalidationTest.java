package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 4: changed source invalidates dependents without eager attribution. */
@Tag("phase-4")
class ReverseDependencyInvalidationTest {
    @TempDir Path root;
    @Test void persistedIndexInvalidationIsConsumedBeforeServingCachedDiagnostics()throws Exception{
        Path source=Files.writeString(root.resolve("Revision.java"),"class Revision {}");
        var revision=new java.util.concurrent.atomic.AtomicLong();
        var sink=new dev.jvmd.index.ArtifactGenerationSink(){
            public void publish(dev.jvmd.index.ArtifactIndexFormat.ArtifactData facts,Set<String> refs){}
            public long semanticRevision(Path file){return revision.get();}
        };
        try(var index=new dev.jvmd.index.IndexService(root.resolve("index.db"),root.resolve("repo"),sink);var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of()),index,256L*1024*1024);
            var documents=new dev.jvmd.core.Documents();analyzer.documents(documents);
            analyzer.diagnostics(source,documents);assertThat(analyzer.cachedDiagnostics(source,documents)).isNotNull();
            revision.incrementAndGet();assertThat(analyzer.cachedDiagnostics(source,documents)).isNull();
            analyzer.diagnostics(source,documents);assertThat(analyzer.cachedDiagnostics(source,documents)).isNotNull();
        }
    }
    @Test void aDependencyChangeIsObservedOnlyWhenTouched()throws Exception{
        Path dependency=root.resolve("Dependency.java"),use=root.resolve("Use.java");
        Files.writeString(dependency,"class Dependency { int value() { return 1; } }");
        String text="class Use { Dependency dependency; int use() { return dependency.value(); } }";
        Files.writeString(use,text);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            analyzer.overview(use,text,2,100,0);
            var first=analyzer.bindings(use,text,null);
            assertThat(first.tier()).isEqualTo(2);assertThat(first.warnings()).isEmpty();assertThat(first.diagnostics()).isEmpty();
            assertThat(first.result().dependencies()).contains(dependency);
            long queries=((Number)analyzer.status().get("queries")).longValue();
            Files.writeString(dependency,"class Dependency { String value() { return \"changed\"; } }");
            analyzer.changed(dependency);
            assertThat(((Number)analyzer.status().get("queries")).longValue()).isEqualTo(queries);
            var next=analyzer.bindings(use,text,null);
            assertThat(next.diagnostics()).anyMatch(d->d.code().startsWith("compiler.err.prob.found.req"));
            assertThat(next.result().symbols().values()).anyMatch(s->s.get("name").equals("value")&&s.get("signature").toString().contains("java.lang.String"));
            assertThat(((Number)analyzer.status().get("queries")).longValue()).isEqualTo(queries+1);
        }
    }
    @Test void deletingASourceInvalidatesKnownDependantsOnNextTouch()throws Exception{
        Path dependency=root.resolve("Dependency.java"),use=root.resolve("Use.java");
        Files.writeString(dependency,"class Dependency { int value(){return 1;} }");
        String text="class Use { int use(){return new Dependency().value();} }";Files.writeString(use,text);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            assertThat(analyzer.bindings(use,text,null).diagnostics()).isEmpty();
            Files.delete(dependency);
            assertThat(analyzer.bindings(use,text,null).diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));
        }
    }
    @Test void compiledOutputCannotHideAContentChangeWithPreservedMtime()throws Exception{
        Path dependency=root.resolve("Dependency.java"),use=root.resolve("Use.java"),classes=Files.createDirectories(root.resolve("classes"));
        Files.writeString(dependency,"class Dependency { int value() { return 1; } }");
        String text="class Use { int use() { return new Dependency().value(); } }";Files.writeString(use,text);
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",classes.toString(),dependency.toString(),use.toString())).isZero();
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(classes),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            assertThat(analyzer.bindings(use,text,null).diagnostics()).isEmpty();
            var stamp=Files.getLastModifiedTime(dependency);Files.writeString(dependency,"class Dependency { int other() { return 1; } }");Files.setLastModifiedTime(dependency,stamp);
            var next=analyzer.bindings(use,text,null);assertThat(next.warnings()).isEmpty();assertThat(next.diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));
        }
    }
    @Test void statPreservingChangesInvalidateCachedBindings()throws Exception{
        Path dependency=root.resolve("Dependency.java"),use=root.resolve("Use.java");Files.writeString(dependency,"class Dependency { int value() { return 1; } }");
        String text="class Use { int use() { return new Dependency().value(); } }";Files.writeString(use,text);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,256L*1024*1024);
            assertThat(analyzer.bindings(use,text,null).diagnostics()).isEmpty();
            var stamp=Files.getLastModifiedTime(dependency);Files.writeString(dependency,"class Dependency { int other() { return 1; } }");Files.setLastModifiedTime(dependency,stamp);
            var next=analyzer.bindings(use,text,null);
            assertThat(next.diagnostics()).anyMatch(d->d.code().contains("cant.resolve"));
        }
    }
}

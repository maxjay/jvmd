package dev.jvmd.index;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * Which package a unit's attributed memo binds as "its own package" when a comment containing
 * {@code package com.old;} comes before the real declaration. {@code AttributedMemos.consultedPackages}
 * takes the first regex match over the raw text, so the certificate binds the comment's package.
 * Reported, not fixed yet: the first test pins today's binding, the second states the consequence.
 */
class PackageDeclarationCertificateTest {
    @TempDir Path root;
    private static final String GAV="g:app:1";

    private Path sources()throws Exception{return Files.createDirectories(root.resolve("app/src/main/java"));}
    private static void write(Path root,String relative,String text)throws Exception{
        Path file=root.resolve(relative);Files.createDirectories(file.getParent());Files.writeString(file,text);
    }
    private Analyzer analyzer()throws Exception{
        Path sources=sources();var analyzer=new Analyzer(new FileStateRegistry());
        analyzer.configure(new Analyzer.Context(GAV,"25",List.of(),List.of(sources),"reactor:"+GAV+":main",
                Map.of(root.resolve("app").toString(),GAV,sources.toString(),GAV),List.of("--release","25"),
                Set.of(),List.of(),List.of(sources),true,"",Processing.NONE),null,256L*1024*1024);
        analyzer.documents(new Documents(new FileStateRegistry()));analyzer.memos(new SemanticMemoStore(root.resolve("memo")));
        return analyzer;
    }
    private static long queries(Analyzer analyzer){return ((Number)analyzer.status().get("queries")).longValue();}
    record Round(boolean compiled,String diagnostics) { }
    /** One process lifetime: diagnostics for every unit of the project, then A's outcome. */
    private Round diagnose(String unit)throws Exception{
        try(var analyzer=analyzer()){
            Round result=null;
            List<Path> files;try(var walk=Files.walk(sources())){files=walk.filter(path->path.toString().endsWith(".java")).sorted().toList();}
            for(Path file:files){
                long before=queries(analyzer);var envelope=analyzer.diagnostics(file,Files.readString(file));
                if(file.equals(sources().resolve(unit)))result=new Round(queries(analyzer)>before,String.valueOf(envelope.result()));
            }
            analyzer.awaitMemoWrites();
            System.out.println("attributed_memo: "+analyzer.status().get("attributed_memo"));
            return result;
        }
    }
    /** NAMESPACE keys of every record in the store. */
    private Set<String> namespaceKeys()throws Exception{
        var keys=new TreeSet<String>();
        try(var walk=Files.walk(root.resolve("memo"))){
            for(Path file:walk.filter(path->path.toString().endsWith(".memo")).toList())
                for(var dependency:SemanticMemoStore.decode(Files.readAllBytes(file)).certificate().dependencies().dependencies())
                    if(dependency.key().domain()==QueryProof.Domain.NAMESPACE)keys.add(dependency.key().value());
        }
        return keys;
    }
    private void project()throws Exception{
        write(sources(),"other/Thing.java","package other; public class Thing { public int size(){ return 1; } }");
        // The real package is p; a comment above the declaration mentions another package.
        write(sources(),"p/A.java","/* Moved here from package com.old; see the migration notes. */\npackage p;\n\n"
                +"import other.*;\n\nclass A { int f(){ return new Thing().size(); } }\n");
    }

    @Test void theCertificateBindsThePackageNamedInTheCommentNotTheDeclaredOne()throws Exception{
        project();
        var cold=diagnose("p/A.java");
        assertThat(cold.diagnostics()).doesNotContain("ERROR");
        var keys=namespaceKeys();
        System.out.println("NAMESPACE keys of A's record: "+keys);
        assertThat(keys).as("today's binding (the defect): the comment's package").anyMatch(key->key.endsWith("|com.old"));
        assertThat(keys).as("today's binding (the defect): the declared package is not bound").noneMatch(key->key.endsWith("|p"));
        assertThat(keys).as("the star-imported package is bound").anyMatch(key->key.endsWith("|other"));
    }

    @Disabled("Known defect, reported and not fixed yet: consultedPackages reads the package from a comment")
    @Test void aTypeAddedToTheDeclaredPackageInvalidatesTheRecord()throws Exception{
        project();diagnose("p/A.java");
        // p.Thing now shadows the star-imported other.Thing, and has no size().
        write(sources(),"p/Thing.java","package p; public class Thing { }");
        var restart=diagnose("p/A.java");
        assertThat(restart.compiled()).as("A must be recompiled: its own package gained a top-level type").isTrue();
        assertThat(restart.diagnostics()).contains("cant.resolve");
    }

    /** What the disabled test would see today: the stale record is restored. */
    @Test void todayATypeAddedToTheDeclaredPackageRestoresTheStaleResult()throws Exception{
        project();diagnose("p/A.java");
        write(sources(),"p/Thing.java","package p; public class Thing { }");
        var restart=diagnose("p/A.java");
        System.out.println("after adding p/Thing.java: compiled="+restart.compiled()+" diagnostics="+restart.diagnostics());
        assertThat(restart.compiled()).as("today (the defect): restored without javac").isFalse();
        assertThat(restart.diagnostics()).as("today (the defect): the restored result misses the new error").doesNotContain("cant.resolve");
    }
}

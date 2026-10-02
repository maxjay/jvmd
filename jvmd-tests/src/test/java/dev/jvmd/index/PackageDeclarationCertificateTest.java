package dev.jvmd.index;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.analyzer.NamespaceResolutionProofs;
import dev.jvmd.analyzer.Processing;
import dev.jvmd.core.Documents;
import dev.jvmd.core.FileStateRegistry;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.*;

/**
 * An attributed memo binds the package and imports javac actually used, read from the attributed unit's
 * syntax tree, never from the raw text: a comment mentioning {@code package com.old;} must not become the
 * bound package (a regex over the text once did, so adding a type to the real package restored a stale
 * clean result).
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

    @Test void theCertificateBindsTheDeclaredPackageNotTheOneNamedInAComment()throws Exception{
        project();
        var cold=diagnose("p/A.java");
        assertThat(cold.diagnostics()).doesNotContain("ERROR");
        var keys=namespaceKeys();
        assertThat(keys).as("the declared package is bound").anyMatch(key->key.endsWith("|p"));
        assertThat(keys).as("the star-imported package is bound").anyMatch(key->key.endsWith("|other"));
        assertThat(keys).as("a package named only in a comment is not").noneMatch(key->key.endsWith("|com.old"));
    }

    @Test void aTypeAddedToTheDeclaredPackageInvalidatesTheRecord()throws Exception{
        project();diagnose("p/A.java");
        // p.Thing now shadows the star-imported other.Thing, and has no size().
        write(sources(),"p/Thing.java","package p; public class Thing { }");
        var restart=diagnose("p/A.java");
        assertThat(restart.compiled()).as("A must be recompiled: its own package gained a top-level type").isTrue();
        assertThat(restart.diagnostics()).contains("cant.resolve");
    }

    /**
     * A record of the previous function version (v5, whose certificates may name the wrong
     * package) is never consulted, even with a valid checksum. The function version is part of the
     * static key identity, so the v6 lookup cannot reach a v5 record. Unaffected functions stay usable:
     * the S0 namespace records written before are restored, not reparsed.
     */
    @Test void previousVersionRecordsMissAndOtherFunctionsStayUsable()throws Exception{
        project();diagnose("p/A.java");
        var store=new SemanticMemoStore(root.resolve("memo"));int rewritten=0;
        try(var walk=Files.walk(root.resolve("memo"))){
            for(Path file:walk.filter(path->path.toString().endsWith(".memo")).toList()){
                var record=SemanticMemoStore.decode(Files.readAllBytes(file));
                if(!record.key().function().name().equals("attributed-diagnostics"))continue;
                assertThat(record.key().function().version()).isEqualTo(6);
                var old=new SemanticMemoStore.StaticKey(new SemanticMemoStore.Function("attributed-diagnostics",5),record.key().staticInputs());
                store.put(new SemanticMemoStore.MemoRecord(old,record.certificate(),record.coverage(),record.completeness(),record.result()));
                Files.delete(file);rewritten++;
            }
        }
        assertThat(rewritten).as("A's and Thing's records").isGreaterThanOrEqualTo(2);
        try(var analyzer=analyzer()){
            for(String unit:List.of("other/Thing.java","p/A.java")){
                Path file=sources().resolve(unit);
                long before=queries(analyzer);analyzer.diagnostics(file,Files.readString(file));
                assertThat(queries(analyzer)).as("a v5 record with a valid checksum is not restored: "+unit).isGreaterThan(before);
            }
            analyzer.awaitMemoWrites();
            var namespaces=(Map<?,?>)((Map<?,?>)analyzer.status().get("attributed_memo")).get("source_namespaces");
            assertThat(((Number)namespaces.get("memo_hits")).longValue()).as("S0 namespace records are still reused").isPositive();
        }
    }

    private static NamespaceResolutionProofs.Header header(String source){return NamespaceResolutionProofs.header(source);}

    @Test void theHeaderIsWhatJavacParsesNotWhatTheTextMentions(){
        var block=header("/* package com.old; import x.*; */ package p; import other.*; class A {}");
        assertThat(block.packageName()).isEqualTo("p");assertThat(block.onDemandImports()).containsExactly("other");
        var line=header("// package com.old;\npackage p;\nclass A {}");
        assertThat(line.packageName()).isEqualTo("p");
        var javadoc=header("/** package com.old; */\npackage p;\nclass A {}");
        assertThat(javadoc.packageName()).isEqualTo("p");
        var string=header("package p; class A { String s=\"package com.old; import q.*;\"; }");
        assertThat(string.packageName()).isEqualTo("p");assertThat(string.onDemandImports()).isEmpty();
        var textBlock=header("package p; class A { String s=\"\"\"\n    import static z.Z.*;\n    \"\"\"; }");
        assertThat(textBlock.staticImports()).isEmpty();
        // Unicode escapes are translated before lexing: this is "package p;" and "import q.*;".
        var escaped=header("\\u0070ackage p;\nimport q.\\u002a;\nclass A {}");
        assertThat(escaped.packageName()).isEqualTo("p");assertThat(escaped.onDemandImports()).containsExactly("q");
        assertThat(header("class A {}").packageName()).isEmpty();
    }

    @Test void explicitStarAndStaticImportsAreSeparated(){
        var value=header("package p;\nimport a.One;\nimport b.*;\nimport static c.Outer.Inner.member;\nimport static d.Util.*;\n"
                +"class A {}\nclass B { class Nested {} }\n");
        assertThat(value.singleImports()).containsExactly("a.One");
        assertThat(value.onDemandImports()).containsExactly("b");
        assertThat(value.staticImports()).containsExactly("c.Outer.Inner.member","d.Util.*");
        assertThat(value.staticImportTypes()).containsExactly("c.Outer.Inner","d.Util");
        assertThat(value.consultedPackages()).containsExactly("b","p");
        assertThat(value.complete()).isTrue();
    }

    @Test void incompleteSourceKeepsAWellFormedHeaderAndAnErroneousHeaderIsIncomplete(){
        var body=header("package p;\nimport q.*;\nclass A { int f( { return }\n");
        assertThat(body.packageName()).isEqualTo("p");assertThat(body.onDemandImports()).containsExactly("q");assertThat(body.complete()).isTrue();
        assertThat(header("package p;\nimport q.;\nclass A {}").complete()).isFalse();
        assertThat(header("package p;\nimport module java.base;\nclass A {}").complete()).as("module imports are not modelled").isFalse();
    }

    @Test void plansFromIncompleteHeadersOrStaticImportsAreNeverPrecise(){
        assertThat(NamespaceResolutionProofs.plan("package p; import q.*; class A {}","Thing",null).precise()).isTrue();
        assertThat(NamespaceResolutionProofs.plan("package p; import static q.U.*; class A {}","Thing",null).precise()).isFalse();
        assertThat(NamespaceResolutionProofs.plan("package p; import module java.base; class A {}","Thing",null).precise()).isFalse();
        var commented=NamespaceResolutionProofs.plan("/* package com.old; */ package p; import other.*; class A {}","Thing",null);
        assertThat(commented.domains()).containsExactly("p.Thing","java.lang.Thing","other.Thing");
    }
}

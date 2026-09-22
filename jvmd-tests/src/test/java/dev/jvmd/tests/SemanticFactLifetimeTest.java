package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.*;
import dev.jvmd.dist.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class SemanticFactLifetimeTest {
    @TempDir Path root;
    @Test void zeroDecodedBudgetRetainsFactsAndOldReadRevisionAcrossEditsAndDeletion()throws Exception {
        Path api=root.resolve("Api.java"),use=root.resolve("Use.java"),other=root.resolve("Other.java");
        Files.writeString(api,"class Api { static int a(){return 1;} static int b(){return 2;} }");
        Files.writeString(use,"class Use { int value(){return Api.a();} }");Files.writeString(other,"class Other {}");
        var files=new ArrayList<>(List.of(api,use,other));var loaded=new ArrayList<Path>();var documents=new Documents();
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,64L*1024*1024);
            WorkspaceBindings.Loader loader=(file,text)->{loaded.add(file);return analyzer.bindings(file,text,null);};
            var old=cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents,0,loader);assertThat(loaded).hasSize(3);loaded.clear();
            var method=old.lookup("Use/value()").getFirst().get("scip").toString();
            assertThat(old.adjacent(Set.of(method),true)).anyMatch(e->e.dst().endsWith("Api#a()."));
            var warm=cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents,0,loader);assertThat(warm).isNotSameAs(old);assertThat(warm.revision()).isSameAs(old.revision());assertThat(loaded).isEmpty();
            Files.writeString(use,"class Use { int value(){return Api.b();} }");
            var changed=cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents,0,loader);assertThat(loaded).containsExactly(use);loaded.clear();
            assertThat(changed.adjacent(Set.of(method),true)).anyMatch(e->e.dst().endsWith("Api#b().")).noneMatch(e->e.dst().endsWith("Api#a()."));
            assertThat(old.adjacent(Set.of(method),true)).anyMatch(e->e.dst().endsWith("Api#a().")).noneMatch(e->e.dst().endsWith("Api#b()."));
            Files.delete(use);files.remove(use);var deleted=cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents,0,loader);
            assertThat(deleted.symbol(method)).isNull();assertThat(deleted.adjacent(Set.of(method),true)).isEmpty();assertThat(loaded).isEmpty();
            assertThat(cache.status()).containsEntry("fragment_files",2).containsEntry("decoded_estimated_bytes",0L).doesNotContainKey("serialized_bytes");
        }
    }
    @Test void indexedNamePagesReturnEveryDeclarationOnce()throws Exception {
        Path file=root.resolve("Types.java");Files.writeString(file,java.util.stream.IntStream.range(0,20).mapToObj(i->"class Type"+i+" {}").collect(java.util.stream.Collectors.joining("\n")));
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,64L*1024*1024);
            var view=cache.get(()->List.of(file),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),new Documents(),0,(path,text)->analyzer.bindings(path,text,null));
            var found=new ArrayList<String>();String cursor=null;int pages=0;
            do{var page=view.find("Type",true,Set.of("class"),3,cursor);assertThat(page.symbols()).hasSizeLessThanOrEqualTo(3);
                for(var symbol:page.symbols())found.add(symbol.get("name").toString());cursor=page.cursor();assertThat(++pages).isLessThanOrEqualTo(7);
            }while(cursor!=null);
            assertThat(found).containsExactlyInAnyOrderElementsOf(java.util.stream.IntStream.range(0,20).mapToObj(i->"Type"+i).toList());
        }
    }
    @Test void abortedFullRebuildRetainsCommittedOwnersForRetry()throws Exception {
        Path a=root.resolve("A.java"),b=root.resolve("B.java");
        Files.writeString(a,"class A {}");Files.writeString(b,"class B {}");
        var files=new ArrayList<>(List.of(a,b));var documents=new Documents();
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,64L*1024*1024);
            WorkspaceBindings.Loader loader=(path,text)->analyzer.bindings(path,text,null);
            try(var old=cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents,0,loader)){
                String removed=old.find("B",false,Set.of(),10,null).symbols().getFirst().get("scip").toString();
                Files.delete(b);files.remove(b);
                assertThatThrownBy(()->cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("2",List.of(root),List.of(),List.of("--release","25")),documents,0,(path,text)->{throw new java.io.IOException("loader failed");})).isInstanceOf(java.io.IOException.class);
                assertThat(cache.status()).containsEntry("fragment_files",2);
                try(var retry=cache.get(()->files,new dev.jvmd.core.CompilerInputs.Configuration("2",List.of(root),List.of(),List.of("--release","25")),documents,0,loader)){
                    assertThat(retry.symbol(removed)).isNull();
                    assertThat(retry.find("B",false,Set.of(),10,null).symbols()).isEmpty();
                    assertThat(retry.find("A",false,Set.of(),10,null).symbols()).hasSize(1);
                    assertThat(old.symbol(removed)).isNotNull();
                    assertThat(cache.status()).containsEntry("fragment_files",1);
                }
            }
        }
    }
    @Test void closingCallerLeaseAllowsIndependentReuseThroughEveryCacheHitPath()throws Exception {
        Path file=root.resolve("A.java");Files.writeString(file,"class A {}");var documents=new Documents();int[] loads={0};
        try(var analyzer=new Analyzer();var cache=new WorkspaceBindings()){
            analyzer.configure(new Analyzer.Context("test:app:1","25",List.of(),List.of(root),"1",Map.of(root.toUri().toString(),"test:app:1")),null,64L*1024*1024);
            WorkspaceBindings.BatchLoader loader=sources->{loads[0]++;var results=new LinkedHashMap<Path,CompilerPool.Outcome<Bindings.Snapshot>>();for(var entry:sources.entrySet())results.put(entry.getKey(),analyzer.bindings(entry.getKey(),entry.getValue(),null));return results;};
            var first=cache.getBatch(()->List.of(file),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents,0,loader);
            Object revision=first.revision();first.close();first.close();
            assertThatThrownBy(()->first.find("A",false,Set.of(),10,null)).isInstanceOf(IllegalStateException.class);
            try(var fast=cache.getBatch(()->List.of(file),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents,0,loader)){
                try(var checked=cache.getBatch(()->List.of(file),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents,0,loader)){
                    assertThat(checked.revision()).isSameAs(revision);
                    assertThat(checked.find("A",false,Set.of(),10,null).symbols()).hasSize(1);
                }
                assertThat(fast.find("A",false,Set.of(),10,null).symbols()).hasSize(1);
            }
            try(var checked=cache.peek(List.of(file),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents)){
                assertThat(checked.find("A",false,Set.of(),10,null).symbols()).hasSize(1);
            }
            try(var fast=cache.peek(()->List.of(file),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),documents)){
                assertThat(fast.find("A",false,Set.of(),10,null).symbols()).hasSize(1);
                assertThat(fast.revision()).isSameAs(revision);
            }
            assertThat(loads[0]).isEqualTo(1);
        }
    }
    @Test void incompleteObservationIsRetriedInsteadOfBecomingAuthoritative()throws Exception {
        Path file=root.resolve("A.java");Files.writeString(file,"class A {}");int[] loads={0};
        try(var cache=new WorkspaceBindings()){
            WorkspaceBindings.Loader loader=(path,text)->{loads[0]++;return new CompilerPool.Outcome<>(1,new Bindings.Snapshot(Map.of(),List.of(),List.of(),Set.of()),List.of(),List.of("analyzer_fault: fixture"));};
            cache.get(()->List.of(file),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),new Documents(),0,loader);cache.get(()->List.of(file),new dev.jvmd.core.CompilerInputs.Configuration("1",List.of(root),List.of(),List.of("--release","25")),new Documents(),0,loader);
            assertThat(loads[0]).isEqualTo(2);
        }
    }
}

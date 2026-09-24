package dev.jvmd.tests;

import dev.jvmd.analyzer.Analyzer;
import dev.jvmd.core.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

@Tag("phase-4")
class CompletionPrefixCacheTest {
    @TempDir Path root;
    private Analyzer.Context context(){return new Analyzer.Context("fixture:prefix:1","25",List.of(),List.of(root),"prefix",Map.of());}
    private static String text(String prefix){return "class Use { Object call(Api api){return api."+prefix+"();} }";}
    private static JsonNode complete(Analyzer analyzer,Path file,String text,String prefix)throws Exception{
        var answer=analyzer.completion(file,text,0,text.indexOf("api."+prefix)+4+prefix.length(),100,0);
        assertThat(answer.warnings()).isEmpty();return Json.MAPPER.valueToTree(answer.result());
    }
    @Test void prefixNarrowingReusesDetachedCandidatesAndKeepsTheCurrentEditRange()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} int getPetCount(){return 2;} int other(){return 3;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("g"));var documents=new Documents();documents.open(file,text("g"),1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);int version=1;
            for(String prefix:List.of("g","ge","get","getP","getPe","getPet","getPets")){
                String text=text(prefix);documents.change(file,++version,List.of(new Documents.Change(null,text)));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
                var result=complete(analyzer,file,text,prefix);
                try(var fresh=new Analyzer()){
                    fresh.configure(context(),null,256L*1024*1024);fresh.documents(documents);
                    assertThat(result).isEqualTo(complete(fresh,file,text,prefix));
                }
                assertThat(result.path("range").path("end").path("character").asInt()-result.path("range").path("start").path("character").asInt()).isEqualTo(prefix.length());
            }
            var status=analyzer.status();
            @SuppressWarnings("unchecked") var resident=(Map<String,Object>)status.get("resident_semantic_state");
            assertThat(((Number)resident.get("semantic_facts")).longValue()).isPositive();
            assertThat(((Number)resident.get("semantic_tree_range_entries_read")).longValue()).isPositive();

            // An unchanged repeated request reads the detached document/global semantic state.
            long queries=((Number)status.get("queries")).longValue();
            complete(analyzer,file,text("getPets"),"getPets");
            assertThat(((Number)analyzer.status().get("queries")).longValue()).isEqualTo(queries);

            // Backspacing broadens the ordered range without falling back to javac member discovery.
            String wider=text("");documents.change(file,++version,List.of(new Documents.Change(null,wider)));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertThat(complete(analyzer,file,wider,"").path("items").findValuesAsText("name")).contains("other");
        }
    }
    @Test void otherUnsavedAndTimestampPreservingSourceChangesInvalidateTheCache()throws Exception{
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("get"));var documents=new Documents();documents.open(file,text("get"),1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("label").toString()).contains("int");
            documents.open(api,"class Api { String getPets(){return \"x\";} }",1);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("label").toString()).contains("java.lang.String");
            documents.close(api);var time=Files.getLastModifiedTime(api);Files.writeString(api,"class Api { int getElse(){return 1;} }");Files.setLastModifiedTime(api,time);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getElse").doesNotContain("getPets");
        }
    }
    @Test void timestampPreservingClosedSourceEditsInvalidateWatcherBackedCandidates()throws Exception{
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("get"));
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("label").toString()).contains("int");
            var time=Files.getLastModifiedTime(api);Files.writeString(api,"class Api { String getPets(){return \"x\";} }");Files.setLastModifiedTime(api,time);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("label").toString()).contains("java.lang.String");
            assertThat(analyzer.status()).containsEntry("source_catalog_precise",1L);
        }
    }
    @Test void changedReleaseAndNewSourceNamesCannotReuseOldCandidates()throws Exception{
        String source="class Use { Object call(String api){return api.strip();} }";
        Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(new Analyzer.Context("fixture:prefix:1","8",List.of(),List.of(root),"prefix",Map.of()),null,256L*1024*1024);
            assertThat(complete(analyzer,file,source,"strip").path("items").findValuesAsText("name")).doesNotContain("strip");
            analyzer.configure(new Analyzer.Context("fixture:prefix:1","11",List.of(),List.of(root),"prefix",Map.of()),null,256L*1024*1024);
            assertThat(complete(analyzer,file,source,"strip").path("items").findValuesAsText("name")).contains("strip");
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).doesNotContain("getPets");
            Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");
        }
    }
    @Test void unresolvedReceiverRetriesOnlyRelevantPackageWithoutAWatchEvent()throws Exception{
        Path file=Files.writeString(root.resolve("Use.java"),text("get"));
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).doesNotContain("getPets");
            @SuppressWarnings("unchecked") var before=(Map<String,Object>)analyzer.status().get("live_source_state");
            long fullBefore=((Number)before.get("reconciliations")).longValue(),targetedBefore=((Number)before.get("targeted_reconciliations")).longValue(),
                    eventsBefore=((Number)before.get("events")).longValue();
            // The new source may arrive through the watcher or the targeted package fallback.
            // Either path is bounded; neither may trigger a full workspace reconciliation.
            Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");
            @SuppressWarnings("unchecked") var after=(Map<String,Object>)analyzer.status().get("live_source_state");
            assertThat(((Number)after.get("reconciliations")).longValue()).isEqualTo(fullBefore);
            long boundedAdmissions=((Number)after.get("targeted_reconciliations")).longValue()+((Number)after.get("events")).longValue();
            assertThat(boundedAdmissions).isGreaterThan(targetedBefore+eventsBefore);
        }
    }

    @Test void unresolvedFullyQualifiedReceiverReconcilesOnlyItsNamedPackage()throws Exception{
        Path callerDir=Files.createDirectories(root.resolve("caller")),file=Files.writeString(callerDir.resolve("Use.java"),
                "package caller; class Use { Object call(foo.Api api){return api.get();} }");
        String source=Files.readString(file);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,file,source,"get").path("items").findValuesAsText("name")).doesNotContain("getPets");
            Path apiDir=Files.createDirectories(root.resolve("foo"));Files.writeString(apiDir.resolve("Api.java"),"package foo; public class Api { public int getPets(){return 1;} }");
            assertThat(complete(analyzer,file,source,"get").path("items").findValuesAsText("name")).contains("getPets");
            @SuppressWarnings("unchecked") var state=(Map<String,Object>)analyzer.status().get("live_source_state");
            assertThat(((Number)state.get("targeted_reconciliations")).longValue()).isPositive();
        }
    }

    @Test void nonSourceFilesystemChurnDoesNotInvalidateWatcherBackedCandidates()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("g"));var documents=new Documents();documents.open(file,text("g"),1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("g"),"g").path("items").findValuesAsText("name")).contains("getPets");
            Files.writeString(root.resolve("state.tmp"),"not source");
            documents.change(file,2,List.of(new Documents.Change(null,text("ge"))));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("ge"),"ge").path("items").findValuesAsText("name")).contains("getPets");
        }
    }
    @Test void coarseWorkspaceRootsUseConservativeSourceValidationWithoutWatchingTheWholeWorkspace()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("g"));var documents=new Documents();documents.open(file,text("g"),1);
        var context=new Analyzer.Context("fixture:coarse:1","25",List.of(),List.of(root),"coarse",Map.of(),List.of("--release","25"),Set.of(),List.of(),List.of(root),false);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context,null,256L*1024*1024);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("g"),"g").path("items").findValuesAsText("name")).contains("getPets");
            documents.change(file,2,List.of(new Documents.Change(null,text("ge"))));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("ge"),"ge").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(analyzer.status()).containsEntry("source_catalog_precise",0L);
        }
    }
    @Test void largeSourceContextsReuseCompletionCandidatesWithoutScanningEverySource()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} int getPetCount(){return 2;} }");
        for(int i=0;i<300;i++)Files.writeString(root.resolve("Helper"+i+".java"),"class Helper"+i+" { int value(){return "+i+";} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("g"));var documents=new Documents();documents.open(file,text("g"),1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("g"),"g").path("items").findValuesAsText("name")).contains("getPets");
            documents.change(file,2,List.of(new Documents.Change(null,text("ge"))));analyzer.changed(file,documents.hash(file));analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("ge"),"ge").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(analyzer.status()).containsEntry("source_catalog_precise",1L);
        }
    }
    @Test void semanticApiIdentityReusesBodyEditsAndInvalidatesApiEdits()throws Exception{
        Path api=Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path other=Files.writeString(root.resolve("Other.java"),"class Other { int value(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("get"));
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");
            Files.writeString(other,"class Other { int value(){int x=1; return x;} }");
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");

            Files.writeString(api,"class Api { int getPets(){return 2;} }");
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");

            Files.writeString(api,"class Api { int getPets(){return 2;} int getElse(){return 3;} }");
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets","getElse");
        }
    }

    @Test void receiverHierarchyProvenanceInvalidatesInheritedCandidates()throws Exception{
        Files.writeString(root.resolve("BaseA.java"),"class BaseA { int getA(){return 1;} }");
        Files.writeString(root.resolve("BaseB.java"),"class BaseB { int getB(){return 2;} }");
        Path api=Files.writeString(root.resolve("Api.java"),"class Api extends BaseA {}");
        Path file=Files.writeString(root.resolve("Use.java"),text("get"));
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getA").doesNotContain("getB");
            Files.writeString(api,"class Api extends BaseB {}");
            var names=complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name");
            assertThat(names).contains("getB").doesNotContain("getA");
        }
    }

    @Test void negativeNameResolutionChangesInvalidateQualifiedCompletion()throws Exception{
        Path a=Files.createDirectories(root.resolve("a")),b=Files.createDirectories(root.resolve("b"));
        Files.writeString(a.resolve("Api.java"),"package a; public class Api { public int getA(){return 1;} }");
        Path competing=Files.writeString(b.resolve("Api.java"),"package b; class Api { public int getB(){return 2;} }");
        String source="import a.*; import b.*; class Use { Object call(Api api){return api.get();} }";
        Path file=Files.writeString(root.resolve("Use.java"),source);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);
            var before=complete(analyzer,file,source,"get").path("items").findValuesAsText("name");
            assertThat(before).contains("getA").doesNotContain("getB");
            Files.writeString(competing,"package b; class Api { public int getB(){return 3;} }");
            var bodyOnly=complete(analyzer,file,source,"get").path("items").findValuesAsText("name");
            assertThat(bodyOnly).contains("getA").doesNotContain("getB");

            Files.writeString(competing,"package b; public class Api { public int getB(){return 2;} }");
            var after=complete(analyzer,file,source,"get").path("items").findValuesAsText("name");

            assertThat(after).doesNotContain("getA");
        }
    }

    @Test void sourceMembershipChangesInvalidateSemanticCompletionIdentity()throws Exception{
        Files.writeString(root.resolve("Api.java"),"class Api { int getPets(){return 1;} }");
        Path file=Files.writeString(root.resolve("Use.java"),text("get")),added=root.resolve("Added.java");
        var documents=new Documents();documents.open(file,text("get"),1);
        try(var analyzer=new Analyzer()){
            analyzer.configure(context(),null,256L*1024*1024);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");
            documents.open(added,"class Added {}",1);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");

            documents.close(added);analyzer.documents(documents);
            assertThat(complete(analyzer,file,text("get"),"get").path("items").findValuesAsText("name")).contains("getPets");
        }
    }

    @Test void detachedHitsStillDetectTimestampPreservingJarReplacementAndDeletion()throws Exception{
        Path jar=IndexFixtures.jar(root.resolve("repository"),"api","package lib; public class Sample { public int getPets(){return 1;} }",true);
        Path sources=Files.createDirectories(root.resolve("sources"));String source="class Use { Object call(lib.Sample api){return api.getPets();} }";
        Path file=Files.writeString(sources.resolve("Use.java"),source);var identities=new FileStateRegistry();
        try(var analyzer=new Analyzer(identities)){
            analyzer.configure(new Analyzer.Context("fixture:prefix:1","25",List.of(jar),List.of(sources),"jar",Map.of()),null,256L*1024*1024);
            assertThat(complete(analyzer,file,source,"get").path("items").findValuesAsText("name")).contains("getPets");
            assertThat(complete(analyzer,file,source,"getP").path("items").findValuesAsText("name")).contains("getPets");
            var time=Files.getLastModifiedTime(jar);
            Path replacement=IndexFixtures.jar(root.resolve("replacement"),"api","package lib; public class Sample { public String getElse(){return \"new\";} }",true);
            Files.copy(replacement,jar,StandardCopyOption.REPLACE_EXISTING);Files.setLastModifiedTime(jar,time);
            assertThat(complete(analyzer,file,source,"get").path("items").findValuesAsText("name")).contains("getElse").doesNotContain("getPets");
            Files.delete(jar);
            var deleted=analyzer.completion(file,source,0,source.indexOf("api.get")+7,100,0);
            assertThat(deleted.warnings()).anyMatch(w->w.startsWith("analyzer_fault:"));
            assertThat(Json.MAPPER.valueToTree(deleted.result()).path("items").isEmpty()).isTrue();
        }
    }
}

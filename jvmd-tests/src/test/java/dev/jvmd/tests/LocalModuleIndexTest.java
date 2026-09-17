package dev.jvmd.tests;

import dev.jvmd.analyzer.*;
import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

/** Implements phase 6: content-hashed local artifacts and workspace-specific SCIP source variants. */
@Tag("phase-6")
class LocalModuleIndexTest {
    @TempDir Path root;
    @Test void localAndInstalledCoordinatesKeepIndependentSourcesThroughEdits()throws Exception{
        String original="package fixture; /** Installed API. */ public class Sample { private int secret; public int old(){return 1;} }";
        Path jar=IndexFixtures.jar(root.resolve("repository"),"sample",original,true);
        Path module=Files.createDirectories(root.resolve("checkout")),source=Files.createDirectories(module.resolve("src/main/java/fixture")).resolve("Sample.java");
        Files.writeString(source,original.replace("Installed API.","Checkout API."));
        Path classes=Files.createDirectories(module.resolve("target/classes"));assertThat(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-g","-parameters","-d",classes.toString(),source.toString())).isZero();
        String gav="fixture:sample:1";
        try(var index=new IndexService(root.resolve("index.db"),root.resolve("repository"))){
            index.indexJar(jar,gav,"jar");index.indexSources(jar.resolveSibling("sample-sources.jar"));
            index.registerLocal(new IndexService.LocalModule(module,gav,List.of(module.resolve("src/main/java")),List.of(classes)));index.refreshLocal(module);
            index.loadWorkspace("checkout",List.of(new IndexService.WorkspaceArtifact(module.toString(),"local")),List.of());
            index.loadWorkspace("installed",List.of(new IndexService.WorkspaceArtifact(jar.toString(),"compile")),List.of());
            var local=index.find("old","checkout",false,10,0).getFirst();var installed=index.find("old","installed",false,10,0).getFirst();
            assertThat(local.get("scip")).isEqualTo(installed.get("scip"));assertThat(local).containsEntry("source_file",source.toString()).containsEntry("artifact_kind","local");
            assertThat(installed.get("source_file").toString()).startsWith("jar:");assertThat(index.find("secret","checkout",false,10,0)).hasSize(1);assertThat(index.find("secret","installed",false,10,0)).isEmpty();
            String oldHash=index.artifact(module).sha256();var timestamp=Files.getLastModifiedTime(source);String edited=original.replace("old()","fresh()");Files.writeString(source,edited);Files.setLastModifiedTime(source,timestamp);
            index.refreshLocal(module);assertThat(index.artifact(module).sha256()).isNotEqualTo(oldHash);assertThat(index.find("old","checkout",false,10,0)).isEmpty();
            try(var analyzer=new Analyzer()){
                analyzer.configure(new Analyzer.Context(gav,"25",List.of(),List.of(module.resolve("src/main/java")),"edited",Map.of(module.toString(),gav)),index,256L*1024*1024);
                var bound=analyzer.bindings(source,edited,null);assertThat(bound.diagnostics()).isEmpty();
            }
            assertThat(awaitFind(index,"fresh","checkout",1)).singleElement().satisfies(s->assertThat(s).containsEntry("source_file",source.toString()));
            assertThat(index.find("old","installed",false,10,0)).hasSize(1);
            assertThat(index.find("fresh","installed",false,10,0)).isEmpty();
            // Replacing an installed jar must preserve shared local symbol identities.
            IndexFixtures.jar(root.resolve("repository"),"sample",original.replace("old()","published()"),true);
            index.indexJar(jar,gav,"jar");
            assertThat(index.find("fresh","checkout",false,10,0)).hasSize(1);
            assertThat(index.find("published","installed",false,10,0)).hasSize(1);
            assertThat(index.find("old","installed",false,10,0)).isEmpty();
        }
    }
    @Test void unchangedContentReusesIdentityAndDeletingAFileRemovesItsSymbols()throws Exception{
        Path module=Files.createDirectories(root.resolve("plain")),source=module.resolve("A.java");String text="class A { int value(){return 7;} }";Files.writeString(source,text);
        try(var index=new IndexService(root.resolve("plain.db"),root.resolve("empty"))){
            index.registerLocal(new IndexService.LocalModule(module,"fixture:plain:1",List.of(module),List.of()));long id=index.refreshLocal(module);
            index.loadWorkspace("plain",List.of(new IndexService.WorkspaceArtifact(module.toString(),"local")),List.of());
            try(var analyzer=new Analyzer()){analyzer.configure(new Analyzer.Context("fixture:plain:1","25",List.of(),List.of(module),"plain",Map.of(module.toString(),"fixture:plain:1")),index,256L*1024*1024);analyzer.bindings(source,text,null);}
            assertThat(awaitFind(index,"value","plain",1)).hasSize(1);Files.setLastModifiedTime(source,java.nio.file.attribute.FileTime.fromMillis(1));assertThat(index.refreshLocal(module)).isEqualTo(id);
            Files.delete(source);index.refreshLocalWorkspace("plain");assertThat(index.find("value","plain",false,10,0)).isEmpty();
        }
    }
    private static List<Map<String,Object>> awaitFind(IndexService index,String query,String workspace,int expected)throws Exception{
        long deadline=System.nanoTime()+5_000_000_000L;List<Map<String,Object>> found;
        do{found=index.find(query,workspace,false,10,0);if(found.size()==expected)return found;Thread.sleep(10);}while(System.nanoTime()<deadline);
        return found;
    }
}

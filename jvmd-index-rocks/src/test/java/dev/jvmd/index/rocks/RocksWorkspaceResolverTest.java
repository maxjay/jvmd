package dev.jvmd.index.rocks;

import dev.jvmd.index.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksWorkspaceResolverTest {
    @TempDir Path temp;

    @Test void classpathOrderSelectsVersionWithoutGlobalCrossVersionLinking()throws Exception{
        var source=facts('a',"source.Type",List.of(new ArtifactIndexFormat.Relationship(0,"dep.Type","calls")));
        var depOne=facts('b',"dep.Type",List.of());var depTwo=facts('c',"dep.Type",List.of());
        try(var artifacts=new RocksArtifactRepository(temp.resolve("artifacts"))){
            artifacts.publish(source,Set.of());artifacts.publish(depOne,Set.of());artifacts.publish(depTwo,Set.of());
            try(var resolver=new RocksWorkspaceResolver(temp.resolve("resolution"),artifacts)){
                var sourceEntry=entry(source,"fixture:source:1");
                var one=entry(depOne,"fixture:dep:1");
                var two=entry(depTwo,"fixture:dep:2");
                var first=new RocksWorkspaceResolver.Workspace(List.of(sourceEntry,one,two),"javac-25");
                var second=new RocksWorkspaceResolver.Workspace(List.of(sourceEntry,two,one),"javac-25");
                assertThat(resolver.resolveFirst(first,"dep.Type").orElseThrow().artifactCacheKey()).isEqualTo(depOne.key().cacheKey());
                assertThat(resolver.resolveFirst(second,"dep.Type").orElseThrow().artifactCacheKey()).isEqualTo(depTwo.key().cacheKey());
                assertThat(resolver.resolveAll(first,"dep.Type")).extracting(RocksWorkspaceResolver.ResolvedSymbol::artifactCacheKey)
                        .containsExactly(depOne.key().cacheKey(),depTwo.key().cacheKey());
                var resolved=resolver.outgoing(first,source.key().cacheKey(),0,Set.of("calls"),10);
                assertThat(resolved).hasSize(1);
                assertThat(resolved.getFirst().target().artifactCacheKey()).isEqualTo(depOne.key().cacheKey());
            }
        }
    }

    @Test void workspaceSearchUsesContextAndExplicitNamePathSubstringIndexes()throws Exception{
        var depOne=facts('f',"dep.Type",List.of());var depTwo=facts('9',"dep.Type",List.of());
        try(var artifacts=new RocksArtifactRepository(temp.resolve("search-artifacts"))){
            artifacts.publish(depOne,Set.of());artifacts.publish(depTwo,Set.of());
            try(var resolver=new RocksWorkspaceResolver(temp.resolve("search-resolution"),artifacts)){
                var one=entry(depOne,"one:dep:1");
                var two=entry(depTwo,"two:dep:2");
                var workspace=new RocksWorkspaceResolver.Workspace(List.of(one,two),"javac-25");
                assertThat(resolver.findName(workspace,"Type",false,10)).extracting(RocksWorkspaceResolver.WorkspaceSymbol::scip)
                        .containsExactly(one.context().scip(depOne.symbols().getFirst()),two.context().scip(depTwo.symbols().getFirst()));
                assertThat(resolver.findPathPrefix(workspace,"dep.Type",10)).hasSize(2);
                assertThat(resolver.findSubstring(workspace,"ype",10)).hasSize(2);
                assertThat(resolver.findExact(workspace,"Type",10)).extracting(RocksWorkspaceResolver.WorkspaceSymbol::scip)
                        .containsExactly(one.context().scip(depOne.symbols().getFirst()),two.context().scip(depTwo.symbols().getFirst()));
                assertThat(resolver.findExact(workspace,"dep.Type",10)).hasSize(2);
                assertThat(resolver.findExact(workspace,one.context().scip(depOne.symbols().getFirst()),10)).hasSize(1);
                String second=two.context().scip(depTwo.symbols().getFirst());
                assertThat(resolver.byScip(workspace,second)).get().extracting(RocksWorkspaceResolver.WorkspaceSymbol::scip).isEqualTo(second);
            }
        }
    }

    @Test void completeWorkspaceInputsChangeResolutionCacheIdentity()throws Exception{
        var artifact=facts('d',"dep.Type",List.of());var base=entry(artifact,"fixture:dep:1");
        var ordered=new RocksWorkspaceResolver.Workspace(List.of(base),"javac-a");
        var scope=new RocksWorkspaceResolver.Workspace(List.of(new RocksWorkspaceResolver.Entry(base.artifactCacheKey(),base.context(),"test",base.module(),base.sourceOverlayFingerprint())),"javac-a");
        var module=new RocksWorkspaceResolver.Workspace(List.of(new RocksWorkspaceResolver.Entry(base.artifactCacheKey(),base.context(),base.scope(),"module-b",base.sourceOverlayFingerprint())),"javac-a");
        var overlay=new RocksWorkspaceResolver.Workspace(List.of(new RocksWorkspaceResolver.Entry(base.artifactCacheKey(),base.context(),base.scope(),base.module(),"overlay-b")),"javac-a");
        var compiler=new RocksWorkspaceResolver.Workspace(List.of(base),"javac-b");
        var coordinate=new RocksWorkspaceResolver.Workspace(List.of(new RocksWorkspaceResolver.Entry(base.artifactCacheKey(),
                new ArtifactContext("other:dep:1","jar","/repo/other.jar"),base.scope(),base.module(),base.sourceOverlayFingerprint())),"javac-a");
        assertThat(Set.of(ordered.identity(),scope.identity(),module.identity(),overlay.identity(),compiler.identity(),coordinate.identity())).hasSize(6);
    }

    @Test void persistedResolutionCacheIsScopedByWorkspaceIdentity()throws Exception{
        var dep=facts('e',"dep.Type",List.of());
        Path artifactRoot=temp.resolve("persist-artifacts"),resolutionRoot=temp.resolve("persist-resolution");
        try(var artifacts=new RocksArtifactRepository(artifactRoot)){
            artifacts.publish(dep,Set.of());
            var workspace=new RocksWorkspaceResolver.Workspace(List.of(entry(dep,"fixture:dep:1")),"javac-25");
            try(var first=new RocksWorkspaceResolver(resolutionRoot,artifacts)){
                assertThat(first.resolveFirst(workspace,"dep.Type")).isPresent();
                assertThat(first.status()).containsEntry("cache_misses",1L);
            }
            try(var reopened=new RocksWorkspaceResolver(resolutionRoot,artifacts)){
                assertThat(reopened.resolveFirst(workspace,"dep.Type")).isPresent();
                assertThat(reopened.status()).containsEntry("cache_hits",1L);
            }
        }
    }

    private static RocksWorkspaceResolver.Entry entry(ArtifactIndexFormat.ArtifactData data,String gav){
        String artifact=gav.split(":")[1];
        return new RocksWorkspaceResolver.Entry(data.key().cacheKey(),new ArtifactContext(gav,"jar","/repo/"+artifact+".jar"),"compile","module-a","");
    }

    private static ArtifactIndexFormat.ArtifactData facts(char hash,String binaryKey,List<ArtifactIndexFormat.Relationship> relationships){
        var key=new ArtifactIndexFormat.Key(String.valueOf(hash).repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        String name=binaryKey.substring(binaryKey.lastIndexOf('.')+1);
        var symbol=new ArtifactIndexFormat.SymbolRecord(0,-1,binaryKey,binaryKey,name,"class","class "+binaryKey,
                null,1,binaryKey.replace('.','/')+".class",List.of(),"{}");
        return new ArtifactIndexFormat.ArtifactData(key,List.of(symbol),relationships);
    }

    @Test void outgoingOverlaysLazyCodeGenerationOnSignatureGeneration()throws Exception{
        try(var artifacts=new RocksArtifactRepository(temp.resolve("code-artifacts"));
            var resolver=new RocksWorkspaceResolver(temp.resolve("code-resolution"),artifacts)){
            var signature=facts('a',"source.Type",List.of());
            var target=facts('b',"target.Type",List.of());
            artifacts.publish(signature,Set.of());artifacts.publish(target,Set.of());

            var codeKey=new ArtifactIndexFormat.Key(signature.key().binarySha256(),signature.key().formatVersion(),
                    signature.key().indexerVersion(),signature.key().runtimeFeature(),"code");
            var code=new ArtifactIndexFormat.ArtifactData(codeKey,signature.symbols(),
                    List.of(new ArtifactIndexFormat.Relationship(0,"target.Type","calls")));
            artifacts.publish(code,Set.of("target.Type"));

            var sourceEntry=entry(signature,"source:artifact:1");
            var targetEntry=entry(target,"target:artifact:1");
            var workspace=new RocksWorkspaceResolver.Workspace(List.of(sourceEntry,targetEntry),"compiler");
            var outgoing=resolver.outgoing(workspace,signature.key().cacheKey(),0,Set.of("calls"),10);
            assertThat(outgoing).hasSize(1);
            assertThat(outgoing.getFirst().symbolicTarget()).isEqualTo("target.Type");
            assertThat(outgoing.getFirst().target().artifactCacheKey()).isEqualTo(target.key().cacheKey());
        }
    }

}

package dev.jvmd.index.rocks;

import dev.jvmd.index.ArtifactIndexFormat;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;

class RocksWorkspaceResolverTest {
    @TempDir Path temp;

    @Test void classpathOrderSelectsVersionWithoutGlobalCrossVersionLinking()throws Exception{
        var source=facts('a',"source.Type",List.of(new ArtifactIndexFormat.Relationship(0,"dep.Type","calls")));
        var depOne=facts('b',"dep.Type",List.of());
        var depTwo=facts('c',"dep.Type",List.of());

        try(var artifacts=new RocksArtifactRepository(temp.resolve("artifacts"))){
            artifacts.publish(source,Set.of());artifacts.publish(depOne,Set.of());artifacts.publish(depTwo,Set.of());
            try(var resolver=new RocksWorkspaceResolver(temp.resolve("resolution"),artifacts)){
                var sourceEntry=entry(source),one=entry(depOne),two=entry(depTwo);
                var first=new RocksWorkspaceResolver.Workspace(List.of(sourceEntry,one,two),"javac-25");
                var second=new RocksWorkspaceResolver.Workspace(List.of(sourceEntry,two,one),"javac-25");

                assertThat(resolver.resolveFirst(first,"dep.Type").orElseThrow().artifactCacheKey()).isEqualTo(depOne.key().cacheKey());
                assertThat(resolver.resolveFirst(second,"dep.Type").orElseThrow().artifactCacheKey()).isEqualTo(depTwo.key().cacheKey());
                assertThat(resolver.resolveAll(first,"dep.Type")).extracting(RocksWorkspaceResolver.ResolvedSymbol::artifactCacheKey)
                        .containsExactly(depOne.key().cacheKey(),depTwo.key().cacheKey());

                var resolved=resolver.outgoing(first,source.key().cacheKey(),0,Set.of("calls"),10);
                assertThat(resolved).hasSize(1);
                assertThat(resolved.getFirst().target().artifactCacheKey()).isEqualTo(depOne.key().cacheKey());
                assertThat(resolved.getFirst().symbolicTarget()).isEqualTo("dep.Type");
            }
        }
    }

    @Test void completeWorkspaceInputsChangeResolutionCacheIdentity()throws Exception{
        var artifact=facts('d',"dep.Type",List.of());var base=entry(artifact);
        var ordered=new RocksWorkspaceResolver.Workspace(List.of(base),"javac-a");
        var scope=new RocksWorkspaceResolver.Workspace(List.of(new RocksWorkspaceResolver.Entry(base.artifactCacheKey(),"test",base.module(),base.sourceOverlayFingerprint())),"javac-a");
        var module=new RocksWorkspaceResolver.Workspace(List.of(new RocksWorkspaceResolver.Entry(base.artifactCacheKey(),base.scope(),"module-b",base.sourceOverlayFingerprint())),"javac-a");
        var overlay=new RocksWorkspaceResolver.Workspace(List.of(new RocksWorkspaceResolver.Entry(base.artifactCacheKey(),base.scope(),base.module(),"overlay-b")),"javac-a");
        var compiler=new RocksWorkspaceResolver.Workspace(List.of(base),"javac-b");
        assertThat(Set.of(ordered.identity(),scope.identity(),module.identity(),overlay.identity(),compiler.identity())).hasSize(5);
    }

    @Test void persistedResolutionCacheIsScopedByWorkspaceIdentity()throws Exception{
        var dep=facts('e',"dep.Type",List.of());
        Path artifactRoot=temp.resolve("persist-artifacts"),resolutionRoot=temp.resolve("persist-resolution");
        try(var artifacts=new RocksArtifactRepository(artifactRoot)){
            artifacts.publish(dep,Set.of());
            var workspace=new RocksWorkspaceResolver.Workspace(List.of(entry(dep)),"javac-25");
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

    private static RocksWorkspaceResolver.Entry entry(ArtifactIndexFormat.ArtifactData data){
        return new RocksWorkspaceResolver.Entry(data.key().cacheKey(),"compile","module-a","");
    }

    private static ArtifactIndexFormat.ArtifactData facts(char hash,String binaryKey,List<ArtifactIndexFormat.Relationship> relationships){
        var key=new ArtifactIndexFormat.Key(String.valueOf(hash).repeat(64),ArtifactIndexFormat.FORMAT_VERSION,
                ArtifactIndexFormat.INDEXER_VERSION,Runtime.version().feature(),"signatures");
        String name=binaryKey.substring(binaryKey.lastIndexOf('.')+1);
        var symbol=new ArtifactIndexFormat.SymbolRecord(0,-1,binaryKey,binaryKey,name,"class","class "+binaryKey,
                null,1,binaryKey.replace('.','/')+".class",List.of(),"{}");
        return new ArtifactIndexFormat.ArtifactData(key,List.of(symbol),relationships);
    }
}

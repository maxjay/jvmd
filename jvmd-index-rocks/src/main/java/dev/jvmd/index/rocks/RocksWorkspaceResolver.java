package dev.jvmd.index.rocks;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.ArtifactIndexFormat;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.rocksdb.*;

/**
 * Workspace-specific symbolic resolution. Nothing is globally linked across installed versions:
 * an unresolved binary target is resolved against one ordered workspace identity.
 */
public final class RocksWorkspaceResolver implements AutoCloseable {
    public record Entry(String artifactCacheKey,String scope,String module,String sourceOverlayFingerprint) {
        public Entry {
            Objects.requireNonNull(artifactCacheKey);Objects.requireNonNull(scope);
            module=module==null?"":module;sourceOverlayFingerprint=sourceOverlayFingerprint==null?"":sourceOverlayFingerprint;
            if(!artifactCacheKey.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("artifactCacheKey");
        }
    }

    public record Workspace(List<Entry> classpath,String compilerFingerprint) {
        public Workspace {
            classpath=List.copyOf(classpath);compilerFingerprint=compilerFingerprint==null?"":compilerFingerprint;
        }
        public String identity(){
            var value=new StringBuilder("workspace-v1\n").append(compilerFingerprint).append('\n');
            for(var entry:classpath)value.append(entry.artifactCacheKey()).append('\t').append(entry.scope()).append('\t')
                    .append(entry.module()).append('\t').append(entry.sourceOverlayFingerprint()).append('\n');
            return Hashing.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public record ResolvedSymbol(String artifactCacheKey,int localId,int classpathIndex) { }
    public record ResolvedRelationship(String sourceArtifactCacheKey,int sourceLocalId,String kind,String symbolicTarget,
                                       ResolvedSymbol target) { }

    private static final byte MISS=0, HIT=1;
    static {RocksDB.loadLibrary();}
    private final RocksArtifactRepository artifacts;
    private final Options options;
    private final RocksDB cache;
    private long cacheHits,cacheMisses,resolutions;

    public RocksWorkspaceResolver(Path root,RocksArtifactRepository artifacts)throws Exception{
        this.artifacts=Objects.requireNonNull(artifacts);
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=new Options().setCreateIfMissing(true).setMaxOpenFiles(64);
        cache=RocksDB.open(options,path.toString());
    }

    public synchronized Optional<ResolvedSymbol> resolveFirst(Workspace workspace,String binaryKey)throws Exception{
        String workspaceId=workspace.identity();byte[] cacheKey=cacheKey(workspaceId,binaryKey),stored=cache.get(cacheKey);
        if(stored!=null){
            cacheHits++;
            if(stored.length==1&&stored[0]==MISS)return Optional.empty();
            return Optional.of(decode(stored));
        }
        cacheMisses++;
        for(int i=0;i<workspace.classpath().size();i++){
            var entry=workspace.classpath().get(i);Integer localId=artifacts.binaryId(entry.artifactCacheKey(),binaryKey);
            if(localId==null)continue;
            var resolved=new ResolvedSymbol(entry.artifactCacheKey(),localId,i);
            cache.put(cacheKey,encode(resolved));resolutions++;return Optional.of(resolved);
        }
        cache.put(cacheKey,new byte[]{MISS});resolutions++;return Optional.empty();
    }

    public List<ResolvedSymbol> resolveAll(Workspace workspace,String binaryKey)throws Exception{
        var result=new ArrayList<ResolvedSymbol>();
        for(int i=0;i<workspace.classpath().size();i++){
            var entry=workspace.classpath().get(i);Integer localId=artifacts.binaryId(entry.artifactCacheKey(),binaryKey);
            if(localId!=null)result.add(new ResolvedSymbol(entry.artifactCacheKey(),localId,i));
        }
        return List.copyOf(result);
    }

    public List<ResolvedRelationship> outgoing(Workspace workspace,String sourceArtifactCacheKey,int sourceLocalId,
                                                Set<String> kinds,int limit)throws Exception{
        if(workspace.classpath().stream().noneMatch(entry->entry.artifactCacheKey().equals(sourceArtifactCacheKey)))
            throw new IllegalArgumentException("Source artifact is not in workspace");
        var result=new ArrayList<ResolvedRelationship>();
        for(var relationship:artifacts.outgoing(sourceArtifactCacheKey,sourceLocalId,kinds,limit)){
            var target=resolveFirst(workspace,relationship.target());
            if(target.isEmpty())continue;
            result.add(new ResolvedRelationship(sourceArtifactCacheKey,sourceLocalId,relationship.kind(),relationship.target(),target.get()));
        }
        return List.copyOf(result);
    }

    public synchronized Map<String,Object> status(){
        return Map.of("cache_hits",cacheHits,"cache_misses",cacheMisses,"resolutions",resolutions);
    }

    private static byte[] cacheKey(String workspaceId,String binaryKey){
        return ("workspace|"+workspaceId+"|binary|"+binaryKey).getBytes(StandardCharsets.UTF_8);
    }
    private static byte[] encode(ResolvedSymbol value){
        byte[] artifact=value.artifactCacheKey().getBytes(StandardCharsets.US_ASCII);
        var buffer=ByteBuffer.allocate(1+artifact.length+Integer.BYTES*2);
        buffer.put(HIT).put(artifact).putInt(value.localId()).putInt(value.classpathIndex());return buffer.array();
    }
    private static ResolvedSymbol decode(byte[] value){
        if(value.length!=1+64+Integer.BYTES*2||value[0]!=HIT)throw new IllegalStateException("Corrupt workspace resolution cache");
        String artifact=new String(value,1,64,StandardCharsets.US_ASCII);
        var buffer=ByteBuffer.wrap(value,65,Integer.BYTES*2);
        return new ResolvedSymbol(artifact,buffer.getInt(),buffer.getInt());
    }

    @Override public void close(){cache.close();options.close();}
}

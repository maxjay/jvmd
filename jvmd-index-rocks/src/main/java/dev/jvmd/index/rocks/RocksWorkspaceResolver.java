package dev.jvmd.index.rocks;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import org.rocksdb.*;

/**
 * Workspace-specific symbolic resolution and search. Installed versions are never globally linked:
 * target selection and public identity are derived from one ordered workspace.
 */
public final class RocksWorkspaceResolver implements AutoCloseable {
    public record Entry(String artifactCacheKey,ArtifactContext context,String scope,String module,String sourceOverlayFingerprint) {
        public Entry {
            Objects.requireNonNull(artifactCacheKey);Objects.requireNonNull(context);Objects.requireNonNull(scope);
            module=module==null?"":module;sourceOverlayFingerprint=sourceOverlayFingerprint==null?"":sourceOverlayFingerprint;
            if(!artifactCacheKey.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("artifactCacheKey");
        }
    }

    public record Workspace(List<Entry> classpath,String compilerFingerprint) {
        public Workspace {
            classpath=List.copyOf(classpath);compilerFingerprint=compilerFingerprint==null?"":compilerFingerprint;
        }
        public String identity(){
            var value=new StringBuilder("workspace-v2\n").append(compilerFingerprint).append('\n');
            for(var entry:classpath)value.append(entry.artifactCacheKey()).append('\t')
                    .append(entry.context().gav()).append('\t').append(entry.context().kind()).append('\t').append(entry.context().path()).append('\t')
                    .append(entry.scope()).append('\t').append(entry.module()).append('\t').append(entry.sourceOverlayFingerprint()).append('\n');
            return Hashing.sha256(value.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public record ResolvedSymbol(String artifactCacheKey,int localId,int classpathIndex) { }
    public record ResolvedRelationship(String sourceArtifactCacheKey,int sourceLocalId,String kind,String symbolicTarget,
                                       ResolvedSymbol target) { }
    public record WorkspaceSymbol(Entry entry,ArtifactIndexFormat.SymbolRecord symbol,String scip,String namePath,Map<String,Object> sourceData) {
        public WorkspaceSymbol { sourceData=Map.copyOf(sourceData); }
    }

    private static final byte MISS=0, HIT=1;
    static {RocksDB.loadLibrary();}
    private final RocksArtifactRepository artifacts;
    private final RocksArtifactInventory inventory;
    private final Options options;
    private final RocksDB cache;
    private long cacheHits,cacheMisses,resolutions;

    public RocksWorkspaceResolver(Path root,RocksArtifactRepository artifacts)throws Exception{
        this(root,artifacts,null);
    }
    public RocksWorkspaceResolver(Path root,RocksArtifactRepository artifacts,RocksArtifactInventory inventory)throws Exception{
        this.artifacts=Objects.requireNonNull(artifacts);this.inventory=inventory;
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=new Options().setCreateIfMissing(true).setMaxOpenFiles(64);
        cache=RocksDB.open(options,path.toString());
    }


    public OptionalLong cursorByScip(Workspace workspace,String scip)throws Exception{
        var value=byScip(workspace,scip);if(value.isEmpty())return OptionalLong.empty();
        int classpathIndex=indexOf(workspace,value.get().entry());if(classpathIndex<0)return OptionalLong.empty();
        return OptionalLong.of(cursor(classpathIndex,value.get().symbol().id()));
    }

    public static long cursor(int classpathIndex,int localId){
        if(classpathIndex<0||localId<0)throw new IllegalArgumentException("cursor");
        return ((long)(classpathIndex+1)<<32)|(localId&0xffff_ffffL);
    }

    private static int indexOf(Workspace workspace,Entry entry){
        for(int i=0;i<workspace.classpath().size();i++)if(workspace.classpath().get(i).equals(entry))return i;
        return -1;
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


    public Optional<WorkspaceSymbol> resolvedSymbol(Workspace workspace,ResolvedSymbol resolved)throws Exception{
        if(resolved.classpathIndex()<0||resolved.classpathIndex()>=workspace.classpath().size())return Optional.empty();
        var entry=workspace.classpath().get(resolved.classpathIndex());
        if(!entry.artifactCacheKey().equals(resolved.artifactCacheKey()))return Optional.empty();
        var data=artifacts.artifact(resolved.artifactCacheKey());
        if(data==null||resolved.localId()<0||resolved.localId()>=data.symbols().size())return Optional.empty();
        var symbol=data.symbols().get(resolved.localId());if(symbol.id()!=resolved.localId())return Optional.empty();
        return Optional.of(workspaceSymbol(entry,symbol));
    }

    public List<ResolvedRelationship> incoming(Workspace workspace,String targetBinaryKey,Set<String> kinds,int limit)throws Exception{
        if(limit<=0)return List.of();
        var target=resolveFirst(workspace,targetBinaryKey);if(target.isEmpty())return List.of();
        var result=new ArrayList<ResolvedRelationship>();
        for(var entry:workspace.classpath()){
            if(result.size()>=limit)break;
            var generationKeys=new ArrayList<String>();generationKeys.add(entry.artifactCacheKey());
            var signature=artifacts.artifact(entry.artifactCacheKey());
            if(signature!=null){
                var codeKey=new ArtifactIndexFormat.Key(signature.key().binarySha256(),signature.key().formatVersion(),
                        signature.key().indexerVersion(),signature.key().runtimeFeature(),"code").cacheKey();
                if(artifacts.contains(codeKey))generationKeys.add(codeKey);
            }
            var seen=new LinkedHashSet<String>();
            for(String generation:generationKeys){
                for(var relationship:artifacts.incoming(generation,targetBinaryKey,kinds,limit-result.size())){
                    String dedupe=relationship.sourceId()+"|"+relationship.kind();
                    if(!seen.add(dedupe))continue;
                    result.add(new ResolvedRelationship(entry.artifactCacheKey(),relationship.sourceId(),
                            relationship.kind(),targetBinaryKey,target.get()));
                    if(result.size()>=limit)break;
                }
            }
        }
        return List.copyOf(result);
    }

    public List<ResolvedRelationship> outgoing(Workspace workspace,String sourceArtifactCacheKey,int sourceLocalId,
                                                Set<String> kinds,int limit)throws Exception{
        if(workspace.classpath().stream().noneMatch(entry->entry.artifactCacheKey().equals(sourceArtifactCacheKey)))
            throw new IllegalArgumentException("Source artifact is not in workspace");
        var result=new ArrayList<ResolvedRelationship>();
        var relationships=new LinkedHashSet<ArtifactIndexFormat.Relationship>();
        relationships.addAll(artifacts.outgoing(sourceArtifactCacheKey,sourceLocalId,kinds,limit));
        var signature=artifacts.artifact(sourceArtifactCacheKey);
        if(signature!=null&&result.size()<limit){
            var codeKey=new ArtifactIndexFormat.Key(signature.key().binarySha256(),signature.key().formatVersion(),
                    signature.key().indexerVersion(),signature.key().runtimeFeature(),"code").cacheKey();
            if(artifacts.contains(codeKey))relationships.addAll(artifacts.outgoing(codeKey,sourceLocalId,kinds,limit));
        }
        for(var relationship:relationships){
            if(result.size()>=limit)break;
            var target=resolveFirst(workspace,relationship.target());
            if(target.isEmpty())continue;
            result.add(new ResolvedRelationship(sourceArtifactCacheKey,sourceLocalId,relationship.kind(),relationship.target(),target.get()));
        }
        return List.copyOf(result);
    }


    public List<WorkspaceSymbol> findExact(Workspace workspace,String query,int limit)throws Exception{
        return findExact(workspace,query,limit,0L);
    }
    public List<WorkspaceSymbol> findExact(Workspace workspace,String query,int limit,long after)throws Exception{
        if(limit<=0||query.isBlank())return List.of();
        var result=new TreeMap<Long,WorkspaceSymbol>();
        var direct=byScip(workspace,query);
        if(direct.isPresent()){
            int i=indexOf(workspace,direct.get().entry());long cursor=cursor(i,direct.get().symbol().id());
            if(cursor>after)result.put(cursor,direct.get());
        }
        for(var value:findName(workspace,query,false,limit,after)){
            int i=indexOf(workspace,value.entry());result.putIfAbsent(cursor(i,value.symbol().id()),value);
        }
        for(int i=0;i<workspace.classpath().size();i++){
            var entry=workspace.classpath().get(i);if(cursor(i,Integer.MAX_VALUE)<=after)continue;
            Integer binary=artifacts.binaryId(entry.artifactCacheKey(),query);
            if(binary!=null){
                long cursor=cursor(i,binary);
                if(cursor>after){
                    var data=artifacts.artifact(entry.artifactCacheKey());
                    if(data!=null&&binary>=0&&binary<data.symbols().size()&&data.symbols().get(binary).id()==binary)
                        result.putIfAbsent(cursor,workspaceSymbol(entry,data.symbols().get(binary)));
                }
            }
            var ids=artifacts.pathIds(entry.artifactCacheKey(),query,Math.max(limit,1));
            var data=artifacts.artifact(entry.artifactCacheKey());if(data==null)continue;
            for(int id:ids){
                long cursor=cursor(i,id);if(cursor<=after||id<0||id>=data.symbols().size())continue;
                var symbol=data.symbols().get(id);
                if(symbol.id()!=id||!ArtifactContext.namePath(symbol).equals(query))continue;
                result.putIfAbsent(cursor,workspaceSymbol(entry,symbol));
            }
        }
        return result.values().stream().limit(limit).toList();
    }

    public List<WorkspaceSymbol> findName(Workspace workspace,String query,boolean prefix,int limit)throws Exception{
        return findName(workspace,query,prefix,limit,0L);
    }
    public List<WorkspaceSymbol> findName(Workspace workspace,String query,boolean prefix,int limit,long after)throws Exception{
        if(limit<=0)return List.of();var result=new ArrayList<WorkspaceSymbol>();
        for(int i=0;i<workspace.classpath().size();i++){
            var entry=workspace.classpath().get(i);if(cursor(i,Integer.MAX_VALUE)<=after)continue;
            var ids=artifacts.nameIds(entry.artifactCacheKey(),query,Math.max(limit-result.size(),1));
            addSymbols(result,workspace,i,entry,ids,symbol->prefix?symbol.name().startsWith(query):symbol.name().equals(query),limit,after);
            if(result.size()>=limit)break;
        }
        return List.copyOf(result);
    }

    public List<WorkspaceSymbol> findPathPrefix(Workspace workspace,String pathPrefix,int limit)throws Exception{
        if(limit<=0)return List.of();var result=new ArrayList<WorkspaceSymbol>();
        for(int i=0;i<workspace.classpath().size();i++){
            var entry=workspace.classpath().get(i);
            var ids=artifacts.pathIds(entry.artifactCacheKey(),pathPrefix,Math.max(limit-result.size(),1));
            addSymbols(result,workspace,i,entry,ids,symbol->ArtifactContext.namePath(symbol).startsWith(pathPrefix),limit,0L);
            if(result.size()>=limit)break;
        }
        return List.copyOf(result);
    }

    public List<WorkspaceSymbol> findSubstring(Workspace workspace,String query,int limit)throws Exception{
        return findSubstring(workspace,query,limit,0L);
    }
    public List<WorkspaceSymbol> findSubstring(Workspace workspace,String query,int limit,long after)throws Exception{
        if(limit<=0||query.isBlank())return List.of();
        String normalized=query.toLowerCase(Locale.ROOT);var result=new ArrayList<WorkspaceSymbol>();
        for(int i=0;i<workspace.classpath().size();i++){
            var entry=workspace.classpath().get(i);if(cursor(i,Integer.MAX_VALUE)<=after)continue;
            var ids=artifacts.substringIds(entry.artifactCacheKey(),normalized,Math.max((limit-result.size())*8,32));
            addSymbols(result,workspace,i,entry,ids,symbol->{
                String name=symbol.name().toLowerCase(Locale.ROOT),path=ArtifactContext.namePath(symbol).toLowerCase(Locale.ROOT);
                return name.contains(normalized)||path.contains(normalized);
            },limit,after);
            if(result.size()>=limit)break;
        }
        return List.copyOf(result);
    }

    public Optional<WorkspaceSymbol> byScip(Workspace workspace,String scip)throws Exception{
        for(var entry:workspace.classpath()){
            if(!scip.startsWith(scipPrefix(entry.context())))continue;
            var data=artifacts.artifact(entry.artifactCacheKey());if(data==null)continue;
            for(var symbol:data.symbols())if(scip.equals(entry.context().scip(symbol)))return Optional.of(workspaceSymbol(entry,symbol));
        }
        return Optional.empty();
    }

    private void addSymbols(List<WorkspaceSymbol> output,Workspace workspace,int classpathIndex,Entry entry,List<Integer> ids,
                            Predicate<ArtifactIndexFormat.SymbolRecord> predicate,int limit,long after)throws Exception{
        if(ids.isEmpty())return;var data=artifacts.artifact(entry.artifactCacheKey());if(data==null)return;
        var seen=new HashSet<Integer>();
        for(int id:ids){
            if(output.size()>=limit)break;if(!seen.add(id)||id<0||id>=data.symbols().size()||cursor(classpathIndex,id)<=after)continue;
            var symbol=data.symbols().get(id);if(symbol.id()!=id||!predicate.test(symbol))continue;
            output.add(workspaceSymbol(entry,symbol));
        }
    }

    private WorkspaceSymbol workspaceSymbol(Entry entry,ArtifactIndexFormat.SymbolRecord symbol){
        Map<String,Object> sourceData=Map.of();
        if(inventory!=null)try{
            var docs=inventory.documentation(entry.artifactCacheKey());
            if(docs.isPresent())sourceData=artifacts.documentation(docs.get(),symbol.key());
        }catch(Exception e){throw new IllegalStateException("Unable to load source documentation overlay",e);}
        return new WorkspaceSymbol(entry,symbol,entry.context().scip(symbol),ArtifactContext.namePath(symbol),sourceData);
    }
    private static String scipPrefix(ArtifactContext context){
        String[] parts=context.gav().split(":",3);if(parts.length!=3)return "";
        return "maven "+parts[0]+"/"+parts[1]+" "+parts[2]+" ";
    }

    public synchronized Map<String,Object> status(){return Map.of("cache_hits",cacheHits,"cache_misses",cacheMisses,"resolutions",resolutions);}

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

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
        public WorkspaceSymbol { sourceData=Collections.unmodifiableMap(new LinkedHashMap<>(sourceData)); }
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
    public RocksWorkspaceResolver(Path root,RocksArtifactRepository artifacts,RocksArtifactInventory inventory)throws Exception{this(root,artifacts,inventory,null);}
    RocksWorkspaceResolver(Path root,RocksArtifactRepository artifacts,RocksArtifactInventory inventory,RocksMemory memory)throws Exception{
        this.artifacts=Objects.requireNonNull(artifacts);this.inventory=inventory;
        Path path=root.toAbsolutePath().normalize();Files.createDirectories(path);
        options=memory==null?new Options().setCreateIfMissing(true).setMaxOpenFiles(64):memory.options(64);
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
        var resolved=lookup(workspace,binaryKey,new HashSet<>());
        if(resolved.isPresent()){
            cache.put(cacheKey,encode(resolved.get()));resolutions++;return resolved;
        }
        cache.put(cacheKey,new byte[]{MISS});resolutions++;return Optional.empty();
    }

    private Optional<ResolvedSymbol> lookup(Workspace workspace,String binaryKey,Set<String> visited)throws Exception{
        if(!visited.add(binaryKey))return Optional.empty();
        int member=binaryKey.indexOf('#');String owner=member<0?binaryKey:binaryKey.substring(0,member);
        for(int i=0;i<workspace.classpath().size();i++){
            var entry=workspace.classpath().get(i);Integer ownerId=artifacts.binaryId(entry.artifactCacheKey(),owner);
            if(ownerId==null)continue;
            Integer id=member<0?ownerId:artifacts.binaryId(entry.artifactCacheKey(),binaryKey);
            if(id!=null)return Optional.of(new ResolvedSymbol(entry.artifactCacheKey(),id,i));
            // Once a class has been selected, a later duplicate must not supply missing members.
            if(binaryKey.substring(member+1).startsWith("<init>("))return Optional.empty();
            for(var edge:artifacts.outgoing(entry.artifactCacheKey(),ownerId,Set.of("extends","implements"),Integer.MAX_VALUE)){
                var inherited=lookup(workspace,edge.target()+binaryKey.substring(member),visited);
                if(inherited.isPresent()){
                    var symbol=artifacts.symbol(inherited.get().artifactCacheKey(),inherited.get().localId());
                    if(symbol!=null&&(symbol.flags()&2)==0)return inherited;
                }
            }
            return Optional.empty();
        }
        return Optional.empty();
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
        var symbol=artifacts.symbol(resolved.artifactCacheKey(),resolved.localId());
        if(symbol==null)return Optional.empty();
        return Optional.of(workspaceSymbol(entry,symbol));
    }

    public List<ResolvedRelationship> incoming(Workspace workspace,String targetBinaryKey,Set<String> kinds,int limit)throws Exception{
        if(limit<=0)return List.of();
        var target=resolveFirst(workspace,targetBinaryKey);if(target.isEmpty())return List.of();
        var result=new ArrayList<ResolvedRelationship>();
        for(var entry:workspace.classpath()){
            if(result.size()>=limit)break;
            var generationKeys=new ArrayList<String>();generationKeys.add(entry.artifactCacheKey());
            var signature=artifacts.artifactKey(entry.artifactCacheKey());
            if(signature!=null){
                var codeKey=new ArtifactIndexFormat.Key(signature.binarySha256(),signature.formatVersion(),
                        signature.indexerVersion(),signature.runtimeFeature(),"code").cacheKey();
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
        var signature=artifacts.artifactKey(sourceArtifactCacheKey);
        if(signature!=null&&result.size()<limit){
            var codeKey=new ArtifactIndexFormat.Key(signature.binarySha256(),signature.formatVersion(),
                    signature.indexerVersion(),signature.runtimeFeature(),"code").cacheKey();
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
        return findExact(workspace,query,limit,after,Set.of());
    }
    public List<WorkspaceSymbol> findExact(Workspace workspace,String query,int limit,long after,Set<String> kinds)throws Exception{
        if(limit<=0||query.isBlank())return List.of();
        var result=new ArrayList<WorkspaceSymbol>();
        for(int i=0;i<workspace.classpath().size()&&result.size()<limit;i++){
            var entry=workspace.classpath().get(i);int localAfter=localAfter(i,after);if(localAfter==Integer.MAX_VALUE)continue;
            int remaining=limit-result.size();var selected=new TreeMap<Integer,ArtifactIndexFormat.SymbolRecord>();
            Predicate<ArtifactIndexFormat.SymbolRecord> filter=symbol->kinds.isEmpty()||kinds.contains(symbol.kind());
            var prefixes=new ArrayList<String>(List.of("3|name|"+query+"|","7|path|"+query+"|"));
            String scipPrefix=scipPrefix(entry.context());
            if(!scipPrefix.isEmpty()&&query.startsWith(scipPrefix))prefixes.add("2|scip|"+query.substring(scipPrefix.length())+"|");
            for(String prefix:prefixes)for(var symbol:artifacts.select(entry.artifactCacheKey(),prefix,localAfter,remaining,filter))selected.put(symbol.id(),symbol);
            Integer binary=artifacts.binaryId(entry.artifactCacheKey(),query);
            if(binary!=null&&binary>localAfter){
                var symbol=artifacts.symbol(entry.artifactCacheKey(),binary);
                if(symbol!=null&&filter.test(symbol))selected.put(symbol.id(),symbol);
            }
            for(var symbol:selected.values()){
                result.add(workspaceSymbol(entry,symbol));if(result.size()==limit)break;
            }
        }
        return List.copyOf(result);
    }

    public List<WorkspaceSymbol> findName(Workspace workspace,String query,boolean prefix,int limit)throws Exception{
        return findName(workspace,query,prefix,limit,0L);
    }
    public List<WorkspaceSymbol> findName(Workspace workspace,String query,boolean prefix,int limit,long after)throws Exception{
        return select(workspace,"3|name|"+query+(prefix?"":"|"),limit,after,
                symbol->prefix?symbol.name().startsWith(query):symbol.name().equals(query));
    }
    public List<WorkspaceSymbol> findPathPrefix(Workspace workspace,String pathPrefix,int limit)throws Exception{
        return select(workspace,"7|path|"+pathPrefix,limit,0L,symbol->ArtifactContext.namePath(symbol).startsWith(pathPrefix));
    }
    public List<WorkspaceSymbol> findSubstring(Workspace workspace,String query,int limit)throws Exception{
        return findSubstring(workspace,query,limit,0L);
    }
    public List<WorkspaceSymbol> findSubstring(Workspace workspace,String query,int limit,long after)throws Exception{
        return findSubstring(workspace,query,limit,after,Set.of());
    }
    public List<WorkspaceSymbol> findSubstring(Workspace workspace,String query,int limit,long after,Set<String> kinds)throws Exception{
        if(query.isBlank())return List.of();String normalized=query.toLowerCase(Locale.ROOT);
        String gram=normalized.substring(0,Math.min(3,normalized.length()));
        return select(workspace,"8|gram|"+gram+"|",limit,after,symbol->
                (kinds.isEmpty()||kinds.contains(symbol.kind()))&&
                (symbol.name().toLowerCase(Locale.ROOT).contains(normalized)||ArtifactContext.namePath(symbol).toLowerCase(Locale.ROOT).contains(normalized)));
    }
    public Optional<WorkspaceSymbol> byScip(Workspace workspace,String scip)throws Exception{
        for(var entry:workspace.classpath()){
            String prefix=scipPrefix(entry.context());if(prefix.isEmpty()||!scip.startsWith(prefix))continue;
            var symbols=artifacts.select(entry.artifactCacheKey(),"2|scip|"+scip.substring(prefix.length())+"|",-1,1,symbol->scip.equals(entry.context().scip(symbol)));
            if(!symbols.isEmpty())return Optional.of(workspaceSymbol(entry,symbols.getFirst()));
        }
        return Optional.empty();
    }
    private List<WorkspaceSymbol> select(Workspace workspace,String prefix,int limit,long after,
                                         Predicate<ArtifactIndexFormat.SymbolRecord> filter)throws Exception{
        if(limit<=0)return List.of();var result=new ArrayList<WorkspaceSymbol>();
        for(int i=0;i<workspace.classpath().size()&&result.size()<limit;i++){
            var entry=workspace.classpath().get(i);int localAfter=localAfter(i,after);if(localAfter==Integer.MAX_VALUE)continue;
            for(var symbol:artifacts.select(entry.artifactCacheKey(),prefix,localAfter,limit-result.size(),filter))result.add(workspaceSymbol(entry,symbol));
        }
        return List.copyOf(result);
    }
    private static int localAfter(int classpathIndex,long after){
        if(after<cursor(classpathIndex,0))return -1;
        if(after>=cursor(classpathIndex,Integer.MAX_VALUE))return Integer.MAX_VALUE;
        return (int)after;
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

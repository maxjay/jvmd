package dev.jvmd.index.rocks;

import dev.jvmd.core.*;
import dev.jvmd.index.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Predicate;
import org.rocksdb.*;

/**
 * Authoritative index: immutable binary facts plus atomic path and per-file source manifests.
 * No SQL database is opened. Numeric handles are opaque; SCIP remains the external identity.
 * A query holds the metadata monitor, so publication cannot change its selected generations.
 */
public final class RocksIndexStore implements IndexStore {
    public record StoredArtifact(long id,ArtifactInput input,String docsKey,String codeKey,String resolutionIdentity,
                                 long symbols,long edges,boolean classReferences,long sourceRevision,long simpleNames) {
        public StoredArtifact {
            Objects.requireNonNull(input);
            resolutionIdentity=resolutionIdentity==null||resolutionIdentity.isBlank()
                    ?input.key().binarySha256():resolutionIdentity;
        }
    }
    private record LegacySourceFile(String file,String hash,List<Map<String,Object>> symbols,List<SourceRelationship> edges) { }
    private final RocksArtifactRepository repository;
    private final RocksArtifactAdmission admission;
    private final Options options;
    private final RocksDB state;
    private final WriteOptions durable=new WriteOptions().setSync(true);
    private final NavigableMap<Long,StoredArtifact> artifacts=new TreeMap<>();
    private final Map<String,Long> paths=new HashMap<>();
    private final Map<String,List<WorkspaceEntry>> workspaces=new HashMap<>();
    private final SourceOverlay sourceOverlay;
    private final Map<Long,Long> unmatched=new HashMap<>();
    private final LinkedHashMap<String,Hash256> semanticProofIdentities=new LinkedHashMap<>(128,.75f,true);
    private long nextArtifact=1,nextSource=0x80000000L;
    private long metadataWrites,sourceWrites;
    private boolean closing,closed;
    private int builds;
    private static final Set<String> TYPES=Set.of("class","interface","enum","record","annotation");
    private static final Set<String> SOURCE_KINDS=Set.of("package","class","interface","enum","record","annotation","method","ctor","field","enumconst");

    RocksIndexStore(Path root,RocksArtifactRepository repository,RocksMemory memory,RocksArtifactAdmission admission)throws Exception{
        this.repository=repository;this.admission=admission;Files.createDirectories(root);options=memory.options(64);
        state=RocksDB.open(options,root.toString());sourceOverlay=new SourceOverlay(state);
        try{
            for(byte[] value:values("A|")){
                var artifact=Json.MAPPER.readValue(value,StoredArtifact.class);
                if(!repository.contains(artifact.input().key().cacheKey())&&!artifact.input().context().kind().equals("sources"))
                    throw new IllegalStateException("Artifact manifest references absent generation: "+artifact.id());
                artifacts.put(artifact.id(),artifact);paths.put(artifact.input().context().path(),artifact.id());
                nextArtifact=Math.max(nextArtifact,artifact.id()+1);
                byte[] count=state.get(bytes("U|"+key(artifact.id())));if(count!=null)unmatched.put(artifact.id(),Long.parseLong(new String(count,StandardCharsets.UTF_8)));
            }
            migrateSources();
            byte[] sequence=state.get(bytes("next-source"));if(sequence!=null)nextSource=Long.parseLong(new String(sequence,StandardCharsets.UTF_8));
            sequence=state.get(bytes("next-artifact"));if(sequence!=null)nextArtifact=Math.max(nextArtifact,Long.parseLong(new String(sequence,StandardCharsets.UTF_8)));
        }catch(Exception error){sourceOverlay.close();state.close();options.close();durable.close();throw error;}
    }
    @Override public String backend(){return "rocksdb-sst";}
    private static byte[] bytes(String value){return value.getBytes(StandardCharsets.UTF_8);}
    private static String key(long id){return String.format(Locale.ROOT,"%016x",id);}
    private static String location(Path path){return path.getFileSystem().provider().getScheme().equals("file")?path.toAbsolutePath().normalize().toString():path.toUri().toString();}
    private List<byte[]> values(String prefix)throws Exception{
        var result=new ArrayList<byte[]>();byte[] start=bytes(prefix);
        try(var iterator=state.newIterator()){
            for(iterator.seek(start);iterator.isValid()&&new String(iterator.key(),StandardCharsets.UTF_8).startsWith(prefix);iterator.next())result.add(iterator.value());
            iterator.status();
        }return result;
    }
    private void save(WriteBatch batch,StoredArtifact artifact)throws Exception{
        batch.put(bytes("A|"+key(artifact.id())),Json.MAPPER.writeValueAsBytes(artifact));batch.put(bytes("next-artifact"),bytes(Long.toString(nextArtifact)));
    }
    private void installed(StoredArtifact artifact){artifacts.put(artifact.id(),artifact);paths.put(artifact.input().context().path(),artifact.id());metadataWrites++;}
    private StoredArtifact required(long id){ensureOpen();var value=artifacts.get(id);if(value==null)throw new IllegalArgumentException("Unknown artifact: "+id);return value;}
    private void ensureOpen(){if(closed)throw new IllegalStateException("Index store is closed");}
    private synchronized AutoCloseable admitBuild(){
        ensureOpen();if(closing)throw new IllegalStateException("Index store is closing");builds++;
        return ()->{synchronized(this){builds--;notifyAll();}};
    }
    private static ArtifactRecord record(StoredArtifact value){var input=value.input();return new ArtifactRecord(value.id(),input.context().gav(),input.context().kind(),input.key().binarySha256(),input.context().path(),input.size(),input.mtime(),value.docsKey()!=null,value.codeKey()!=null,!input.context().kind().equals("sources"));}
    @Override public synchronized ArtifactRecord artifact(Path path){Long id=paths.get(location(path));return id==null?null:record(required(id));}
    @Override public synchronized void publishPath(Path path,long id,long size,long mtime)throws Exception{
        var previous=required(id);String pathString=location(path);
        // Aliases keep independent context handles while sharing immutable content.
        Long alias=paths.get(pathString);
        long selected=pathString.equals(previous.input().context().path())?id:alias==null?nextArtifact++:alias;
        var context=new ArtifactContext(previous.input().context().gav(),previous.input().context().kind(),pathString);
        var value=new StoredArtifact(selected,new ArtifactInput(context,previous.input().key(),size,mtime),previous.docsKey(),previous.codeKey(),previous.resolutionIdentity(),previous.symbols(),previous.edges(),previous.classReferences(),previous.sourceRevision(),previous.simpleNames());
        try(var batch=new WriteBatch()){save(batch,value);state.write(durable,batch);}installed(value);
    }
    @Override public long publishArtifact(ArtifactInput input,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences,Map<String,Map<String,Object>> sourceData)throws Exception{
        try(var permit=admitBuild();var capacity=admission.acquirePublication(facts,classReferences)){return publishArtifactData(input,facts,classReferences,sourceData);}
    }
    private long publishArtifactData(ArtifactInput input,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences,Map<String,Map<String,Object>> sourceData)throws Exception{
        if(!input.key().equals(facts.key()))throw new IllegalArgumentException("Artifact facts/key mismatch");
        repository.publish(facts,classReferences);
        String docs=null;
        if(!sourceData.isEmpty()){
            String hash=Hashing.sha256(Json.MAPPER.writeValueAsBytes(new TreeMap<>(sourceData)));
            docs=repository.publishDocumentation(input.key().cacheKey(),ArtifactIndexFormat.key(hash,"sources"),sourceData,0);
        }
        synchronized(this){
            Long existing=paths.get(input.context().path());long id=existing==null?nextArtifact++:existing;
            var previous=artifacts.get(id);
            boolean same=previous!=null&&previous.input().key().equals(input.key());
            var value=new StoredArtifact(id,input,docs,same?previous.codeKey():null,ArtifactIndexFormat.resolutionIdentity(facts).hex(),facts.symbols().size(),facts.relationships().size(),!classReferences.isEmpty(),previous==null?0:previous.sourceRevision(),facts.symbols().stream().filter(symbol->TYPES.contains(symbol.kind())).count());
            try(var batch=new WriteBatch()){
                // Preserve unchanged source files when another file changes the module fingerprint.
                if(input.context().kind().equals("local"))for(var file:sourceOverlay.files(id)){
                    Path path=Path.of(file.file());
                    if(!Files.isRegularFile(path)||!Hashing.sha256(path).equals(file.hash()))sourceOverlay.remove(batch,id,file.file());
                }
                save(batch,value);state.write(durable,batch);
            }
            installed(value);return id;
        }
    }
    @Override public void publishCode(long id,ArtifactContext context,ArtifactIndexFormat.ArtifactData facts,Set<String> references)throws Exception{
        try(var permit=admitBuild();var capacity=admission.acquirePublication(facts,references)){publishCodeData(id,context,facts,references);}
    }
    private void publishCodeData(long id,ArtifactContext context,ArtifactIndexFormat.ArtifactData facts,Set<String> references)throws Exception{
        repository.publish(facts,references);
        synchronized(this){var old=required(id);
            if(!old.input().key().binarySha256().equals(facts.key().binarySha256()))throw new IllegalStateException("Binary changed during code indexing");
            var value=new StoredArtifact(id,old.input(),old.docsKey(),facts.key().cacheKey(),old.resolutionIdentity(),facts.symbols().size(),old.edges(),old.classReferences()||!references.isEmpty(),old.sourceRevision(),old.simpleNames());
            try(var batch=new WriteBatch()){save(batch,value);state.write(durable,batch);}installed(value);
        }
    }
    @Override public synchronized void publishClassReferences(long id,Set<String> references)throws Exception{
        // Older formats may lack the class-reference facts; store a small independent supplement.
        var old=required(id);var value=new StoredArtifact(id,old.input(),old.docsKey(),old.codeKey(),old.resolutionIdentity(),old.symbols(),old.edges(),true,old.sourceRevision(),old.simpleNames());
        try(var batch=new WriteBatch()){
            for(String target:references)batch.put(bytes("C|"+key(id)+"|"+target),new byte[0]);
            save(batch,value);state.write(durable,batch);
        }installed(value);
    }
    /** Migrate each legacy owner with its postings in one durable batch; interruption is restartable. */
    private void migrateSources()throws Exception{
        try(var iterator=state.newIterator()){
            for(iterator.seek(bytes("S|"));iterator.isValid()&&new String(iterator.key(),StandardCharsets.UTF_8).startsWith("S|");iterator.next()){
                String key=new String(iterator.key(),StandardCharsets.UTF_8);long id=Long.parseUnsignedLong(key.substring(2,18),16);
                var file=Json.MAPPER.readValue(iterator.value(),LegacySourceFile.class);
                try(var batch=new WriteBatch()){
                    sourceOverlay.replace(batch,id,new SourceOverlay.FileStamp(file.file(),file.hash()),file.symbols(),file.edges());
                    batch.delete(iterator.key());state.write(durable,batch);
                }
            }iterator.status();
        }
    }
    private static String binaryKey(Map<String,Object> symbol){
        if(symbol.get("binary_key")!=null)return symbol.get("binary_key").toString();
        String fqn=Objects.toString(symbol.get("fqn"),Objects.toString(symbol.get("name_path"),""));
        if(TYPES.contains(symbol.get("kind")))return fqn;
        return fqn+"#"+("ctor".equals(symbol.get("kind"))?"<init>":symbol.get("name"))+
                (Set.of("method","ctor").contains(symbol.get("kind"))?Objects.toString(symbol.get("erased_descriptor"),""):"");
    }
    @Override public synchronized void publishSourceFile(long id,Path file,String contentHash,List<Map<String,Object>> symbols,int tier,List<SourceRelationship> relationships)throws Exception{
        var old=required(id);String path=location(file);var rows=new ArrayList<Map<String,Object>>();
        for(var symbol:symbols){
            if(symbol.get("scip")==null||!SOURCE_KINDS.contains(symbol.get("kind"))||!path.equals(symbol.get("source_file")))continue;
            if(nextSource>0xffffffffL)throw new IllegalStateException("Source symbol handle space exhausted");
            var row=new LinkedHashMap<String,Object>(symbol);row.put("id",(id<<32)|nextSource++);row.put("artifact_id",id);row.put("tier",tier);
            row.put("binary_key",binaryKey(symbol));row.putIfAbsent("metadata",Map.of());rows.add(row);
        }
        var source=new SourceOverlay.FileStamp(path,contentHash);
        var value=new StoredArtifact(id,old.input(),old.docsKey(),old.codeKey(),old.resolutionIdentity(),old.symbols(),old.edges(),old.classReferences(),old.sourceRevision()+1,old.simpleNames());
        try(var batch=new WriteBatch()){
            sourceOverlay.replace(batch,id,source,rows,relationships);batch.put(bytes("next-source"),bytes(Long.toString(nextSource)));
            save(batch,value);state.write(durable,batch);
        }
        installed(value);sourceWrites++;
    }
    @Override public long publishDocumentation(long binaryId,ArtifactInput input,Map<String,Map<String,Object>> members,int unmatched)throws Exception{
        try(var permit=admitBuild()){return publishDocumentationData(binaryId,input,members,unmatched);}
    }
    private long publishDocumentationData(long binaryId,ArtifactInput input,Map<String,Map<String,Object>> members,int unmatched)throws Exception{
        StoredArtifact binary; synchronized(this){binary=required(binaryId);}
        String docs=repository.publishDocumentation(binary.input().key().cacheKey(),input.key(),members,unmatched);
        synchronized(this){
            if(!required(binaryId).input().key().equals(binary.input().key()))throw new IllegalStateException("Binary changed during documentation indexing");
            Long existing=paths.get(input.context().path());long id=existing==null?nextArtifact++:existing;
            var source=new StoredArtifact(id,input,docs,null,input.key().binarySha256(),0,0,false,0,0);
            var updated=new StoredArtifact(binaryId,binary.input(),docs,binary.codeKey(),binary.resolutionIdentity(),binary.symbols(),binary.edges(),binary.classReferences(),binary.sourceRevision(),binary.simpleNames());
            try(var batch=new WriteBatch()){save(batch,source);save(batch,updated);batch.put(bytes("U|"+key(id)),bytes(Integer.toString(unmatched)));state.write(durable,batch);}
            this.unmatched.put(id,(long)unmatched);installed(source);installed(updated);return id;
        }
    }
    @Override public void resolveGlobalRelationships(){/* Targets remain symbolic until a workspace query. */}
    @Override public synchronized boolean reconcilePaths(Path root,Set<Path> present)throws Exception{
        var removed=new ArrayList<StoredArtifact>();Path normalized=root.toAbsolutePath().normalize();
        for(var value:artifacts.values()){
            var context=value.input().context();if(!Set.of("jar","sources").contains(context.kind())||context.path().startsWith("jrt:"))continue;
            Path path=Path.of(context.path());if(path.startsWith(normalized)&&!present.contains(path))removed.add(value);
        }
        if(removed.isEmpty())return false;
        try(var batch=new WriteBatch()){for(var value:removed)batch.delete(bytes("A|"+key(value.id())));state.write(durable,batch);}
        for(var value:removed){artifacts.remove(value.id());paths.remove(value.input().context().path());unmatched.remove(value.id());}metadataWrites+=removed.size();return true;
    }
    @Override public synchronized Map<String,Long> counts(){return Map.of("artifacts",(long)artifacts.size(),"symbols",artifacts.values().stream().mapToLong(StoredArtifact::symbols).sum(),"edges",artifacts.values().stream().mapToLong(StoredArtifact::edges).sum(),"simple_names",artifacts.values().stream().mapToLong(StoredArtifact::simpleNames).sum(),"unmatched_source_members",unmatched.values().stream().mapToLong(Long::longValue).sum());}
    @Override public synchronized Map<String,Object> status(){return Map.of("backend",backend(),"link_passes",0L,"metadata_writes",metadataWrites,"source_file_writes",sourceWrites,"source_record_decodes",sourceOverlay.decoded(),"source_cache_estimated_bytes",sourceOverlay.cacheBytes(),"source_cache_budget_bytes",sourceOverlay.cacheBudget());}

    private List<StoredArtifact> selected(String workspace,boolean jdk){
        ensureOpen();
        var selected=new LinkedHashMap<Long,StoredArtifact>();
        if(workspace==null)artifacts.values().stream().filter(a->!a.input().context().kind().equals("sources"))
                .sorted(Comparator.comparingInt(a->a.input().context().kind().equals("local")?0:1)).forEach(a->selected.put(a.id(),a));
        else{
            // Local declarations take precedence over installed copies with identical coordinates.
            var entries=workspaces.getOrDefault(workspace,List.of());
            for(boolean local:List.of(true,false))for(var entry:entries){
                Long id=paths.get(entry.path());if(id==null)continue;var value=artifacts.get(id);
                if(value!=null&&value.input().context().kind().equals("local")==local)selected.put(id,value);
            }
            if(jdk)for(var value:artifacts.values())if(value.input().context().gav().startsWith("jdk:"))selected.putIfAbsent(value.id(),value);
        }
        return List.copyOf(selected.values());
    }
    private List<StoredArtifact> selected(String workspace,boolean jdk,SemanticLayer layer){
        return selected(workspace,jdk).stream()
                .filter(artifact->layer==SemanticLayer.LOCAL
                        ?artifact.input().context().kind().equals("local")
                        :!artifact.input().context().kind().equals("local"))
                .toList();
    }
    @Override public synchronized Optional<ClasspathSequence> semanticClasspathSequence(String workspace){
        var entries=new ArrayList<ClasspathSequence.Entry>();
        for(var artifact:selected(workspace,false,SemanticLayer.MACHINE)){
            if(artifact.input().context().kind().equals("sources"))continue;
            entries.add(new ClasspathSequence.Entry(
                    artifact.input().context().path(),
                    Hash256.fromHex(artifact.resolutionIdentity())));
        }
        return Optional.of(ClasspathSequence.of(entries));
    }
    @Override public synchronized Optional<ClasspathSearchProof> semanticClasspathSearch(String workspace,String binaryName)throws Exception{
        Objects.requireNonNull(binaryName);
        int searched=0;
        for(var artifact:selected(workspace,false,SemanticLayer.MACHINE)){
            if(artifact.input().context().kind().equals("sources"))continue;
            searched++;
            var symbol=semanticType(artifact,binaryName,SemanticLayer.MACHINE);
            if(symbol!=null)return Optional.of(new ClasspathSearchProof(
                    binaryName,searched,artifact.input().context().path(),symbol.id(),symbol.resolution().identity()));
        }
        return Optional.of(new ClasspathSearchProof(binaryName,searched,null,null,null));
    }

    private String semanticProofCacheKey(String ownerScip,String value,String workspace,SemanticLayer layer,String domain)throws Exception{
        var owner=byScip(ownerScip,workspace,layer);if(owner==null)return null;
        long artifactId=((Number)owner.get("artifact_id")).longValue();var artifact=required(artifactId);
        return domain+"|"+layer+"|"+artifact.id()+"|"+artifact.resolutionIdentity()+"|"+artifact.sourceRevision()+"|"
                +Objects.toString(artifact.codeKey(),"")+"|"+ownerScip+"|"+Objects.requireNonNullElse(value,"");
    }
    private Hash256 cacheSemanticProof(String key,java.util.concurrent.Callable<Hash256> loader)throws Exception{
        if(key==null)return loader.call();
        var cached=semanticProofIdentities.get(key);if(cached!=null)return cached;
        var value=loader.call();semanticProofIdentities.put(key,value);
        while(semanticProofIdentities.size()>4096)semanticProofIdentities.remove(semanticProofIdentities.keySet().iterator().next());
        return value;
    }
    @Override public synchronized Hash256 semanticMemberRangeIdentity(String ownerScip,String prefix,String workspace,SemanticLayer layer)throws Exception{
        String key=semanticProofCacheKey(ownerScip,prefix,workspace,layer,"range");
        return cacheSemanticProof(key,()->IndexStore.super.semanticMemberRangeIdentity(ownerScip,prefix,workspace,layer));
    }
    @Override public synchronized Hash256 semanticOverloadGroupIdentity(String ownerScip,String name,String workspace,SemanticLayer layer)throws Exception{
        String key=semanticProofCacheKey(ownerScip,name,workspace,layer,"overload");
        return cacheSemanticProof(key,()->IndexStore.super.semanticOverloadGroupIdentity(ownerScip,name,workspace,layer));
    }

    @Override public synchronized List<String> loadWorkspace(String workspace,List<WorkspaceEntry> entries,List<Map.Entry<String,String>> dependencies)throws Exception{
        workspaces.put(workspace,entries.stream().map(e->new WorkspaceEntry(Path.of(e.path()).toAbsolutePath().normalize().toString(),e.scope())).toList());
        var classes=new TreeMap<String,Set<String>>();var packages=new TreeMap<String,Set<String>>();
        for(var artifact:selected(workspace,false))for(var symbol:repository.select(symbolsKey(artifact),"0|type|",-1,Integer.MAX_VALUE,s->TYPES.contains(s.kind()))){
            var context=artifact.input().context();String label=context.gav()+" ["+context.path()+"]";
            classes.computeIfAbsent(symbol.fqn(),ignored->new LinkedHashSet<>()).add(label);
            int dot=symbol.fqn().lastIndexOf('.');String pkg=dot<0?"":symbol.fqn().substring(0,dot);
            packages.computeIfAbsent(pkg,ignored->new LinkedHashSet<>()).add(label);
        }
        var warnings=new ArrayList<String>();classes.forEach((name,owners)->{if(owners.size()>1)warnings.add("duplicate_class: "+name+": "+String.join("; ",owners));});
        packages.forEach((name,owners)->{if(owners.size()>1)warnings.add("split_package: "+name+": "+String.join("; ",owners));});return warnings;
    }

    private static String symbolsKey(StoredArtifact artifact){return artifact.codeKey()==null?artifact.input().key().cacheKey():artifact.codeKey();}
    private long symbolId(StoredArtifact artifact,ArtifactIndexFormat.SymbolRecord symbol)throws Exception{
        if(artifact.codeKey()==null)return (artifact.id()<<32)|Integer.toUnsignedLong(symbol.id());
        Integer original=repository.binaryId(artifact.input().key().cacheKey(),symbol.key());
        return (artifact.id()<<32)|(original==null?0x40000000L|Integer.toUnsignedLong(symbol.id()):Integer.toUnsignedLong(original));
    }
    private IndexedSemanticSymbol indexedSemantic(StoredArtifact artifact,ArtifactIndexFormat.SymbolRecord symbol,SemanticLayer layer){
        var context=artifact.input().context();
        return new IndexedSemanticSymbol(
                context.scip(symbol),symbol.name(),symbol.kind(),symbol.fqn(),symbol.key(),
                Objects.toString(symbol.signature(),""),Objects.toString(symbol.descriptor(),""),
                symbol.resolution(),symbol.parameters(),null,layer);
    }
    private IndexedSemanticSymbol indexedSemantic(StoredArtifact artifact,Map<String,Object> row,SemanticLayer layer){
        String id=Objects.toString(row.get("scip"),"");
        String binary=Objects.toString(row.get("binary_key"),id);
        String fqn=Objects.toString(row.get("fqn"),"");
        String name=Objects.toString(row.get("name"),"");
        String kind=Objects.toString(row.get("kind"),"");
        String signature=Objects.toString(row.get("signature"),"");
        String descriptor=Objects.toString(row.get("erased_descriptor"),"");
        int flags=row.get("flags") instanceof Number value?value.intValue():0;
        Object encoded=row.get("resolution_fact");
        ResolutionFact resolution=encoded instanceof ResolutionFact value?value
                :encoded instanceof String value?ResolutionFact.decode(value)
                :ResolutionFact.legacy(binary,fqn,name,kind,descriptor,flags);
        var parameters=new ArrayList<String>();
        Object raw=row.get("parameters");
        if(raw instanceof Iterable<?> values)for(Object value:values)parameters.add(Objects.toString(value,""));
        String source=row.get("source_file")==null?null:row.get("source_file").toString();
        return new IndexedSemanticSymbol(id,name,kind,fqn,binary,signature,descriptor,resolution,List.copyOf(parameters),source,layer);
    }

    /** Semantic row without documentation/source enrichment. */
    private Map<String,Object> semanticRow(StoredArtifact artifact,ArtifactIndexFormat.SymbolRecord symbol)throws Exception{
        var context=artifact.input().context();var result=new LinkedHashMap<String,Object>();
        result.put("id",symbolId(artifact,symbol));result.put("artifact_id",artifact.id());
        result.put("owner_id",symbol.ownerId()<0?null:artifact.codeKey()==null?(artifact.id()<<32)|Integer.toUnsignedLong(symbol.ownerId()):symbolId(artifact,repository.symbol(symbolsKey(artifact),symbol.ownerId())));result.put("flags",symbol.flags());result.put("line",null);
        for(String name:List.of("source_start","source_end","body_start","body_end"))result.put(name,-1);
        result.put("scip",context.scip(symbol));result.put("kind",symbol.kind());result.put("name",symbol.name());result.put("name_path",ArtifactContext.namePath(symbol));
        result.put("signature",symbol.signature());result.put("erased_descriptor",symbol.descriptor());result.put("source_file",null);result.put("doc",null);
        result.put("fqn",symbol.fqn());result.put("binary_key",symbol.key());result.put("class_entry",symbol.entry());result.put("parameters",Json.MAPPER.valueToTree(symbol.parameters()));
        result.put("metadata",Json.MAPPER.readTree(symbol.metadataJson()));result.put("resolution_fact",symbol.resolution().encode());result.put("resolution_identity",symbol.resolution().identity().hex());result.put("tier",2);
        return contextual(artifact,result);
    }
    private Map<String,Object> row(StoredArtifact artifact,ArtifactIndexFormat.SymbolRecord symbol)throws Exception{
        var result=new LinkedHashMap<String,Object>(semanticRow(artifact,symbol));
        if(artifact.docsKey()!=null){
            var overlay=new LinkedHashMap<>(repository.documentation(artifact.docsKey(),symbol.key()));
            Object names=overlay.remove("parameters");
            if(names instanceof List<?> parameters&&!parameters.isEmpty()&&!Json.MAPPER.readTree(symbol.metadataJson()).path("parameter_names_from_class").asBoolean()){
                String signature=Objects.toString(symbol.signature(),"");
                for(int i=0;i<Math.min(parameters.size(),symbol.parameters().size());i++)signature=signature.replaceAll("\\b"+java.util.regex.Pattern.quote(symbol.parameters().get(i))+"\\b",java.util.regex.Matcher.quoteReplacement(parameters.get(i).toString()));
                result.put("signature",signature);result.put("parameters",Json.MAPPER.valueToTree(parameters));
            }
            result.putAll(overlay);
        }
        return result;
    }
    private static Map<String,Object> contextual(StoredArtifact artifact,Map<String,Object> value){
        var result=new LinkedHashMap<>(value);var context=artifact.input().context();
        result.put("artifact_id",artifact.id());result.put("gav",context.gav());result.put("artifact_path",context.path());result.put("artifact_kind",context.kind());
        result.put("parameters",Json.MAPPER.valueToTree(result.getOrDefault("parameters",List.of())));
        result.put("metadata",Json.MAPPER.valueToTree(result.getOrDefault("metadata",Map.of())));
        if(!result.containsKey("resolution_identity")&&result.get("resolution_fact") instanceof String encoded)try{
            result.put("resolution_identity",ResolutionFact.decode(encoded).identity().hex());
        }catch(IllegalArgumentException ignored){}
        return result;
    }
    private static Map<String,Object> searchFields(StoredArtifact artifact,ArtifactIndexFormat.SymbolRecord symbol){
        var result=new HashMap<String,Object>();result.put("scip",artifact.input().context().scip(symbol));result.put("kind",symbol.kind());
        result.put("name",symbol.name());result.put("name_path",ArtifactContext.namePath(symbol));result.put("binary_key",symbol.key());result.put("erased_descriptor",symbol.descriptor());return result;
    }
    private Map<String,Object> sourceByScip(StoredArtifact artifact,String scip)throws Exception{
        var symbol=sourceOverlay.first(artifact.id(),"scip",scip);return symbol==null?null:contextual(artifact,symbol);
    }
    private boolean sourcePreferred(StoredArtifact artifact,Map<String,Object> symbol,List<StoredArtifact> selected){
        try{return preferred(artifact,symbol.get("scip").toString(),selected);}catch(Exception error){throw new IllegalStateException(error);}
    }
    private boolean preferred(StoredArtifact artifact,String scip,List<StoredArtifact> selected)throws Exception{
        for(var earlier:selected){
            if(earlier.id()==artifact.id())break;
            if(!earlier.input().context().gav().equals(artifact.input().context().gav()))continue;
            if(sourceByScip(earlier,scip)!=null)return false;
            int suffix=scip.indexOf(' ',scip.indexOf(' ',scip.indexOf(' ')+1)+1)+1;
            if(!repository.select(symbolsKey(earlier),"2|scip|"+scip.substring(suffix)+"|",-1,1,s->earlier.input().context().scip(s).equals(scip)).isEmpty())return false;
        }return true;
    }
    @Override public synchronized Map<String,Object> byScip(String scip,String workspace)throws Exception{
        return byScipFrom(scip,selected(workspace,true));
    }
    @Override public synchronized Map<String,Object> byScip(String scip,String workspace,SemanticLayer layer)throws Exception{
        return byScipFrom(scip,selected(workspace,true,layer));
    }
    @Override public synchronized IndexedSemanticSymbol semanticByScip(String scip,String workspace,SemanticLayer layer)throws Exception{
        for(var artifact:selected(workspace,true,layer)){
            var source=sourceOverlay.semanticFirst(artifact.id(),"scip",scip,layer);
            if(source!=null)return source;
            var context=artifact.input().context();String[] gav=context.gav().split(":",3);
            String prefix="maven "+gav[0]+"/"+gav[1]+" "+gav[2]+" ";
            if(!scip.startsWith(prefix))continue;
            var found=repository.select(symbolsKey(artifact),"2|scip|"+scip.substring(prefix.length())+"|",-1,1,
                    symbol->context.scip(symbol).equals(scip));
            if(!found.isEmpty())return indexedSemantic(artifact,found.getFirst(),layer);
        }
        return null;
    }
    @Override public synchronized List<IndexedSemanticSymbol> semanticTypesByName(String simpleName,String workspace,int limit,SemanticLayer layer)throws Exception{
        if(limit<=0)return List.of();
        var result=new ArrayList<IndexedSemanticSymbol>(Math.min(limit,64));var seen=new HashSet<String>();
        for(var artifact:selected(workspace,true,layer)){
            for(var entry:sourceOverlay.semanticSelect(artifact.id(),simpleName,true,limit,0,layer,
                    value->simpleName.equals(value.name())&&TYPES.contains(value.kind()))){
                var value=entry.symbol();
                if(seen.add(value.id()))result.add(value);
                if(result.size()>=limit)return List.copyOf(result);
            }
            var matches=repository.select(symbolsKey(artifact),"3|name|"+simpleName+"|",-1,limit,
                    symbol->simpleName.equals(symbol.name())&&TYPES.contains(symbol.kind()));
            for(var symbol:matches){
                String scip=artifact.input().context().scip(symbol);
                if(sourceOverlay.contains(artifact.id(),scip)||!seen.add(scip))continue;
                result.add(indexedSemantic(artifact,symbol,layer));
                if(result.size()>=limit)return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }
    private IndexedSemanticSymbol semanticType(StoredArtifact artifact,String binaryName,SemanticLayer layer)throws Exception{
        var source=sourceOverlay.semanticFirst(artifact.id(),"binary_key",binaryName,layer);
        if(source!=null&&TYPES.contains(source.kind()))return source;
        Integer id=repository.binaryId(symbolsKey(artifact),binaryName);
        if(id==null)return null;
        var symbol=repository.symbol(symbolsKey(artifact),id);
        return symbol!=null&&TYPES.contains(symbol.kind())?indexedSemantic(artifact,symbol,layer):null;
    }
    @Override public synchronized IndexedSemanticSymbol semanticType(String binaryName,String workspace,SemanticLayer layer)throws Exception{
        for(var artifact:selected(workspace,true,layer)){
            var value=semanticType(artifact,binaryName,layer);
            if(value!=null)return value;
        }
        return null;
    }
    private Map<String,Object> byScipFrom(String scip,List<StoredArtifact> candidates)throws Exception{
        for(var artifact:candidates){
            var source=sourceByScip(artifact,scip);if(source!=null)return source;
            var context=artifact.input().context();String[] gav=context.gav().split(":",3);String prefix="maven "+gav[0]+"/"+gav[1]+" "+gav[2]+" ";
            if(!scip.startsWith(prefix))continue;
            var found=repository.select(symbolsKey(artifact),"2|scip|"+scip.substring(prefix.length())+"|",-1,1,s->context.scip(s).equals(scip));
            if(!found.isEmpty())return row(artifact,found.getFirst());
        }return null;
    }
    @Override public synchronized Map<String,Object> byId(long id,String workspace)throws Exception{
        long artifactId=id>>>32;var artifact=artifacts.get(artifactId);if(artifact==null||selected(workspace,true).stream().noneMatch(a->a.id()==artifactId))return null;
        if((id&0x80000000L)!=0){var symbol=sourceOverlay.byId(artifactId,id);return symbol==null?null:contextual(artifact,symbol);}
        String generation=(id&0x40000000L)!=0?artifact.codeKey():artifact.input().key().cacheKey();if(generation==null)return null;
        var symbol=repository.symbol(generation,(int)(id&0x3fffffffL));if(symbol==null)return null;
        if(artifact.codeKey()!=null){Integer enriched=repository.binaryId(artifact.codeKey(),symbol.key());if(enriched!=null)symbol=repository.symbol(artifact.codeKey(),enriched);}
        var source=sourceByScip(artifact,artifact.input().context().scip(symbol));return source==null?row(artifact,symbol):source;
    }
    @Override public synchronized List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds)throws Exception{
        return findMatching(query,workspace,substring,limit,after,kinds,ignored->true);
    }
    /** Preserve the established navigation/search contract while semantic reads retain full declarations. */
    private static boolean searchVisible(StoredArtifact artifact,ArtifactIndexFormat.SymbolRecord symbol){
        return "local".equals(artifact.input().context().kind())||artifact.codeKey()!=null
                ||(symbol.flags()&java.lang.classfile.ClassFile.ACC_PRIVATE)==0;
    }
    private static boolean searchVisible(StoredArtifact artifact,Map<String,Object> symbol){
        return "local".equals(artifact.input().context().kind())||artifact.codeKey()!=null||(flags(symbol)&2)==0;
    }
    @Override public synchronized List<Map<String,Object>> findNamePrefix(String prefix,String workspace,int limit,Set<String> kinds)throws Exception{
        if(limit<=0)return List.of();
        var found=new TreeMap<Long,Map<String,Object>>();var seen=new HashSet<String>();var selectedArtifacts=selected(workspace,true);
        for(var artifact:selectedArtifacts){
            for(var symbol:sourceOverlay.select(artifact.id(),prefix,false,true,limit,0,
                    value->searchVisible(artifact,value)&&sourcePreferred(artifact,value,selectedArtifacts)&&!seen.contains(value.get("scip").toString())&&(kinds.isEmpty()||kinds.contains(value.get("kind")))&&Objects.toString(value.get("name"),"").startsWith(prefix))){
                String scip=symbol.get("scip").toString();if(seen.add(scip)&&preferred(artifact,scip,selectedArtifacts))offer(found,contextual(artifact,symbol),0,limit);
            }
            String posting="3|name|"+prefix;
            Predicate<ArtifactIndexFormat.SymbolRecord> accepts=s->{try{
                if(!searchVisible(artifact,s)||!kinds.isEmpty()&&!kinds.contains(s.kind())||!s.name().startsWith(prefix))return false;
                String scip=artifact.input().context().scip(s);
                return !sourceOverlay.contains(artifact.id(),scip)&&!seen.contains(scip)&&preferred(artifact,scip,selectedArtifacts);
            }catch(Exception e){throw new IllegalStateException(e);}};
            var matches=artifact.codeKey()==null?
                    repository.selectLocalIds(symbolsKey(artifact),posting,artifact.id()<<32,0,limit,accepts):
                    repository.selectRanked(symbolsKey(artifact),posting,0,limit,accepts,s->{try{return symbolId(artifact,s);}catch(Exception e){throw new IllegalStateException(e);}});
            for(var symbol:matches){
                var value=row(artifact,symbol);String scip=value.get("scip").toString();
                if(searchVisible(artifact,value)&&!sourceOverlay.contains(artifact.id(),scip)&&seen.add(scip)&&preferred(artifact,scip,selectedArtifacts)
                        &&(kinds.isEmpty()||kinds.contains(value.get("kind")))&&Objects.toString(value.get("name"),"").startsWith(prefix))
                    offer(found,value,0,limit);
            }
        }
        return List.copyOf(found.values());
    }
    @Override public synchronized MemberPage membersByOwner(String ownerScip,String prefix,String workspace,int limit,String cursor)throws Exception{
        if(limit<=0)return new MemberPage(List.of(),null);
        var owner=byScip(ownerScip,workspace);if(owner==null)return new MemberPage(List.of(),null);
        return membersByOwner(ownerScip,prefix,limit,cursor,owner);
    }
    @Override public synchronized MemberPage membersByOwner(String ownerScip,String prefix,String workspace,int limit,String cursor,SemanticLayer layer)throws Exception{
        if(limit<=0)return new MemberPage(List.of(),null);
        var owner=byScip(ownerScip,workspace,layer);if(owner==null)return new MemberPage(List.of(),null);
        return membersByOwner(ownerScip,prefix,limit,cursor,owner);
    }
    @Override public synchronized SemanticMemberPage semanticMembersByOwner(String ownerScip,String prefix,String workspace,
                                                                            int limit,String cursor,SemanticLayer layer)throws Exception{
        if(limit<=0)return new SemanticMemberPage(List.of(),null);
        var owner=byScip(ownerScip,workspace,layer);if(owner==null)return new SemanticMemberPage(List.of(),null);
        long artifactId=((Number)owner.get("artifact_id")).longValue();var artifact=required(artifactId);
        String ownerFqn=Objects.toString(owner.get("fqn"),Objects.toString(owner.get("binary_key"),""));
        if(sourceOverlay.contains(artifactId,ownerScip)){
            long after=0;
            if(cursor!=null){
                if(!cursor.startsWith("source:"))throw new IllegalArgumentException("Invalid local semantic member cursor");
                after=Long.parseUnsignedLong(cursor.substring("source:".length()));
            }
            var values=sourceOverlay.semanticSelect(artifactId,Objects.requireNonNullElse(prefix,""),true,limit+1,after,layer,
                    value->ownerFqn.equals(value.fqn())
                            &&Set.of("method","ctor","field","enumconst","class","interface","record","enum","annotation").contains(value.kind()));
            boolean more=values.size()>limit;var page=values.subList(0,Math.min(limit,values.size()));
            var typed=page.stream().map(SourceOverlay.SemanticEntry::symbol).toList();
            String next=more?"source:"+Long.toUnsignedString(page.getLast().handle()&0xffffffffL):null;
            return new SemanticMemberPage(typed,next);
        }
        String generation=symbolsKey(artifact);
        String binaryOwner=Objects.toString(owner.get("binary_key"),"");
        String token=cursor==null?null:cursor.startsWith("binary:")?cursor.substring("binary:".length()):null;
        if(cursor!=null&&token==null)throw new IllegalArgumentException("Invalid binary semantic member cursor");
        var page=repository.ownerMembers(generation,binaryOwner,Objects.requireNonNullElse(prefix,""),limit,token);
        var typed=new ArrayList<IndexedSemanticSymbol>(page.symbols().size());
        for(var symbol:page.symbols())typed.add(indexedSemantic(artifact,symbol,layer));
        return new SemanticMemberPage(typed,page.cursor()==null?null:"binary:"+page.cursor());
    }
    private MemberPage membersByOwner(String ownerScip,String prefix,int limit,String cursor,Map<String,Object> owner)throws Exception{
        long artifactId=((Number)owner.get("artifact_id")).longValue();var artifact=required(artifactId);
        String ownerFqn=Objects.toString(owner.get("fqn"),Objects.toString(owner.get("binary_key"),""));

        if(sourceOverlay.contains(artifactId,ownerScip)){
            long after=0;
            if(cursor!=null){
                if(!cursor.startsWith("source:"))throw new IllegalArgumentException("Invalid local member cursor");
                after=Long.parseUnsignedLong(cursor.substring("source:".length()));
            }
            var values=sourceOverlay.select(artifactId,Objects.requireNonNullElse(prefix,""),false,true,limit+1,after,
                    value->ownerFqn.equals(Objects.toString(value.get("fqn"),""))
                            &&Set.of("method","ctor","field","enumconst","class","interface","record","enum","annotation").contains(Objects.toString(value.get("kind"),"")));
            boolean more=values.size()>limit;var page=new ArrayList<Map<String,Object>>(values.subList(0,Math.min(limit,values.size())));
            String next=more?"source:"+Long.toUnsignedString(((Number)page.getLast().get("id")).longValue()&0xffffffffL):null;
            return new MemberPage(page.stream().map(value->contextual(artifact,value)).toList(),next);
        }

        String generation=symbolsKey(artifact);
        String binaryOwner=Objects.toString(owner.get("binary_key"),"");
        String token=cursor==null?null:cursor.startsWith("binary:")?cursor.substring("binary:".length()):null;
        if(cursor!=null&&token==null)throw new IllegalArgumentException("Invalid binary member cursor");
        var page=repository.ownerMembers(generation,binaryOwner,Objects.requireNonNullElse(prefix,""),limit,token);
        var rows=new ArrayList<Map<String,Object>>(page.symbols().size());
        for(var symbol:page.symbols())rows.add(semanticRow(artifact,symbol));
        return new MemberPage(rows,page.cursor()==null?null:"binary:"+page.cursor());
    }

    @Override public synchronized List<Map<String,Object>> descendants(String path,String workspace,int depth,int limit,long after,Set<String> kinds)throws Exception{
        long parentDepth=path.chars().filter(c->c=='/').count();
        return findMatching(path+"/",workspace,true,limit,after,kinds,s->{String candidate=Objects.toString(s.get("name_path"),"");return candidate.startsWith(path+"/")&&candidate.chars().filter(c->c=='/').count()-parentDepth<=depth;});
    }
    private List<Map<String,Object>> findMatching(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds,Predicate<Map<String,Object>> filter)throws Exception{
        if(limit<=0)return List.of();NamePath name=null;if(!substring)try{name=NamePath.parse(query);}catch(RpcException ignored){}
        final NamePath parsed=name;String lower=query.toLowerCase(Locale.ROOT);
        Predicate<Map<String,Object>> match=s->(kinds.isEmpty()||kinds.contains(s.get("kind")))&&filter.test(s)&&
                (substring?(Objects.toString(s.get("name"),"").toLowerCase(Locale.ROOT).contains(lower)||Objects.toString(s.get("name_path"),"").toLowerCase(Locale.ROOT).contains(lower)):
                        query.equals(s.get("binary_key"))||query.equals(s.get("scip"))||(parsed!=null&&parsed.matches(s)));
        var found=new TreeMap<Long,Map<String,Object>>();var seen=new HashSet<String>();
        var selectedArtifacts=selected(workspace,false);
        for(var artifact:selectedArtifacts){
            for(var symbol:sourceOverlay.select(artifact.id(),query,substring,false,limit,after,value->searchVisible(artifact,value)&&sourcePreferred(artifact,value,selectedArtifacts)&&!seen.contains(value.get("scip").toString())&&match.test(value))){
                String scip=symbol.get("scip").toString();if(seen.add(scip)&&preferred(artifact,scip,selectedArtifacts))offer(found,contextual(artifact,symbol),after,limit);
            }
            if(after>>>32>artifact.id())continue;
            var prefixes=new LinkedHashSet<String>();
            if(substring){
                boolean typesOnly=!kinds.isEmpty()&&TYPES.containsAll(kinds);
                String posting=lower.isBlank()?"1|symbol|":"8|gram|"+lower.substring(0,Math.min(3,lower.length()))+"|";
                // Common owner-name grams can contain every member in a JAR. Compare
                // posting cardinality without loading symbols; retain selective grams
                // for rare names instead of always walking all type declarations.
                if(typesOnly&&(lower.isBlank()||repository.postingCountExceeds(symbolsKey(artifact),posting,artifact.simpleNames())))posting="0|type|";
                prefixes.add(posting);
            }
            else{
                if(parsed!=null&&!parsed.identity())prefixes.add("3|name|"+parsed.leaf()+"|");
                String[] gav=artifact.input().context().gav().split(":",3);String prefix="maven "+gav[0]+"/"+gav[1]+" "+gav[2]+" ";
                if(query.startsWith(prefix))prefixes.add("2|scip|"+query.substring(prefix.length())+"|");
            }
            var candidates=new TreeMap<Integer,ArtifactIndexFormat.SymbolRecord>();
            Predicate<ArtifactIndexFormat.SymbolRecord> accepts=s->{try{if(!searchVisible(artifact,s)||!kinds.isEmpty()&&!kinds.contains(s.kind()))return false;String scip=artifact.input().context().scip(s);return !sourceOverlay.contains(artifact.id(),scip)&&!seen.contains(scip)&&preferred(artifact,scip,selectedArtifacts)&&match.test(searchFields(artifact,s));}catch(Exception e){throw new IllegalStateException(e);}};
            for(String prefix:prefixes){
                // Code-enriched generations can remap IDs to original signatures; their
                // rank still needs the decoded symbol. Plain signatures can reject IDs first.
                var matches=artifact.codeKey()==null?
                        repository.selectLocalIds(symbolsKey(artifact),prefix,artifact.id()<<32,after,limit,accepts):
                        repository.selectRanked(symbolsKey(artifact),prefix,after,limit,accepts,s->{try{return symbolId(artifact,s);}catch(Exception e){throw new IllegalStateException(e);}});
                for(var symbol:matches)candidates.put(symbol.id(),symbol);
            }
            Integer direct=substring?null:repository.binaryId(symbolsKey(artifact),query);if(direct!=null)candidates.put(direct,repository.symbol(symbolsKey(artifact),direct));
            for(var symbol:candidates.values()){
                var value=row(artifact,symbol);String scip=value.get("scip").toString();
                if(searchVisible(artifact,value)&&!sourceOverlay.contains(artifact.id(),scip)&&seen.add(scip)&&preferred(artifact,scip,selectedArtifacts)&&match.test(value))offer(found,value,after,limit);
            }
        }return List.copyOf(found.values());
    }
    private static void offer(TreeMap<Long,Map<String,Object>> selected,Map<String,Object> value,long after,int limit){
        long id=((Number)value.get("id")).longValue();if(id<=after)return;selected.put(id,value);if(selected.size()>limit)selected.pollLastEntry();
    }

    private Map<String,Object> direct(StoredArtifact artifact,String binaryKey)throws Exception{
        var source=sourceOverlay.first(artifact.id(),"binary_key",binaryKey);if(source!=null)return contextual(artifact,source);
        Integer id=repository.binaryId(symbolsKey(artifact),binaryKey);
        return id==null?null:row(artifact,repository.symbol(symbolsKey(artifact),id));
    }
    private Map<String,Object> resolve(String target,String workspace,Set<String> visited)throws Exception{
        if(target.startsWith("maven ")||target.startsWith("local "))return byScip(target,workspace);
        if(!visited.add(target))return null;int member=target.indexOf('#');String owner=member<0?target:target.substring(0,member);
        for(var artifact:selected(workspace,true)){
            var type=direct(artifact,owner);if(type==null)continue;
            var value=member<0?type:direct(artifact,target);if(value!=null)return value;
            if(target.substring(member+1).startsWith("<init>("))return null;
            for(var edge:raw(type,Set.of("extends","implements"),false)){
                var parent=resolve(edge.targetBinaryKey(),workspace,new HashSet<>());if(parent==null)continue;
                var inherited=resolve(parent.get("binary_key")+target.substring(member),workspace,visited);
                if(inherited!=null&&(flags(inherited)&2)==0&&accessible(inherited,owner))return inherited;
            }return null;
        }return null;
    }
    private static int flags(Map<String,Object> symbol){
        if(symbol.get("flags") instanceof Number value)return value.intValue();
        int result=0;Object modifiers=symbol.get("modifiers");if(modifiers instanceof Collection<?> values){
            if(values.contains("public"))result|=1;if(values.contains("private"))result|=2;if(values.contains("protected"))result|=4;if(values.contains("static"))result|=8;
        }return result;
    }
    private static boolean accessible(Map<String,Object> parent,String childOwner){
        if((flags(parent)&5)!=0)return true;String owner=Objects.toString(parent.get("fqn"),"");
        return owner.substring(0,Math.max(0,owner.lastIndexOf('.'))).equals(childOwner.substring(0,Math.max(0,childOwner.lastIndexOf('.'))));
    }
    private List<SymbolicReference> raw(Map<String,Object> symbol,Set<String> kinds,boolean code)throws Exception{
        var result=new LinkedHashSet<SymbolicReference>();long artifactId=((Number)symbol.get("artifact_id")).longValue();var artifact=required(artifactId);
        String scip=symbol.get("scip").toString();
        if(sourceOverlay.contains(artifactId,scip)){
            for(var edge:sourceOverlay.edges(artifactId,scip,true,kinds))result.add(new SymbolicReference(scip,edge.targetScip(),edge.kind()));
            return List.copyOf(result);
        }
        String generation=code?artifact.codeKey():artifact.input().key().cacheKey();if(generation==null)return List.of();
        Integer local=repository.binaryId(generation,symbol.get("binary_key").toString());if(local==null)return List.of();
        for(var edge:repository.outgoing(generation,local,kinds,Integer.MAX_VALUE))result.add(new SymbolicReference(scip,edge.target(),edge.kind()));
        return List.copyOf(result);
    }
    @Override public synchronized List<Map<String,Object>> symbolsByBinaryKey(String binaryKey,String workspace)throws Exception{
        var result=new LinkedHashMap<String,Map<String,Object>>();for(var artifact:selected(workspace,true)){
            var value=direct(artifact,binaryKey);if(value!=null)result.putIfAbsent(value.get("scip").toString(),value);
        }return List.copyOf(result.values());
    }
    private static ArtifactCandidate candidate(StoredArtifact a){return new ArtifactCandidate(a.id(),a.input().context().path(),a.input().context().gav(),a.classReferences(),a.codeKey()!=null);}
    @Override public synchronized List<ArtifactCandidate> binaryArtifacts(String workspace){return selected(workspace,false).stream().filter(a->a.input().context().kind().equals("jar")&&!a.input().context().path().startsWith("jrt:")).map(RocksIndexStore::candidate).toList();}
    @Override public List<ArtifactWork> pendingSignatureArtifacts(String workspace){return List.of();}
    @Override public synchronized List<ArtifactCandidate> artifactsOwning(Collection<String> scips,String workspace)throws Exception{
        var result=new TreeMap<Long,ArtifactCandidate>();for(String scip:scips){var symbol=byScip(scip,workspace);if(symbol==null)continue;var artifact=required(((Number)symbol.get("artifact_id")).longValue());
            if(artifact.input().context().kind().equals("jar")&&!artifact.input().context().path().startsWith("jrt:"))result.put(artifact.id(),candidate(artifact));
        }return List.copyOf(result.values());
    }
    @Override public synchronized List<ArtifactCandidate> artifactsReferencing(Collection<String> fqns,String workspace)throws Exception{
        var result=new ArrayList<ArtifactCandidate>();for(var artifact:selected(workspace,false)){
            if(!artifact.input().context().kind().equals("jar"))continue;
            for(String fqn:fqns)if(repository.referencesClass(artifact.input().key().cacheKey(),fqn)||state.get(bytes("C|"+key(artifact.id())+"|"+fqn))!=null){result.add(candidate(artifact));break;}
        }return List.copyOf(result);
    }
    @Override public synchronized List<SymbolicReference> codeReferences(Collection<String> frontier,boolean outgoing,Set<String> kinds,String workspace)throws Exception{
        var result=new LinkedHashSet<SymbolicReference>();
        if(outgoing){for(String scip:frontier){var source=byScip(scip,workspace);if(source!=null)result.addAll(raw(source,kinds,true));}}
        else for(var artifact:selected(workspace,true)){
            if(artifact.codeKey()!=null)for(String target:frontier)for(var edge:repository.incoming(artifact.codeKey(),target,kinds,Integer.MAX_VALUE)){
                var symbol=repository.symbol(artifact.codeKey(),edge.sourceId());var source=byScip(artifact.input().context().scip(symbol),workspace);
                if(source!=null&&((Number)source.get("artifact_id")).longValue()==artifact.id()&&!sourceOverlay.contains(artifact.id(),source.get("scip").toString()))result.add(new SymbolicReference(source.get("scip").toString(),target,edge.kind()));
            }
        }
        if(!outgoing)for(String target:frontier){
            var resolved=resolve(target,workspace,new HashSet<>());if(resolved==null)continue;
            for(var artifact:selected(workspace,true))for(var edge:sourceOverlay.edges(artifact.id(),resolved.get("scip").toString(),false,kinds)){
                var source=byScip(edge.sourceScip(),workspace);
                if(source!=null&&((Number)source.get("artifact_id")).longValue()==artifact.id())result.add(new SymbolicReference(edge.sourceScip(),target,edge.kind()));
            }
        }
        return result.stream().sorted(Comparator.comparing(SymbolicReference::sourceScip).thenComparing(SymbolicReference::targetBinaryKey).thenComparing(SymbolicReference::kind)).toList();
    }
    @Override public synchronized List<ResolvedRelationship> relationships(Collection<String> scips,boolean outgoing,Set<String> kinds,String workspace)throws Exception{
        return relationships(scips,outgoing,kinds,workspace,null);
    }
    @Override public synchronized List<ResolvedRelationship> relationships(Collection<String> scips,boolean outgoing,Set<String> kinds,String workspace,SemanticLayer layer)throws Exception{
        var result=new LinkedHashMap<String,ResolvedRelationship>();
        if(outgoing){
            for(String scip:scips){var source=layer==null?byScip(scip,workspace):byScip(scip,workspace,layer);if(source==null)continue;
                for(var edge:raw(source,kinds,false)){var target=resolve(edge.targetBinaryKey(),workspace,new HashSet<>());if(target!=null)add(result,source,target,edge.kind());}
                if(kinds.isEmpty()||kinds.contains("overrides"))for(var parent:overrideParents(scip,workspace,Integer.MAX_VALUE))add(result,source,parent,"overrides");
            }
        }else{
            for(String scip:scips){var target=byScip(scip,workspace);if(target==null)continue;String binary=target.get("binary_key").toString();
                for(var artifact:selected(workspace,true)){
                    for(var edge:repository.incoming(artifact.input().key().cacheKey(),binary,kinds,Integer.MAX_VALUE)){
                        var symbol=repository.symbol(artifact.input().key().cacheKey(),edge.sourceId());var source=byScip(artifact.input().context().scip(symbol),workspace);
                        var resolved=resolve(edge.target(),workspace,new HashSet<>());
                        if(source!=null&&(layer==null||layerMatches(source,layer))&&((Number)source.get("artifact_id")).longValue()==artifact.id()&&!sourceOverlay.contains(artifact.id(),source.get("scip").toString())&&resolved!=null&&scip.equals(resolved.get("scip")))add(result,source,target,edge.kind());
                    }
                    for(var edge:sourceOverlay.edges(artifact.id(),scip,false,kinds)){var source=byScip(edge.sourceScip(),workspace);if(source!=null&&(layer==null||layerMatches(source,layer))&&((Number)source.get("artifact_id")).longValue()==artifact.id())add(result,source,target,edge.kind());}
                    if((kinds.isEmpty()||kinds.contains("overrides"))&&"method".equals(target.get("kind"))){
                        for(var candidate:find(target.get("name").toString(),workspace,false,Integer.MAX_VALUE,0,Set.of("method")))
                            for(var parent:overrideParents(candidate.get("scip").toString(),workspace,Integer.MAX_VALUE))if(scip.equals(parent.get("scip")))add(result,candidate,target,"overrides");
                    }
                }
            }
        }
        return result.values().stream().sorted(Comparator.comparingLong((ResolvedRelationship r)->((Number)r.source().get("id")).longValue()).thenComparingLong(r->((Number)r.target().get("id")).longValue()).thenComparing(ResolvedRelationship::kind)).toList();
    }
    private static void add(Map<String,ResolvedRelationship> result,Map<String,Object> source,Map<String,Object> target,String kind){result.putIfAbsent(source.get("scip")+"|"+kind+"|"+target.get("scip"),new ResolvedRelationship(source,target,kind));}
    private static boolean layerMatches(Map<String,Object> row,SemanticLayer layer){
        boolean local="local".equals(Objects.toString(row.get("artifact_kind"),""));
        return layer==SemanticLayer.LOCAL?local:!local;
    }
    @Override public synchronized List<Map<String,Object>> relationshipClosure(String scip,int depth,Set<String> kinds,String workspace,int limit,int offset)throws Exception{
        if(depth<=0||kinds.isEmpty()||limit<=0)return List.of();var visited=new HashSet<String>();visited.add(scip);var frontier=List.of(scip);var all=new ArrayList<Map<String,Object>>();
        for(int level=0;level<depth&&!frontier.isEmpty()&&all.size()<limit+(long)offset;level++){
            var next=new TreeMap<Long,Map<String,Object>>();for(var edge:relationships(frontier,true,kinds,workspace))if(visited.add(edge.target().get("scip").toString()))next.put(((Number)edge.target().get("id")).longValue(),edge.target());
            all.addAll(next.values());frontier=next.values().stream().map(s->s.get("scip").toString()).toList();
        }return all.stream().skip(Math.max(0,offset)).limit(limit).toList();
    }
    @Override public synchronized Set<String> unresolvedSignatureTargets(String scip,int depth,Set<String> kinds,String workspace,int limit)throws Exception{
        if(depth<=0||kinds.isEmpty()||limit<=0)return Set.of();var result=new LinkedHashSet<String>();var visited=new HashSet<String>();var frontier=List.of(scip);
        for(int level=0;level<depth&&!frontier.isEmpty();level++){
            var next=new ArrayList<String>();for(String value:frontier){if(!visited.add(value))continue;var source=byScip(value,workspace);if(source==null)continue;
                for(var edge:raw(source,kinds,false)){var target=resolve(edge.targetBinaryKey(),workspace,new HashSet<>());
                    if(target==null){result.add(edge.targetBinaryKey());if(result.size()==limit)return result;}else next.add(target.get("scip").toString());
                }
            }frontier=next;
        }return result;
    }
    @Override public synchronized List<Map<String,Object>> overrideParents(String scip,String workspace,int limit)throws Exception{
        if(limit<=0)return List.of();var child=byScip(scip,workspace);if(child==null||!"method".equals(child.get("kind"))||(flags(child)&10)!=0)return List.of();
        String descriptor=Objects.toString(child.get("erased_descriptor"),"");int end=descriptor.indexOf(')');if(end<0)return List.of();String parameters=descriptor.substring(0,end+1);
        String owner=Objects.toString(child.get("fqn"),"");var ownerSymbol=resolve(owner,workspace,new HashSet<>());if(ownerSymbol==null)return List.of();
        var result=new LinkedHashMap<String,Map<String,Object>>();var visited=new HashSet<String>();var queue=new ArrayDeque<Map<String,Object>>();queue.add(ownerSymbol);
        while(!queue.isEmpty()&&result.size()<limit){var current=queue.removeFirst();if(!visited.add(current.get("scip").toString()))continue;
            for(var edge:raw(current,Set.of("extends","implements"),false)){
                var parent=resolve(edge.targetBinaryKey(),workspace,new HashSet<>());if(parent==null)continue;queue.add(parent);
                for(var method:find(child.get("name").toString(),workspace,false,Integer.MAX_VALUE,0,Set.of("method"))){
                    String parentDescriptor=Objects.toString(method.get("erased_descriptor"),"");
                    if(Objects.equals(method.get("fqn"),parent.get("fqn"))&&parentDescriptor.startsWith(parameters)&&(flags(method)&10)==0&&accessible(method,owner)){
                        result.putIfAbsent(method.get("scip").toString(),method);if(result.size()==limit)break;
                    }
                }
            }
        }return List.copyOf(result.values());
    }
    @Override public synchronized List<Path> localWorkspaceArtifacts(String workspace){return selected(workspace,false).stream().filter(a->a.input().context().kind().equals("local")).map(a->Path.of(a.input().context().path())).sorted().toList();}
    @Override public synchronized void close()throws Exception{
        if(closed)return;closing=true;long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        while(builds>0){long remaining=deadline-System.nanoTime();if(remaining<=0)throw new IllegalStateException("Artifact publishers did not stop; native handles remain open");java.util.concurrent.TimeUnit.NANOSECONDS.timedWait(this,remaining);}
        closed=true;sourceOverlay.close();state.close();options.close();durable.close();
    }
}

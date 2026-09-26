package dev.jvmd.index;

import dev.jvmd.core.Hash256;
import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.CanonicalDigestWriter;
import java.nio.file.Path;
import java.util.*;

/**
 * Backend-neutral persisted index contract. Public identities remain SCIP strings; backend-local
 * numeric ids are opaque implementation details used only for bounded pagination/traversal.
 */
public interface IndexStore extends AutoCloseable {
    record ArtifactRecord(long id,String gav,String kind,String sha256,String path,long size,long mtime,
                          boolean hasDocs,boolean hasCodeEdges,boolean hasSignatureEdges) { }
    record WorkspaceEntry(String path,String scope) { }
    record ArtifactCandidate(long id,String path,String gav,boolean hasClassRefs,boolean hasCodeEdges) { }
    record ArtifactWork(String path,String gav,String kind) { }
    record ResolvedRelationship(Map<String,Object> source,Map<String,Object> target,String kind) { }
    record MemberPage(List<Map<String,Object>> symbols,String cursor) {
        public MemberPage { symbols=List.copyOf(symbols); }
    }
    record SymbolicReference(String sourceScip,String targetBinaryKey,String kind) { }
    record SourceRelationship(String sourceScip,String targetScip,String kind) { }
    enum SemanticLayer { LOCAL, MACHINE }

    /**
     * Typed detached semantic record. Navigation/search maps remain a separate compatibility surface;
     * semantic consumers carry the already-decoded canonical ResolutionFact directly.
     */
    record IndexedSemanticSymbol(String id,String name,String kind,String fqn,String binaryKey,String signature,
                                 String erasedDescriptor,ResolutionFact resolution,List<String> parameterNames,
                                 String sourceFile,SemanticLayer layer) {
        public IndexedSemanticSymbol {
            Objects.requireNonNull(id);Objects.requireNonNull(name);Objects.requireNonNull(kind);
            fqn=Objects.requireNonNullElse(fqn,"");binaryKey=Objects.requireNonNullElse(binaryKey,id);
            signature=Objects.requireNonNullElse(signature,"");erasedDescriptor=Objects.requireNonNullElse(erasedDescriptor,"");
            Objects.requireNonNull(resolution);parameterNames=List.copyOf(parameterNames);Objects.requireNonNull(layer);
        }
    }
    record SemanticMemberPage(List<IndexedSemanticSymbol> symbols,String cursor) {
        public SemanticMemberPage { symbols=List.copyOf(symbols); }
    }
    /**
     * Semantic evidence for Java classpath resolution of one exact binary name.
     *
     * searchedEntries records the ordered structural prefix that had to be examined. The canonical
     * semantic identity deliberately records only the winning declaration (or exact negative result),
     * so a changed/inserted slot that is rechecked and remains irrelevant reaches a fixed point.
     */
    record ClasspathSearchProof(String binaryName,int searchedEntries,String winnerArtifactKey,
                                String winnerScip,Hash256 winnerResolutionIdentity) {
        public ClasspathSearchProof {
            Objects.requireNonNull(binaryName);
            if(binaryName.isBlank())throw new IllegalArgumentException("Classpath search binary name must not be blank");
            if(searchedEntries<0)throw new IllegalArgumentException("Negative searched classpath size");
            boolean winner=winnerScip!=null||winnerArtifactKey!=null||winnerResolutionIdentity!=null;
            if(winner&&(winnerScip==null||winnerArtifactKey==null||winnerResolutionIdentity==null))
                throw new IllegalArgumentException("Incomplete classpath winner evidence");
        }
        public boolean resolved(){return winnerScip!=null;}
        public QueryProof.Key key(){return new QueryProof.Key(QueryProof.Domain.CLASSPATH_SEARCH,"binary:"+binaryName);}
        public Hash256 identity(){
            return resolved()
                    ?CanonicalDigestWriter.digest("classpath-search-proof-v1",binaryName,winnerArtifactKey,winnerScip,winnerResolutionIdentity)
                    :CanonicalDigestWriter.digest("classpath-search-proof-v1",binaryName,"<missing>");
        }
        /** Structural diff discovery: later slots cannot affect an already established winner. */
        public boolean affectedBy(ClasspathSequence.Difference difference){
            Objects.requireNonNull(difference);
            if(difference.equal())return false;
            if(!resolved())return true;
            for(var interval:difference.intervals())
                if(interval.previousStart()<searchedEntries||interval.currentStart()<searchedEntries)return true;
            return false;
        }
    }
    record ArtifactInput(ArtifactContext context,ArtifactIndexFormat.Key key,long size,long mtime) {
        public ArtifactInput {
            Objects.requireNonNull(context);Objects.requireNonNull(key);
            if(size<0)throw new IllegalArgumentException("size");
        }
    }

    /** Query contract shared with the live source overlay; numeric storage handles stay inside the adapter. */
    default SymbolReadView readView(String workspace){
        var store=this;return new SymbolReadView(){
            public boolean declares(String scip)throws Exception{return byScip(scip)!=null;}
            public Map<String,Object> byScip(String scip)throws Exception{return store.byScip(scip,workspace);}
            private Page page(List<Map<String,Object>> rows,int limit){boolean more=rows.size()>limit;var items=rows.subList(0,Math.min(limit,rows.size()));return new Page(items,more?items.getLast().get("id").toString():null);}
            public Page find(String query,boolean substring,Set<String> kinds,int limit,String cursor)throws Exception{return page(store.find(query,workspace,substring,limit+1,cursor==null?0:Long.parseLong(cursor),kinds),limit);}
            public Page descendants(String path,int depth,Set<String> kinds,int limit,String cursor)throws Exception{return page(store.descendants(path,workspace,depth,limit+1,cursor==null?0:Long.parseLong(cursor),kinds),limit);}
        };
    }

    String backend();
    ArtifactRecord artifact(Path path)throws Exception;
    void publishPath(Path path,long artifactId,long size,long mtime)throws Exception;
    long publishArtifact(ArtifactInput input,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences,
                         Map<String,Map<String,Object>> sourceData)throws Exception;
    default long publishBinary(ArtifactInput input,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception{
        return publishArtifact(input,facts,classReferences,Map.of());
    }
    void publishCode(long artifactId,ArtifactContext context,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception;
    void publishClassReferences(long artifactId,Set<String> classReferences)throws Exception;
    default void publishSourceFile(long artifactId,Path file,List<Map<String,Object>> symbols,int tier,List<SourceRelationship> relationships)throws Exception {
        publishSourceFile(artifactId,file,dev.jvmd.core.Hashing.sha256(file),symbols,tier,relationships);
    }
    void publishSourceFile(long artifactId,Path file,String contentHash,List<Map<String,Object>> symbols,int tier,
                           List<SourceRelationship> relationships)throws Exception;
    long publishDocumentation(long binaryArtifactId,ArtifactInput sourceInput,
                              Map<String,Map<String,Object>> members,int unmatchedMembers)throws Exception;
    void resolveGlobalRelationships()throws Exception;
    default boolean reconcilePaths(Path root,Set<Path> present)throws Exception{return false;}

    Map<String,Long> counts()throws Exception;
    Map<String,Object> status();
    List<String> loadWorkspace(String workspace,List<WorkspaceEntry> paths,List<Map.Entry<String,String>> dependencies)throws Exception;
    /**
     * Resolution-scoped ordered dependency classpath for one workspace.
     * The sequence root is for structural equality/diff discovery, not default query invalidation.
     */
    default Optional<ClasspathSequence> semanticClasspathSequence(String workspace)throws Exception{return Optional.empty();}
    default Optional<Hash256> semanticClasspathIdentity(String workspace)throws Exception{
        return semanticClasspathSequence(workspace).map(ClasspathSequence::identity);
    }
    /**
     * Exact binary-name classpath resolution proof. Production stores should stop at the first
     * selected artifact that declares the requested type.
     */
    default Optional<ClasspathSearchProof> semanticClasspathSearch(String workspace,String binaryName)throws Exception{
        return Optional.empty();
    }
    List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds)throws Exception;
    /** Prefix-only simple-name lookup used by editor completion; implementations should avoid substring scans. */
    List<Map<String,Object>> findNamePrefix(String prefix,String workspace,int limit,Set<String> kinds)throws Exception;
    /** Bounded direct-member range for one canonical owner. Production Rocks overrides this. */
    default MemberPage membersByOwner(String ownerScip,String prefix,String workspace,int limit,String cursor)throws Exception{
        throw new UnsupportedOperationException("owner/member range unavailable");
    }
    /** Layer-specific owner/member range. Production stores should override to avoid pre-composition masking. */
    default MemberPage membersByOwner(String ownerScip,String prefix,String workspace,int limit,String cursor,SemanticLayer layer)throws Exception{
        var page=membersByOwner(ownerScip,prefix,workspace,limit,cursor);
        var filtered=page.symbols().stream().filter(row->layerMatches(row,layer)).toList();
        return new MemberPage(filtered,page.cursor());
    }
    List<Map<String,Object>> descendants(String path,String workspace,int depth,int limit,long after,Set<String> kinds)throws Exception;
    Map<String,Object> byId(long id,String workspace)throws Exception;
    Map<String,Object> byScip(String scip,String workspace)throws Exception;
    /** Layer-specific exact lookup used before LIVE/LOCAL/MACHINE composition. */
    default Map<String,Object> byScip(String scip,String workspace,SemanticLayer layer)throws Exception{
        var row=byScip(scip,workspace);return row!=null&&layerMatches(row,layer)?row:null;
    }

    /** Typed exact semantic lookup. Production stores should override to avoid map/JSON projection. */
    default IndexedSemanticSymbol semanticByScip(String scip,String workspace,SemanticLayer layer)throws Exception{
        var row=byScip(scip,workspace,layer);return row==null?null:semanticSymbol(row,layer);
    }
    /** Typed owner/member range. Production stores should override to keep SymbolRecord/ResolutionFact typed. */
    default SemanticMemberPage semanticMembersByOwner(String ownerScip,String prefix,String workspace,int limit,
                                                       String cursor,SemanticLayer layer)throws Exception{
        var page=membersByOwner(ownerScip,prefix,workspace,limit,cursor,layer);
        var values=new ArrayList<IndexedSemanticSymbol>(page.symbols().size());
        for(var row:page.symbols())values.add(semanticSymbol(row,layer));
        return new SemanticMemberPage(values,page.cursor());
    }
    /** Typed exact-simple-name type lookup for semantic context resolution. */
    default List<IndexedSemanticSymbol> semanticTypesByName(String simpleName,String workspace,int limit,SemanticLayer layer)throws Exception{
        var result=new ArrayList<IndexedSemanticSymbol>();
        for(var row:findNamePrefix(simpleName,workspace,limit,Set.of("class","interface","enum","record","annotation"))){
            if(layerMatches(row,layer)&&simpleName.equals(Objects.toString(row.get("name"),"")))
                result.add(semanticSymbol(row,layer));
        }
        return List.copyOf(result);
    }
    /** Exact type lookup by canonical binary/FQN key. */
    default IndexedSemanticSymbol semanticType(String binaryName,String workspace,SemanticLayer layer)throws Exception{
        int split=Math.max(binaryName.lastIndexOf('.'),binaryName.lastIndexOf(36));
        String simple=binaryName.substring(split+1);
        for(var value:semanticTypesByName(simple,workspace,256,layer))
            if(binaryName.equals(value.fqn())||binaryName.equals(value.binaryKey())||binaryName.equals(value.fqn().replace((char)36,'.')))
                return value;
        return null;
    }
    /** Resolution-only identity of one direct owner/name-prefix semantic range. */
    default Hash256 semanticMemberRangeIdentity(String ownerScip,String prefix,String workspace,SemanticLayer layer)throws Exception{
        var aggregate=new AlgebraicAccumulator("semantic-member-range-v1");
        String cursor=null;
        do{
            var page=semanticMembersByOwner(ownerScip,Objects.requireNonNullElse(prefix,""),workspace,256,cursor,layer);
            for(var symbol:page.symbols())
                aggregate.add(symbol.resolution().symbolKey(),symbol.resolution().identity());
            cursor=page.cursor();
        }while(cursor!=null);
        return aggregate.identity();
    }
    /** Resolution-only identity of the exact overload group for one direct member name. */
    default Hash256 semanticOverloadGroupIdentity(String ownerScip,String name,String workspace,SemanticLayer layer)throws Exception{
        var aggregate=new AlgebraicAccumulator("semantic-member-range-v1");
        String cursor=null;
        do{
            var page=semanticMembersByOwner(ownerScip,Objects.requireNonNullElse(name,""),workspace,256,cursor,layer);
            for(var symbol:page.symbols())if(symbol.name().equals(name))
                aggregate.add(symbol.resolution().symbolKey(),symbol.resolution().identity());
            cursor=page.cursor();
        }while(cursor!=null);
        return CanonicalDigestWriter.digest("semantic-overload-group-v1",aggregate.identity());
    }
    List<ArtifactCandidate> binaryArtifacts(String workspace)throws Exception;
    List<ArtifactWork> pendingSignatureArtifacts(String workspace)throws Exception;
    List<ArtifactCandidate> artifactsOwning(Collection<String> scips,String workspace)throws Exception;
    List<ArtifactCandidate> artifactsReferencing(Collection<String> fqns,String workspace)throws Exception;
    List<ResolvedRelationship> relationships(Collection<String> scips,boolean outgoing,Set<String> kinds,String workspace)throws Exception;
    /** Layer-specific relationship read; production stores should override when layers can overlap. */
    default List<ResolvedRelationship> relationships(Collection<String> scips,boolean outgoing,Set<String> kinds,String workspace,SemanticLayer layer)throws Exception{
        return relationships(scips,outgoing,kinds,workspace).stream()
                .filter(edge->layerMatches(edge.source(),layer)).toList();
    }
    List<SymbolicReference> codeReferences(Collection<String> frontier,boolean outgoing,Set<String> kinds,String workspace)throws Exception;
    List<Map<String,Object>> symbolsByBinaryKey(String binaryKey,String workspace)throws Exception;
    List<Map<String,Object>> relationshipClosure(String rootScip,int depth,Set<String> kinds,String workspace,int limit,int offset)throws Exception;
    Set<String> unresolvedSignatureTargets(String rootScip,int depth,Set<String> kinds,String workspace,int limit)throws Exception;
    List<Map<String,Object>> overrideParents(String scip,String workspace,int limit)throws Exception;
    List<Path> localWorkspaceArtifacts(String workspace)throws Exception;

    private static IndexedSemanticSymbol semanticSymbol(Map<String,Object> row,SemanticLayer layer){
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
    private static boolean layerMatches(Map<String,Object> row,SemanticLayer layer){
        boolean local="local".equals(Objects.toString(row.get("artifact_kind"),""));
        return layer==SemanticLayer.LOCAL?local:!local;
    }
}

package dev.jvmd.index;

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
    List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds)throws Exception;
    /** Prefix-only simple-name lookup used by editor completion; implementations should avoid substring scans. */
    List<Map<String,Object>> findNamePrefix(String prefix,String workspace,int limit,Set<String> kinds)throws Exception;
    /** Bounded direct-member range for one canonical owner. Production Rocks overrides this. */
    default MemberPage membersByOwner(String ownerScip,String prefix,String workspace,int limit,String cursor)throws Exception{
        throw new UnsupportedOperationException("owner/member range unavailable");
    }
    List<Map<String,Object>> descendants(String path,String workspace,int depth,int limit,long after,Set<String> kinds)throws Exception;
    Map<String,Object> byId(long id,String workspace)throws Exception;
    Map<String,Object> byScip(String scip,String workspace)throws Exception;
    List<ArtifactCandidate> binaryArtifacts(String workspace)throws Exception;
    List<ArtifactWork> pendingSignatureArtifacts(String workspace)throws Exception;
    List<ArtifactCandidate> artifactsOwning(Collection<String> scips,String workspace)throws Exception;
    List<ArtifactCandidate> artifactsReferencing(Collection<String> fqns,String workspace)throws Exception;
    List<ResolvedRelationship> relationships(Collection<String> scips,boolean outgoing,Set<String> kinds,String workspace)throws Exception;
    List<SymbolicReference> codeReferences(Collection<String> frontier,boolean outgoing,Set<String> kinds,String workspace)throws Exception;
    List<Map<String,Object>> symbolsByBinaryKey(String binaryKey,String workspace)throws Exception;
    List<Map<String,Object>> relationshipClosure(String rootScip,int depth,Set<String> kinds,String workspace,int limit,int offset)throws Exception;
    Set<String> unresolvedSignatureTargets(String rootScip,int depth,Set<String> kinds,String workspace,int limit)throws Exception;
    List<Map<String,Object>> overrideParents(String scip,String workspace,int limit)throws Exception;
    List<Path> localWorkspaceArtifacts(String workspace)throws Exception;
}

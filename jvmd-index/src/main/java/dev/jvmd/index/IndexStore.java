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
    record ResolvedRelationship(Map<String,Object> source,Map<String,Object> target,String kind) { }
    record SymbolicReference(String sourceScip,String targetBinaryKey,String kind) { }
    record SourceRelationship(String sourceScip,String targetScip,String kind) { }
    record ArtifactInput(ArtifactIndexFormat.Context context,ArtifactIndexFormat.Key key,long size,long mtime) {
        public ArtifactInput {
            Objects.requireNonNull(context);Objects.requireNonNull(key);
            if(size<0)throw new IllegalArgumentException("size");
        }
    }

    String backend();
    ArtifactRecord artifact(Path path)throws Exception;
    long publishArtifact(ArtifactInput input,ArtifactIndexFormat.ArtifactData facts,Map<String,Map<String,Object>> sourceData)throws Exception;
    void publishCode(long artifactId,ArtifactIndexFormat.Context context,ArtifactIndexFormat.ArtifactData facts)throws Exception;
    void publishClassReferences(long artifactId,Set<String> references)throws Exception;
    void publishSourceFile(long artifactId,Path file,List<Map<String,Object>> symbols,int tier,List<SourceRelationship> relationships)throws Exception;
    long publishDocumentation(long binaryArtifactId,ArtifactInput sourceInput,Map<String,Map<String,Object>> members,int unmatchedMembers)throws Exception;
    void publishPath(Path path,long artifactId,long size,long mtime)throws Exception;
    void resolveGlobalRelationships()throws Exception;
    Map<String,Long> counts()throws Exception;
    Map<String,Object> status();
    List<String> loadWorkspace(String workspace,List<WorkspaceEntry> paths,List<Map.Entry<String,String>> dependencies)throws Exception;
    List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds)throws Exception;
    List<Map<String,Object>> descendants(String path,String workspace,int depth,int limit,long after,Set<String> kinds)throws Exception;
    Map<String,Object> byId(long id,String workspace)throws Exception;
    Map<String,Object> byScip(String scip,String workspace)throws Exception;
    List<ArtifactCandidate> binaryArtifacts(String workspace)throws Exception;
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

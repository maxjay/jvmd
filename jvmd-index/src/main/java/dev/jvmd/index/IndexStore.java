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
    record ArtifactInput(ArtifactContext context,ArtifactIndexFormat.Key key,long size,long mtime) {
        public ArtifactInput {
            Objects.requireNonNull(context);Objects.requireNonNull(key);
            if(size<0)throw new IllegalArgumentException("size");
        }
    }

    String backend();
    ArtifactRecord artifact(Path path)throws Exception;
    void publishPath(Path path,long artifactId,long size,long mtime)throws Exception;
    long publishBinary(ArtifactInput input,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception;
    void publishCode(long artifactId,ArtifactContext context,ArtifactIndexFormat.ArtifactData facts,Set<String> classReferences)throws Exception;

    Map<String,Long> counts()throws Exception;
    Map<String,Object> status();
    List<String> loadWorkspace(String workspace,List<WorkspaceEntry> paths,List<Map.Entry<String,String>> dependencies)throws Exception;
    List<Map<String,Object>> find(String query,String workspace,boolean substring,int limit,long after,Set<String> kinds)throws Exception;
    List<Map<String,Object>> descendants(String path,String workspace,int depth,int limit,long after,Set<String> kinds)throws Exception;
    Map<String,Object> byId(long id,String workspace)throws Exception;
}

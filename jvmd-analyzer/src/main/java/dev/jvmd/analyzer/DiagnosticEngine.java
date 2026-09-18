package dev.jvmd.analyzer;

import dev.jvmd.core.Documents;
import dev.jvmd.core.Envelope;
import java.nio.file.Path;
import java.util.*;

/**
 * Detached diagnostics surface. Implementations may be direct analyzers or isolated
 * module actors; compiler-owned objects never cross this boundary.
 */
public interface DiagnosticEngine {
    String contextKey();
    Set<Path> pendingPrerequisites(Path file) throws Exception;
    default Map<Path,Set<Path>> pendingPrerequisites(Collection<Path> files)throws Exception{
        var result=new LinkedHashMap<Path,Set<Path>>();
        for(Path file:files)result.put(file,pendingPrerequisites(file));
        return Map.copyOf(result);
    }
    void changed(Path path,String hash) throws Exception;
    void namespaceChanged() throws Exception;
    Envelope cachedDiagnostics(Path path,Documents documents) throws Exception;
    default Map<Path,Envelope> cachedDiagnostics(Collection<Path> files,Documents documents)throws Exception{
        var result=new LinkedHashMap<Path,Envelope>();
        for(Path file:files){var value=cachedDiagnostics(file,documents);if(value!=null)result.put(file,value);}
        return Map.copyOf(result);
    }
    Envelope diagnostics(Path path,Documents documents) throws Exception;
    Map<Path,Envelope> diagnosticsBatch(Map<Path,String> sources) throws Exception;
    Map<String,Object> status() throws Exception;
    default long cpuNanos(){return 0L;}
    default boolean isolated(){return false;}
}

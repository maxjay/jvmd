package dev.jvmd.analyzer;

import dev.jvmd.core.Documents;
import dev.jvmd.core.Envelope;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/**
 * Detached diagnostics surface. Implementations may be direct analyzers or isolated
 * module actors; compiler-owned objects never cross this boundary.
 */
public interface DiagnosticEngine {
    String contextKey();
    Set<Path> pendingPrerequisites(Path file) throws Exception;
    void changed(Path path,String hash) throws Exception;
    void namespaceChanged() throws Exception;
    Envelope cachedDiagnostics(Path path,Documents documents) throws Exception;
    Envelope diagnostics(Path path,Documents documents) throws Exception;
    Map<Path,Envelope> diagnosticsBatch(Map<Path,String> sources) throws Exception;
    Map<String,Object> status() throws Exception;
    default long cpuNanos(){return 0L;}
    default boolean isolated(){return false;}
}

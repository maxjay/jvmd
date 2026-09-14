package dev.jvmd.resolver.engine;

import dev.jvmd.resolver.*;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.repository.*;

/** Implements 4.6: the real, bundle-local Aether WorkspaceReader; no version-specific type crosses the loader boundary. */
final class OverlayReader implements WorkspaceReader {
    private final WorkspaceSource source;
    private final WorkspaceRepository repository = new WorkspaceRepository("jvmd-local");
    OverlayReader(WorkspaceSource source) { this.source=source; }
    private static WorkspaceSource.Artifact descriptor(Artifact a) {
        return new WorkspaceSource.Artifact(a.getGroupId(),a.getArtifactId(),a.getExtension(),a.getClassifier(),a.getVersion());
    }
    @Override public WorkspaceRepository getRepository() { return repository; }
    @Override public File findArtifact(Artifact artifact) { return source.findArtifact(descriptor(artifact)); }
    public Path findArtifactPath(Artifact artifact) { File result=findArtifact(artifact); return result==null?null:result.toPath(); }
    @Override public List<String> findVersions(Artifact artifact) { return source.findVersions(descriptor(artifact)); }
    boolean sourceOnly(Artifact artifact) { return artifact!=null && source instanceof WorkspaceOverlay overlay && overlay.sourceOnly(descriptor(artifact)); }
}

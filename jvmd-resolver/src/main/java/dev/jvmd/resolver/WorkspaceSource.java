package dev.jvmd.resolver;

import java.io.File;
import java.util.List;

/** Transport-neutral workspace seam across isolated resolver bundles. */
public interface WorkspaceSource {
    record Artifact(String groupId, String artifactId, String extension, String classifier, String version) {
        public String gav() { return groupId + ":" + artifactId + ":" + version; }
    }
    File findArtifact(Artifact artifact);
    List<String> findVersions(Artifact artifact);
}

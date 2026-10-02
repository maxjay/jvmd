package dev.jvmd.index.machine;

import dev.jvmd.core.Hashing;
import dev.jvmd.index.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.jar.JarFile;

/**
 * Joins a leaf's paired sources archive to the class models its builder already parsed. The binary
 * is never read again; the sources archive is read twice, once to hash it and once for its text.
 */
public final class MachineDocumentation {
    private final ArtifactAdmission admission;
    private final MachineStore store;

    public MachineDocumentation(ArtifactAdmission admission,MachineStore store){
        this.admission=Objects.requireNonNull(admission);this.store=Objects.requireNonNull(store);
    }

    public MachineLeaf join(MachineLeafBuilder.Built built,MachineInputs.Sources sources)throws Exception{
        try(var permit=admission.acquireArtifact(sources.path())){
            String sha=Hashing.sha256(sources.path());
            var texts=new LinkedHashMap<String,String>();
            try(var jar=new JarFile(sources.path().toFile(),false,JarFile.OPEN_READ,Runtime.version())){
                for(var entry:jar.versionedStream().filter(e->e.getName().endsWith(".java")&&!e.getName().startsWith("META-INF/")).toList())
                    try(var stream=jar.getInputStream(entry)){texts.put(entry.getName(),new String(stream.readAllBytes(),StandardCharsets.UTF_8));}
            }
            var joined=new SourceJoin().join(built.models(),texts);
            var members=SourceJoin.documentation(joined,texts,sources.path().toUri().toString());
            String docsKey=store.ingestDocumentation(built.leaf().cacheKey(),ArtifactIndexFormat.key(sha,"sources"),members,joined.unmatched().size());
            return built.leaf().withDocumentation(new MachineLeaf.Documentation(docsKey,sha,sources,joined.unmatched().size()));
        }
    }
}

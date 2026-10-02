package dev.jvmd.index.machine;

import dev.jvmd.index.*;
import java.lang.classfile.ClassModel;
import java.util.*;

/**
 * Builds one MACHINE leaf from one content's bytes: parse, build facts and resolution identity,
 * publish the facts. Admission is held around parse and publish only.
 */
public final class MachineLeafBuilder {
    private static final Set<String> TYPES=Set.of("class","interface","enum","record","annotation");

    /** A built leaf with the class models its documentation is joined against. */
    public record Built(MachineLeaf leaf,Map<String,ClassModel> models,List<String> warnings) {
        public Built { Objects.requireNonNull(leaf);models=Map.copyOf(models);warnings=List.copyOf(warnings); }
    }

    private final ArtifactAdmission admission;
    private final MachineStore store;

    public MachineLeafBuilder(ArtifactAdmission admission,MachineStore store){
        this.admission=Objects.requireNonNull(admission);this.store=Objects.requireNonNull(store);
    }

    public Built build(MachineInputs.Content content)throws Exception{
        String mode=content.kind()==MachineInputs.Kind.JDK_MODULE?"jdk-signatures":"signatures";
        var key=ArtifactIndexFormat.key(content.sha256(),mode);
        var input=content.primary();
        try(var permit=admission.acquireArtifact(input.path())){
            var parsed=new BinaryReader().read(input.path(),false);
            var facts=ArtifactIndexFormat.from(parsed,key);
            var classReferences=CodeReader.classReferences(parsed.models().values());
            store.ingest(facts,classReferences);
            var locations=content.inputs().stream()
                    .map(each->new MachineLeaf.Location(each.location(),each.gav(),each.kind(),each.stamp())).toList();
            var counts=new MachineLeaf.Counts(facts.symbols().size(),facts.relationships().size(),
                    facts.symbols().stream().filter(symbol->TYPES.contains(symbol.kind())).count(),!classReferences.isEmpty());
            var leaf=new MachineLeaf(key.cacheKey(),content.sha256(),mode,locations,ArtifactIndexFormat.resolutionIdentity(facts),null,counts);
            return new Built(leaf,parsed.models(),parsed.warnings());
        }
    }
}

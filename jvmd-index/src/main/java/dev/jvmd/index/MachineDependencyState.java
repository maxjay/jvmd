package dev.jvmd.index;

import dev.jvmd.core.AlgebraicAccumulator;
import dev.jvmd.core.Hash256;
import java.util.*;

/**
 * Resolution-relevant machine dependency state.
 *
 * The machine identity is a commutative inventory identity over all known artifact resolution
 * semantics. A workspace dependency identity is deliberately derived separately from only that
 * workspace's selected artifacts in resolver order.
 *
 * Updating an unrelated machine artifact can therefore change {@link #identity()} while leaving a
 * workspace dependency root mathematically equal. The machine identity is an observation/diff
 * boundary; it is never implicitly folded into every workspace root.
 */
public final class MachineDependencyState {
    public record Artifact(String key,Hash256 resolutionIdentity) {
        public Artifact {
            Objects.requireNonNull(key);
            Objects.requireNonNull(resolutionIdentity);
            if(key.isBlank())throw new IllegalArgumentException("Artifact key must not be blank");
        }
    }

    public record WorkspaceDependencies(ClasspathSequence sequence) {
        public WorkspaceDependencies { Objects.requireNonNull(sequence); }
        public Hash256 identity(){return sequence.identity();}
        public List<ClasspathSequence.Entry> entries(){return sequence.entries();}
        public ClasspathSequence.Difference diff(WorkspaceDependencies current){
            return sequence.diff(Objects.requireNonNull(current).sequence);
        }
    }

    private final Map<String,Artifact> artifacts=new HashMap<>();
    private final AlgebraicAccumulator machine=new AlgebraicAccumulator("machine-dependency-resolution-v1");

    public int size(){return artifacts.size();}
    public Hash256 identity(){return machine.identity();}
    public Optional<Artifact> artifact(String key){return Optional.ofNullable(artifacts.get(Objects.requireNonNull(key)));}

    public void put(Artifact artifact){
        Objects.requireNonNull(artifact);
        Artifact previous=artifacts.put(artifact.key(),artifact);
        machine.replace(artifact.key(),previous==null?null:previous.resolutionIdentity(),
                artifact.key(),artifact.resolutionIdentity());
    }

    public Optional<Artifact> remove(String key){
        Objects.requireNonNull(key);
        Artifact previous=artifacts.remove(key);
        if(previous!=null)machine.remove(previous.key(),previous.resolutionIdentity());
        return Optional.ofNullable(previous);
    }

    /**
     * Compose only the selected ordered artifacts. The global machine identity is intentionally not
     * an input: it may change because of artifacts this workspace cannot resolve through.
     */
    public WorkspaceDependencies workspaceDependencies(List<String> orderedArtifactKeys){
        Objects.requireNonNull(orderedArtifactKeys);
        var entries=new ArrayList<ClasspathSequence.Entry>(orderedArtifactKeys.size());
        var seen=new HashSet<String>();
        for(String key:orderedArtifactKeys){
            Objects.requireNonNull(key);
            if(!seen.add(key))throw new IllegalArgumentException("Duplicate workspace classpath artifact: "+key);
            Artifact artifact=artifacts.get(key);
            if(artifact==null)throw new IllegalArgumentException("Unknown machine artifact: "+key);
            entries.add(new ClasspathSequence.Entry(artifact.key(),artifact.resolutionIdentity()));
        }
        return new WorkspaceDependencies(ClasspathSequence.of(entries));
    }
}

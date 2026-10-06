package dev.jvmd.index.layer.local;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.machine.MachineStore;
import java.util.function.Function;

/** Reads one scope's processing inputs and exact derivations through its committed LOCAL tree, never a store scan. */
public final class ProcessorPlan {
    public record Generation(Identity derivation, Root outputs) { }
    private final ContentTree tree;
    private final Identity local;
    private final Identity project;
    private final String module;
    private final int scope;
    private final Function<byte[], byte[]> records;
    private final ProcessorRecords.Scope invocation;
    private final LocalRoot committed;

    private ProcessorPlan(ContentTree tree, LocalRoot local, Identity project, String module, int scope,
                          Function<byte[], byte[]> records) {
        this.tree = tree; this.local = local.local().hash(); this.project = project; this.module = module; this.scope = scope; this.records = records;
        this.committed = local;
        invocation = ProcessorRecords.Scope.decode(required(LocalStore.processorScopeKey(project, module, scope)), tree.digest().width());
    }

    /** The caller supplies the current committed LROOT, already checked against the model/MACHINE generation. */
    public static ProcessorPlan load(ContentTree tree, LocalRoot local, Identity project, String module, int scope,
                                     Function<byte[], byte[]> records) {
        return new ProcessorPlan(tree, local, project, module, scope, records);
    }

    public ProcessorRecords.Scope invocation() { return invocation; }

    /** Bind source metadata by its committed module/scope origins, independently of the invocation plan. */
    public ProcessorSources.Binding sources() {
        return ProcessorSources.bind(tree, committed, project, module, scope, records);
    }

    /** Null means this origin had no admitted derivation in that generation; it does not mean an empty output set. */
    public Generation generation(String processor, String origin) {
        var capability = invocation.capability(processor);
        if (capability == null) throw new IllegalArgumentException("Processor was not configured in this scope: " + processor);
        if (capability.declared() == ProcessorRecords.AGGREGATING && !origin.isEmpty())
            throw new IllegalArgumentException("An aggregate derivation has no source origin");
        var value = read(LocalStore.processorGenerationKey(project, module, scope, processor, origin));
        if (value == null) return null;
        if (value.length != tree.digest().width()) throw new IllegalStateException("Invalid committed processor generation reference");
        var id = Identity.of(value.clone());
        var outputs = DefinerIndex.decodeRoot(required(LocalStore.generatedKey(id)), tree.digest().width());
        return new Generation(id, outputs);
    }

    /** Exact path/kind/content comparison. This proves output conservation, not the processor's model-read proof. */
    public boolean matches(Generation generation, java.util.List<GeneratedOutputs.Output> outputs) {
        java.util.Objects.requireNonNull(generation);
        return GeneratedOutputs.matches(tree, generation.outputs(), id -> records.apply(MachineStore.nodeKey(id)), outputs);
    }

    private byte[] required(byte[] key) {
        var value = read(key);
        if (value == null) throw new IllegalStateException("Required processor record is not in the committed LOCAL tree");
        return value;
    }

    private byte[] read(byte[] key) {
        var entry = tree.get(local, id -> records.apply(MachineStore.nodeKey(id)), key);
        if (entry == null) return null;
        var value = records.apply(key);
        if (value == null || !tree.digest().hash(value).equals(entry.h()))
            throw new IllegalStateException("Processor record differs from the committed LOCAL tree");
        return value;
    }
}

package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Root;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.GeneratedOutputs;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProcessorRecords;
import dev.jvmd.index.layer.local.ProjectModel;
import dev.jvmd.index.layer.machine.Keys;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.tools.JavaFileObject;

/** Completes processor derivations after the scope's own leaf and header proofs have been sealed. */
final class ModuleProcessing {
    final Boot boot;
    final ProjectModel.Module module;
    final int scope;
    final ProcessorHost host;
    final ProcessorConfiguration configuration;
    final Identity optionsHash;
    final List<String> processorNames;

    ModuleProcessing(Boot boot, ProjectModel.Module module, int scope, List<String> options, int release, List<String> paths) throws IOException {
        this.boot = boot; this.module = module; this.scope = scope;
        configuration = boot.configuration();
        var processorPath = module.processing().path().stream().map(j -> boot.repository.resolve(j.location())).toList();
        host = new ProcessorHost(processorPath, module.processing().processors(), boot.digest, boot.generatedDirectory(module.name(), scope));
        host.sourcePaths(boot::sourcePath);
        processorNames = host.names();
        for (var name : host.names()) {
            var previous = boot.store.get(LocalStore.processorKey(host.pathHash(), name));
            if (previous != null) host.previousCapability(name, ProcessorRecords.Capability.decode(previous));
        }
        optionsHash = HeaderCompiler.optionsHash(boot.digest, options, release, host.pathHash(), host.names());
        for (var path : paths) for (var config : configuration.unmodelledImports(path)) host.rejectReuse("unmodelled configuration import in " + config);
    }

    private ProcessorRecords.Context context(String path) {
        return new ProcessorRecords.Context(host.pathHash(), optionsHash, configuration.proof(path));
    }

    void finish(Map<String, FileRow> rows) {
        boot.processingRecords.put(LocalStore.processorDiagnosticsKey(boot.projectKey, module.name(), scope), host.diagnostics().encode());
        for (var message : host.diagnostics().messages()) if (message.kind() == javax.tools.Diagnostic.Kind.ERROR)
            boot.faults.add((message.processorClass().isEmpty() ? "javac processing" : message.processorClass()) + ": "
                    + (message.path() == null ? "" : message.path() + ":" + message.line() + ":" + message.column() + ": ") + message.text());
        var capabilities = new TreeMap<>(host.capabilities());
        boolean reusable = host.faults().isEmpty() && capabilities.values().stream().allMatch(ProcessorRecords.Capability::reusable);
        boot.faults.addAll(host.faults());
        var domains = new TreeMap<String, Root>();
        for (var e : host.domains().entrySet()) {
            var root = boot.tree.build(e.getValue(), boot.sink);
            domains.put(e.getKey(), root);
            boot.processingRecords.put(LocalStore.processorDomainKey(boot.projectKey, module.name(), scope, e.getKey()), DefinerIndex.encodeRoot(root));
        }
        boot.sink.flush();

        var generated = new TreeMap<String, ProcessorHost.Output>();
        for (var output : host.outputs()) if (output.kind() == JavaFileObject.Kind.SOURCE) generated.put(boot.sourcePath(output.uri()), output);
        var byDerivation = new TreeMap<Identity, List<GeneratedOutputs.Output>>();
        var processorByDerivation = new TreeMap<Identity, String>();
        var generationReferences = new TreeMap<byte[], byte[]>(java.util.Arrays::compareUnsigned);
        var ids = new TreeMap<String, Identity>();
        var origins = new TreeMap<String, String>();
        if (reusable) for (var e : capabilities.entrySet()) {
            if (e.getValue().observed() != ProcessorRecords.GENERATOR) continue;
            if (e.getValue().declared() == ProcessorRecords.AGGREGATING) {
                var id = aggregateId(e.getKey(), domains.get(e.getKey()));
                byDerivation.put(id, new ArrayList<>()); // the empty output set is an exact manifest too
                processorByDerivation.put(id, e.getKey());
                generationReferences.put(generationKey(e.getKey(), ""), id.bytes());
            } else for (var uri : host.inputs().getOrDefault(e.getKey(), List.of())) {
                var id = isolatingId(e.getKey(), boot.sourcePath(uri), rows);
                byDerivation.put(id, new ArrayList<>());
                processorByDerivation.put(id, e.getKey());
                generationReferences.put(generationKey(e.getKey(), boot.sourcePath(uri)), id.bytes());
            }
        }
        for (var e : generated.entrySet()) {
            var output = e.getValue();
            String origin = output.origins().size() == 1 ? boot.sourcePath(output.origins().getFirst()) : null;
            if (origin != null) origins.put(e.getKey(), origin);
            if (!reusable) continue;
            var capability = capabilities.get(output.processorClass());
            Identity id;
            if (capability.declared() == ProcessorRecords.AGGREGATING) {
                id = aggregateId(output.processorClass(), domains.get(output.processorClass()));
            } else id = isolatingId(output.processorClass(), origin, rows);
            ids.put(e.getKey(), id);
            generationReferences.put(generationKey(output.processorClass(), capability.declared() == ProcessorRecords.AGGREGATING ? "" : origin), id.bytes());
            processorByDerivation.put(id, output.processorClass());
            byDerivation.computeIfAbsent(id, ignored -> new ArrayList<>()).add(new GeneratedOutputs.Output(0, output.name(), output.bytes()));
        }
        var roots = new TreeMap<Identity, Root>();
        for (var e : byDerivation.entrySet()) {
            try {
                roots.put(e.getKey(), GeneratedOutputs.persist(boot.digest, boot.tree, boot.store, boot.sink, e.getKey(), e.getValue()));
            } catch (GeneratedOutputs.Inconsistent inconsistent) {
                var name = processorByDerivation.get(e.getKey());
                capabilities.put(name, new ProcessorRecords.Capability(capabilities.get(name).declared(), ProcessorRecords.VIOLATED));
                boot.faults.add(name + ": unsupported for reuse: " + inconsistent.getMessage());
                reusable = false;
                ids.clear();
            }
        }
        if (reusable) {
            for (var e : roots.entrySet()) boot.processingRecords.put(LocalStore.generatedKey(e.getKey()), DefinerIndex.encodeRoot(e.getValue()));
            boot.processingRecords.putAll(generationReferences);
        }
        var scopeRecord = new ProcessorRecords.Scope(host.pathHash(), optionsHash, processorNames.stream()
                .map(name -> new ProcessorRecords.Invocation(name, capabilities.get(name))).toList());
        boot.processingRecords.put(LocalStore.processorScopeKey(boot.projectKey, module.name(), scope), scopeRecord.encode());
        for (var e : capabilities.entrySet()) {
            var key = LocalStore.processorKey(host.pathHash(), e.getKey());
            // A violation in either scope remains a violation for this processor code during this boot.
            boot.processingRecords.merge(key, e.getValue().encode(), (a, b) -> {
                var previous = ProcessorRecords.Capability.decode(a);
                var next = ProcessorRecords.Capability.decode(b);
                return previous.merge(next).encode();
            });
        }
        for (var row : rows.values()) {
            boolean isGenerated = generated.containsKey(row.path());
            var updated = new FileRow(row.path(), row.kappa(), row.size(), row.mtimeNanos(), row.sum(), row.typeKeys(), row.faults(), row.headerProof(),
                    row.ownR(), row.absences(), isGenerated, origins.get(row.path()), ids.get(row.path()), context(row.path()));
            boot.files.put(updated.path(), updated);
        }
    }

    private byte[] generationKey(String processor, String origin) {
        return LocalStore.processorGenerationKey(boot.projectKey, module.name(), scope, processor, origin);
    }

    private Identity aggregateId(String processor, Root domain) {
        return boot.digest.hash(new Codec.Writer().str(processor).id(host.pathHash()).id(optionsHash).id(domain.sum())
                .lenBytes(host.modelProof(processor, null)).toBytes());
    }

    private Identity isolatingId(String processor, String origin, Map<String, FileRow> rows) {
        var row = origin == null ? null : rows.get(origin);
        if (row == null) throw new IllegalStateException("Recorded generator origin has no file row: " + origin);
        var input = new Codec.Writer().str(processor).id(host.pathHash()).id(optionsHash).zstr(origin).id(row.kappa());
        var configuration = context(origin).configuration();
        input.u32(configuration.size());
        for (var observation : configuration) observation.encode(input);
        var proof = new TreeMap<byte[], Identity>(java.util.Arrays::compareUnsigned);
        for (var observation : row.headerProof()) proof.put(new Codec.Writer().u8(0).raw(observation.range().key()).toBytes(), observation.sum());
        for (var absence : row.absences()) proof.put(new Codec.Writer().u8(absence.form() == 0 ? 2 : 1).raw(absence.key()).toBytes(), boot.tree.sums().zero());
        input.u32(proof.size());
        for (var observation : proof.entrySet()) input.lenBytes(observation.getKey()).id(observation.getValue());
        input.lenBytes(host.modelProof(processor, origin));
        return boot.digest.hash(input.toBytes());
    }
}

package dev.jvmd.boot.cold.stage3;

import dev.jvmd.boot.cold.stage2.ProcessorConfiguration;
import dev.jvmd.boot.cold.stage2.ProcessorHost;
import dev.jvmd.core.hash.Digest;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.GeneratedOutputs;
import dev.jvmd.index.layer.local.ProcessorPlan;
import dev.jvmd.index.layer.local.ProcessorRecords;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.JavaFileObject;

/** One body's processor execution and conservation against the current committed Stage 2 plan. */
final class ProcessorBody {
    private final ProcessorPlan plan;
    private final List<Path> path;
    private final Path project;

    ProcessorBody(ProcessorPlan plan, List<Path> path, Path project) {
        this.plan = plan; this.path = List.copyOf(path); this.project = project.toAbsolutePath().normalize();
    }

    List<String> check(Digest digest, FileRow row, URI uri) throws IOException {
        if (!project.resolve(row.path()).normalize().toUri().equals(uri))
            throw new IllegalArgumentException("Processor source location differs from F: " + row.path());
        var snapshot = ProcessorConfiguration.current(digest, project, row.path());
        var current = new ProcessorRecords.Context(plan.invocation().processorPathHash(), plan.invocation().optionsHash(), snapshot.proof());
        if (!current.equals(row.processor()))
            throw new IllegalArgumentException("Processor context differs from F: " + row.path());
        var faults = new ArrayList<String>();
        for (var imported : snapshot.unmodelledImports()) for (var processor : plan.invocation().processors())
            faults.add(processor.processorClass() + ": unsupported for reuse: unmodelled configuration import in " + imported);
        return List.copyOf(faults);
    }

    ProcessorHost open(Digest digest, Attribute.Options options) throws IOException {
        return ProcessorHost.bodies(path, digest, project.resolve(".jvmd/body-capture"), options.charset(), plan.invocation(), options.hash());
    }

    /** Check every configured generator, including a native derivation whose processor did not run in this unit. */
    List<String> conservation(ProcessorHost host, FileRow row, URI source) {
        var faults = new ArrayList<String>();
        for (var processor : plan.invocation().processors()) {
            var name = processor.processorClass(); var capability = processor.capability();
            if (!capability.reusable()) faults.add(name + ": unsupported for reuse: Stage 2 scope did not admit this processor");
            var generated = host.outputs().stream().filter(o -> o.processorClass().equals(name)).toList();
            if (capability.declared() == ProcessorRecords.AGGREGATING
                    || capability.observed() != ProcessorRecords.GENERATOR && generated.isEmpty()) continue;
            var generation = plan.generation(name, row.path());
            if (generation == null) {
                // An uninvoked/no-op processor may have no origin at all. A known empty origin must still have PG/GEN.
                if (!generated.isEmpty() || host.inputs().getOrDefault(name, List.of()).contains(source))
                    faults.add(name + ": unsupported for reuse: no committed derivation for " + row.path());
            } else {
                var outputs = new ArrayList<GeneratedOutputs.Output>();
                for (var output : generated) {
                    if (output.kind() != JavaFileObject.Kind.SOURCE || !output.origins().equals(List.of(source)))
                        faults.add(name + ": unsupported for reuse: output does not belong to this source: " + output.name());
                    outputs.add(new GeneratedOutputs.Output(switch (output.kind()) { case SOURCE -> 0; case CLASS -> 1; default -> 2; },
                            output.name(), output.bytes()));
                }
                if (!plan.matches(generation, outputs))
                    faults.add(name + ": unsupported for reuse: generated output differs from committed GEN for " + row.path());
            }
        }
        return List.copyOf(faults);
    }
}

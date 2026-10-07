package dev.jvmd.boot.cold.stage2;

import dev.jvmd.core.tree.Entry;
import dev.jvmd.index.layer.local.DefinerIndex;
import dev.jvmd.index.layer.local.FileRow;
import dev.jvmd.index.layer.local.LocalStore;
import dev.jvmd.index.layer.local.ProcessorElementProjection;
import dev.jvmd.index.layer.local.ProcessorSources;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import javax.lang.model.element.TypeElement;

/** Materializes source declarations while the scope's completed header model is live. No source/body is reread. */
final class SourceDeclarations {
    private final Boot boot;
    private final ProcessorElementProjection projection;
    private final ProcessorElementProjection.Graph graph;
    private final javax.lang.model.util.Elements elements;
    private final TreeMap<byte[], Entry> entries = new TreeMap<>(Arrays::compareUnsigned);

    SourceDeclarations(Boot boot, HeaderCompiler.Compiled compiled) {
        this.boot = boot;
        elements = compiled.elements;
        projection = new ProcessorElementProjection(compiled.elements, compiled.types);
        graph=projection.graph(boot.digest,(id,bytes)->boot.store.put(LocalStore.processorDeclarationKey(id),bytes));
    }

    void add(String path, List<TypeElement> declared, javax.lang.model.element.PackageElement pkg, List<FileRow.Fault> faults) {
        for (var type : declared) {
            var owner = (javax.lang.model.element.PackageElement) type.getEnclosingElement();
            presence(owner.getQualifiedName().toString());
            members(path, owner, faults);
            add(path, type, faults);
        }
        if (pkg != null) {
            members(path, pkg, faults);
            presence(pkg.getQualifiedName().toString());
            var key = projection.key(pkg);
            var previous = entries.get(key);
            if (previous != null && previous.value()[0] != 2) return;
            Entry entry;
            try { entry = ProcessorSources.available(boot.tree, key, path, graph.node(pkg)); }
            catch (RuntimeException incomplete) {
                String reason = incomplete.getClass().getSimpleName() + ": " + incomplete.getMessage();
                entry = ProcessorSources.unavailable(boot.tree, key, path, reason);
                faults.add(new FileRow.Fault(key, "processor package: " + reason));
            }
            entries.put(key, entry);
        }
    }

    private void members(String path, javax.lang.model.element.PackageElement pkg, List<FileRow.Fault> faults) {
        String name = pkg.getQualifiedName().toString();
        var key = ProcessorSources.packageMembersKey(name);
        if (entries.containsKey(key)) return;
        Entry entry;
        try {
            var members = pkg.getEnclosedElements().stream().map(e -> elements.getBinaryName((TypeElement) e).toString()).toList();
            entry = ProcessorSources.packageMembers(boot.tree, name, members);
        } catch (RuntimeException incomplete) {
            String reason = incomplete.getClass().getSimpleName() + ": " + incomplete.getMessage();
            entry = ProcessorSources.unavailable(boot.tree, key, path, reason);
            faults.add(new FileRow.Fault(key, "processor package members: " + reason));
        }
        entries.put(key, entry);
    }

    private void presence(String name) {
        while (true) {
            var entry = ProcessorSources.packagePresence(boot.tree, name);
            entries.putIfAbsent(entry.key(), entry);
            int dot = name.lastIndexOf('.');
            if (dot < 0) break;
            name = name.substring(0, dot);
        }
    }

    private void add(String path, TypeElement type, List<FileRow.Fault> faults) {
        var key = projection.key(type);
        if (entries.containsKey(key)) return; // the header compiler already reports duplicate source declarations
        Entry entry;
        try { entry = ProcessorSources.available(boot.tree, key, path, graph.node(type)); }
        catch (RuntimeException incomplete) {
            String reason = incomplete.getClass().getSimpleName() + ": " + incomplete.getMessage();
            entry = ProcessorSources.unavailable(boot.tree, key, path, reason);
            faults.add(new FileRow.Fault(key, "processor declaration: " + reason));
        }
        entries.put(key, entry);
        // A nested name may contain literal '$'. Give it its own exact key, never infer its owner by splitting a name.
        for (var child : type.getEnclosedElements()) if (child instanceof TypeElement nested) add(path, nested, faults);
    }

    void finish(String module, int scope) {
        long started = System.nanoTime();
        var root = boot.tree.build(entries.values(), boot.sink);
        boot.processingRecords.put(LocalStore.processorSourcesKey(boot.projectKey, module, scope), DefinerIndex.encodeRoot(root));
        boot.factsNanos.addAndGet(System.nanoTime() - started);
    }
}

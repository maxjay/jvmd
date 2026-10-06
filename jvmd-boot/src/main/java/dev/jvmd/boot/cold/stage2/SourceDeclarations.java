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
    private final TreeMap<byte[], Entry> entries = new TreeMap<>(Arrays::compareUnsigned);

    SourceDeclarations(Boot boot, HeaderCompiler.Compiled compiled) {
        this.boot = boot;
        projection = new ProcessorElementProjection(compiled.elements, compiled.types);
    }

    void add(String path, List<TypeElement> declared, javax.lang.model.element.PackageElement pkg, List<FileRow.Fault> faults) {
        for (var type : declared) {
            presence(((javax.lang.model.element.PackageElement) type.getEnclosingElement()).getQualifiedName().toString());
            add(path, type, faults);
        }
        if (pkg != null) {
            presence(pkg.getQualifiedName().toString());
            var key = projection.key(pkg);
            var previous = entries.get(key);
            if (previous != null && previous.value()[0] != 2) return;
            Entry entry;
            try { entry = ProcessorSources.available(boot.tree, key, projection.packageHeader(pkg, path)); }
            catch (RuntimeException incomplete) {
                String reason = incomplete.getClass().getSimpleName() + ": " + incomplete.getMessage();
                entry = ProcessorSources.unavailable(boot.tree, key, path, reason);
                faults.add(new FileRow.Fault(key, "processor package: " + reason));
            }
            entries.put(key, entry);
        }
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
        try { entry = ProcessorSources.available(boot.tree, key, projection.of(type, path)); }
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

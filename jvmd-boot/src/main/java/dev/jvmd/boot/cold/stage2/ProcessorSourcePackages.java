package dev.jvmd.boot.cold.stage2;

import com.sun.tools.javac.code.Scope;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.Modules;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Names;
import dev.jvmd.index.layer.local.ProcessorSources;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.PackageElement;
import javax.lang.model.util.Elements;

/** Source packages need not emit a class file. Missing handles are detached, never installed in cached compiler scopes. */
final class ProcessorSourcePackages {
    private final Elements elements;
    private final ProcessorSources.Binding sources;
    private final Modules modules;
    private final Symtab symbols;
    private final Names names;
    private final com.sun.tools.javac.util.Log log;
    private final Map<String, Symbol.PackageSymbol> detached = new HashMap<>();
    private final Set<String> duplicates = new HashSet<>();

    ProcessorSourcePackages(Context context, Elements elements, ProcessorSources.Binding sources) {
        this.elements = elements; this.sources = sources;
        modules = Modules.instance(context); symbols = Symtab.instance(context); names = Names.instance(context);
        log = com.sun.tools.javac.util.Log.instance(context);
    }

    PackageElement qualified(ModuleElement module, CharSequence name) {
        // Let javac validate null/foreign module handles and invalid names before consulting source presence.
        var nativePackage = elements.getPackageElement(module, name);
        if (nativePackage != null || module != modules.getDefaultModule() || !valid(name)) return nativePackage;
        return sources.packageExists(name.toString()) ? detached(name.toString()) : null;
    }

    PackageElement unbound(CharSequence name) {
        if (name == null || !valid(name) || sources.packageHeader(name.toString()) == null) return elements.getPackageElement(name);
        if (modules.getDefaultModule() == symbols.noModule) return qualified(symbols.noModule, name);
        // Match javac's two-stage search and ignore parent-only packages in unqualified lookups.
        var remaining = new HashSet<>(modules.allModules()); remaining.removeAll(modules.getRootModules());
        for (var candidates : java.util.List.of(modules.getRootModules(), remaining)) {
            var found = new LinkedHashSet<PackageElement>();
            for (var module : candidates) {
                var pkg = (Symbol.PackageSymbol) qualified(module, name);
                if (pkg != null && (module == modules.getDefaultModule()
                        || !pkg.members().isEmpty() || pkg.package_info != null)) found.add(pkg);
            }
            if (found.size() == 1) return found.iterator().next();
            if (found.size() > 1) {
                if (duplicates.add(name.toString())) log.note(new com.sun.tools.javac.util.JCDiagnostic.Note("compiler", "multiple.elements",
                        "getPackageElement", name.toString(), found.stream().map(p -> elements.getModuleOf(p).toString())
                                .collect(java.util.stream.Collectors.joining(", "))));
                return null;
            }
        }
        return null;
    }

    Set<? extends PackageElement> all(CharSequence name) {
        var modules = elements.getAllModuleElements();
        if (modules.isEmpty()) {
            var pkg = unbound(name); return pkg == null ? Collections.emptySet() : Collections.singleton(pkg);
        }
        var result = new LinkedHashSet<PackageElement>();
        for (var module : modules) {
            var pkg = qualified(module, name); if (pkg != null) result.add(pkg);
        }
        return Collections.unmodifiableSet(result);
    }

    java.util.List<? extends javax.lang.model.element.Element> enclosed(PackageElement pkg) {
        if (elements.getModuleOf(pkg) != modules.getDefaultModule()) return null;
        var saved = sources.packageMembers(pkg.getQualifiedName().toString());
        if (saved == null) return null;
        var nativeMembers = new java.util.HashMap<String, javax.lang.model.element.Element>();
        for (var member : pkg.getEnclosedElements())
            nativeMembers.put(elements.getBinaryName((javax.lang.model.element.TypeElement) member).toString(), member);
        return saved.stream().map(name -> {
            var member = nativeMembers.get(name);
            if (member == null) throw new IllegalStateException("Source package member has no native handle: " + name);
            return member;
        }).toList();
    }

    private Symbol.PackageSymbol detached(String name) {
        var existing = detached.get(name);
        if (existing != null) return existing;
        int dot = name.lastIndexOf('.');
        var parent = dot < 0 ? symbols.rootPackage : (Symbol.PackageSymbol) qualified(modules.getDefaultModule(), name.substring(0, dot));
        var pkg = new Symbol.PackageSymbol(names.fromString(name.substring(dot + 1)), parent);
        pkg.modle = modules.getDefaultModule();
        pkg.members_field = Scope.WriteableScope.create(pkg);
        pkg.flags_field |= com.sun.tools.javac.code.Flags.EXISTS;
        detached.put(name, pkg); return pkg;
    }

    java.util.List<? extends PackageElement> enclosed(ModuleElement module, java.util.List<String> packages) {
        return packages.stream().map(name -> {
            var pkg = qualified(module, name);
            if (pkg == null) throw new IllegalStateException("Observed module package has no native handle: " + name);
            return pkg;
        }).toList();
    }

    private static boolean valid(CharSequence name) { return name.length() == 0 || SourceVersion.isName(name); }
}

package dev.jvmd.boot.cold.stage2;

import dev.jvmd.index.layer.local.ProcessorDeclaration;
import dev.jvmd.index.layer.local.ProcessorElementProjection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/** Matches saved source declarations to native handles; no symbol mutations or eager traversal for a type-only query. */
final class ProcessorSourceElements {
    private final Elements elements;
    private final ProcessorElementProjection keys;
    private final Function<String, ProcessorDeclaration.Source> sources;
    private final Predicate<Element> explicit;
    private final java.util.function.BiFunction<TypeElement, ProcessorDeclaration, Element> missing;
    private final Map<Element, ProcessorDeclaration> declarations = new IdentityHashMap<>();
    private final Map<TypeElement, Map<ProcessorDeclaration.Key, Element>> members = new IdentityHashMap<>();
    private final Map<ProcessorDeclaration.Key, Element> parameters = new LinkedHashMap<>();

    ProcessorSourceElements(Elements elements, Types types, Function<String, ProcessorDeclaration.Source> sources, Predicate<Element> explicit,
                            java.util.function.BiFunction<TypeElement, ProcessorDeclaration, Element> missing) {
        this.elements = elements; this.keys = new ProcessorElementProjection(elements, types); this.sources = sources; this.explicit = explicit;
        this.missing = missing;
    }

    ProcessorDeclaration declaration(Element element) {
        if (declarations.containsKey(element)) return declarations.get(element);
        if (element instanceof TypeElement type) {
            var source = explicit.test(type) ? null : sources.apply(elements.getBinaryName(type).toString().replace('.', '/'));
            var declaration = source == null ? null : source.declaration(); declarations.put(type, declaration); return declaration;
        }
        var owner = element.getEnclosingElement();
        var parent = owner == null ? null : declaration(owner);
        if (parent != null) {
            switch (owner) {
                case TypeElement type -> members(type, (ProcessorDeclaration.TypeDeclaration) parent.detail());
                case ExecutableElement method -> {
                    var executable = (ProcessorDeclaration.Executable) parent.detail();
                    parameters(method.getParameters(), executable.parameters());
                    parameters(method.getTypeParameters(), executable.typeParameters());
                }
                default -> { }
            }
        }
        // Extra binary-only declarations are not silently admitted as source declarations.
        if (parent != null && !declarations.containsKey(element))
            throw new IllegalStateException("Native element has no source declaration: " + element.getKind() + " " + element);
        if (!declarations.containsKey(element)) declarations.put(element, null);
        return declarations.get(element);
    }

    List<Element> enclosed(TypeElement type) {
        var declaration = declaration(type);
        if (declaration == null) return null;
        var data = (ProcessorDeclaration.TypeDeclaration) declaration.detail();
        var nativeMembers = members(type, data);
        return data.enclosed().stream().map(member -> {
            var element = nativeMembers.get(member.key());
            if (element == null) throw new IllegalStateException("Source declaration has no native element: " + member.kind() + " " + member.name());
            return element;
        }).toList();
    }

    private Map<ProcessorDeclaration.Key, Element> members(TypeElement type, ProcessorDeclaration.TypeDeclaration data) {
        var existing = members.get(type);
        if (existing != null) return existing;
        var nativeMembers = new LinkedHashMap<ProcessorDeclaration.Key, Element>();
        for (var member : type.getEnclosedElements()) nativeMembers.put(new ProcessorDeclaration.Key(keys.key(member)), member);
        for (var component : type.getRecordComponents()) nativeMembers.put(new ProcessorDeclaration.Key(keys.key(component)), component);
        enclosingParameters(type);
        for (var member : data.enclosed()) if (!nativeMembers.containsKey(member.key())) {
            var element = missing.apply(type, member); nativeMembers.put(member.key(), element);
        }
        for (var member : data.enclosed()) bind(nativeMembers, member);
        for (var component : data.components()) bind(nativeMembers, component);
        parameters(type.getTypeParameters(), data.parameters());
        members.put(type, nativeMembers); return nativeMembers;
    }

    private void enclosingParameters(TypeElement type) {
        if (type.getEnclosingElement() instanceof TypeElement outer) enclosingParameters(outer);
        var source = declaration(type);
        if (source != null) parameters(type.getTypeParameters(), ((ProcessorDeclaration.TypeDeclaration) source.detail()).parameters());
    }

    Element variable(ProcessorDeclaration.Key key) {
        var element = parameters.get(key);
        if (element == null) throw new IllegalStateException("Source type variable has no native declaration");
        return element;
    }

    private void bind(Map<ProcessorDeclaration.Key, Element> nativeMembers, ProcessorDeclaration declaration) {
        var element = nativeMembers.get(declaration.key());
        if (element != null) declarations.put(element, declaration);
    }

    private void parameters(List<? extends Element> nativeParameters, List<ProcessorDeclaration> sourceParameters) {
        if (nativeParameters.size() != sourceParameters.size()) throw new IllegalStateException("Native/source parameter count differs");
        // Binary parameter names can be arg0/arg1. Their owning method is matched by its full erased descriptor first.
        for (int i = 0; i < nativeParameters.size(); i++) {
            var element = nativeParameters.get(i); var declaration = sourceParameters.get(i);
            if (element.getKind() != declaration.kind()) throw new IllegalStateException("Native/source parameter kinds differ");
            declarations.put(element, declaration);
            parameters.put(declaration.key(), element);
        }
    }
}

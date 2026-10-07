package dev.jvmd.boot.cold.stage2;

import com.sun.tools.javac.code.Attribute;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.util.List;
import dev.jvmd.index.layer.local.ProcessorDeclaration;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

/** Per-task source type views. Native symbols remain unchanged; recursive variables retain their original element handles. */
final class ProcessorSourceTypes {
    private final javax.lang.model.util.Types types;
    private final com.sun.tools.javac.code.Types compilerTypes;
    private final Symtab symbols;
    private final ProcessorSourceElements declarations;
    private final Function<String, Type> descriptor;
    private final Function<java.util.List<ProcessorDeclaration.Annotation>, List<Attribute.TypeCompound>> annotations;
    private final Map<Element, Type> elementTypes = new IdentityHashMap<>();
    private final Map<ProcessorDeclaration.Type, Type> typeUses = new IdentityHashMap<>();
    private final Map<Type, Type> hierarchies = new IdentityHashMap<>();
    private final Map<Type, Boolean> detached = new IdentityHashMap<>();

    ProcessorSourceTypes(javax.lang.model.util.Types types, com.sun.tools.javac.code.Types compilerTypes, Symtab symbols,
                         ProcessorSourceElements declarations, Function<String, Type> descriptor,
                         Function<java.util.List<ProcessorDeclaration.Annotation>, List<Attribute.TypeCompound>> annotations) {
        this.types = types; this.compilerTypes = compilerTypes; this.symbols = symbols; this.declarations = declarations;
        this.descriptor = descriptor; this.annotations = annotations;
    }

    Type of(Element element) {
        var existing = elementTypes.get(element);
        if (existing != null) return existing;
        var source = declarations.declaration(element);
        if (source == null) return (Type) element.asType();
        // Resolving a declaration may also construct its private siblings, including recursive references to this variable.
        existing = elementTypes.get(element);
        if (existing != null) return existing;
        Type result;
        switch (source.detail()) {
            case ProcessorDeclaration.TypeDeclaration ignored -> {
                var nativeType = (Type.ClassType) element.asType();
                var enclosing = nativeType.getEnclosingType();
                if (enclosing.getKind() == TypeKind.DECLARED) enclosing = of(enclosing.asElement());
                var declared = new Type.ClassType(enclosing, List.nil(), nativeType.tsym);
                elementTypes.put(element, declared); detached.put(declared, true);
                declared.typarams_field = List.from(((TypeElement) element).getTypeParameters().stream().map(this::of).toList());
                result = declared;
            }
            case ProcessorDeclaration.Parameter parameter -> {
                var nativeType = (Type.TypeVar) element.asType();
                var variable = new Type.TypeVar(nativeType.tsym, null, nativeType.getLowerBound());
                elementTypes.put(element, variable);
                compilerTypes.setBounds(variable, list(parameter.bounds()));
                result = variable;
            }
            case ProcessorDeclaration.Executable executable -> {
                var method = (ExecutableElement) element;
                var variables = List.from(method.getTypeParameters().stream().map(this::of).toList());
                var arguments = List.from(method.getParameters().stream().map(this::of).toList());
                var signature = new Type.MethodType(arguments, type(executable.returns()), list(executable.thrown()), symbols.methodClass);
                signature.recvtype = type(executable.receiver());
                result = variables.isEmpty() ? signature : new Type.ForAll(variables, signature);
            }
            case ProcessorDeclaration.Variable variable -> result = type(variable.type());
            case ProcessorDeclaration.Other other -> result = type(other.type());
            default -> result = (Type) element.asType();
        }
        elementTypes.put(element, result); return result;
    }

    List<Type> list(java.util.List<ProcessorDeclaration.Type> sources) { return List.from(sources.stream().map(this::type).toList()); }

    Type type(ProcessorDeclaration.Type source) {
        var existing = typeUses.get(source);
        if (existing != null) return existing;
        var result = type(source, Map.of()); typeUses.put(source, result); return result;
    }

    Type type(ProcessorDeclaration.Type source, Map<ProcessorDeclaration.Key, Type> variables) {
        Type result = (Type) switch (source.kind()) {
            case BOOLEAN, BYTE, CHAR, SHORT, INT, LONG, FLOAT, DOUBLE -> types.getPrimitiveType(source.kind());
            case NONE, VOID, PACKAGE, MODULE -> types.getNoType(source.kind());
            case NULL -> types.getNullType();
            case ARRAY -> types.getArrayType(type(((ProcessorDeclaration.Array) source.shape()).component(), variables));
            case DECLARED -> {
                var declared = (ProcessorDeclaration.Declared) source.shape();
                var symbol = descriptor.apply("L" + declared.binaryName().replace('.', '/') + ";").tsym;
                var arguments = List.from(declared.arguments().stream().map(t -> type(t, variables)).toList());
                yield new Type.ClassType(type(declared.enclosing(), variables), arguments, symbol);
            }
            case TYPEVAR -> {
                var key = new ProcessorDeclaration.Key(((ProcessorDeclaration.VariableReference) source.shape()).elementKey());
                yield variables.containsKey(key) ? variables.get(key) : of(declarations.variable(key));
            }
            case WILDCARD -> {
                var wildcard = (ProcessorDeclaration.Wildcard) source.shape();
                yield types.getWildcardType(wildcard.extendsBound() == null ? null : type(wildcard.extendsBound(), variables),
                        wildcard.superBound() == null ? null : type(wildcard.superBound(), variables));
            }
            case INTERSECTION -> compilerTypes.makeIntersectionType(List.from(((ProcessorDeclaration.Intersection) source.shape()).bounds().stream().map(t -> type(t, variables)).toList()));
            default -> throw new IllegalStateException("Unsupported source declaration type: " + source.kind());
        };
        if (!source.annotations().isEmpty()) result = result.annotatedType(annotations.apply(source.annotations()));
        if (source.kind() == TypeKind.DECLARED) detached.put(result, true);
        return result;
    }

    /** Install hierarchy fields only on detached type instances, substituting source formals for the actual arguments. */
    private Type hierarchy(Type type) {
        if (!(type instanceof Type.ClassType declared) || type.getKind() != TypeKind.DECLARED) return type;
        var existing = hierarchies.get(type);
        if (existing != null) return existing;
        var source = declarations.declaration(declared.asElement());
        if (source == null) return type;
        var data = (ProcessorDeclaration.TypeDeclaration) source.detail();
        var formal = of(declared.asElement());
        // Native factories may return a cached erasure or symbol type. Copy those before setting any hierarchy fields.
        boolean strip = declared.stripMetadataIfNeeded() != declared;
        var view = detached.containsKey(type) ? declared
                : new Type.ClassType(declared.getEnclosingType(), declared.getTypeArguments(), declared.tsym, declared.getMetadata()) {
                    @Override protected boolean needsStripping() { return strip; }
                };
        hierarchies.put(type, view); hierarchies.put(view, view);
        view.supertype_field = hierarchy(type == formal ? type(data.superclass()) : substitute(type(data.superclass()), formal, view));
        view.interfaces_field = list(data.interfaces()).map(t -> hierarchy(type == formal ? t : substitute(t, formal, view)));
        return view;
    }

    private Type substitute(Type value, Type formal, Type actual) {
        return actual.isRaw() ? compilerTypes.erasure(value) : compilerTypes.subst(value, formal.allparams(), actual.allparams());
    }

    java.util.List<? extends TypeMirror> directSupertypes(Type type) {
        return types.directSupertypes(hierarchy(type));
    }

    Type member(Type site, Element element) {
        // Preserve native validation before adapting the source member's type.
        types.asMemberOf((javax.lang.model.type.DeclaredType) site, element);
        var source = declarations.declaration(element);
        if (source == null) return (Type) types.asMemberOf((javax.lang.model.type.DeclaredType) site, element);
        var symbol = (Symbol) element;
        var result = of(element);
        if ((symbol.flags() & com.sun.tools.javac.code.Flags.STATIC) != 0) return result;
        var base = compilerTypes.asOuterSuper(hierarchy(site), symbol.owner);
        return base == null ? result : substitute(result, of(symbol.owner), base);
    }
}

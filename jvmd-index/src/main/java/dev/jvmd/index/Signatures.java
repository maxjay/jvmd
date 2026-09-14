package dev.jvmd.index;
import java.lang.classfile.*;
import java.lang.constant.ClassDesc;
import java.util.*;
import java.util.stream.Collectors;
/** Implements 4.4: readable generic signatures using the standard class-file signature model. */
public final class Signatures {
    private Signatures() { }
    public static String type(Signature signature) {
        return switch (signature) {
            case Signature.BaseTypeSig b -> ClassDesc.ofDescriptor(String.valueOf(b.baseType())).displayName();
            case Signature.ArrayTypeSig a -> type(a.componentSignature()) + "[]";
            case Signature.TypeVarSig v -> v.identifier();
            case Signature.ClassTypeSig c -> {
                String name = c.className().replace('/', '.').replace('$', '.');
                if (c.outerType().isPresent()) name = type(c.outerType().get()) + "." + name;
                yield name + (c.typeArgs().isEmpty() ? "" : c.typeArgs().stream().map(Signatures::argument).collect(Collectors.joining(", ", "<", ">")));
            }
        };
    }
    private static String argument(Signature.TypeArg arg) {
        return switch (arg) {
            case Signature.TypeArg.Unbounded _ -> "?";
            case Signature.TypeArg.Bounded b -> switch (b.wildcardIndicator()) {
                case NONE -> type(b.boundType()); case EXTENDS -> "? extends " + type(b.boundType()); case SUPER -> "? super " + type(b.boundType());
            };
        };
    }
    public static String parameters(List<Signature.TypeParam> parameters) {
        if (parameters.isEmpty()) return "";
        return parameters.stream().map(p -> {
            var bounds = new ArrayList<String>(); p.classBound().ifPresent(b -> { if (!type(b).equals("java.lang.Object")) bounds.add(type(b)); });
            p.interfaceBounds().forEach(b -> bounds.add(type(b)));
            return p.identifier() + (bounds.isEmpty() ? "" : " extends " + String.join(" & ", bounds));
        }).collect(Collectors.joining(", ", "<", ">"));
    }
    public static String qualified(ClassDesc type) { return type(Signature.of(type)); }
    public static Set<String> referenced(Signature signature) {
        var names = new LinkedHashSet<String>();
        switch (signature) {
            case Signature.ClassTypeSig c -> { names.add(qualified(c.classDesc())); for (var arg:c.typeArgs()) if(arg instanceof Signature.TypeArg.Bounded b) names.addAll(referenced(b.boundType())); }
            case Signature.ArrayTypeSig a -> names.addAll(referenced(a.componentSignature()));
            default -> { }
        }
        return names;
    }
}

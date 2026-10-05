package dev.jvmd.index.layer.machine;

import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.constant.ClassDesc;
import java.util.List;
import java.util.function.Consumer;

/**
 * The {@code E} edges of a declaration (stage 1, A.5): their kinds, and the type names a descriptor or generic signature mentions.
 * A class file and a source file are read by different front ends but name types through the same strings, so the rules are here once.
 */
public final class Edges {
    private Edges() { }

    public static final int EXTENDS = 1, IMPLEMENTS = 2, PERMITS = 3, ENCLOSES = 4, FIELD_TYPE = 5, PARAM_TYPE = 6, RETURN_TYPE = 7,
            THROWS = 8, ANNOTATION = 9, RECORD_COMPONENT_TYPE = 10;

    /** Receives one edge's target and kind. */
    public interface Sink { void edge(String target, int kind); }

    /** The edges of a field or record component of the given kind: its descriptor and, if present, its generic signature. */
    public static void typeNames(String descriptor, String signature, Consumer<String> out) {
        descriptorNames(descriptor, out);
        if (signature == null) return;
        try { signatureNames(Signature.parseFrom(signature), out); } catch (RuntimeException malformed) { /* a signature javac cannot read names nothing */ }
    }

    /** The edges of a method: parameter, return and thrown types from its descriptor, then those only its generic signature mentions. */
    public static void method(String descriptor, String signature, List<String> thrown, Sink out) {
        int close = descriptor.indexOf(')');
        descriptorNames(descriptor.substring(1, close), n -> out.edge(n, PARAM_TYPE));
        descriptorNames(descriptor.substring(close + 1), n -> out.edge(n, RETURN_TYPE));
        for (var t : thrown) out.edge(t, THROWS);
        if (signature == null) return;
        try {
            var sig = MethodSignature.parseFrom(signature);
            for (var tp : sig.typeParameters()) typeParamNames(tp, n -> out.edge(n, PARAM_TYPE));
            for (var a : sig.arguments()) signatureNames(a, n -> out.edge(n, PARAM_TYPE));
            signatureNames(sig.result(), n -> out.edge(n, RETURN_TYPE));
            for (var t : sig.throwableSignatures()) signatureNames(t, n -> out.edge(n, THROWS));
        } catch (RuntimeException malformed) {
            // A generic signature javac cannot read contributes no edges; the signature string itself is still in res.
        }
    }

    /** Class names in a plain descriptor: every {@code L...;}. Primitive letters never include 'L'. */
    public static void descriptorNames(String descriptor, Consumer<String> out) {
        for (int i = 0; i < descriptor.length(); i++) {
            if (descriptor.charAt(i) == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) return;
                out.accept(descriptor.substring(i + 1, end));
                i = end;
            }
        }
    }

    private static String internalName(ClassDesc desc) {
        String d = desc.descriptorString();
        return d.substring(1, d.length() - 1);
    }

    private static void signatureNames(Signature s, Consumer<String> out) {
        switch (s) {
            case Signature.ClassTypeSig c -> {
                c.outerType().ifPresent(o -> signatureNames(o, out));
                out.accept(internalName(c.classDesc()));
                for (var arg : c.typeArgs()) if (arg instanceof Signature.TypeArg.Bounded b) signatureNames(b.boundType(), out);
            }
            case Signature.ArrayTypeSig a -> signatureNames(a.componentSignature(), out);
            default -> { }
        }
    }

    private static void typeParamNames(Signature.TypeParam tp, Consumer<String> out) {
        tp.classBound().ifPresent(b -> signatureNames(b, out));
        for (var b : tp.interfaceBounds()) signatureNames(b, out);
    }
}

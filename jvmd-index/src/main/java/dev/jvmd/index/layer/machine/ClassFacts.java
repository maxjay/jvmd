package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Entry;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.MethodSignature;
import java.lang.classfile.Signature;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ClassDesc;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Φ (stage 1, 2.2 and appendix A): the facts and edges of one class file, a pure function of the class bytes. Every key inside
 * is content, never a position or a constant-pool index: annotations and constants are serialized structurally.
 */
public record ClassFacts(String ownerKey, List<Fact> facts, List<Entry> edges) {
    /** A class entry that cannot be used: recorded in the leaf's faults and skipped (A.7). Never aborts the boot. */
    public static final class Fault extends Exception {
        public Fault(String message, Throwable cause) { super(message, cause); }
        public Fault(String message) { super(message); }
    }

    static final int KIND_TYPE = 0, KIND_FIELD = 1, KIND_METHOD = 2;
    static final int EXTENDS = 1, IMPLEMENTS = 2, PERMITS = 3, ENCLOSES = 4, FIELD_TYPE = 5, PARAM_TYPE = 6, RETURN_TYPE = 7,
            THROWS = 8, ANNOTATION = 9, RECORD_COMPONENT_TYPE = 10;
    private static final int TYPE_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
            | ClassFile.ACC_ANNOTATION | ClassFile.ACC_ENUM | ClassFile.ACC_MODULE;
    private static final int INNER_ACCESS = ClassFile.ACC_STATIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED;
    private static final int FIELD_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
            | ClassFile.ACC_VOLATILE | ClassFile.ACC_TRANSIENT | ClassFile.ACC_ENUM;
    // ACC_SYNCHRONIZED, ACC_NATIVE and ACC_STRICT are dropped: they do not change how a call site compiles (A.4).
    private static final int METHOD_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
            | ClassFile.ACC_ABSTRACT | ClassFile.ACC_VARARGS;
    private static final List<String> META_ANNOTATIONS = List.of("Retention", "Target", "Repeatable", "Inherited", "Documented");

    /** The {@code N} entries of this class: its facts keyed by simple name. */
    public List<Entry> byName() {
        var out = new ArrayList<Entry>(facts.size());
        for (var f : facts) out.add(f.byName());
        return out;
    }

    /**
     * @param expectedInternalName the owner the entry name promises ({@code this_class + ".class"} after removing any versions
     *                             prefix); a class file that says otherwise is a fault
     */
    public static ClassFacts of(Digest digest, byte[] classBytes, String expectedInternalName) throws Fault {
        try {
            return new Extractor(digest, classBytes, expectedInternalName).extract();
        } catch (Fault fault) {
            throw fault;
        } catch (RuntimeException | StackOverflowError | OutOfMemoryError bad) {
            // Malformed constant pool, truncated attributes, bad magic: all surface from the parser as unchecked exceptions.
            if (bad instanceof OutOfMemoryError oom) throw oom;
            throw new Fault("Unreadable class file: " + bad.getMessage(), bad);
        }
    }

    /**
     * C.4: a local or anonymous class has no source-level API and cannot be produced from a source file without attributing
     * bodies, so it is excluded on both sides. Local classes carry {@code EnclosingMethod}; an anonymous class's own
     * {@code InnerClasses} entry has neither an outer class nor a simple name. A class with no facts is skipped by the caller.
     */
    static boolean isLocalOrAnonymous(ClassModel cm, String owner) {
        if (cm.findAttribute(Attributes.enclosingMethod()).isPresent()) return true;
        var inner = cm.findAttribute(Attributes.innerClasses());
        if (inner.isPresent()) for (var info : inner.get().classes())
            if (info.innerClass().asInternalName().equals(owner) && info.outerClass().isEmpty() && info.innerName().isEmpty()) return true;
        return false;
    }

    // ---- keys ------------------------------------------------------------------------------------------------------------

    static byte[] typeKey(String internalName) { return new Codec.Writer(internalName.length() + 2).zstr(internalName).u8(KIND_TYPE).toBytes(); }

    static byte[] memberKey(String internalName, int kind, String name, String descriptor) {
        return new Codec.Writer(internalName.length() + name.length() + descriptor.length() + 6)
                .zstr(internalName).u8(kind).zstr(name).zstr(descriptor).toBytes();
    }

    /** {@code key(edge) = zstr target || u8 kind || m(source)}. */
    static byte[] edgeKey(String target, int kind, byte[] source) {
        return new Codec.Writer(target.length() + source.length + 2).zstr(target).u8(kind).raw(source).toBytes();
    }

    /** The text after the last '/' and the last '$' (A.6). */
    static String simpleName(String internalName) {
        int cut = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
        return internalName.substring(cut + 1);
    }

    // ---- extraction ------------------------------------------------------------------------------------------------------

    private static final class Extractor {
        final Digest digest;
        final byte[] bytes;
        final String expected;
        final List<Fact> facts = new ArrayList<>();
        final TreeMap<byte[], Entry> edges = new TreeMap<>(Arrays::compareUnsigned);

        Extractor(Digest digest, byte[] bytes, String expected) { this.digest = digest; this.bytes = bytes; this.expected = expected; }

        ClassFacts extract() throws Fault {
            var cm = ClassFile.of().parse(bytes);
            if (cm.majorVersion() > ClassFile.latestMajorVersion())
                throw new Fault("Unsupported class file major version " + cm.majorVersion());
            String owner = cm.thisClass().asInternalName();
            if (!owner.equals(expected)) throw new Fault("Entry name does not match this_class: " + owner);

            if (isLocalOrAnonymous(cm, owner)) return new ClassFacts(owner, List.of(), List.of());

            typeFact(cm, owner);
            for (var field : cm.fields()) {
                int flags = field.flags().flagsMask();
                if ((flags & (ClassFile.ACC_PRIVATE | ClassFile.ACC_SYNTHETIC)) != 0) continue;
                String name = field.fieldName().stringValue(), desc = field.fieldType().stringValue();
                var res = new Codec.Writer();
                res.u16(flags & FIELD_ACCESS);
                var signature = signature(field);
                res.optStr(signature);
                var constant = field.findAttribute(Attributes.constantValue());
                if (constant.isPresent()) {
                    res.u8(1);
                    var entry = constant.get().constant();
                    res.u8(entry.tag());
                    switch (entry) {
                        case java.lang.classfile.constantpool.IntegerEntry i -> res.u32(i.intValue() & 0xFFFFFFFFL);
                        case java.lang.classfile.constantpool.FloatEntry f -> res.u32(Float.floatToRawIntBits(f.floatValue()) & 0xFFFFFFFFL);
                        case java.lang.classfile.constantpool.LongEntry l -> res.u64(l.longValue());
                        case java.lang.classfile.constantpool.DoubleEntry d -> res.u64(Double.doubleToRawLongBits(d.doubleValue()));
                        case java.lang.classfile.constantpool.StringEntry s -> res.str(s.stringValue());
                        default -> throw new IllegalArgumentException("Unexpected ConstantValue tag " + entry.tag());
                    }
                } else res.u8(0);
                var key = memberKey(owner, KIND_FIELD, name, desc);
                add(key, name, res, tail(field, false));
                typeNames(desc, signature, n -> edge(n, FIELD_TYPE, key));
                annotationEdges(field, key);
            }
            for (var method : cm.methods()) {
                int flags = method.flags().flagsMask();
                String name = method.methodName().stringValue(), desc = method.methodType().stringValue();
                if ((flags & (ClassFile.ACC_PRIVATE | ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0 || name.equals("<clinit>")) continue;
                var res = new Codec.Writer();
                res.u16(flags & METHOD_ACCESS);
                var signature = signature(method);
                res.optStr(signature);
                var exceptions = method.findAttribute(Attributes.exceptions());
                var thrown = exceptions.isPresent() ? exceptions.get().exceptions() : List.<java.lang.classfile.constantpool.ClassEntry>of();
                res.u32(thrown.size());
                for (var t : thrown) res.str(t.asInternalName());
                var def = method.findAttribute(Attributes.annotationDefault());
                if (def.isPresent()) { res.u8(1); writeValue(res, def.get().defaultValue()); } else res.u8(0); // opt<value>, structural (A.4a)
                var key = memberKey(owner, KIND_METHOD, name, desc);
                add(key, name, res, tail(method, true));
                methodEdges(key, desc, signature, thrown);
                annotationEdges(method, key);
            }
            facts.sort((a, b) -> Arrays.compareUnsigned(a.m(), b.m()));
            return new ClassFacts(owner, List.copyOf(facts), List.copyOf(edges.values()));
        }

        void typeFact(ClassModel cm, String owner) {
            int flags = cm.flags().flagsMask();
            boolean module = (flags & ClassFile.ACC_MODULE) != 0;
            var record = cm.findAttribute(Attributes.record());
            int kind = module ? 5 : (flags & ClassFile.ACC_ANNOTATION) != 0 ? 4 : (flags & ClassFile.ACC_INTERFACE) != 0 ? 1
                    : (flags & ClassFile.ACC_ENUM) != 0 ? 2 : record.isPresent() ? 3 : 0;
            int access = flags & TYPE_ACCESS;
            InnerClassInfo self = null;
            var inner = cm.findAttribute(Attributes.innerClasses());
            if (inner.isPresent()) for (var info : inner.get().classes()) if (info.innerClass().asInternalName().equals(owner)) self = info;
            // For a nested class the consumer sees its own inner flags, not the top-level ones (A.4).
            if (self != null) access |= self.flagsMask() & INNER_ACCESS;

            var key = typeKey(owner);
            var res = new Codec.Writer();
            res.u8(kind).u16(access);
            var signature = signature(cm);
            res.optStr(signature);
            var superclass = cm.superclass();
            res.optStr(superclass.map(c -> c.asInternalName()).orElse(null));
            res.u32(cm.interfaces().size());
            for (var i : cm.interfaces()) res.str(i.asInternalName());
            var permitted = cm.findAttribute(Attributes.permittedSubclasses());
            var permits = permitted.isPresent() ? permitted.get().permittedSubclasses() : List.<java.lang.classfile.constantpool.ClassEntry>of();
            res.u32(permits.size());
            for (var p : permits) res.str(p.asInternalName());
            var host = cm.findAttribute(Attributes.nestHost());
            res.optStr(host.map(h -> h.nestHost().asInternalName()).orElse(null));
            // The outer class of this class's own InnerClasses entry: javac resolves Outer.Inner through it, so it is a resolution
            // field, and it is what makes the encloses edge a function of res, so that L is a function of k (A.4, A.5).
            String outer = self != null && self.outerClass().isPresent() ? self.outerClass().get().asInternalName() : null;
            res.optStr(outer);
            var components = record.isPresent() ? record.get().components() : List.<java.lang.classfile.attribute.RecordComponentInfo>of();
            res.u32(components.size());
            for (var rc : components) {
                String csig = signature(rc);
                res.str(rc.name().stringValue()).str(rc.descriptor().stringValue()).optStr(csig);
                typeNames(rc.descriptor().stringValue(), csig, n -> edge(n, RECORD_COMPONENT_TYPE, key));
            }
            if (kind == 4) {
                // opt<annotation> per meta-annotation, in the fixed order, with the structural encoding of A.4a.
                var visible = cm.findAttribute(Attributes.runtimeVisibleAnnotations());
                for (var meta : META_ANNOTATIONS) {
                    String descriptor = "Ljava/lang/annotation/" + meta + ";";
                    Annotation found = null;
                    if (visible.isPresent()) for (var a : visible.get().annotations())
                        if (a.className().stringValue().equals(descriptor)) found = a;
                    if (found == null) res.u8(0); else { res.u8(1); writeAnnotation(res, found); }
                }
            }
            if (kind == 5) {
                var module0 = cm.findAttribute(Attributes.module());
                if (module0.isPresent()) moduleBytes(res, module0.get());
            }
            add(key, simpleName(owner), res, tail(cm, false));

            superclass.ifPresent(s -> edge(s.asInternalName(), EXTENDS, key));
            for (var i : cm.interfaces()) edge(i.asInternalName(), IMPLEMENTS, key);
            for (var p : permits) edge(p.asInternalName(), PERMITS, key);
            if (outer != null) edge(outer, ENCLOSES, key);
            annotationEdges(cm, key);
        }

        void moduleBytes(Codec.Writer res, ModuleAttribute m) {
            res.str(m.moduleName().name().stringValue()).u16(m.moduleFlagsMask()).optStr(m.moduleVersion().map(v -> v.stringValue()).orElse(null));
            res.u32(m.requires().size());
            for (var r : m.requires())
                res.str(r.requires().name().stringValue()).u16(r.requiresFlagsMask()).optStr(r.requiresVersion().map(v -> v.stringValue()).orElse(null));
            res.u32(m.exports().size());
            for (var x : m.exports()) {
                res.str(x.exportedPackage().name().stringValue()).u16(x.exportsFlagsMask()).u32(x.exportsTo().size());
                for (var to : x.exportsTo()) res.str(to.name().stringValue());
            }
            res.u32(m.opens().size());
            for (var o : m.opens()) {
                res.str(o.openedPackage().name().stringValue()).u16(o.opensFlagsMask()).u32(o.opensTo().size());
                for (var to : o.opensTo()) res.str(to.name().stringValue());
            }
            res.u32(m.uses().size());
            for (var u : m.uses()) res.str(u.asInternalName());
            res.u32(m.provides().size());
            for (var p : m.provides()) {
                res.str(p.provides().asInternalName()).u32(p.providesWith().size());
                for (var w : p.providesWith()) res.str(w.asInternalName());
            }
        }

        void methodEdges(byte[] key, String desc, String signature, List<java.lang.classfile.constantpool.ClassEntry> thrown) {
            int close = desc.indexOf(')');
            descriptorNames(desc.substring(1, close), n -> edge(n, PARAM_TYPE, key));
            descriptorNames(desc.substring(close + 1), n -> edge(n, RETURN_TYPE, key));
            for (var t : thrown) edge(t.asInternalName(), THROWS, key);
            if (signature == null) return;
            try {
                var sig = MethodSignature.parseFrom(signature);
                for (var tp : sig.typeParameters()) typeParamNames(tp, n -> edge(n, PARAM_TYPE, key));
                for (var a : sig.arguments()) signatureNames(a, n -> edge(n, PARAM_TYPE, key));
                signatureNames(sig.result(), n -> edge(n, RETURN_TYPE, key));
                for (var t : sig.throwableSignatures()) signatureNames(t, n -> edge(n, THROWS, key));
            } catch (RuntimeException malformed) {
                // A generic signature javac cannot read contributes no edges; the signature string itself is still in res.
            }
        }

        /** Names in a field or record-component descriptor and, if present, its generic signature. */
        void typeNames(String descriptor, String signature, Consumer<String> out) {
            descriptorNames(descriptor, out);
            if (signature == null) return;
            try { signatureNames(Signature.parseFrom(signature), out); } catch (RuntimeException malformed) { /* see methodEdges */ }
        }

        void annotationEdges(AttributedElement element, byte[] key) {
            var visible = element.findAttribute(Attributes.runtimeVisibleAnnotations());
            if (visible.isPresent()) for (var a : visible.get().annotations()) descriptorNames(a.className().stringValue(), n -> edge(n, ANNOTATION, key));
            var invisible = element.findAttribute(Attributes.runtimeInvisibleAnnotations());
            if (invisible.isPresent()) for (var a : invisible.get().annotations()) descriptorNames(a.className().stringValue(), n -> edge(n, ANNOTATION, key));
            var typeVisible = element.findAttribute(Attributes.runtimeVisibleTypeAnnotations());
            if (typeVisible.isPresent()) for (var t : typeVisible.get().annotations()) descriptorNames(t.annotation().className().stringValue(), n -> edge(n, ANNOTATION, key));
            var typeInvisible = element.findAttribute(Attributes.runtimeInvisibleTypeAnnotations());
            if (typeInvisible.isPresent()) for (var t : typeInvisible.get().annotations()) descriptorNames(t.annotation().className().stringValue(), n -> edge(n, ANNOTATION, key));
        }

        void edge(String target, int kind, byte[] source) {
            var key = edgeKey(target, kind, source);
            edges.computeIfAbsent(key, k -> new Entry(k, Entry.NONE, digest.hash(k)));
        }

        /** Adds one fact: {@code e = u32 resLen || res || tail}, {@code h = Digest(res)}. */
        void add(byte[] key, String simpleName, Codec.Writer res, byte[] tail) {
            var resBytes = res.toBytes();
            var e = new Codec.Writer(resBytes.length + tail.length + 4).u32(resBytes.length).raw(resBytes).raw(tail).toBytes();
            facts.add(new Fact(key, e, digest.hash(resBytes), simpleName));
        }
    }

    // ---- shared helpers --------------------------------------------------------------------------------------------------

    private static String signature(AttributedElement element) {
        return element.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue()).orElse(null);
    }

    /** Class names in a plain descriptor: every {@code L...;}. Primitive letters never include 'L'. */
    static void descriptorNames(String descriptor, Consumer<String> out) {
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

    /** Everything else stage 1 keeps (A.4): annotations, type annotations, deprecation, and for methods parameter names. */
    private static byte[] tail(AttributedElement element, boolean method) {
        var out = new Codec.Writer();
        var visible = element.findAttribute(Attributes.runtimeVisibleAnnotations());
        var invisible = element.findAttribute(Attributes.runtimeInvisibleAnnotations());
        writeAnnotations(out, visible.isPresent() ? visible.get().annotations() : List.of());
        writeAnnotations(out, invisible.isPresent() ? invisible.get().annotations() : List.of());
        var typeVisible = element.findAttribute(Attributes.runtimeVisibleTypeAnnotations());
        var typeInvisible = element.findAttribute(Attributes.runtimeInvisibleTypeAnnotations());
        var types = new ArrayList<TypeAnnotation>();
        if (typeVisible.isPresent()) types.addAll(typeVisible.get().annotations());
        if (typeInvisible.isPresent()) types.addAll(typeInvisible.get().annotations());
        out.u32(types.size());
        for (var t : types) writeTypeAnnotation(out, t);
        out.u8(element.findAttribute(Attributes.deprecated()).isPresent() ? 1 : 0);
        if (method) {
            var params = element.findAttribute(Attributes.methodParameters());
            var list = params.isPresent() ? params.get().parameters() : List.<java.lang.classfile.attribute.MethodParameterInfo>of();
            out.u32(list.size());
            for (var p : list) out.optStr(p.name().map(n -> n.stringValue()).orElse(null)).u16(p.flagsMask());
        }
        return out.toBytes();
    }

    /** {@code list<annotation>}: a u32 count, then the annotations inline (A.4a). They are self-delimiting, so no per-item length. */
    private static void writeAnnotations(Codec.Writer out, List<Annotation> annotations) {
        out.u32(annotations.size());
        for (var a : annotations) writeAnnotation(out, a);
    }

    /** {@code annotation = str typeDescriptor || u16 elementCount || (str name || value)[elementCount]} (A.4a). */
    private static void writeAnnotation(Codec.Writer out, Annotation a) {
        out.str(a.className().stringValue()).u16(a.elements().size());
        for (var element : a.elements()) {
            out.str(element.name().stringValue());
            writeValue(out, element.value());
        }
    }

    /** {@code value = u8 tag || payload} (A.4a). Every name and constant is resolved: no constant-pool index is ever stored. */
    private static void writeValue(Codec.Writer out, AnnotationValue value) {
        out.u8(value.tag());
        switch (value) {
            case AnnotationValue.OfString s -> out.str(s.stringValue());
            case AnnotationValue.OfBoolean z -> out.u32(z.booleanValue() ? 1 : 0);
            case AnnotationValue.OfByte b -> out.u32(b.byteValue() & 0xFFFFFFFFL);
            case AnnotationValue.OfChar c -> out.u32(c.charValue());
            case AnnotationValue.OfShort s -> out.u32(s.shortValue() & 0xFFFFFFFFL);
            case AnnotationValue.OfInt i -> out.u32(i.intValue() & 0xFFFFFFFFL);
            case AnnotationValue.OfLong l -> out.u64(l.longValue());
            case AnnotationValue.OfFloat f -> out.u32(Float.floatToRawIntBits(f.floatValue()) & 0xFFFFFFFFL);
            case AnnotationValue.OfDouble d -> out.u64(Double.doubleToRawLongBits(d.doubleValue()));
            case AnnotationValue.OfEnum e -> out.str(e.className().stringValue()).str(e.constantName().stringValue());
            case AnnotationValue.OfClass c -> out.str(c.className().stringValue());
            case AnnotationValue.OfAnnotation a -> writeAnnotation(out, a.annotation());
            case AnnotationValue.OfArray a -> {
                out.u16(a.values().size());
                for (var v : a.values()) writeValue(out, v);
            }
        }
    }

    /** {@code typeAnnotation = u8 targetType || targetInfo || u8 pathLength || (u8 kind || u8 argumentIndex)[] || annotation} (A.4a). */
    private static void writeTypeAnnotation(Codec.Writer out, TypeAnnotation t) {
        var info = t.targetInfo();
        out.u8(info.targetType().targetTypeValue());
        switch (info) {
            case TypeAnnotation.TypeParameterTarget p -> out.u8(p.typeParameterIndex());
            case TypeAnnotation.SupertypeTarget s -> out.u16(s.supertypeIndex());
            case TypeAnnotation.TypeParameterBoundTarget b -> out.u8(b.typeParameterIndex()).u8(b.boundIndex());
            case TypeAnnotation.FormalParameterTarget f -> out.u8(f.formalParameterIndex());
            case TypeAnnotation.ThrowsTarget th -> out.u16(th.throwsTargetIndex());
            default -> { }
        }
        out.u8(t.targetPath().size());
        for (var c : t.targetPath()) out.u8(c.typePathKind().tag()).u8(c.typeArgumentIndex());
        writeAnnotation(out, t.annotation());
    }
}

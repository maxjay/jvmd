package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.Entry;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attributes;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.TypeAnnotation;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.ModuleAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import static dev.jvmd.index.layer.machine.Edges.ANNOTATION;
import static dev.jvmd.index.layer.machine.Edges.ENCLOSES;
import static dev.jvmd.index.layer.machine.Edges.EXTENDS;
import static dev.jvmd.index.layer.machine.Edges.FIELD_TYPE;
import static dev.jvmd.index.layer.machine.Edges.IMPLEMENTS;
import static dev.jvmd.index.layer.machine.Edges.PERMITS;
import static dev.jvmd.index.layer.machine.Edges.RECORD_COMPONENT_TYPE;
import static dev.jvmd.index.layer.machine.Edges.descriptorNames;
import static dev.jvmd.index.layer.machine.Edges.typeNames;

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

    private static final int TYPE_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
            | ClassFile.ACC_ANNOTATION | ClassFile.ACC_ENUM | ClassFile.ACC_MODULE;
    private static final int INNER_ACCESS = ClassFile.ACC_STATIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED;
    private static final int FIELD_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
            | ClassFile.ACC_VOLATILE | ClassFile.ACC_TRANSIENT | ClassFile.ACC_ENUM;
    // ACC_SYNCHRONIZED, ACC_NATIVE and ACC_STRICT are dropped: they do not change how a call site compiles (A.4).
    private static final int METHOD_ACCESS = ClassFile.ACC_PUBLIC | ClassFile.ACC_PROTECTED | ClassFile.ACC_STATIC | ClassFile.ACC_FINAL
            | ClassFile.ACC_ABSTRACT | ClassFile.ACC_VARARGS;

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
                var signature = signature(field);
                var constant = field.findAttribute(Attributes.constantValue());
                Res.Constant value = null;
                if (constant.isPresent()) {
                    var entry = constant.get().constant();
                    value = switch (entry) {
                        case java.lang.classfile.constantpool.IntegerEntry i -> new Res.Constant(entry.tag(), i.intValue(), null);
                        case java.lang.classfile.constantpool.FloatEntry f -> new Res.Constant(entry.tag(), Float.floatToRawIntBits(f.floatValue()), null);
                        case java.lang.classfile.constantpool.LongEntry l -> new Res.Constant(entry.tag(), l.longValue(), null);
                        case java.lang.classfile.constantpool.DoubleEntry d -> new Res.Constant(entry.tag(), Double.doubleToRawLongBits(d.doubleValue()), null);
                        case java.lang.classfile.constantpool.StringEntry s -> new Res.Constant(entry.tag(), 0, s.stringValue());
                        default -> throw new IllegalArgumentException("Unexpected ConstantValue tag " + entry.tag());
                    };
                }
                var key = Keys.memberKey(owner, Keys.FIELD, name, desc);
                add(key, name, new Res.Field(flags & FIELD_ACCESS, signature, value, warnings(field)).encode(), tail(field, false));
                typeNames(desc, signature, n -> edge(n, FIELD_TYPE, key));
            }
            for (var method : cm.methods()) {
                int flags = method.flags().flagsMask();
                String name = method.methodName().stringValue(), desc = method.methodType().stringValue();
                if ((flags & (ClassFile.ACC_PRIVATE | ClassFile.ACC_SYNTHETIC | ClassFile.ACC_BRIDGE)) != 0 || name.equals("<clinit>")) continue;
                var signature = signature(method);
                var exceptions = method.findAttribute(Attributes.exceptions());
                var thrown = exceptions.isPresent() ? exceptions.get().exceptions() : List.<java.lang.classfile.constantpool.ClassEntry>of();
                var thrownNames = new ArrayList<String>(thrown.size());
                for (var t : thrown) thrownNames.add(t.asInternalName());
                var def = method.findAttribute(Attributes.annotationDefault());
                var key = Keys.memberKey(owner, Keys.METHOD, name, desc);
                add(key, name, new Res.Method(flags & METHOD_ACCESS, signature, thrownNames, def.isPresent() ? value(def.get().defaultValue()) : null, warnings(method)).encode(), tail(method, true));
                Edges.method(desc, signature, thrownNames, (target, kind) -> edge(target, kind, key));
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

            var key = Keys.typeKey(owner);
            var signature = signature(cm);
            var superclass = cm.superclass();
            var interfaces = new ArrayList<String>();
            for (var i : cm.interfaces()) interfaces.add(i.asInternalName());
            var permitted = cm.findAttribute(Attributes.permittedSubclasses());
            var permits = permitted.isPresent() ? permitted.get().permittedSubclasses() : List.<java.lang.classfile.constantpool.ClassEntry>of();
            var permitNames = new ArrayList<String>();
            for (var p : permits) permitNames.add(p.asInternalName());
            var host = cm.findAttribute(Attributes.nestHost());
            // The outer class of this class's own InnerClasses entry: javac resolves Outer.Inner through it, so it is a resolution
            // field, and it is what makes the encloses edge a function of res, so that L is a function of k (A.4, A.5).
            String outer = self != null && self.outerClass().isPresent() ? self.outerClass().get().asInternalName() : null;
            var recordComponents = record.isPresent() ? record.get().components() : List.<java.lang.classfile.attribute.RecordComponentInfo>of();
            var components = new ArrayList<Res.Component>();
            for (var rc : recordComponents) {
                String csig = signature(rc);
                components.add(new Res.Component(rc.name().stringValue(), rc.descriptor().stringValue(), csig));
                typeNames(rc.descriptor().stringValue(), csig, n -> edge(n, RECORD_COMPONENT_TYPE, key));
            }
            var metas = new ArrayList<Ann>();
            if (kind == Res.Type.ANNOTATION) {
                // One slot per meta-annotation, in the fixed order.
                var visible = cm.findAttribute(Attributes.runtimeVisibleAnnotations());
                for (var meta : Res.META_ANNOTATIONS) {
                    String descriptor = "Ljava/lang/annotation/" + meta + ";";
                    Annotation found = null;
                    if (visible.isPresent()) for (var a : visible.get().annotations())
                        if (a.className().stringValue().equals(descriptor)) found = a;
                    metas.add(found == null ? null : annotation(found));
                }
            }
            Res.Module moduleRes = null;
            if (kind == Res.Type.MODULE) {
                var module0 = cm.findAttribute(Attributes.module());
                if (module0.isPresent()) moduleRes = module(module0.get());
            }
            var res = new Res.Type(kind, access, signature, superclass.map(c -> c.asInternalName()).orElse(null), interfaces, permitNames,
                    host.map(h -> h.nestHost().asInternalName()).orElse(null), outer, self == null ? null : self.innerName().map(n -> n.stringValue()).orElse(null), components, metas, moduleRes, warnings(cm));
            add(key, res.innerName() == null ? Keys.topLevelName(owner) : res.innerName(), res.encode(), tail(cm, false));

            superclass.ifPresent(s -> edge(s.asInternalName(), EXTENDS, key));
            for (var i : cm.interfaces()) edge(i.asInternalName(), IMPLEMENTS, key);
            for (var p : permits) edge(p.asInternalName(), PERMITS, key);
            if (outer != null) edge(outer, ENCLOSES, key);
        }

        Res.Module module(ModuleAttribute m) {
            var requires = new ArrayList<Res.Requires>();
            for (var r : m.requires())
                requires.add(new Res.Requires(r.requires().name().stringValue(), r.requiresFlagsMask(), r.requiresVersion().map(v -> v.stringValue()).orElse(null)));
            var exports = new ArrayList<Res.Directive>();
            for (var x : m.exports()) {
                var to = new ArrayList<String>();
                for (var t : x.exportsTo()) to.add(t.name().stringValue());
                exports.add(new Res.Directive(x.exportedPackage().name().stringValue(), x.exportsFlagsMask(), to));
            }
            var opens = new ArrayList<Res.Directive>();
            for (var o : m.opens()) {
                var to = new ArrayList<String>();
                for (var t : o.opensTo()) to.add(t.name().stringValue());
                opens.add(new Res.Directive(o.openedPackage().name().stringValue(), o.opensFlagsMask(), to));
            }
            var uses = new ArrayList<String>();
            for (var u : m.uses()) uses.add(u.asInternalName());
            var provides = new ArrayList<Res.Provides>();
            for (var p : m.provides()) {
                var with = new ArrayList<String>();
                for (var w : p.providesWith()) with.add(w.asInternalName());
                provides.add(new Res.Provides(p.provides().asInternalName(), with));
            }
            return new Res.Module(m.moduleName().name().stringValue(), m.moduleFlagsMask(), m.moduleVersion().map(v -> v.stringValue()).orElse(null),
                    requires, exports, opens, uses, provides);
        }

        void edge(String target, int kind, byte[] source) {
            var key = Keys.edgeKey(target, kind, source);
            edges.computeIfAbsent(key, k -> new Entry(k, Entry.NONE, digest.hash(k)));
        }

        /** LAYOUT 4: the two projections are independent, and h binds the key. */
        void add(byte[] key, String simpleName, byte[] resBytes, byte[] tail) {
            facts.add(Fact.of(digest, key, resBytes, tail, simpleName));
            for (var descriptor : Ann.tailAnnotationTypes(tail)) descriptorNames(descriptor, n -> edge(n, ANNOTATION, key));
        }
    }

    // ---- shared helpers --------------------------------------------------------------------------------------------------

    private static String signature(AttributedElement element) {
        return element.findAttribute(Attributes.signature()).map(s -> s.signature().stringValue()).orElse(null);
    }

    private static Res.Warnings warnings(AttributedElement element) {
        Ann deprecated = null, safeVarargs = null;
        var annotations = new ArrayList<Annotation>();
        element.findAttribute(Attributes.runtimeVisibleAnnotations()).ifPresent(a -> annotations.addAll(a.annotations()));
        element.findAttribute(Attributes.runtimeInvisibleAnnotations()).ifPresent(a -> annotations.addAll(a.annotations()));
        for (var a : annotations) {
            if (a.className().stringValue().equals("Ljava/lang/Deprecated;")) deprecated = annotation(a);
            if (a.className().stringValue().equals("Ljava/lang/SafeVarargs;")) safeVarargs = annotation(a);
        }
        return Res.Warnings.of(element.findAttribute(Attributes.deprecated()).isPresent(), deprecated, safeVarargs != null);
    }

    /** Annotation metadata javac does not read during dependency resolution. */
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
        if (method) {
            var params = element.findAttribute(Attributes.methodParameters());
            var list = params.isPresent() ? params.get().parameters() : List.<java.lang.classfile.attribute.MethodParameterInfo>of();
            out.u32(list.size());
            for (var p : list) out.optStr(p.name().map(n -> n.stringValue()).orElse(null)).u16(p.flagsMask());
        }
        var bytes = out.toBytes();
        return Arrays.equals(bytes, new byte[bytes.length]) ? Entry.NONE : bytes;
    }

    private static void writeAnnotations(Codec.Writer out, List<Annotation> annotations) {
        var list = new ArrayList<Ann>(annotations.size());
        for (var a : annotations) if (!Res.Warnings.reads(a.className().stringValue())) list.add(annotation(a));
        Ann.encodeList(out, list);
    }

    private static Ann annotation(Annotation a) {
        var elements = new ArrayList<Ann.Element>(a.elements().size());
        for (var element : a.elements()) elements.add(new Ann.Element(element.name().stringValue(), value(element.value())));
        return new Ann(a.className().stringValue(), elements);
    }

    /** Every name and constant is resolved: no constant-pool index is ever stored. */
    private static Ann.Val value(AnnotationValue value) {
        int tag = value.tag();
        return switch (value) {
            case AnnotationValue.OfString s -> new Ann.Val.Str(s.stringValue());
            case AnnotationValue.OfBoolean z -> new Ann.Val.Prim(tag, z.booleanValue() ? 1 : 0);
            case AnnotationValue.OfByte b -> new Ann.Val.Prim(tag, b.byteValue());
            case AnnotationValue.OfChar c -> new Ann.Val.Prim(tag, c.charValue());
            case AnnotationValue.OfShort s -> new Ann.Val.Prim(tag, s.shortValue());
            case AnnotationValue.OfInt i -> new Ann.Val.Prim(tag, i.intValue());
            case AnnotationValue.OfLong l -> new Ann.Val.Prim(tag, l.longValue());
            case AnnotationValue.OfFloat f -> new Ann.Val.Prim(tag, Float.floatToRawIntBits(f.floatValue()));
            case AnnotationValue.OfDouble d -> new Ann.Val.Prim(tag, Double.doubleToRawLongBits(d.doubleValue()));
            case AnnotationValue.OfEnum e -> new Ann.Val.Enum(e.className().stringValue(), e.constantName().stringValue());
            case AnnotationValue.OfClass c -> new Ann.Val.Cls(c.className().stringValue());
            case AnnotationValue.OfAnnotation a -> new Ann.Val.Nested(annotation(a.annotation()));
            case AnnotationValue.OfArray a -> {
                var values = new ArrayList<Ann.Val>(a.values().size());
                for (var v : a.values()) values.add(value(v));
                yield new Ann.Val.Array(values);
            }
        };
    }

    /** {@code typeAnnotation = u8 targetType || targetInfo || u8 pathLength || (u8 kind || u8 argumentIndex)[] || annotation} (A.4a). */
    private static void writeTypeAnnotation(Codec.Writer out, TypeAnnotation t) {
        var info = t.targetInfo();
        int index = switch (info) {
            case TypeAnnotation.TypeParameterTarget p -> p.typeParameterIndex();
            case TypeAnnotation.SupertypeTarget p -> p.supertypeIndex();
            case TypeAnnotation.TypeParameterBoundTarget p -> p.typeParameterIndex();
            case TypeAnnotation.FormalParameterTarget p -> p.formalParameterIndex();
            case TypeAnnotation.ThrowsTarget p -> p.throwsTargetIndex();
            default -> 0;
        };
        int bound = info instanceof TypeAnnotation.TypeParameterBoundTarget p ? p.boundIndex() : 0;
        var path = new Codec.Writer();
        for (var step : t.targetPath()) path.u8(step.typePathKind().tag()).u8(step.typeArgumentIndex());
        annotation(t.annotation()).encodeTypeAnnotation(out, info.targetType().targetTypeValue(), index, bound, path.toBytes());
    }
}

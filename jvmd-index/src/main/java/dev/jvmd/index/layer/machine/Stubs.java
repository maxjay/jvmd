package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
import dev.jvmd.core.tree.Root;
import java.lang.classfile.Annotation;
import java.lang.classfile.AnnotationElement;
import java.lang.classfile.AnnotationValue;
import java.lang.classfile.Attribute;
import java.lang.classfile.ClassBuilder;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.AnnotationDefaultAttribute;
import java.lang.classfile.attribute.ConstantValueAttribute;
import java.lang.classfile.attribute.ExceptionsAttribute;
import java.lang.classfile.attribute.InnerClassInfo;
import java.lang.classfile.attribute.InnerClassesAttribute;
import java.lang.classfile.attribute.NestHostAttribute;
import java.lang.classfile.attribute.PermittedSubclassesAttribute;
import java.lang.classfile.attribute.RecordAttribute;
import java.lang.classfile.attribute.RecordComponentInfo;
import java.lang.classfile.attribute.RuntimeInvisibleAnnotationsAttribute;
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Stubs (stage 2, 3.14, 5.2a, C.8 and B.10): class files synthesised from a leaf's {@code T}, with every declared member and
 * everything in its {@code res}, including warning metadata and annotation-type meta-annotations. Given the leaf's annotation tree
 * {@code A}, each class, field and method also carries the annotations its class file retains, visible and invisible: annotation
 * processors read them through a sibling's stubs (a JPA metamodel generator decides a superclass by its {@code @MappedSuperclass}).
 * Not emitted yet: type annotations, parameter annotations and parameter names, record-component annotations, package annotations
 * (package-info), and private members, which have no facts; nor executable bodies. A method without
 * {@code Code} is legal input to javac; stubs are compile-time input and are never loaded by a JVM. A stub is a pure function of one
 * type's {@code res}, its retained annotations and its direct members' InnerClasses projection, so it is cached per type and shared.
 *
 * <p>What a stub lacks that javac needs would be a field of {@code res} that was classified as {@code tail}; invariant 7.3.14
 * (compiling against stubs gives the same result as against the real class files) is where that would surface.
 */
public final class Stubs {
    private Stubs() { }

    /** One synthesised class file. */
    public record Stub(String internalName, byte[] bytes) { }

    private static final int ACC_SUPER = 0x20;

    private record FieldDecl(String name, String descriptor, Res.Field res) { }

    private record MethodDecl(String name, String descriptor, Res.Method res) { }

    /** One type of a leaf with its members, decoded once. The entries of a leaf arrive in key order, which is the order members are written in. */
    private static final class TypeDecl {
        String owner;
        Res.Type type;
        final List<FieldDecl> fields = new ArrayList<>();
        final List<MethodDecl> methods = new ArrayList<>();
    }

    /** One entry of {@code S|k}: a type of the leaf and the key of its stub (B.10). */
    public record Ref(String internalName, Identity stKey) { }

    /**
     * Where stubs are kept (5.2a): {@code S|k}, a leaf's list of {@link Ref}, and {@code ST|stKey}, one type's stub. Both are shared,
     * derivable records. A cache may drop anything; a stub it lacks is synthesised again.
     */
    public interface Cache {
        /** No cache: every stub is synthesised and nothing is kept. */
        Cache NONE = new Cache() {
            @Override public byte[] list(Identity k) { return null; }
            @Override public void putList(Identity k, byte[] value) { }
            @Override public byte[] type(Identity stKey) { return null; }
            @Override public void putType(Identity stKey, byte[] value) { }
        };

        byte[] list(Identity k);
        void putList(Identity k, byte[] value);
        byte[] type(Identity stKey);
        void putType(Identity stKey, byte[] value);
    }

    /**
     * One stub per type of the leaf, in type key order (5.2a). Its own {@code res} is identified by {@code oSum}; its direct
     * members contribute only the projection below. The resulting per-type key is shared across leaves, projects and time,
     * and an edit regenerates the stubs of the types it changed and never the module's. A module descriptor has no stub: it is not a
     * type a member can be resolved in.
     *
     * <p>The key of a type is
     * <pre>ST|Digest(typeKey || oSum || sorted (memberTypeKey || opt&lt;str&gt; innerName || u16 flags))</pre>
     * One thing besides {@code res} is in a class file: an outer type's {@code InnerClasses} attribute, which is how javac finds its member
     * types. Those are other types' facts, so the outer type's key names its member types, exact inner names and emitted inner flags,
     * and nothing else about them: never a member's {@code oSum}, so that a change to a method of {@code Inner}
     * changes {@code Inner}'s stub and not {@code Outer}'s. Every key ends with the renderer version (see {@link #stKey}).
     */
    public static List<Stub> stubs(Digest digest, ContentTree tree, MachineLeaf leaf, Function<Identity, byte[]> reader, Cache cache) {
        return stubs(digest, tree, leaf, null, null, reader, cache);
    }

    /**
     * As above, with the retained annotations of the leaf's annotation tree. A type whose stub emits annotations adds the digest of
     * exactly what it emits to its key, so a stub with them never stands in for one without, and a type that emits none keeps the key
     * above. The leaf's list is {@code S|Digest(u8 RENDERER || k || a)}: two leaves with one {@code k} and different annotations share
     * no list.
     *
     * @param a           the leaf's annotation identity, or null for stubs without retained annotations
     * @param annotations the root of its {@code A} tree, null exactly when {@code a} is
     */
    public static List<Stub> stubs(Digest digest, ContentTree tree, MachineLeaf leaf, Identity a, Root annotations, Function<Identity, byte[]> reader, Cache cache) {
        var listKey = a == null ? digest.hash(new byte[] {RENDERER}, leaf.k().view()) : digest.hash(new byte[] {RENDERER}, leaf.k().view(), a.view());
        var listed = cache.list(listKey);
        if (listed != null) {
            var out = new ArrayList<Stub>();
            boolean complete = true;
            for (var ref : decodeList(listed, digest.width())) {
                var bytes = cache.type(ref.stKey());
                if (bytes == null) { complete = false; break; }
                out.add(new Stub(ref.internalName(), new Codec.Reader(bytes).lenBytes()));
            }
            if (complete) return out;
        }

        var decls = new TreeMap<String, TypeDecl>();
        tree.forEach(leaf.k(), reader, entry -> {
            var m = Keys.Member.decode(entry.key());
            var decl = decls.computeIfAbsent(m.owner(), o -> { var d = new TypeDecl(); d.owner = o; return d; });
            byte[] res = entry.value();
            switch (m.kind()) {
                case Keys.TYPE -> decl.type = Res.Type.decode(res);
                case Keys.FIELD -> decl.fields.add(new FieldDecl(m.name(), m.descriptor(), Res.Field.decode(res)));
                default -> decl.methods.add(new MethodDecl(m.name(), m.descriptor(), Res.Method.decode(res)));
            }
        });

        // A: one entry per element with a tail, keyed as in T. What a stub emits of it is its annotations; the projection of a type is
        // each emitting element's key and annotation lists, in key order, and nothing else of the tail (parameter names, type
        // annotations), so that only what a stub shows moves its key.
        var retained = new HashMap<ByteBuffer, Retained>();
        var projections = new HashMap<String, List<byte[]>>();
        if (annotations != null) tree.forEach(annotations.hash(), reader, entry -> {
            var r = Retained.of(entry.value());
            if (r.visible().isEmpty() && r.invisible().isEmpty()) return;
            retained.put(ByteBuffer.wrap(entry.key()), r);
            var projection = new Codec.Writer().lenBytes(entry.key());
            Ann.encodeList(projection, r.visibleAnns());
            Ann.encodeList(projection, r.invisibleAnns());
            projections.computeIfAbsent(Keys.Member.decode(entry.key()).owner(), o -> new ArrayList<>()).add(projection.toBytes());
        });

        // The members of a type are the types whose outer class it is: the outer's InnerClasses attribute is how javac finds them.
        var members = new TreeMap<String, List<String>>();
        for (var decl : decls.values()) {
            if (decl.type == null || decl.type.outer() == null) continue;
            members.computeIfAbsent(decl.type.outer(), o -> new ArrayList<>()).add(decl.owner);
        }
        var out = new ArrayList<Stub>();
        var refs = new ArrayList<Ref>();
        for (var decl : decls.values()) {
            if (decl.type == null || decl.type.kind() == Res.Type.MODULE) continue; // no type fact (not produced), or a module descriptor
            var oSum = tree.get(leaf.oHash(), reader, Keys.ownerKey(decl.owner)).h();
            var memberTypes = new ArrayList<Member>();
            for (var member : members.getOrDefault(decl.owner, List.of())) {
                var type = decls.get(member).type;
                memberTypes.add(new Member(member, type.innerName(), innerFlags(type.access())));
            }
            var projection = projections.get(decl.owner);
            var shown = projection == null ? null : digest.hash(projection.toArray(byte[][]::new));
            var stKey = stKey(digest, decl.owner, oSum, memberTypes, shown);
            var stored = cache.type(stKey);
            byte[] bytes;
            if (stored != null) bytes = new Codec.Reader(stored).lenBytes();
            else {
                bytes = build(decl, members.getOrDefault(decl.owner, List.of()), decls, retained);
                cache.putType(stKey, new Codec.Writer(bytes.length + 4).lenBytes(bytes).toBytes());
            }
            out.add(new Stub(decl.owner, bytes));
            refs.add(new Ref(decl.owner, stKey));
        }
        cache.putList(listKey, encodeList(refs));
        return out;
    }

    /** Exactly one entry in the outer stub's InnerClasses attribute, excluding the already identified outer name. */
    public record Member(String internalName, String innerName, int flags) { }

    /**
     * The version of how a stub is rendered from its inputs (amendments 4.7): a stub cached by an earlier renderer is never served by a
     * later one, whatever else of its key is equal. 2 added retained annotations.
     */
    public static final byte RENDERER = 2;

    /**
     * {@code Digest(typeKey || oSum || sorted (memberTypeKey || opt<str> innerName || u16 flags) || u8 0 || u8 RENDERER)} (B.10): the
     * member part sorted by the unsigned bytes of {@code memberTypeKey} ({@code zstr internalName}), so it is the same on every machine
     * and in every language, and absent for a type without member types. A member part starts with a non-empty name, so the 0 that
     * follows the last one is unambiguous.
     */
    public static Identity stKey(Digest digest, String owner, Identity oSum, List<Member> members) {
        return stKey(digest, owner, oSum, members, null);
    }

    /** As above, then, for a type whose stub emits annotations, {@code || id shown}: the digest of what it emits. */
    public static Identity stKey(Digest digest, String owner, Identity oSum, List<Member> members, Identity shown) {
        var parts = new ArrayList<byte[]>(List.of(Keys.ownerKey(owner), oSum.view()));
        var sorted = new ArrayList<byte[]>();
        for (var member : members) sorted.add(new Codec.Writer().zstr(member.internalName()).optStr(member.innerName()).u16(member.flags()).toBytes());
        // memberTypeKey is a zstr, so the NUL after the name ends it: comparing the whole parts compares the keys first.
        sorted.sort(Arrays::compareUnsigned);
        parts.addAll(sorted);
        parts.add(new byte[] {0, RENDERER});
        if (shown != null) parts.add(shown.view());
        return digest.hash(parts.toArray(byte[][]::new));
    }

    /** B.10: {@code S|k = u32 count || (str internalName || id stKey)[count]}. */
    public static byte[] encodeList(List<Ref> refs) {
        var out = new Codec.Writer(64 + refs.size() * 64).u32(refs.size());
        for (var r : refs) out.str(r.internalName()).id(r.stKey());
        return out.toBytes();
    }

    public static List<Ref> decodeList(byte[] bytes, int width) {
        var in = new Codec.Reader(bytes);
        int n = in.count();
        var out = new ArrayList<Ref>(n);
        for (int i = 0; i < n; i++) out.add(new Ref(in.str(), in.id(width)));
        return List.copyOf(out);
    }

    // ---- one class -------------------------------------------------------------------------------------------------------

    private static ClassDesc internal(String name) { return ClassDesc.ofInternalName(name); }

    /** The access flags a type's InnerClasses entry carries: its own modifiers as a member, from the class-level and inner bits of {@code res}. */
    private static int innerFlags(int access) {
        int flags = access & (ClassFile.ACC_STATIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED | ClassFile.ACC_FINAL
                | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT | ClassFile.ACC_ANNOTATION | ClassFile.ACC_ENUM);
        // The class-level public bit also stands for protected (javac writes protected members as public classes).
        if ((access & ClassFile.ACC_PUBLIC) != 0 && (access & ClassFile.ACC_PROTECTED) == 0) flags |= ClassFile.ACC_PUBLIC;
        return flags;
    }

    /**
     * What a tail retains first (A.4a): the visible, then the invisible, annotations of one element, all of them, including the
     * warning annotations and meta-annotations {@code res} repeats. Null for an element without a tail.
     */
    private record Retained(List<Ann> visibleAnns, List<Ann> invisibleAnns) {
        static Retained of(byte[] tail) {
            var in = new Codec.Reader(tail);
            return new Retained(list(in), list(in));
        }

        private static List<Ann> list(Codec.Reader in) {
            int n = in.count();
            var out = new ArrayList<Ann>(n);
            for (int i = 0; i < n; i++) out.add(Ann.decode(in));
            return out;
        }

        List<Annotation> visible() { return visibleAnns.stream().map(Stubs::annotation).toList(); }
        List<Annotation> invisible() { return invisibleAnns.stream().map(Stubs::annotation).toList(); }

        /** The element's annotations: the retained ones, or else those {@code res} carries. */
        static void apply(Retained retained, List<Annotation> fromRes, Consumer<RuntimeVisibleAnnotationsAttribute> visible,
                          Consumer<RuntimeInvisibleAnnotationsAttribute> invisible) {
            var shown = retained == null ? fromRes : retained.visible();
            if (!shown.isEmpty()) visible.accept(RuntimeVisibleAnnotationsAttribute.of(shown));
            if (retained != null && !retained.invisibleAnns().isEmpty()) invisible.accept(RuntimeInvisibleAnnotationsAttribute.of(retained.invisible()));
        }
    }

    private static byte[] build(TypeDecl decl, List<String> memberTypes, Map<String, TypeDecl> all, Map<ByteBuffer, Retained> retained) {
        var type = decl.type;
        int kind = type.kind(), access = type.access();
        String signature = type.signature(), superName = type.superName(), host = type.nestHost(), outer = type.outer();
        var interfaces = new ArrayList<ClassDesc>();
        for (var i : type.interfaces()) interfaces.add(internal(i));
        var permits = new ArrayList<ClassDesc>();
        for (var p : type.permits()) permits.add(internal(p));
        var metaAnnotations = new ArrayList<Annotation>();
        for (var meta : type.metas()) if (meta != null) metaAnnotations.add(annotation(meta));
        for (var a : type.warnings().annotations()) metaAnnotations.add(annotation(a));

        final int classFlags = (access & (ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
                | ClassFile.ACC_ANNOTATION | ClassFile.ACC_ENUM)) | ((access & ClassFile.ACC_INTERFACE) == 0 ? ACC_SUPER : 0);

        return ClassFile.of().build(internal(decl.owner), cb -> {
            cb.withFlags(classFlags);
            if (type.warnings().deprecatedAttribute()) cb.with(java.lang.classfile.attribute.DeprecatedAttribute.of());
            if (superName != null) cb.withSuperclass(internal(superName));
            if (!interfaces.isEmpty()) cb.withInterfaceSymbols(interfaces);
            if (signature != null) cb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(signature)));
            if (host != null) cb.with(NestHostAttribute.of(internal(host)));
            if (!permits.isEmpty()) cb.with(PermittedSubclassesAttribute.ofSymbols(permits));
            var inner = new ArrayList<InnerClassInfo>();
            if (outer != null) inner.add(InnerClassInfo.of(internal(decl.owner), Optional.of(internal(outer)), Optional.ofNullable(type.innerName()), innerFlags(access)));
            for (var member : memberTypes) {
                inner.add(InnerClassInfo.of(internal(member), Optional.of(internal(decl.owner)), Optional.ofNullable(all.get(member).type.innerName()), innerFlags(all.get(member).type.access())));
            }
            if (!inner.isEmpty()) cb.with(InnerClassesAttribute.of(inner));
            if (kind == 3) {
                var infos = new ArrayList<RecordComponentInfo>();
                for (var part : type.components()) {
                    var attributes = new ArrayList<Attribute<?>>();
                    if (part.signature() != null) attributes.add(SignatureAttribute.of(cb.constantPool().utf8Entry(part.signature())));
                    infos.add(RecordComponentInfo.of(part.name(), ClassDesc.ofDescriptor(part.descriptor()), attributes));
                }
                cb.with(RecordAttribute.of(infos));
            }
            Retained.apply(retained.get(ByteBuffer.wrap(Keys.typeKey(decl.owner))), metaAnnotations, cb::with, cb::with);
            for (var field : decl.fields) field(cb, field, retained.get(ByteBuffer.wrap(Keys.memberKey(decl.owner, Keys.FIELD, field.name(), field.descriptor()))));
            for (var method : decl.methods) method(cb, method, retained.get(ByteBuffer.wrap(Keys.memberKey(decl.owner, Keys.METHOD, method.name(), method.descriptor()))));
        });
    }

    private static void field(ClassBuilder cb, FieldDecl decl, Retained retained) {
        var res = decl.res();
        java.lang.constant.ConstantDesc constant = res.constant() == null ? null : switch (res.constant().tag()) {
            case 3 -> (int) res.constant().bits();
            case 4 -> Float.intBitsToFloat((int) res.constant().bits());
            case 5 -> res.constant().bits();
            case 6 -> Double.longBitsToDouble(res.constant().bits());
            default -> res.constant().text();
        };
        cb.withField(decl.name(), ClassDesc.ofDescriptor(decl.descriptor()), fb -> {
            fb.withFlags(res.access());
            if (res.warnings().deprecatedAttribute()) fb.with(java.lang.classfile.attribute.DeprecatedAttribute.of());
            Retained.apply(retained, res.warnings().annotations().stream().map(Stubs::annotation).toList(), fb::with, fb::with);
            if (res.signature() != null) fb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(res.signature())));
            if (constant != null) fb.with(ConstantValueAttribute.of(constant));
        });
    }

    private static void method(ClassBuilder cb, MethodDecl decl, Retained retained) {
        var res = decl.res();
        var thrown = new ArrayList<ClassDesc>();
        for (var t : res.thrown()) thrown.add(internal(t));
        AnnotationValue defaultValue = res.defaultValue() == null ? null : value(res.defaultValue());
        cb.withMethod(decl.name(), MethodTypeDesc.ofDescriptor(decl.descriptor()), res.access(), mb -> {
            if (res.warnings().deprecatedAttribute()) mb.with(java.lang.classfile.attribute.DeprecatedAttribute.of());
            Retained.apply(retained, res.warnings().annotations().stream().map(Stubs::annotation).toList(), mb::with, mb::with);
            if (res.signature() != null) mb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(res.signature())));
            if (!thrown.isEmpty()) mb.with(ExceptionsAttribute.ofSymbols(thrown));
            if (defaultValue != null) mb.with(AnnotationDefaultAttribute.of(defaultValue));
        });
    }

    // ---- the structural annotation model of A.4a, as class-file values ----------------------------------------------------------

    private static Annotation annotation(Ann a) {
        var elements = new ArrayList<AnnotationElement>(a.elements().size());
        for (var element : a.elements()) elements.add(AnnotationElement.of(element.name(), value(element.value())));
        return Annotation.of(ClassDesc.ofDescriptor(a.descriptor()), elements);
    }

    private static AnnotationValue value(Ann.Val v) {
        return switch (v) {
            case Ann.Val.Str s -> AnnotationValue.ofString(s.value());
            case Ann.Val.Prim p -> switch (p.tag()) {
                case 'Z' -> AnnotationValue.ofBoolean(p.bits() != 0);
                case 'B' -> AnnotationValue.ofByte((byte) p.bits());
                case 'C' -> AnnotationValue.ofChar((char) p.bits());
                case 'S' -> AnnotationValue.ofShort((short) p.bits());
                case 'I' -> AnnotationValue.ofInt((int) p.bits());
                case 'J' -> AnnotationValue.ofLong(p.bits());
                case 'F' -> AnnotationValue.ofFloat(Float.intBitsToFloat((int) p.bits()));
                case 'D' -> AnnotationValue.ofDouble(Double.longBitsToDouble(p.bits()));
                default -> throw new IllegalArgumentException("Unexpected annotation value tag " + p.tag());
            };
            case Ann.Val.Enum e -> AnnotationValue.ofEnum(ClassDesc.ofDescriptor(e.descriptor()), e.constant());
            case Ann.Val.Cls c -> AnnotationValue.ofClass(ClassDesc.ofDescriptor(c.descriptor()));
            case Ann.Val.Nested n -> AnnotationValue.ofAnnotation(annotation(n.annotation()));
            case Ann.Val.Array a -> {
                var values = new ArrayList<AnnotationValue>(a.values().size());
                for (var item : a.values()) values.add(value(item));
                yield AnnotationValue.ofArray(values);
            }
        };
    }
}

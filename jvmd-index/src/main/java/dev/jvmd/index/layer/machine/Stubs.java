package dev.jvmd.index.layer.machine;

import dev.jvmd.core.hash.Digest;
import dev.jvmd.core.hash.Identity;
import dev.jvmd.core.tree.Codec;
import dev.jvmd.core.tree.ContentTree;
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
import java.lang.classfile.attribute.RuntimeVisibleAnnotationsAttribute;
import java.lang.classfile.attribute.SignatureAttribute;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Stubs (stage 2, 3.14, 5.2a, C.8 and B.10): class files synthesised from a leaf's {@code T}, with every declared member and
 * everything in its {@code res}, including warning metadata and annotation-type meta-annotations. It reads no annotation tree
 * and includes no executable bodies or parameter names. A method without {@code Code} is legal input to javac; stubs are
 * compile-time input and are never loaded by a JVM. A stub is a pure function of one type's {@code res}, so it is cached per type and shared.
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
     * One stub per type of the leaf, in type key order (5.2a). A stub is a function of one type's {@code res}, which is what its
     * {@code oSum} identifies, so it is cached per type by {@code Digest(typeKey || oSum)}: shared across leaves, projects and time,
     * and an edit regenerates the stubs of the types it changed and never the module's. A module descriptor has no stub: it is not a
     * type a member can be resolved in.
     *
     * <p>The key of a type is
     * <pre>ST|Digest(typeKey || oSum || sorted (memberTypeKey || u16 flags))</pre>
     * One thing besides {@code res} is in a class file: an outer type's {@code InnerClasses} attribute, which is how javac finds its member
     * types. Those are other types' facts, so the outer type's key names its member types and their flags (the access bits of the
     * member's own {@code res}) and nothing else about them: never a member's {@code oSum}, so that a change to a method of {@code Inner}
     * changes {@code Inner}'s stub and not {@code Outer}'s. A type without member types has exactly the key {@code Digest(typeKey || oSum)}.
     */
    public static List<Stub> stubs(Digest digest, ContentTree tree, MachineLeaf leaf, Function<Identity, byte[]> reader, Cache cache) {
        var listed = cache.list(leaf.k());
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
            for (var member : members.getOrDefault(decl.owner, List.of())) memberTypes.add(new Member(member, decls.get(member).type.access()));
            var stKey = stKey(digest, decl.owner, oSum, memberTypes);
            var stored = cache.type(stKey);
            byte[] bytes;
            if (stored != null) bytes = new Codec.Reader(stored).lenBytes();
            else {
                bytes = build(decl, members.getOrDefault(decl.owner, List.of()), decls);
                cache.putType(stKey, new Codec.Writer(bytes.length + 4).lenBytes(bytes).toBytes());
            }
            out.add(new Stub(decl.owner, bytes));
            refs.add(new Ref(decl.owner, stKey));
        }
        cache.putList(leaf.k(), encodeList(refs));
        return out;
    }

    /** A member type of a type, as its outer type's stub names it: its internal name and its flags (the access bits of its own {@code res}). */
    public record Member(String internalName, int flags) { }

    /**
     * {@code Digest(typeKey || oSum || sorted (memberTypeKey || u16 flags))} (B.10): the member part sorted by the unsigned bytes of
     * {@code memberTypeKey} ({@code zstr internalName}), so it is the same on every machine and in every language, and absent for a
     * type without member types.
     */
    public static Identity stKey(Digest digest, String owner, Identity oSum, List<Member> members) {
        var parts = new ArrayList<byte[]>(List.of(Keys.ownerKey(owner), oSum.view()));
        var sorted = new ArrayList<byte[]>();
        for (var member : members) sorted.add(new Codec.Writer(member.internalName().length() + 3).zstr(member.internalName()).u16(member.flags()).toBytes());
        // memberTypeKey is a zstr, so the NUL after the name ends it: comparing the whole parts compares the keys first.
        sorted.sort(Arrays::compareUnsigned);
        parts.addAll(sorted);
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

    private static byte[] build(TypeDecl decl, List<String> memberTypes, Map<String, TypeDecl> all) {
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
            if (type.warnings().deprecated()) cb.with(java.lang.classfile.attribute.DeprecatedAttribute.of());
            if (superName != null) cb.withSuperclass(internal(superName));
            if (!interfaces.isEmpty()) cb.withInterfaceSymbols(interfaces);
            if (signature != null) cb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(signature)));
            if (host != null) cb.with(NestHostAttribute.of(internal(host)));
            if (!permits.isEmpty()) cb.with(PermittedSubclassesAttribute.ofSymbols(permits));
            var inner = new ArrayList<InnerClassInfo>();
            if (outer != null) inner.add(InnerClassInfo.of(internal(decl.owner), Optional.of(internal(outer)), Optional.of(Keys.simpleName(decl.owner)), innerFlags(access)));
            for (var member : memberTypes) {
                inner.add(InnerClassInfo.of(internal(member), Optional.of(internal(decl.owner)), Optional.of(Keys.simpleName(member)), innerFlags(all.get(member).type.access())));
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
            if (!metaAnnotations.isEmpty()) cb.with(RuntimeVisibleAnnotationsAttribute.of(metaAnnotations));
            for (var field : decl.fields) field(cb, field);
            for (var method : decl.methods) method(cb, method);
        });
    }

    private static void field(ClassBuilder cb, FieldDecl decl) {
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
            if (res.warnings().deprecated()) fb.with(java.lang.classfile.attribute.DeprecatedAttribute.of());
            if (!res.warnings().annotations().isEmpty()) fb.with(RuntimeVisibleAnnotationsAttribute.of(res.warnings().annotations().stream().map(Stubs::annotation).toList()));
            if (res.signature() != null) fb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(res.signature())));
            if (constant != null) fb.with(ConstantValueAttribute.of(constant));
        });
    }

    private static void method(ClassBuilder cb, MethodDecl decl) {
        var res = decl.res();
        var thrown = new ArrayList<ClassDesc>();
        for (var t : res.thrown()) thrown.add(internal(t));
        AnnotationValue defaultValue = res.defaultValue() == null ? null : value(res.defaultValue());
        cb.withMethod(decl.name(), MethodTypeDesc.ofDescriptor(decl.descriptor()), res.access(), mb -> {
            if (res.warnings().deprecated()) mb.with(java.lang.classfile.attribute.DeprecatedAttribute.of());
            if (!res.warnings().annotations().isEmpty()) mb.with(RuntimeVisibleAnnotationsAttribute.of(res.warnings().annotations().stream().map(Stubs::annotation).toList()));
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

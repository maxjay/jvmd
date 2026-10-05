package dev.jvmd.index.layer.machine;

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
 * everything in its {@code res} and nothing else: no bodies, no annotations but the meta-annotations of an annotation type, no
 * parameter names. A method without {@code Code} is legal input to javac (it is what {@code ct.sym} is made of); stubs are
 * compile-time input and are never loaded by a JVM. A stub is a pure function of {@code k}, so it is cached by {@code k} and shared.
 *
 * <p>What a stub lacks that javac needs would be a field of {@code res} that was classified as {@code tail}; invariant 7.3.14
 * (compiling against stubs gives the same result as against the real class files) is where that would surface.
 */
public final class Stubs {
    private Stubs() { }

    /** One synthesised class file. */
    public record Stub(String internalName, byte[] bytes) { }

    private static final int ACC_SUPER = 0x20;

    private static final class TypeDecl {
        String owner;
        byte[] res;
        final TreeMap<byte[], byte[]> fields = new TreeMap<>(Arrays::compareUnsigned);
        final TreeMap<byte[], byte[]> methods = new TreeMap<>(Arrays::compareUnsigned);
    }

    /** One stub per type of the leaf, in type key order. A module descriptor has no stub: it is not a type a member can be resolved in. */
    public static List<Stub> stubs(ContentTree tree, MachineLeaf leaf, Function<Identity, byte[]> reader) {
        var decls = new TreeMap<String, TypeDecl>();
        tree.forEach(leaf.k(), reader, entry -> {
            var m = new Codec.Reader(entry.key());
            String owner = zstr(m);
            int kind = m.u8();
            var decl = decls.computeIfAbsent(owner, o -> { var d = new TypeDecl(); d.owner = o; return d; });
            var value = new Codec.Reader(entry.value());
            byte[] res = value.raw((int) value.u32());
            switch (kind) {
                case 0 -> decl.res = res;
                case 1 -> decl.fields.put(entry.key(), res);
                default -> decl.methods.put(entry.key(), res);
            }
        });

        // The members of a type are the types whose outer class it is: the outer's InnerClasses attribute is how javac finds them.
        var members = new TreeMap<String, List<String>>();
        for (var decl : decls.values()) {
            if (decl.res == null) continue;
            String outer = outer(decl.res);
            if (outer != null) members.computeIfAbsent(outer, o -> new ArrayList<>()).add(decl.owner);
        }
        var out = new ArrayList<Stub>();
        for (var decl : decls.values()) {
            if (decl.res == null || decl.res[0] == 5) continue; // no type fact (not produced), or a module descriptor
            out.add(new Stub(decl.owner, build(decl, members.getOrDefault(decl.owner, List.of()), decls)));
        }
        return out;
    }

    /** B.10: {@code u32 count || (str internalName || u32 len || classBytes)[count]}. */
    public static byte[] encode(List<Stub> stubs) {
        var out = new Codec.Writer(1024).u32(stubs.size());
        for (var s : stubs) out.str(s.internalName()).lenBytes(s.bytes());
        return out.toBytes();
    }

    public static List<Stub> decode(byte[] bytes) {
        var in = new Codec.Reader(bytes);
        int n = in.count();
        var out = new ArrayList<Stub>(n);
        for (int i = 0; i < n; i++) out.add(new Stub(in.str(), in.lenBytes()));
        return List.copyOf(out);
    }

    // ---- one class -------------------------------------------------------------------------------------------------------

    private static String zstr(Codec.Reader in) {
        var out = new java.io.ByteArrayOutputStream();
        for (int b; (b = in.u8()) != 0; ) out.write(b);
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String optStr(Codec.Reader in) { return in.u8() == 1 ? in.str() : null; }

    private static ClassDesc internal(String name) { return ClassDesc.ofInternalName(name); }

    /** The outer class field of a type's {@code res}, without decoding the rest. */
    private static String outer(byte[] res) {
        var in = new Codec.Reader(res);
        in.u8(); in.u16(); optStr(in); optStr(in);
        for (int i = in.count(); i > 0; i--) in.str();
        for (int i = in.count(); i > 0; i--) in.str();
        optStr(in);
        return optStr(in);
    }

    private static String simpleName(String internalName) {
        int cut = Math.max(internalName.lastIndexOf('/'), internalName.lastIndexOf('$'));
        return internalName.substring(cut + 1);
    }

    /** The access flags a type's InnerClasses entry carries: its own modifiers as a member, from the class-level and inner bits of {@code res}. */
    private static int innerFlags(int access) {
        int flags = access & (ClassFile.ACC_STATIC | ClassFile.ACC_PRIVATE | ClassFile.ACC_PROTECTED | ClassFile.ACC_FINAL
                | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT | ClassFile.ACC_ANNOTATION | ClassFile.ACC_ENUM);
        // The class-level public bit also stands for protected (javac writes protected members as public classes).
        if ((access & ClassFile.ACC_PUBLIC) != 0 && (access & ClassFile.ACC_PROTECTED) == 0) flags |= ClassFile.ACC_PUBLIC;
        return flags;
    }

    private static byte[] build(TypeDecl decl, List<String> memberTypes, Map<String, TypeDecl> all) {
        var in = new Codec.Reader(decl.res);
        int kind = in.u8();
        int access = in.u16();
        String signature = optStr(in);
        String superName = optStr(in);
        var interfaces = new ArrayList<ClassDesc>();
        for (int i = in.count(); i > 0; i--) interfaces.add(internal(in.str()));
        var permits = new ArrayList<ClassDesc>();
        for (int i = in.count(); i > 0; i--) permits.add(internal(in.str()));
        String host = optStr(in), outer = optStr(in);
        int components = in.count();
        var recordParts = new ArrayList<String[]>();
        for (int i = 0; i < components; i++) recordParts.add(new String[] {in.str(), in.str(), optStr(in)});
        var metaAnnotations = new ArrayList<Annotation>();
        if (kind == 4) for (int i = 0; i < 5; i++) if (in.u8() == 1) metaAnnotations.add(annotation(in));

        final int classFlags = (access & (ClassFile.ACC_PUBLIC | ClassFile.ACC_FINAL | ClassFile.ACC_INTERFACE | ClassFile.ACC_ABSTRACT
                | ClassFile.ACC_ANNOTATION | ClassFile.ACC_ENUM)) | ((access & ClassFile.ACC_INTERFACE) == 0 ? ACC_SUPER : 0);

        return ClassFile.of().build(internal(decl.owner), cb -> {
            cb.withFlags(classFlags);
            if (superName != null) cb.withSuperclass(internal(superName));
            if (!interfaces.isEmpty()) cb.withInterfaceSymbols(interfaces);
            if (signature != null) cb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(signature)));
            if (host != null) cb.with(NestHostAttribute.of(internal(host)));
            if (!permits.isEmpty()) cb.with(PermittedSubclassesAttribute.ofSymbols(permits));
            var inner = new ArrayList<InnerClassInfo>();
            if (outer != null) inner.add(InnerClassInfo.of(internal(decl.owner), Optional.of(internal(outer)), Optional.of(simpleName(decl.owner)), innerFlags(access)));
            for (var member : memberTypes) {
                int memberAccess = accessOf(all.get(member).res);
                inner.add(InnerClassInfo.of(internal(member), Optional.of(internal(decl.owner)), Optional.of(simpleName(member)), innerFlags(memberAccess)));
            }
            if (!inner.isEmpty()) cb.with(InnerClassesAttribute.of(inner));
            if (kind == 3) {
                var infos = new ArrayList<RecordComponentInfo>();
                for (var part : recordParts) {
                    var attributes = new ArrayList<Attribute<?>>();
                    if (part[2] != null) attributes.add(SignatureAttribute.of(cb.constantPool().utf8Entry(part[2])));
                    infos.add(RecordComponentInfo.of(part[0], ClassDesc.ofDescriptor(part[1]), attributes));
                }
                cb.with(RecordAttribute.of(infos));
            }
            if (!metaAnnotations.isEmpty()) cb.with(RuntimeVisibleAnnotationsAttribute.of(metaAnnotations));
            for (var entry : decl.fields.entrySet()) field(cb, entry.getKey(), entry.getValue());
            for (var entry : decl.methods.entrySet()) method(cb, entry.getKey(), entry.getValue());
        });
    }

    private static int accessOf(byte[] res) {
        var in = new Codec.Reader(res);
        in.u8();
        return in.u16();
    }

    private static void field(ClassBuilder cb, byte[] key, byte[] res) {
        var m = new Codec.Reader(key);
        zstr(m); m.u8();
        String name = zstr(m), desc = zstr(m);
        var in = new Codec.Reader(res);
        int flags = in.u16();
        String signature = optStr(in);
        java.lang.constant.ConstantDesc constant = null;
        if (in.u8() == 1) {
            constant = switch (in.u8()) {
                case 3 -> (int) in.u32();
                case 4 -> Float.intBitsToFloat((int) in.u32());
                case 5 -> in.u64();
                case 6 -> Double.longBitsToDouble(in.u64());
                case 8 -> in.str();
                default -> throw new IllegalArgumentException("Unexpected ConstantValue tag");
            };
        }
        final var value = constant;
        cb.withField(name, ClassDesc.ofDescriptor(desc), fb -> {
            fb.withFlags(flags);
            if (signature != null) fb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(signature)));
            if (value != null) fb.with(ConstantValueAttribute.of(value));
        });
    }

    private static void method(ClassBuilder cb, byte[] key, byte[] res) {
        var m = new Codec.Reader(key);
        zstr(m); m.u8();
        String name = zstr(m), desc = zstr(m);
        var in = new Codec.Reader(res);
        int flags = in.u16();
        String signature = optStr(in);
        var thrown = new ArrayList<ClassDesc>();
        for (int i = in.count(); i > 0; i--) thrown.add(internal(in.str()));
        AnnotationValue defaultValue = in.u8() == 1 ? value(in) : null;
        cb.withMethod(name, MethodTypeDesc.ofDescriptor(desc), flags, mb -> {
            if (signature != null) mb.with(SignatureAttribute.of(cb.constantPool().utf8Entry(signature)));
            if (!thrown.isEmpty()) mb.with(ExceptionsAttribute.ofSymbols(thrown));
            if (defaultValue != null) mb.with(AnnotationDefaultAttribute.of(defaultValue));
        });
    }

    // ---- the structural annotation encoding of A.4a, back to class-file values -----------------------------------------------

    private static Annotation annotation(Codec.Reader in) {
        var type = ClassDesc.ofDescriptor(in.str());
        int n = in.u16();
        var elements = new ArrayList<AnnotationElement>(n);
        for (int i = 0; i < n; i++) elements.add(AnnotationElement.of(in.str(), value(in)));
        return Annotation.of(type, elements);
    }

    private static AnnotationValue value(Codec.Reader in) {
        int tag = in.u8();
        return switch (tag) {
            case 's' -> AnnotationValue.ofString(in.str());
            case 'Z' -> AnnotationValue.ofBoolean(in.u32() != 0);
            case 'B' -> AnnotationValue.ofByte((byte) in.u32());
            case 'C' -> AnnotationValue.ofChar((char) in.u32());
            case 'S' -> AnnotationValue.ofShort((short) in.u32());
            case 'I' -> AnnotationValue.ofInt((int) in.u32());
            case 'J' -> AnnotationValue.ofLong(in.u64());
            case 'F' -> AnnotationValue.ofFloat(Float.intBitsToFloat((int) in.u32()));
            case 'D' -> AnnotationValue.ofDouble(Double.longBitsToDouble(in.u64()));
            case 'e' -> AnnotationValue.ofEnum(ClassDesc.ofDescriptor(in.str()), in.str());
            case 'c' -> AnnotationValue.ofClass(ClassDesc.ofDescriptor(in.str()));
            case '@' -> AnnotationValue.ofAnnotation(annotation(in));
            case '[' -> {
                int n = in.u16();
                var values = new ArrayList<AnnotationValue>(n);
                for (int i = 0; i < n; i++) values.add(value(in));
                yield AnnotationValue.ofArray(values);
            }
            default -> throw new IllegalArgumentException("Unexpected annotation value tag " + tag);
        };
    }
}

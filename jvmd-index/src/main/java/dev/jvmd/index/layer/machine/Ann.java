package dev.jvmd.index.layer.machine;

import dev.jvmd.core.tree.Codec;
import java.util.ArrayList;
import java.util.List;

/**
 * The structural annotation of appendix A.4a as a value: {@code annotation = str typeDescriptor || u16 elementCount ||
 * (str name || value)[elementCount]}, with every name and constant resolved and no constant-pool index. Class files and javac
 * both produce it; the one encoder and decoder here are what makes their bytes equal.
 */
public record Ann(String descriptor, List<Element> elements) {
    public record Element(String name, Val value) { }

    /** {@code value = u8 tag || payload}. */
    public sealed interface Val {
        /** A primitive: tag {@code Z B C S I F} carry 32 bits, {@code J D} 64; the bits of a float or double are its raw bits. */
        record Prim(int tag, long bits) implements Val { }
        record Str(String value) implements Val { }
        record Enum(String descriptor, String constant) implements Val { }
        record Cls(String descriptor) implements Val { }
        record Nested(Ann annotation) implements Val { }
        record Array(List<Val> values) implements Val { }
    }

    public void encode(Codec.Writer out) {
        out.str(descriptor).u16(elements.size());
        for (var element : elements) {
            out.str(element.name());
            encode(out, element.value());
        }
    }

    public static Ann decode(Codec.Reader in) {
        String descriptor = in.str();
        int n = in.u16();
        var elements = new ArrayList<Element>(n);
        for (int i = 0; i < n; i++) elements.add(new Element(in.str(), decodeValue(in)));
        return new Ann(descriptor, List.copyOf(elements));
    }

    /** {@code list<annotation>}: a u32 count, then the annotations inline. They are self-delimiting, so no per-item length. */
    public static void encodeList(Codec.Writer out, List<Ann> annotations) {
        out.u32(annotations.size());
        for (var a : annotations) a.encode(out);
    }

    /** Existing signature type-annotation codec (A.4a); both producers supply the positions already computed by javac. */
    public void encodeTypeAnnotation(Codec.Writer out, int target, int index, int bound, byte[] path) {
        out.u8(target);
        switch (target) {
            case 0x00, 0x01, 0x16 -> out.u8(index);
            case 0x10, 0x17 -> out.u16(index);
            case 0x11, 0x12 -> out.u8(index).u8(bound);
            case 0x13, 0x14, 0x15 -> { }
            default -> throw new IllegalArgumentException("Not a signature type annotation: " + target);
        }
        out.u8(path.length / 2).raw(path);
        encode(out);
    }

    /** EA is a function of the encoded tail, including all retained warning annotations. */
    public static java.util.Set<String> tailAnnotationTypes(byte[] tail) {
        if (tail.length == 0) return java.util.Set.of();
        var in = new Codec.Reader(tail);
        var types = new java.util.TreeSet<String>();
        for (int list = 0; list < 2; list++) {
            int count = in.count();
            for (int i = 0; i < count; i++) types.add(decode(in).descriptor());
        }
        int count = in.count();
        for (int i = 0; i < count; i++) {
            int target = in.u8();
            switch (target) {
                case 0x00, 0x01, 0x16 -> in.u8();
                case 0x10, 0x11, 0x12, 0x17 -> in.u16();
                case 0x13, 0x14, 0x15 -> { }
                default -> throw new IllegalArgumentException("Not a signature type annotation: " + target);
            }
            in.raw(in.u8() * 2);
            types.add(decode(in).descriptor());
        }
        // Parameter names follow, but do not supply annotation edges.
        return types;
    }

    public static void encode(Codec.Writer out, Val value) {
        switch (value) {
            case Val.Prim p -> {
                out.u8(p.tag());
                if (p.tag() == 'J' || p.tag() == 'D') out.u64(p.bits()); else out.u32(p.bits() & 0xFFFFFFFFL);
            }
            case Val.Str s -> out.u8('s').str(s.value());
            case Val.Enum e -> out.u8('e').str(e.descriptor()).str(e.constant());
            case Val.Cls c -> out.u8('c').str(c.descriptor());
            case Val.Nested n -> { out.u8('@'); n.annotation().encode(out); }
            case Val.Array a -> {
                out.u8('[').u16(a.values().size());
                for (var v : a.values()) encode(out, v);
            }
        }
    }

    public static Val decodeValue(Codec.Reader in) {
        int tag = in.u8();
        return switch (tag) {
            case 's' -> new Val.Str(in.str());
            case 'Z', 'B', 'C', 'S', 'I', 'F' -> new Val.Prim(tag, in.u32());
            case 'J', 'D' -> new Val.Prim(tag, in.u64());
            case 'e' -> new Val.Enum(in.str(), in.str());
            case 'c' -> new Val.Cls(in.str());
            case '@' -> new Val.Nested(decode(in));
            case '[' -> {
                int n = in.u16();
                var values = new ArrayList<Val>(n);
                for (int i = 0; i < n; i++) values.add(decodeValue(in));
                yield new Val.Array(List.copyOf(values));
            }
            default -> throw new IllegalArgumentException("Unexpected annotation value tag " + tag);
        };
    }
}
